package com.pianoroll.core.music

import com.pianoroll.core.model.ArpMode
import com.pianoroll.core.model.ArpOptions
import com.pianoroll.core.model.ChopMode
import com.pianoroll.core.model.ChordBlock
import com.pianoroll.core.model.ChordOptions
import com.pianoroll.core.model.ChordQuality
import com.pianoroll.core.model.Commands
import com.pianoroll.core.model.DrumGenre
import com.pianoroll.core.model.DrumOptions
import com.pianoroll.core.model.KeyResult
import com.pianoroll.core.model.Note
import com.pianoroll.core.model.NoteSource
import com.pianoroll.core.model.PPQ
import com.pianoroll.core.model.Project
import com.pianoroll.core.model.ScaleType
import com.pianoroll.core.model.TrackType
import com.pianoroll.core.model.Voicing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val BEAT = PPQ.toLong()
private const val BAR = PPQ * 4L

private fun seq(pitches: List<Int>, beats: Long = 1L, start: Long = 0L): List<Note> =
    pitches.mapIndexed { i, p -> Note(pitch = p, startTick = start + i * beats * BEAT, durationTick = beats * BEAT) }

private val CMAJ = KeyResult(0, ScaleType.MAJOR)

/** C-E-G-C | F-A-C-A | G-B-D-B | C-E-G-C */
private fun progressionMelody(): List<Note> =
    seq(listOf(60, 64, 67, 72, 65, 69, 72, 69, 67, 71, 74, 71, 60, 64, 67, 72))

class KeyAndChordTest {
    @Test
    fun detectsCMajor() {
        val k = DefaultChordGenerator().detectKey(seq(listOf(60, 62, 64, 65, 67, 69, 67, 65, 64, 62, 60)))
        assertEquals(0, k.root)
        assertEquals(ScaleType.MAJOR, k.scale)
    }

    @Test
    fun detectsAMinor() {
        // A C E A | B C D E | F E D C B | G# A(long)
        val pitches = listOf(57, 60, 64, 69, 59, 60, 62, 64, 65, 64, 62, 60, 59, 56, 57)
        val notes = pitches.mapIndexed { i, p ->
            Note(pitch = p, startTick = i * BEAT, durationTick = if (i == pitches.lastIndex) 4 * BEAT else BEAT)
        }
        val k = DefaultChordGenerator().detectKey(notes)
        assertEquals(9, k.root)
        assertEquals(ScaleType.MINOR, k.scale)
    }

    @Test
    fun emptyMelodyGivesNoChords() {
        assertTrue(DefaultChordGenerator().generate(emptyList(), CMAJ, ChordOptions(), BAR).isEmpty())
    }

    @Test
    fun simpleProgressionFollowsMelody() {
        val blocks = DefaultChordGenerator().generate(progressionMelody(), CMAJ, ChordOptions(), BAR)
        assertEquals(4, blocks.size)
        assertEquals(listOf(0L, BAR, 2 * BAR, 3 * BAR), blocks.map { it.startTick })
        assertEquals(listOf(0, 5, 7, 0), blocks.map { it.rootPc })
        assertTrue(blocks.all { it.quality == ChordQuality.MAJOR })
    }

    @Test
    fun simpleChordsAreDiatonicTriads() {
        val major = Scales.pitchClasses(0, ScaleType.MAJOR)
        val blocks = DefaultChordGenerator().generate(progressionMelody(), CMAJ, ChordOptions(), BAR)
        for (b in blocks) assertTrue(ChordTheory.pcs(b.rootPc, b.quality).all { it in major })
    }

    @Test
    fun sameSeedSameResult() {
        val gen = DefaultChordGenerator()
        val o = ChordOptions(seed = 7L)
        val a = gen.generate(progressionMelody(), CMAJ, o, BAR).map { it.rootPc to it.quality }
        val b = gen.generate(progressionMelody(), CMAJ, o, BAR).map { it.rootPc to it.quality }
        assertEquals(a, b)
    }

