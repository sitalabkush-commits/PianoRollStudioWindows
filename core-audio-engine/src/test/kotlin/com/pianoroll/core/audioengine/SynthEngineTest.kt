package com.pianoroll.core.audioengine

import com.pianoroll.core.audio.AudioSink
import com.pianoroll.core.audio.RenderSettings
import com.pianoroll.core.model.*
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SynthEngineTest {
    private val sr = 44100

    private fun projectWithNote(startTick: Long, dur: Long = PPQ.toLong(), mod: (Project) -> Project = { it }): Project {
        val base = Project.new("t")
        val pat = base.patterns.first()
        val melody = pat.tracks.first().copy(notes = listOf(Note(pitch = 69, startTick = startTick, durationTick = dur)))
        return mod(base.copy(patterns = listOf(pat.copy(tracks = listOf(melody) + pat.tracks.drop(1)))))
    }

    private fun renderSeconds(e: SynthEngine, sec: Double): FloatArray {
        val frames = (sec * sr).toInt()
        val out = FloatArray(frames * 2)
        var done = 0
        val chunk = FloatArray(512 * 2)
        while (done < frames) {
            val n = minOf(512, frames - done)
            e.render(chunk, n)
            System.arraycopy(chunk, 0, out, done * 2, n * 2)
            done += n
        }
        return out
    }

    @Test fun notePlaysAtSampleAccurateTime() {
        // 120 bpm: one beat = 0.5 s. Note on beat 1 (tick 480).
        val e = SynthEngine(sr)
        e.setProject(projectWithNote(PPQ.toLong())); e.play()
        val out = renderSeconds(e, 1.0)
        val first = (0 until out.size / 2).first { abs(out[it * 2]) > 1e-4f }
        assertTrue(first in (sr / 2)..(sr / 2 + 300), "first audible sample at $first")
    }

    @Test fun positionAdvancesWithTempo() {
        val e = SynthEngine(sr)
        e.setProject(projectWithNote(0)); e.play()
        renderSeconds(e, 1.0)
        assertTrue(abs(e.positionTick() - 960L) <= 2, "pos=${e.positionTick()}")   // 120bpm: 2 beats/s * 480
    }

    @Test fun muteSilences() {
        val e = SynthEngine(sr)
        e.setProject(projectWithNote(0) { p ->
            val pat = p.patterns.first()
            p.copy(patterns = listOf(pat.copy(tracks = pat.tracks.map { it.copy(mute = true) })))
        }); e.play()
        val out = renderSeconds(e, 0.5)
        assertTrue(out.all { abs(it) < 1e-6f })
    }

    @Test fun loopWraps() {
        val e = SynthEngine(sr)
        e.setProject(projectWithNote(0) { it.copy(loopStartBar = 0, loopEndBar = 1) }); e.play()
        renderSeconds(e, 3.0)   // 1 bar = 2 s at 120 bpm
        assertTrue(e.isPlaying())
        assertTrue(e.positionTick() < 1920L, "pos=${e.positionTick()}")
    }

    @Test fun stopsAtEndWithoutLoop() {
        val e = SynthEngine(sr)
        e.setProject(projectWithNote(0)); e.play()
        renderSeconds(e, 9.0)   // pattern = 4 bars = 8 s
        assertTrue(!e.isPlaying())
        assertEquals(0L, e.positionTick())
    }

    @Test fun previewMakesSound() {
        val e = SynthEngine(sr)
        val p = projectWithNote(0)
        e.setProject(p)
        e.previewNote(p.patterns.first().tracks.first().id, 60, 100)
        val out = renderSeconds(e, 0.2)
        assertTrue(out.any { abs(it) > 0.01f })
    }

    @Test fun offlineRenderProducesPcm() {
        val bos = java.io.ByteArrayOutputStream()
        val sink = object : AudioSink {
            override fun write(buffer: ByteArray, offset: Int, length: Int) { bos.write(buffer, offset, length) }
            override fun close() {}
        }
        val r = OfflineRenderer.render(projectWithNote(0), RenderSettings(), sink, tailSeconds = 0.5f)
        assertTrue(r.isSuccess, r.exceptionOrNull()?.toString())
        val bytes = bos.toByteArray()
        assertEquals(0, bytes.size % 4)
        assertTrue(bytes.size >= sr * 4 * 8)   // >= 8 s stereo 16-bit
        assertTrue(bytes.any { it.toInt() != 0 })
    }

    @Test fun reverbTailOutlivesNote() {
        val e = SynthEngine(sr)
        e.setProject(projectWithNote(0, dur = 240) { p ->
            val pat = p.patterns.first()
            val t = pat.tracks.first().copy(effects = listOf(EffectSpec(EffectType.REVERB, mapOf("mix" to 1f))))
            p.copy(patterns = listOf(pat.copy(tracks = listOf(t) + pat.tracks.drop(1))))
        }); e.play()
        renderSeconds(e, 0.6)
        val late = renderSeconds(e, 0.3)
        assertTrue(late.any { abs(it) > 1e-5f })
    }
}
