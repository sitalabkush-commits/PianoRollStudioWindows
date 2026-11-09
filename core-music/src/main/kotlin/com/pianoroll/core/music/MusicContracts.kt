package com.pianoroll.core.music

import com.pianoroll.core.model.*

/** Claude D implements these. NO Android imports allowed in this module. */
interface ChordGenerator {
    fun detectKey(melody: List<Note>): KeyResult
    fun generate(melody: List<Note>, key: KeyResult, options: ChordOptions, ppqBarTicks: Long): List<ChordBlock>
    fun suggest(melody: List<Note>, barStartTick: Long, barTicks: Long, key: KeyResult, count: Int = 3): List<ChordBlock>
    /** Chord block -> notes shown on the Piano Roll (source = AUTO_CHORD). */
    fun toNotes(block: ChordBlock, voicing: Voicing = Voicing()): List<Note>
}

interface Arpeggiator { fun apply(chordNotes: List<Note>, options: ArpOptions): List<Note> }

interface DrumGenerator { fun generate(options: DrumOptions, bars: Int): List<Note> }

interface TransposeEngine {
    fun transpose(notes: List<Note>, semitones: Int, scaleAware: Boolean, key: KeyResult): List<Note>
}

interface ChopEngine { fun chop(notes: List<Note>, division: Int, mode: ChopMode, seed: Long = 0L): List<Note> }

/** Saved phrase. Library format is owned by Claude D. */
data class Phrase(val id: String, val name: String, val category: String, val lengthTicks: Long, val notes: List<Note>)
interface PhraseLibrary {
    fun all(): List<Phrase>
    fun save(phrase: Phrase)
}