    @Test
    fun barsPerChordMakesLongerBlocks() {
        val blocks = DefaultChordGenerator().generate(progressionMelody(), CMAJ, ChordOptions(barsPerChord = 2), BAR)
        assertEquals(2, blocks.size)
        assertTrue(blocks.all { it.durationTick == 2 * BAR })
    }

    @Test
    fun suggestReturnsDistinctChordsTonicFirst() {
        val melody = seq(listOf(60, 64, 67, 72))
        val s = DefaultChordGenerator().suggest(melody, 0L, BAR, CMAJ, 3)
        assertEquals(3, s.size)
        assertEquals(3, s.map { it.rootPc to it.quality }.toSet().size)
        assertEquals(0, s[0].rootPc)
        assertEquals(ChordQuality.MAJOR, s[0].quality)
    }

    @Test
    fun richJazzUsesExtendedChords() {
        val blocks = DefaultChordGenerator().generate(
            progressionMelody(), CMAJ,
            ChordOptions(style = com.pianoroll.core.model.ChordStyle.JAZZ, complexity = com.pianoroll.core.model.Complexity.RICH), BAR,
        )
        assertTrue(blocks.any { ChordTheory.intervals(it.quality).size >= 4 })
    }
}

class VoicingTest {
    private val gen = DefaultChordGenerator()

    @Test
    fun cMajorTriadAtOctave3() {
        val notes = gen.toNotes(ChordBlock(startTick = 0, durationTick = BAR, rootPc = 0, quality = ChordQuality.MAJOR), Voicing())
        assertEquals(listOf(48, 52, 55), notes.map { it.pitch })
        assertTrue(notes.all { it.source == NoteSource.AUTO_CHORD && it.durationTick == BAR })
    }

    @Test
    fun firstInversionMaj7() {
        val b = ChordBlock(startTick = 0, durationTick = BAR, rootPc = 0, quality = ChordQuality.MAJ7)
        assertEquals(listOf(52, 55, 59, 60), gen.toNotes(b, Voicing(inversion = 1)).map { it.pitch })
    }

    @Test
    fun slashChordPutsBassBelow() {
        val b = ChordBlock(startTick = 0, durationTick = BAR, rootPc = 0, quality = ChordQuality.MAJOR, bassPc = 4)
        val pitches = gen.toNotes(b, Voicing()).map { it.pitch }
        assertEquals(40, pitches.first())
        assertEquals(4, pitches.size)
    }

    @Test
    fun lockedBlockGivesLockedNotes() {
        val b = ChordBlock(startTick = 0, durationTick = BAR, rootPc = 7, quality = ChordQuality.DOM7, locked = true)
        assertTrue(gen.toNotes(b, Voicing()).all { it.locked })
    }

    @Test
    fun identifyRoundTrip() {
        val c = ChordTheory.identify(listOf(48, 52, 55))
        assertNotNull(c)
        assertEquals(0, c.rootPc)
        assertEquals(ChordQuality.MAJOR, c.quality)
        assertEquals(null, c.bassPc)
        val slash = ChordTheory.identify(listOf(40, 48, 52, 55))
        assertNotNull(slash)
        assertEquals(0, slash.rootPc)
        assertEquals(4, slash.bassPc)
    }

    @Test
    fun chordNames() {
        assertEquals("Cmaj7", ChordTheory.name(ChordBlock(startTick = 0, durationTick = 1, rootPc = 0, quality = ChordQuality.MAJ7)))
        assertEquals("Am", ChordTheory.name(ChordBlock(startTick = 0, durationTick = 1, rootPc = 9, quality = ChordQuality.MINOR)))
        assertEquals("C/E", ChordTheory.name(ChordBlock(startTick = 0, durationTick = 1, rootPc = 0, quality = ChordQuality.MAJOR, bassPc = 4)))
    }

