package com.pianoroll.core.music

import com.pianoroll.core.model.ChopMode
import com.pianoroll.core.model.Note
import com.pianoroll.core.model.PPQ
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Note-level chop. Notes are cut on a grid (division 2 = 1/2 note, 4 = 1/4, 8 = 1/8, 16 = 1/16),
 * then whole grid cells are rearranged. Locked notes are left exactly as they are.
 * Audio-block chopping is Claude C's side; this works on MIDI notes only.
 */
class DefaultChopEngine : ChopEngine {

    private class Piece(val cell: Long, val rel: Long, val dur: Long, val src: Note)

    private fun sliceTicks(division: Int): Long = max(1L, (PPQ * 4L) / division.coerceIn(1, 64))

    private fun cut(free: List<Note>, slice: Long): List<Piece> {
        val pieces = ArrayList<Piece>()
        for (n in free) {
            var t = max(0L, n.startTick)
            val end = n.startTick + n.durationTick
            while (t < end) {
                val cell = t / slice
                val pieceEnd = min(end, (cell + 1) * slice)
                pieces.add(Piece(cell, t - cell * slice, pieceEnd - t, n))
                t = pieceEnd
            }
        }
        return pieces
    }

    override fun chop(notes: List<Note>, division: Int, mode: ChopMode, seed: Long): List<Note> {
        val slice = sliceTicks(division)
        val locked = notes.filter { it.locked }
        val free = notes.filter { !it.locked }
        if (free.isEmpty()) return notes
        val pieces = cut(free, slice)
        if (pieces.isEmpty()) return notes

        val minCell = pieces.minOf { it.cell }
        val maxCell = pieces.maxOf { it.cell }
        val cells = (minCell..maxCell).toList()
        val rnd = Random(seed)
        val dstToSrc: Map<Long, Long> = when (mode) {
            ChopMode.SHUFFLE -> {
                val s = cells.shuffled(rnd)
                cells.indices.associate { cells[it] to s[it] }
            }
            ChopMode.REVERSE -> cells.associateWith { minCell + maxCell - it }
            ChopMode.REPEAT -> cells.associateWith { minCell + ((it - minCell) / 2) * 2 }
            ChopMode.REARRANGE -> cells.associateWith { cells[rnd.nextInt(cells.size)] }
        }

        val byCell = pieces.groupBy { it.cell }
        val out = ArrayList<Note>()
        for (dst in cells) {
            val src = dstToSrc[dst] ?: dst
            for (p in byCell[src] ?: emptyList()) {
                out.add(p.src.copy(id = com.pianoroll.core.model.newId(), startTick = dst * slice + p.rel, durationTick = p.dur))
            }
        }
        return (locked + out).sortedBy { it.startTick }
    }

    /**
     * Slice mode: every grid cell that holds sound becomes a trigger on its own pad
     * (firstPad, firstPad+1, ...). Plays the same rhythm of slices on a sampler-style track.
     */
    fun slicePads(notes: List<Note>, division: Int, firstPad: Int = 36): List<Note> {
        val slice = sliceTicks(division)
        val free = notes.filter { !it.locked }
        val pieces = cut(free, slice)
        if (pieces.isEmpty()) return emptyList()
        val minCell = pieces.minOf { it.cell }
        return pieces.groupBy { it.cell }.toSortedMap().map { (cell, ps) ->
            val first = ps.first().src
            first.copy(
                id = com.pianoroll.core.model.newId(),
                pitch = (firstPad + ((cell - minCell) % 16).toInt()).coerceIn(0, 127),
                startTick = cell * slice,
                durationTick = slice,
                velocity = ps.maxOf { it.src.velocity },
            )
        }
    }
}
