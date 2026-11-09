package com.pianoroll.core.audioengine

import com.pianoroll.core.model.*
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Pure-Kotlin, platform-free real-time engine: sample-accurate sequencer + polyphonic synth + mixer + effects.
 * - UI/control thread calls setProject/play/pause/stop/seek/setLoop/previewNote (lock-free queue).
 * - Audio thread (or offline loop) calls render(). No allocation in the hot path.
 * Android: AudioTrackEngine drives it. Desktop/offline: same class, no changes.
 */
class SynthEngine(val sampleRate: Int, private val honorLoop: Boolean = true) {
    private sealed interface Cmd {
        class SetProject(val c: Compiled) : Cmd
        object Play : Cmd
        object Pause : Cmd
        object Stop : Cmd
        class Seek(val tick: Long) : Cmd
        class Loop(val s: Long?, val e: Long?) : Cmd
        class Preview(val trackId: String, val pitch: Int, val vel: Int) : Cmd
    }

    private val queue = ConcurrentLinkedQueue<Cmd>()

    // ---- audio-thread state ----
    private var comp: Compiled = Compiled(120f, emptyList(), emptyList(), 0L, null, null, emptyList())
    private var pos = 0.0
    private var playingA = false
    private var evIdx = 0
    private var loopS: Long? = null
    private var loopE: Long? = null
    private var order = 0L
    private val voices = Array(64) { Voice() }
    private val tl = FloatArray(SEG)
    private val tr = FloatArray(SEG)
    private var fx: Array<List<Fx>> = emptyArray()
    private var fxSpecs: Array<List<EffectSpec>> = emptyArray()
    private var fxIds: Array<String> = emptyArray()
    private var gate = 1f

    // ---- cross-thread readouts ----
    @Volatile private var posOut = 0L
    @Volatile private var playingOut = false

    // ================= control API (any thread) =================
    fun setProject(p: Project) { queue.add(Cmd.SetProject(Compiled.from(p, honorLoop))) }
    fun play() { queue.add(Cmd.Play) }
    fun pause() { queue.add(Cmd.Pause) }
    fun stop() { queue.add(Cmd.Stop) }
    fun seek(tick: Long) { queue.add(Cmd.Seek(tick)) }
    fun setLoop(startTick: Long?, endTick: Long?) { queue.add(Cmd.Loop(startTick, endTick)) }
    fun previewNote(trackId: String, pitch: Int, velocity: Int) { queue.add(Cmd.Preview(trackId, pitch, velocity)) }
    fun positionTick(): Long = posOut
    fun isPlaying(): Boolean = playingOut

    // ================= audio thread =================
    private fun drain() {
        while (true) {
            when (val c = queue.poll() ?: return) {
                is Cmd.SetProject -> applyProject(c.c)
                Cmd.Play -> {
                    if (!playingA && comp.lengthTicks > 0 && pos >= endTick()) pos = (loopS ?: 0L).toDouble()
                    playingA = true; resync()
                }
                Cmd.Pause -> { playingA = false; releaseAll() }
                Cmd.Stop -> { playingA = false; pos = 0.0; releaseAll(); resync() }
                is Cmd.Seek -> { pos = c.tick.coerceAtLeast(0L).toDouble(); releaseAll(); resync() }
                is Cmd.Loop -> { loopS = c.s; loopE = c.e; if (c.s == null || c.e == null || c.e <= c.s) { loopS = null; loopE = null } }
                is Cmd.Preview -> preview(c)
            }
        }
        posOut = pos.toLong(); playingOut = playingA
    }

    private fun endTick(): Long = loopE ?: comp.lengthTicks

    private fun applyProject(c: Compiled) {
        releaseAll()
        comp = c
        loopS = c.loopStart; loopE = c.loopEnd
        // keep effect instances (and their tails) when the track's chain didn't change
        val n = c.tracks.size
        val newFx = Array(n) { emptyList<Fx>() }
        val newSpecs = Array(n) { emptyList<EffectSpec>() }
        val newIds = Array(n) { c.tracks[it].id }
        for (i in 0 until n) {
            val old = fxIds.indexOf(newIds[i])
            val specs = c.tracks[i].effects
            if (old >= 0 && fxSpecs[old] == specs) { newFx[i] = fx[old] }
            else newFx[i] = specs.mapNotNull { FxFactory.create(it, sampleRate) }
            newSpecs[i] = specs
        }
        fx = newFx; fxSpecs = newSpecs; fxIds = newIds
        if (pos > comp.lengthTicks && comp.lengthTicks > 0 && loopE == null) pos = 0.0
        resync()
    }

    private fun resync() { evIdx = comp.firstEventAt(pos.toLong()) }

    private fun releaseAll() { for (v in voices) if (v.active) v.release(sampleRate) }

    private fun allocVoice(): Voice {
        var oldest: Voice? = null
        for (v in voices) {
            if (!v.active) return v
            if (oldest == null || v.startOrder < oldest.startOrder) oldest = v
        }
        return oldest!!
    }

    private fun noteOn(track: Int, pitch: Int, vel: Int) {
        // retrigger: release same pitch on same track
        for (v in voices) if (v.active && v.track == track && v.pitch == pitch && !v.releasing) v.release(sampleRate)
        allocVoice().start(track, pitch, vel, comp.tracks[track].instrument, sampleRate, order++)
    }

    private fun noteOff(track: Int, pitch: Int) {
        var best: Voice? = null
        for (v in voices) if (v.active && v.track == track && v.pitch == pitch && !v.releasing) {
            if (best == null || v.startOrder < best.startOrder) best = v
        }
        best?.release(sampleRate)
    }