    @Test
    fun voiceLeadingKeepsChordsClose() {
        val blocks = listOf(
            ChordBlock(startTick = 0, durationTick = BAR, rootPc = 0, quality = ChordQuality.MAJOR),
            ChordBlock(startTick = BAR, durationTick = BAR, rootPc = 7, quality = ChordQuality.MAJOR),
        )
        val notes = gen.toNotesVoiceLed(blocks)
        val first = notes.filter { it.startTick == 0L }.map { it.pitch }.average()
        val second = notes.filter { it.startTick == BAR }.map { it.pitch }.average()
        assertTrue(kotlin.math.abs(first - second) <= 4.0)
    }
}

class ArpTest {
    private val chord = listOf(60, 64, 67).map { Note(pitch = it, startTick = 0, durationTick = BAR) }
    private val arp = DefaultArpeggiator()

    @Test
    fun upPattern() {
        val out = arp.apply(chord, ArpOptions(mode = ArpMode.UP, rateDivision = 8, gate = 1f))
        assertEquals(8, out.size)
        assertEquals(listOf(60, 64, 67, 60, 64, 67, 60, 64), out.map { it.pitch })
        assertTrue(out.all { it.source == NoteSource.AUTO_ARP && it.startTick < BAR })
    }

    @Test
    fun downAndUpDown() {
        assertEquals(67, arp.apply(chord, ArpOptions(mode = ArpMode.DOWN, rateDivision = 8)).first().pitch)
        val ud = arp.apply(chord, ArpOptions(mode = ArpMode.UP_DOWN, rateDivision = 8)).map { it.pitch }
        assertEquals(listOf(60, 64, 67, 64), ud.take(4))
    }

    @Test
    fun octaveRangeExtendsPool() {
        val out = arp.apply(chord, ArpOptions(mode = ArpMode.UP, rateDivision = 8, octaveRange = 2)).map { it.pitch }
        assertEquals(listOf(60, 64, 67, 72, 76, 79), out.take(6))
    }

    @Test
    fun randomIsDeterministicAndUsesChordTones() {
        val o = ArpOptions(mode = ArpMode.RANDOM, rateDivision = 16)
        val a = arp.apply(chord, o).map { it.pitch }
        assertEquals(a, arp.apply(chord, o).map { it.pitch })
        assertTrue(a.all { it in listOf(60, 64, 67) })
    }

    @Test
    fun gateShortensNotes() {
        val out = arp.apply(chord, ArpOptions(rateDivision = 8, gate = 0.5f))
        assertTrue(out.all { it.durationTick == 120L })
    }
}

class DrumTest {
    private val gen = DefaultDrumGenerator()

    @Test
    fun allGenresStayInsideBarsAndAreAutoDrum() {
        for (g in DrumGenre.values()) {
            for (inten in listOf(0f, 0.5f, 1f)) {
                val notes = gen.generate(DrumOptions(genre = g, intensity = inten, swing = 1f, fill = true, seed = 3L), 4)
                assertTrue(notes.isNotEmpty())
                assertTrue(notes.all { it.startTick >= 0 && it.startTick < 4 * BAR })
                assertTrue(notes.all { it.pitch in 0..127 && it.velocity in 1..127 && it.source == NoteSource.AUTO_DRUM })
            }
        }
    }

    @Test
    fun edmIsFourOnTheFloor() {
        val kicks = gen.generate(DrumOptions(genre = DrumGenre.EDM, intensity = 0f), 2).filter { it.pitch == 36 }
        assertEquals(8, kicks.size)
        assertEquals(List(8) { it * BEAT }, kicks.map { it.startTick })
    }

    @Test
    fun deterministicForSeed() {
        val o = DrumOptions(genre = DrumGenre.TRAP, intensity = 0.8f, seed = 11L)
        assertEquals(gen.generate(o, 4).map { Triple(it.pitch, it.startTick, it.velocity) },
            gen.generate(o, 4).map { Triple(it.pitch, it.startTick, it.velocity) })
    }

    @Test
    fun higherIntensityMeansMoreNotes() {
        val low = gen.generate(DrumOptions(genre = DrumGenre.POP, intensity = 0.1f), 1).size
        val high = gen.generate(DrumOptions(genre = DrumGenre.POP, intensity = 1f), 1).size
        assertTrue(high > low)
    }

