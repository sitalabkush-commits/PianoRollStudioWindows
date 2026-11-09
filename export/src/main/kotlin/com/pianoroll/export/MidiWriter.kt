package com.pianoroll.export

import com.pianoroll.core.audio.AudioSink
import com.pianoroll.core.model.PPQ
import com.pianoroll.core.model.Project
import com.pianoroll.core.model.Track
import com.pianoroll.core.model.TrackType
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** MIDI export options. */
data class MidiOptions(
    /** true: skip muted tracks, and when any track is soloed export only the soloed ones ("what you hear"). */
    val respectMuteSolo: Boolean = true,
)

/**
 * Standard MIDI File, format 1, `PPQ` ticks per quarter note.
 * Track 0 = tempo + time signature. Then one MIDI track per distinct track name (all patterns merged in song mode).
 * Drums go to channel 10. Pure Kotlin, no android.*.
 */
object MidiWriter {
    private const val DRUM_CH = 9

    fun toBytes(project: Project, options: MidiOptions = MidiOptions()): ByteArray {
        val bar = Arrangement.barTicks(project)
        val segments = Arrangement.segments(project)
        val groups = lanes(project, options)

        val out = ByteArrayOutputStream()
        out.write("MThd".toByteArray(Charsets.US_ASCII))
        out.int(6); out.short(1); out.short(1 + groups.size); out.short(PPQ)

        out.chunk(tempoTrack(project))
        var melodicCh = 0
        for (g in groups) {
            val ch = if (g.drums) DRUM_CH else {
                if (melodicCh == DRUM_CH) melodicCh++          // skip channel 10
                (melodicCh % 16).also { melodicCh++ }
            }
            out.chunk(noteTrack(project, g, ch, segments, bar))
        }
        return out.toByteArray()
    }

    fun write(project: Project, out: AudioSink, options: MidiOptions = MidiOptions()) {
        val bytes = toBytes(project, options)
        try { out.write(bytes, 0, bytes.size) } finally { out.close() }
    }

    // ---- internals ----

    internal class Lane(val name: String, val type: TrackType, val first: Track, val trackIds: Set<String>) {
        val drums get() = type == TrackType.DRUMS || first.instrumentId == "gm:drums"
    }

    /** Tracks grouped by name, in first-appearance order. AUDIO tracks carry no notes and are skipped. */
    internal fun lanes(project: Project, options: MidiOptions): List<Lane> {
        val all = project.patterns.flatMap { it.tracks }.filter { it.type != TrackType.AUDIO }
        val anySolo = options.respectMuteSolo && all.any { it.solo }
        val audible = all.filter { t ->
            !options.respectMuteSolo || (!t.mute && (!anySolo || t.solo))
        }
        val byName = LinkedHashMap<String, MutableList<Track>>()
        for (t in audible) byName.getOrPut(t.name) { mutableListOf() }.add(t)
        return byName.map { (name, ts) -> Lane(name, ts.first().type, ts.first(), ts.map { it.id }.toSet()) }
    }

    private class Ev(val tick: Long, val order: Int, val bytes: ByteArray)

    private fun tempoTrack(p: Project): ByteArray {
        val b = ByteArrayOutputStream()
        b.meta(0, 0x03, p.name.toByteArray(Charsets.UTF_8))
        val usPerQuarter = (60_000_000.0 / p.bpm.coerceIn(20f, 400f)).roundToInt()
        b.meta(0, 0x51, byteArrayOf((usPerQuarter shr 16).toByte(), (usPerQuarter shr 8).toByte(), usPerQuarter.toByte()))
        val denPow = Integer.numberOfTrailingZeros(Integer.highestOneBit(p.timeSigDen.coerceAtLeast(1)))
        b.meta(0, 0x58, byteArrayOf(p.timeSigNum.coerceIn(1, 255).toByte(), denPow.toByte(), 24, 8))
        b.meta(0, 0x2F, ByteArray(0))
        return b.toByteArray()
    }

