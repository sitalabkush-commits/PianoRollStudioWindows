package com.pianoroll.core.music

import com.pianoroll.core.model.ArpMode
import com.pianoroll.core.model.ArpOptions
import com.pianoroll.core.model.Note
import com.pianoroll.core.model.NoteSource
import com.pianoroll.core.model.PPQ
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Turns block chords into arpeggios. Chords are groups of notes sharing start and duration. */
class DefaultArpeggiator : Arpeggiator {

    override fun apply(chordNotes: List<Note>, options: ArpOptions): List<Note> {
        val step = max(1L, (PPQ * 4L) / options.rateDivision.coerceIn(1, 64))
        val gate = options.gate.coerceIn(0.05f, 1f)
        val swing = options.swing.coerceIn(0f, 1f)
        val range = options.octaveRange.coerceIn(1, 4)
        val out = ArrayList<Note>()

        val groups = chordNotes.groupBy { it.startTick to it.durationTick }
            .entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))

        for ((key, group) in groups) {
            val start = key.first
            val end = start + key.second
            val pitches = group.map { it.pitch }.distinct().sorted()
            val pool = (0 until range).flatMap { o -> pitches.map { it + 12 * o } }.filter { it in 0..127 }
            if (pool.isEmpty() || end <= start) continue
            val seq: List<Int> = when (options.mode) {
                ArpMode.UP, ArpMode.RANDOM -> pool
                ArpMode.DOWN -> pool.reversed()
                ArpMode.UP_DOWN -> pool + pool.reversed().drop(1).dropLast(1)
            }
            val vel = group.map { it.velocity }.average().toInt().coerceIn(1, 127)
            val rnd = Random(start * 31 + group.size)
            var lastIdx = -1
            var i = 0
            while (true) {
                val shift = if (i % 2 == 1) (swing * step * 0.5f).toLong() else 0L
                val s = start + i * step + shift
                if (s >= end) break
                val pitch = if (options.mode == ArpMode.RANDOM) {
                    var idx = rnd.nextInt(pool.size)
                    if (pool.size > 1 && idx == lastIdx) idx = (idx + 1) % pool.size
                    lastIdx = idx
                    pool[idx]
                } else {
                    seq[i % seq.size]
                }
                val dur = max(1L, min((step * gate).toLong(), end - s))
                val v = if (i % 4 == 0) vel + 12 else vel
                out.add(
                    Note(
                        pitch = pitch,
                        startTick = s,
                        durationTick = dur,
                        velocity = v.coerceIn(1, 127),
                        source = NoteSource.AUTO_ARP,
                    ),
                )
                i++
            }
        }
        return out
    }
}