    @Test
    fun swingDelaysOffbeatSixteenths() {
        val straight = gen.generate(DrumOptions(genre = DrumGenre.POP, intensity = 1f, swing = 0f), 1)
        val swung = gen.generate(DrumOptions(genre = DrumGenre.POP, intensity = 1f, swing = 1f), 1)
        assertTrue(straight.any { it.startTick == 120L })
        assertTrue(swung.none { it.startTick == 120L })
    }

    @Test
    fun fillReplacesLastBeatOfLastBar() {
        val notes = gen.generate(DrumOptions(genre = DrumGenre.POP, fill = true), 2)
        val fillStart = BAR + 12 * 120L
        assertEquals(listOf(38, 50, 47, 45), notes.filter { it.startTick >= fillStart }.map { it.pitch })
    }
}

class TransposeTest {
    private val eng = DefaultTransposeEngine()

    @Test
    fun chromaticShift() {
        assertEquals(listOf(61, 65, 68), eng.transpose(seq(listOf(60, 64, 67)), 1, false, CMAJ).map { it.pitch })
    }

    @Test
    fun scaleAwareShiftStaysDiatonic() {
        val src = seq(listOf(60, 64, 67))
        assertEquals(listOf(62, 65, 69), eng.transpose(src, 1, true, CMAJ).map { it.pitch })
        assertEquals(listOf(67, 71, 74), eng.transpose(src, 7, true, CMAJ).map { it.pitch })
        assertEquals(listOf(72, 76, 79), eng.transpose(src, 12, true, CMAJ).map { it.pitch })
    }

    @Test
    fun scaleAwareNeverLeavesTheKey() {
        val major = Scales.pitchClasses(0, ScaleType.MAJOR)
        val src = seq(listOf(48, 50, 52, 53, 55, 57, 59, 60, 62, 64))
        for (s in -12..12) assertTrue(eng.transpose(src, s, true, CMAJ).all { it.pitch.mod(12) in major }, "shift $s")
    }

    @Test
    fun lockedNotesDoNotMove() {
        val n = Note(pitch = 60, startTick = 0, durationTick = BEAT, locked = true)
        assertEquals(60, eng.transpose(listOf(n), 5, false, CMAJ)[0].pitch)
    }

    @Test
    fun staysInMidiRange() {
        val out = eng.transpose(seq(listOf(125, 2)), 12, false, CMAJ).map { it.pitch }
        assertTrue(out.all { it in 0..127 })
    }
}

class ChopTest {
    private val eng = DefaultChopEngine()
    private val four = seq(listOf(60, 62, 64, 65))   // quarter notes

    @Test
    fun reverseSlices() {
        val out = eng.chop(four, 4, ChopMode.REVERSE)
        assertEquals(listOf(65, 64, 62, 60), out.sortedBy { it.startTick }.map { it.pitch })
    }

    @Test
    fun repeatStutters() {
        val out = eng.chop(four, 4, ChopMode.REPEAT)
        assertEquals(listOf(60, 60, 64, 64), out.sortedBy { it.startTick }.map { it.pitch })
    }

    @Test
    fun shuffleKeepsMaterial() {
        val out = eng.chop(four, 4, ChopMode.SHUFFLE, seed = 5L)
        assertEquals(setOf(60, 62, 64, 65), out.map { it.pitch }.toSet())
        assertEquals(4 * BEAT, out.sumOf { it.durationTick })
    }

    @Test
    fun longNoteIsCutIntoPieces() {
        val out = eng.chop(listOf(Note(pitch = 60, startTick = 0, durationTick = 2 * BEAT)), 8, ChopMode.REVERSE)
        assertEquals(4, out.size)
        assertTrue(out.all { it.durationTick == 240L })
    }

    @Test
    fun lockedNotesStay() {
        val locked = Note(id = "L", pitch = 70, startTick = 0, durationTick = BEAT, locked = true)
        val out = eng.chop(listOf(locked) + seq(listOf(62, 64), start = BEAT), 4, ChopMode.REVERSE)
        val l = out.first { it.id == "L" }
        assertEquals(0L, l.startTick)
        assertEquals(70, l.pitch)
    }