    private fun preview(c: Cmd.Preview) {
        val ti = comp.tracks.indexOfFirst { it.id == c.trackId }
        if (ti < 0) return
        val v = allocVoice()
        v.start(ti, c.pitch.coerceIn(0, 127), c.vel.coerceIn(1, 127), comp.tracks[ti].instrument, sampleRate, order++)
        v.holdLeft = (0.35 * sampleRate).toInt()
    }

    private fun tickPerSample(): Double = comp.bpm.toDouble() * PPQ / (60.0 * sampleRate)

    /** Speed multiplier from Gross-Beat style time effects (tape/half-speed/scratch). */
    private fun rateAt(tick: Long): Float {
        for (e in comp.timeEffects) {
            if (tick < e.startTick || tick >= e.endTick || e.endTick <= e.startTick) continue
            val p = (tick - e.startTick).toFloat() / (e.endTick - e.startTick)
            val amt = curve(e, tick, p)
            return when (e.type) {
                TimeEffectType.HALF_SPEED -> 0.5f
                TimeEffectType.TAPE_STOP -> (1f - amt).coerceAtLeast(0.03f)
                TimeEffectType.VINYL_BRAKE -> ((1f - amt) * (1f - amt)).coerceAtLeast(0.03f)
                TimeEffectType.SCRATCH -> 0.2f + 0.8f * abs(cos(p * 6f * PI.toFloat()))
                else -> 1f
            }
        }
        return 1f
    }

    private fun curve(e: TimeEffect, tick: Long, linear: Float): Float {
        val c = e.curve
        if (c.isEmpty()) return linear
        if (tick <= c.first().tick) return c.first().value
        for (i in 1 until c.size) {
            if (tick <= c[i].tick) {
                val a = c[i - 1]; val b = c[i]
                val f = (tick - a.tick).toFloat() / maxOf(1L, b.tick - a.tick)
                return a.value + (b.value - a.value) * f
            }
        }
        return c.last().value
    }

    private fun gateAt(tick: Long): Float {
        for (e in comp.timeEffects) {
            if (tick < e.startTick || tick >= e.endTick) continue
            val step = when (e.type) { TimeEffectType.GATE -> PPQ / 4; TimeEffectType.STUTTER -> PPQ / 8; else -> return 1f }
            return if (((tick - e.startTick) / step) % 2L == 0L) 1f else 0f
        }
        return 1f
    }

    /** Fill `out` (interleaved stereo float, frames*2) with audio. Returns the peak level. */
    fun render(out: FloatArray, frames: Int): Float {
        drain()
        java.util.Arrays.fill(out, 0, frames * 2, 0f)
        var done = 0
        val tps = tickPerSample()
        var peak = 0f
        while (done < frames) {
            var seg = minOf(frames - done, SEG)
            val rate = rateAt(pos.toLong())
            if (playingA) {
                // fire all events due now
                val ev = comp.events
                while (evIdx < ev.size && ev[evIdx].tick <= pos.toLong()) {
                    val e = ev[evIdx++]
                    if (e.on) noteOn(e.track, e.pitch, e.vel) else noteOff(e.track, e.pitch)
                }
                // shorten the segment so the next event / loop end lands on its exact sample
                val step = tps * rate
                if (evIdx < ev.size) {
                    val need = Math.ceil((ev[evIdx].tick - pos) / step).toInt().coerceAtLeast(1)
                    if (need < seg) seg = need
                }
                val endT = endTick()
                if (endT > 0) {
                    val need = Math.ceil((endT - pos) / step).toInt().coerceAtLeast(1)
                    if (need < seg) seg = need
                }
            }
            renderSegment(out, done, seg, rate)
            if (playingA) {
                pos += seg * tps * rate
                val endT = endTick()
                if (endT > 0 && pos >= endT) {
                    val ls = loopS
                    if (ls != null && loopE != null) {
                        pos = ls + (pos - endT)
                        releaseAll(); resync()
                    } else {
                        playingA = false; pos = 0.0; releaseAll(); resync()
                    }
                }
            }
            done += seg
        }
        for (i in 0 until frames * 2) {
            val s = tanh(out[i] * 0.8f)
            out[i] = s
            val a = abs(s); if (a > peak) peak = a
        }
        posOut = pos.toLong(); playingOut = playingA
        return peak
    }

    private fun renderSegment(out: FloatArray, offset: Int, n: Int, rate: Float) {
        val c = comp
        val g = gateAt(pos.toLong())
        for (ti in c.tracks.indices) {
            java.util.Arrays.fill(tl, 0, n, 0f); java.util.Arrays.fill(tr, 0, n, 0f)
            var any = false
            for (v in voices) if (v.active && v.track == ti) { v.render(tl, tr, n, rate, sampleRate); any = true }
            val chain = if (ti < fx.size) fx[ti] else emptyList()
            if (!any && chain.isEmpty()) continue
            for (f in chain) f.process(tl, tr, n)
            val t = c.tracks[ti]
            val audible = if (c.anySolo) t.solo else !t.mute
            if (!audible) continue
            val ang = (t.pan.coerceIn(-1f, 1f) + 1f) * (PI.toFloat() / 4f)
            val gl = cos(ang) * t.volume * 1.414f
            val gr = sin(ang) * t.volume * 1.414f
            for (i in 0 until n) {
                gate += (g - gate) * 0.02f
                out[(offset + i) * 2] += tl[i] * gl * gate
                out[(offset + i) * 2 + 1] += tr[i] * gr * gate
            }
        }
    }

    companion object { private const val SEG = 64 }
}
