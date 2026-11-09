package com.pianoroll.core.music

import com.pianoroll.core.model.ChordBlock
import com.pianoroll.core.model.ScaleType
import com.pianoroll.core.model.ChordQuality as Q

data class DiatonicChord(val degree: Int, val rootPc: Int, val triad: Q)

data class ChordIdentity(val rootPc: Int, val quality: Q, val bassPc: Int?)

object ChordTheory {
    /** Intervals above the root in semitones, ascending. 9/11/13 are written as compound intervals. */
    fun intervals(q: Q): IntArray = when (q) {
        Q.MAJOR -> intArrayOf(0, 4, 7)
        Q.MINOR -> intArrayOf(0, 3, 7)
        Q.DIM -> intArrayOf(0, 3, 6)
        Q.AUG -> intArrayOf(0, 4, 8)
        Q.SUS2 -> intArrayOf(0, 2, 7)
        Q.SUS4 -> intArrayOf(0, 5, 7)
        Q.DOM7 -> intArrayOf(0, 4, 7, 10)
        Q.MAJ7 -> intArrayOf(0, 4, 7, 11)
        Q.MIN7 -> intArrayOf(0, 3, 7, 10)
        Q.DIM7 -> intArrayOf(0, 3, 6, 9)
        Q.M7B5 -> intArrayOf(0, 3, 6, 10)
        Q.ADD9 -> intArrayOf(0, 4, 7, 14)
        Q.DOM9 -> intArrayOf(0, 4, 7, 10, 14)
        Q.MIN9 -> intArrayOf(0, 3, 7, 10, 14)
        Q.DOM11 -> intArrayOf(0, 7, 10, 14, 17)   // 3rd omitted (clashes with 11th)
        Q.DOM13 -> intArrayOf(0, 4, 10, 14, 21)   // 5th omitted
    }

    fun pcs(rootPc: Int, q: Q): Set<Int> = intervals(q).map { (rootPc + it).mod(12) }.toSet()

    private val NOTE_NAMES = arrayOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")

    fun suffix(q: Q): String = when (q) {
        Q.MAJOR -> ""
        Q.MINOR -> "m"
        Q.DIM -> "dim"
        Q.AUG -> "aug"
        Q.SUS2 -> "sus2"
        Q.SUS4 -> "sus4"
        Q.DOM7 -> "7"
        Q.MAJ7 -> "maj7"
        Q.MIN7 -> "m7"
        Q.DIM7 -> "dim7"
        Q.M7B5 -> "m7b5"
        Q.ADD9 -> "add9"
        Q.DOM9 -> "9"
        Q.MIN9 -> "m9"
        Q.DOM11 -> "11"
        Q.DOM13 -> "13"
    }

    /** Display name (hidden by default in the UI, shown when the user toggles names on). */
    fun name(block: ChordBlock): String {
        val base = NOTE_NAMES[block.rootPc.mod(12)] + suffix(block.quality)
        val bass = block.bassPc ?: return base
        return base + "/" + NOTE_NAMES[bass.mod(12)]
    }

    /** Diatonic triads of the key, degree 0..6 (I..vii). */
    fun diatonicTriads(root: Int, scale: ScaleType): List<DiatonicChord> {
        val iv = Scales.intervals(Scales.harmonyScale(scale))
        return (0 until 7).map { i ->
            fun at(k: Int): Int = iv[k % 7] + (if (k >= 7) 12 else 0)
            val r = at(i)
            val third = at(i + 2) - r
            val fifth = at(i + 4) - r
            val q = when {
                third == 4 && fifth == 7 -> Q.MAJOR
                third == 3 && fifth == 7 -> Q.MINOR
                third == 3 && fifth == 6 -> Q.DIM
                third == 4 && fifth == 8 -> Q.AUG
                third == 3 -> Q.MINOR
                else -> Q.MAJOR
            }
            DiatonicChord(i, (root + r).mod(12), q)
        }
    }

    /**
     * Recognise a chord from sounding pitches (used to rebuild chord blocks from notes).
     * Returns null when nothing matches.
     */
    fun identify(pitches: List<Int>): ChordIdentity? {
        if (pitches.isEmpty()) return null
        val bassPc = pitches.min().mod(12)
        val all = pitches.map { it.mod(12) }.toSet()
        match(all, bassPc)?.let { (r, q) -> return ChordIdentity(r, q, if (r == bassPc) null else bassPc) }
        // Slash chord whose bass is not a chord tone: drop the lowest note and retry.
        val rest = pitches.filter { it.mod(12) != bassPc }.map { it.mod(12) }.toSet()
        if (rest.isNotEmpty()) {
            match(rest, -1)?.let { (r, q) -> return ChordIdentity(r, q, bassPc) }
        }
        return null
    }

    private fun match(set: Set<Int>, preferRoot: Int): Pair<Int, Q>? {
        var first: Pair<Int, Q>? = null
        for (q in Q.values()) {
            for (r in 0 until 12) {
                if (pcs(r, q) == set) {
                    if (r == preferRoot) return r to q
                    if (first == null) first = r to q
                }
            }
        }
        return first
    }
}