    @Test
    fun slicePadsGetOwnPitches() {
        val out = eng.slicePads(four, 4, firstPad = 36)
        assertEquals(listOf(36, 37, 38, 39), out.sortedBy { it.startTick }.map { it.pitch })
    }
}

class AutoCommandsTest {
    private fun setup(): Triple<Project, String, Triple<String, String, String>> {
        var p = Project.new("t")
        val pat = p.patterns[0]
        val mel = pat.tracks.first { it.type == TrackType.MELODY }
        val chord = pat.tracks.first { it.type == TrackType.CHORD }
        val drums = pat.tracks.first { it.type == TrackType.DRUMS }
        p = Commands.replaceNotes(pat.id, mel.id, progressionMelody()).apply(p)
        return Triple(p, pat.id, Triple(mel.id, chord.id, drums.id))
    }

    @Test
    fun autoChordsWriteAutoChordNotes() {
        val (p, pid, ids) = setup()
        val out = AutoCommands.autoChords(pid, ids.first, ids.second).apply(p)
        val notes = out.patterns[0].tracks.first { it.id == ids.second }.notes
        assertTrue(notes.isNotEmpty())
        assertTrue(notes.all { it.source == NoteSource.AUTO_CHORD })
        assertEquals(4, notes.map { it.startTick }.toSet().size)
    }

    @Test
    fun generatorsKeepUserLockedAndRespectManualMode() {
        val (p0, pid, ids) = setup()
        val user = Note(id = "user", pitch = 40, startTick = 0, durationTick = BEAT, source = NoteSource.USER)
        val locked = Note(id = "locked", pitch = 50, startTick = 0, durationTick = BAR, locked = true, source = NoteSource.AUTO_CHORD)
        val old = Note(id = "old", pitch = 52, startTick = BAR, durationTick = BAR, source = NoteSource.AUTO_CHORD)
        val p = Commands.replaceNotes(pid, ids.second, listOf(user, locked, old)).apply(p0)
        val out = AutoCommands.autoChords(pid, ids.first, ids.second).apply(p)
        val notes = out.patterns[0].tracks.first { it.id == ids.second }.notes
        assertTrue(notes.any { it.id == "user" })
        assertTrue(notes.any { it.id == "locked" })
        assertTrue(notes.none { it.id == "old" })
        // The locked region (bar 1) gets no fresh chord notes.
        assertEquals(1, notes.count { it.source == NoteSource.AUTO_CHORD && it.startTick == 0L })

        val manual = Commands.updateTrack(pid, ids.second) { it.copy(autoMode = false) }.apply(p)
        assertEquals(manual, AutoCommands.autoChords(pid, ids.first, ids.second).apply(manual))
    }

    @Test
    fun autoDrumsFillPatternAndKeepUserNotes() {
        val (p0, pid, ids) = setup()
        val user = Note(id = "u", pitch = 36, startTick = 5L, durationTick = 10L, source = NoteSource.USER)
        val p = Commands.replaceNotes(pid, ids.third, listOf(user)).apply(p0)
        val out = AutoCommands.autoDrums(pid, ids.third, DrumOptions(genre = DrumGenre.POP)).apply(p)
        val notes = out.patterns[0].tracks.first { it.id == ids.third }.notes
        assertTrue(notes.any { it.id == "u" })
        assertTrue(notes.count { it.source == NoteSource.AUTO_DRUM } > 8)
        assertTrue(notes.all { it.startTick < out.patterns[0].lengthBars * BAR })
    }

    @Test
    fun transposeProjectMovesKeyButNotDrums() {
        val (p0, pid, ids) = setup()
        val p = AutoCommands.autoDrums(pid, ids.third).apply(p0)
        val out = AutoCommands.transposeProject(2, false).apply(p)
        assertEquals(2, out.keyRoot)
        val tracks = out.patterns[0].tracks
        assertEquals(p.patterns[0].tracks.first { it.id == ids.third }.notes, tracks.first { it.id == ids.third }.notes)
        assertEquals(progressionMelody().map { it.pitch + 2 }, tracks.first { it.id == ids.first }.notes.map { it.pitch })
    }

