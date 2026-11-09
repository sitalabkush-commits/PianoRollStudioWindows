package com.pianoroll.ui.pianoroll

import com.pianoroll.core.model.Note
import com.pianoroll.core.model.NoteSource
import com.pianoroll.core.model.PPQ
import com.pianoroll.core.model.ScaleType

/** Pure functions (no Compose, no android.*). Unit-tested on JVM. */
object PianoRollMath {
    fun ticksPerBar(num: Int, den: Int): Long = PPQ.toLong() * 4L * num / den

    /** div: 4 = 1/4 note, 8 = 1/8, 16 = 1/16, 32 = 1/32. */
    fun gridTicks(div: Int): Long = PPQ.toLong() * 4L / div

    fun floorDiv(a: Long, b: Long): Long {
        val q = a / b
        return if (a % b != 0L && ((a < 0) != (b < 0))) q - 1 else q
    }

    fun snapDown(tick: Long, grid: Long): Long = floorDiv(tick, grid) * grid
    fun snapRound(tick: Long, grid: Long): Long = floorDiv(tick + grid / 2, grid) * grid

    fun scaleIntervals(scale: ScaleType): IntArray = when (scale) {
        ScaleType.MAJOR -> intArrayOf(0, 2, 4, 5, 7, 9, 11)
        ScaleType.MINOR -> intArrayOf(0, 2, 3, 5, 7, 8, 10)
        ScaleType.DORIAN -> intArrayOf(0, 2, 3, 5, 7, 9, 10)
        ScaleType.PHRYGIAN -> intArrayOf(0, 1, 3, 5, 7, 8, 10)
        ScaleType.LYDIAN -> intArrayOf(0, 2, 4, 6, 7, 9, 11)
        ScaleType.MIXOLYDIAN -> intArrayOf(0, 2, 4, 5, 7, 9, 10)
        ScaleType.HARMONIC_MINOR -> intArrayOf(0, 2, 3, 5, 7, 8, 11)
        ScaleType.PENTATONIC_MAJOR -> intArrayOf(0, 2, 4, 7, 9)
        ScaleType.PENTATONIC_MINOR -> intArrayOf(0, 3, 5, 7, 10)
        ScaleType.BLUES -> intArrayOf(0, 3, 5, 6, 7, 10)
        ScaleType.CHROMATIC -> IntArray(12) { it }
    }

    fun inScale(pitch: Int, root: Int, scale: ScaleType): Boolean {
        val pc = (((pitch - root) % 12) + 12) % 12
        return scaleIntervals(scale).contains(pc)
    }

    fun nearestInScale(pitch: Int, root: Int, scale: ScaleType): Int {
        for (d in 0..6) {
            val down = pitch - d
            if (down in 0..127 && inScale(down, root, scale)) return down
            val up = pitch + d
            if (up in 0..127 && inScale(up, root, scale)) return up
        }
        return pitch
    }

    fun isBlackKey(pitch: Int): Boolean = when (pitch % 12) { 1, 3, 6, 8, 10 -> true; else -> false }

    private val NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    /** MIDI 60 = C4. */
    fun noteName(pitch: Int): String = NAMES[pitch % 12] + (pitch / 12 - 1)

    // ---- note operations (return new lists; never mutate) ----

    /** A user edit of an auto-generated note locks it, so generators never overwrite it (CONTRACTS rule 4). */
    private fun Note.edited(): Note = copy(locked = locked || source != NoteSource.USER)

    fun moveNotes(
        base: List<Note>, ids: Set<String>, dTick: Long, dPitch: Int,
        snapPitch: ((Int) -> Int)?, maxStart: Long,
    ): List<Note> {
        val minStart = base.filter { it.id in ids }.minOfOrNull { it.startTick } ?: return base
        val dt = maxOf(dTick, -minStart)
        return base.map { n ->
            if (n.id !in ids) n else {
                var p = (n.pitch + dPitch).coerceIn(0, 127)
                if (snapPitch != null) p = snapPitch(p)
                n.edited().copy(startTick = (n.startTick + dt).coerceAtMost(maxStart), pitch = p)
            }
        }
    }

    fun resizeNotes(base: List<Note>, ids: Set<String>, dDur: Long, minDur: Long): List<Note> =
        base.map { n -> if (n.id !in ids) n else n.edited().copy(durationTick = maxOf(minDur, n.durationTick + dDur)) }

    fun setVelocity(base: List<Note>, ids: Set<String>, velocity: Int): List<Note> =
        base.map { n -> if (n.id !in ids) n else n.edited().copy(velocity = velocity.coerceIn(1, 127)) }

    fun notesInRect(notes: List<Note>, t0: Long, t1: Long, pLo: Int, pHi: Int): Set<String> =
        notes.filter { it.pitch in pLo..pHi && it.startTick < t1 && it.startTick + it.durationTick > t0 }
            .map { it.id }.toSet()
}
