package com.pianoroll.export

import com.pianoroll.core.audio.AudioSink
import com.pianoroll.core.model.*
import java.io.ByteArrayOutputStream

class MemSink : AudioSink {
    val bytes = ByteArrayOutputStream()
    var closeCount = 0
    override fun write(buffer: ByteArray, offset: Int, length: Int) { bytes.write(buffer, offset, length) }
    override fun close() { closeCount++ }
    fun data(): ByteArray = bytes.toByteArray()
}

/** Minimal SMF reader, just enough to verify what MidiWriter produced. */
class Smf(val format: Int, val division: Int, val tracks: List<List<Msg>>) {
    class Msg(val tick: Long, val status: Int, val d1: Int, val d2: Int, val meta: ByteArray? = null)

    companion object {
        fun parse(b: ByteArray): Smf {
            var i = 0
            fun u8() = b[i++].toInt() and 0xFF
            fun u16() = (u8() shl 8) or u8()
            fun u32() = (u16() shl 16) or u16()
            fun tag() = String(b, i, 4, Charsets.US_ASCII).also { i += 4 }
            check(tag() == "MThd"); check(u32() == 6)
            val fmt = u16(); val n = u16(); val div = u16()
            val tracks = ArrayList<List<Msg>>()
            repeat(n) {
                check(tag() == "MTrk")
                val len = u32(); val end = i + len
                val msgs = ArrayList<Msg>(); var tick = 0L; var running = 0
                fun vlq(): Long { var v = 0L; while (true) { val c = u8(); v = (v shl 7) or (c and 0x7F).toLong(); if (c and 0x80 == 0) return v } }
                while (i < end) {
                    tick += vlq()
                    var st = u8()
                    if (st == 0xFF) {
                        val type = u8(); val l = vlq().toInt(); val data = b.copyOfRange(i, i + l); i += l
                        msgs.add(Msg(tick, 0xFF, type, l, data))
                    } else {
                        if (st < 0x80) { i--; st = running } else running = st
                        val hi = st and 0xF0
                        val d1 = u8(); val d2 = if (hi == 0xC0 || hi == 0xD0) 0 else u8()
                        msgs.add(Msg(tick, st, d1, d2))
                    }
                }
                check(i == end) { "track length mismatch" }
                tracks.add(msgs)
            }
            check(i == b.size) { "trailing bytes" }
            return Smf(fmt, div, tracks)
        }
    }
}

fun note(pitch: Int, start: Long, dur: Long = 240, vel: Int = 100) = Note(pitch = pitch, startTick = start, durationTick = dur, velocity = vel)

fun sampleProject(song: Boolean = false): Project {
    val mel = Track(name = "Melody", type = TrackType.MELODY, instrumentId = "gm:24", notes = listOf(note(60, 0), note(64, 480), note(67, 960)), volume = 0.5f, pan = -1f)
    val bass = Track(name = "Bass", type = TrackType.BASS, instrumentId = "gm:33", notes = listOf(note(36, 0, 1920)))
    val drums = Track(name = "Drums", type = TrackType.DRUMS, instrumentId = "gm:drums", notes = listOf(note(36, 0), note(38, 480)))
    val a = Pattern(id = "A", name = "Verse", lengthBars = 1, tracks = listOf(mel, bass, drums))
    val b = Pattern(id = "B", name = "Chorus", lengthBars = 1, tracks = listOf(
        mel.copy(id = "m2", notes = listOf(note(72, 0))), bass.copy(id = "b2", notes = emptyList()), drums.copy(id = "d2", notes = listOf(note(42, 0)))))
    return Project(id = "p", name = "Test Song", bpm = 100f, patterns = listOf(a, b), songMode = song,
        playlist = listOf(PlaylistClip(id = "c1", patternId = "A", startBar = 0, lengthBars = 2, lane = 0), PlaylistClip(id = "c2", patternId = "B", startBar = 2, lengthBars = 1, lane = 0)))
}