    private fun noteTrack(p: Project, lane: Lane, ch: Int, segments: List<Segment>, bar: Long): ByteArray {
        val evs = ArrayList<Ev>()
        val t = lane.first
        val program = programOf(t.instrumentId)
        if (!lane.drums) evs.add(Ev(0, 0, byteArrayOf((0xC0 or ch).toByte(), program.toByte())))
        evs.add(Ev(0, 0, byteArrayOf((0xB0 or ch).toByte(), 7, (t.volume.coerceIn(0f, 1f) * 127f).roundToInt().toByte())))
        evs.add(Ev(0, 0, byteArrayOf((0xB0 or ch).toByte(), 10, (((t.pan.coerceIn(-1f, 1f) + 1f) / 2f) * 127f).roundToInt().toByte())))

        for (seg in segments) {
            val patLen = seg.pattern.lengthBars * bar
            if (patLen <= 0) continue
            for (track in seg.pattern.tracks) {
                if (track.id !in lane.trackIds) continue
                var rep = 0L
                while (rep * patLen < seg.lengthTicks) {
                    val base = seg.startTick + rep * patLen
                    val limit = seg.startTick + seg.lengthTicks
                    for (n in track.notes) {
                        if (n.startTick >= patLen) continue
                        val s = base + n.startTick
                        if (s >= limit) continue
                        val e = minOf(s + maxOf(n.durationTick, 1L), limit)
                        val pitch = n.pitch.coerceIn(0, 127).toByte()
                        evs.add(Ev(s, 2, byteArrayOf((0x90 or ch).toByte(), pitch, n.velocity.coerceIn(1, 127).toByte())))
                        evs.add(Ev(e, 1, byteArrayOf((0x80 or ch).toByte(), pitch, 0)))   // offs sort before ons at the same tick
                    }
                    if (!p.songMode) break
                    rep++
                }
            }
        }
        evs.sortWith(compareBy<Ev> { it.tick }.thenBy { it.order })

        val b = ByteArrayOutputStream()
        b.meta(0, 0x03, lane.name.toByteArray(Charsets.UTF_8))
        var last = 0L
        for (e in evs) {
            b.vlq(e.tick - last); last = e.tick
            b.write(e.bytes)
        }
        b.meta(0, 0x2F, ByteArray(0))
        return b.toByteArray()
    }

    /** "gm:<program>" -> program; "gm:drums" / "sf2:..." -> 0 (an sf2 preset number after '#' is used when present). */
    internal fun programOf(instrumentId: String): Int = when {
        instrumentId.startsWith("gm:") -> instrumentId.removePrefix("gm:").toIntOrNull()?.coerceIn(0, 127) ?: 0
        instrumentId.startsWith("sf2:") -> instrumentId.substringAfter('#', "").toIntOrNull()?.coerceIn(0, 127) ?: 0
        else -> 0
    }

    private fun ByteArrayOutputStream.int(v: Int) { write(v ushr 24); write(v ushr 16); write(v ushr 8); write(v) }
    private fun ByteArrayOutputStream.short(v: Int) { write(v ushr 8); write(v) }
    private fun ByteArrayOutputStream.chunk(body: ByteArray) { write("MTrk".toByteArray(Charsets.US_ASCII)); int(body.size); write(body) }
    private fun ByteArrayOutputStream.meta(delta: Long, type: Int, data: ByteArray) {
        vlq(delta); write(0xFF); write(type); vlq(data.size.toLong()); write(data)
    }
    internal fun ByteArrayOutputStream.vlq(value: Long) {
        var v = value.coerceAtLeast(0L)
        val tmp = ByteArray(10); var i = 0
        tmp[i++] = (v and 0x7F).toByte(); v = v shr 7
        while (v > 0) { tmp[i++] = ((v and 0x7F) or 0x80).toByte(); v = v shr 7 }
        while (i > 0) write(tmp[--i].toInt())
    }
}
