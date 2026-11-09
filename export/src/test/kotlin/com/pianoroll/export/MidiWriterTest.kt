package com.pianoroll.export

import com.pianoroll.core.model.*
import kotlin.test.*

class MidiWriterTest {
    private fun ons(t: List<Smf.Msg>) = t.filter { it.status and 0xF0 == 0x90 }

    @Test fun headerTempoAndTimeSignature() {
        val smf = Smf.parse(MidiWriter.toBytes(sampleProject()))
        assertEquals(1, smf.format); assertEquals(PPQ, smf.division)
        assertEquals(4, smf.tracks.size)                                   // tempo + Melody + Bass + Drums
        val tempo = smf.tracks[0].first { it.status == 0xFF && it.d1 == 0x51 }.meta!!
        val us = ((tempo[0].toInt() and 0xFF) shl 16) or ((tempo[1].toInt() and 0xFF) shl 8) or (tempo[2].toInt() and 0xFF)
        assertEquals(600_000, us)                                           // 100 bpm
        val ts = smf.tracks[0].first { it.d1 == 0x58 && it.status == 0xFF }.meta!!
        assertEquals(4, ts[0].toInt()); assertEquals(2, ts[1].toInt())      // 4/4 -> denominator 2^2
    }

    @Test fun notesChannelsProgramsAndMixer() {
        val smf = Smf.parse(MidiWriter.toBytes(sampleProject()))
        val mel = smf.tracks[1]; val drums = smf.tracks[3]
        assertEquals(listOf(60, 64, 67), ons(mel).map { it.d1 })
        assertEquals(listOf(0L, 480L, 960L), ons(mel).map { it.tick })
        assertTrue(ons(mel).all { it.status and 0x0F == 0 })               // first melodic -> channel 1
        assertEquals(24, mel.first { it.status and 0xF0 == 0xC0 }.d1)      // program from gm:24
        assertEquals(64, mel.first { it.status and 0xF0 == 0xB0 && it.d1 == 7 }.d2)   // volume 0.5
        assertEquals(0, mel.first { it.status and 0xF0 == 0xB0 && it.d1 == 10 }.d2)   // pan hard left
        assertTrue(ons(drums).all { it.status and 0x0F == 9 })             // drums -> channel 10
        assertTrue(drums.none { it.status and 0xF0 == 0xC0 })              // no program change on drums
    }

    @Test fun noteOffComesBeforeNoteOnAtSameTick() {
        val t = Track(name = "M", type = TrackType.MELODY, notes = listOf(note(60, 0, 480), note(60, 480, 480)))
        val smf = Smf.parse(MidiWriter.toBytes(Project(name = "x", patterns = listOf(Pattern(name = "p", tracks = listOf(t))))))
        val at480 = smf.tracks[1].filter { it.tick == 480L && (it.status and 0xF0 == 0x80 || it.status and 0xF0 == 0x90) }
        assertEquals(listOf(0x80, 0x90), at480.map { it.status and 0xF0 })
    }

    @Test fun songModeFlattensPlaylistAndRepeatsPattern() {
        val smf = Smf.parse(MidiWriter.toBytes(sampleProject(song = true)))
        assertEquals(4, smf.tracks.size)                                    // same-named tracks of A and B merge into one lane
        val mel = ons(smf.tracks[1])
        val bar = 1920L
        // Verse (1 bar) fills a 2-bar clip -> 3 notes at 0 and again at +1 bar; Chorus note at bar 2.
        assertEquals(listOf(0L, 480L, 960L, bar, bar + 480, bar + 960, 2 * bar), mel.map { it.tick })
        assertEquals(72, mel.last().d1)
        val drums = ons(smf.tracks[3])
        assertEquals(listOf(36, 38, 36, 38, 42), drums.map { it.d1 })
    }

    @Test fun clipEndTrimsNotes() {
        val p = sampleProject(song = true)
        val shortClip = p.copy(playlist = listOf(PlaylistClip(id = "c", patternId = "A", startBar = 0, lengthBars = 1)))
        val bass = Smf.parse(MidiWriter.toBytes(shortClip)).tracks[2]
        val off = bass.first { it.status and 0xF0 == 0x80 }
        assertEquals(1920L, off.tick)
        val long = p.copy(patterns = p.patterns.map { if (it.id == "A") it.copy(lengthBars = 2, tracks = it.tracks.map { t -> if (t.type == TrackType.BASS) t.copy(notes = listOf(note(36, 0, 99999))) else t }) else it },
            playlist = listOf(PlaylistClip(id = "c", patternId = "A", startBar = 0, lengthBars = 1)))
        assertEquals(1920L, Smf.parse(MidiWriter.toBytes(long)).tracks[2].first { it.status and 0xF0 == 0x80 }.tick)
    }

    @Test fun muteAndSolo() {
        val p = sampleProject()
        fun names(proj: Project, o: MidiOptions = MidiOptions()) = MidiWriter.lanes(proj, o).map { it.name }
        val muted = p.copy(patterns = p.patterns.map { it.copy(tracks = it.tracks.map { t -> if (t.name == "Bass") t.copy(mute = true) else t }) })
        assertEquals(listOf("Melody", "Drums"), names(muted))
        assertEquals(listOf("Melody", "Bass", "Drums"), names(muted, MidiOptions(respectMuteSolo = false)))
        val solo = p.copy(patterns = p.patterns.map { it.copy(tracks = it.tracks.map { t -> if (t.name == "Drums") t.copy(solo = true) else t }) })
        assertEquals(listOf("Drums"), names(solo))
    }

    @Test fun melodicChannelsSkipTen() {
        val tracks = (0 until 11).map { Track(name = "T$it", type = TrackType.PAD, notes = listOf(note(60, 0))) }
        val smf = Smf.parse(MidiWriter.toBytes(Project(name = "x", patterns = listOf(Pattern(name = "p", tracks = tracks)))))
        val channels = smf.tracks.drop(1).map { ons(it).first().status and 0x0F }
        assertEquals((0..8) + listOf(10, 11), channels)
    }

    @Test fun emptyProjectIsStillValid() {
        val smf = Smf.parse(MidiWriter.toBytes(Project(name = "empty")))
        assertEquals(1, smf.tracks.size)
    }

    @Test fun programParsing() {
        assertEquals(5, MidiWriter.programOf("gm:5")); assertEquals(0, MidiWriter.programOf("gm:drums"))
        assertEquals(127, MidiWriter.programOf("gm:999")); assertEquals(12, MidiWriter.programOf("sf2:x.sf2#12")); assertEquals(0, MidiWriter.programOf("sf2:x.sf2"))
    }

    @Test fun writeClosesSinkOnce() {
        val s = MemSink(); MidiWriter.write(sampleProject(), s)
        assertEquals(1, s.closeCount); assertEquals("MThd", String(s.data(), 0, 4))
    }
}