    @Test
    fun replaceChordAndLock() {
        val (p0, pid, ids) = setup()
        val p = AutoCommands.autoChords(pid, ids.first, ids.second).apply(p0)
        val replaced = AutoCommands.replaceChord(pid, ids.second, 0L, ChordQuality.SUS4).apply(p)
        val blocks = AutoCommands.chordBlocks(replaced.patterns[0].tracks.first { it.id == ids.second })
        assertEquals(ChordQuality.SUS4, blocks.first { it.startTick == 0L }.quality)
        val locked = AutoCommands.setChordLock(pid, ids.second, 0L, true).apply(replaced)
        val again = AutoCommands.autoChords(pid, ids.first, ids.second, ChordOptions(seed = 9L)).apply(locked)
        val notes0 = again.patterns[0].tracks.first { it.id == ids.second }.notes.filter { it.startTick == 0L }
        assertTrue(notes0.all { it.locked })
        assertEquals(ChordQuality.SUS4, AutoCommands.chordBlocks(again.patterns[0].tracks.first { it.id == ids.second }).first { it.startTick == 0L }.quality)
    }

    @Test
    fun setKeyAndRegenerateFollowsNewKey() {
        val (p0, pid, ids) = setup()
        val out = AutoCommands.setKeyAndRegenerateChords(2, ScaleType.MAJOR, pid, ids.first, ids.second).apply(p0)
        assertEquals(2, out.keyRoot)
        assertTrue(out.patterns[0].tracks.first { it.id == ids.second }.notes.isNotEmpty())
    }
}

class PhraseTest {
    @Test
    fun builtInsAreValidAndUnique() {
        val all = BuiltInPhrases.all()
        assertTrue(all.size >= 10)
        assertEquals(all.size, all.map { it.id }.toSet().size)
        for (ph in all) {
            assertTrue(ph.notes.isNotEmpty(), ph.id)
            assertTrue(ph.notes.all { it.pitch in 0..127 && it.startTick >= 0 && it.startTick < ph.lengthTicks }, ph.id)
        }
    }

    @Test
    fun saveSelectionNormalisesAndPlaceCopies() {
        val sel = seq(listOf(60, 62), start = 2 * BAR + BEAT)
        val ph = PhraseTools.fromSelection("mine", "Melody", sel)
        assertEquals(0L, ph.notes.minOf { it.startTick })
        assertEquals(BAR, ph.lengthTicks)
        val placed = PhraseTools.place(ph, BAR, semitones = 2)
        assertEquals(listOf(62, 64), placed.map { it.pitch })
        assertTrue(placed.all { it.source == NoteSource.PHRASE && it.startTick >= BAR })
    }

    @Test
    fun jsonRoundTrip() {
        val all = BuiltInPhrases.all()
        assertEquals(all, PhraseJson.decode(PhraseJson.encode(all)))
    }

    @Test
    fun libraryReplacesSameId() {
        val lib = InMemoryPhraseLibrary(emptyList())
        lib.save(com.pianoroll.core.music.Phrase("a", "A", "Melody", BAR, emptyList()))
        lib.save(com.pianoroll.core.music.Phrase("a", "A2", "Melody", BAR, emptyList()))
        assertEquals(listOf("A2"), lib.all().map { it.name })
    }

    @Test
    fun persistentLibraryStoresOnlyUserPhrases() {
        var stored: String? = null
        val storage = object : PhraseStorage {
            override fun read(): String? = stored
            override fun write(text: String) { stored = text }
        }
        val lib = PersistentPhraseLibrary(storage)
        lib.save(Phrase("u1", "Mine", "Melody", BAR, seq(listOf(60))))
        val reloaded = PersistentPhraseLibrary(storage)
        assertTrue(reloaded.all().any { it.id == "u1" })
        assertTrue(PhraseJson.decode(stored!!).none { it.id.startsWith("builtin.") })
    }
}
