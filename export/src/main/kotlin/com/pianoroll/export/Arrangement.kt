package com.pianoroll.export

import com.pianoroll.core.model.PPQ
import com.pianoroll.core.model.Pattern
import com.pianoroll.core.model.Project

/** One stretch of the timeline filled by a pattern. Mirrors how the audio engine flattens a Project. */
data class Segment(val pattern: Pattern, val startTick: Long, val lengthTicks: Long)

/**
 * Flattening rules (must match core-audio-engine `Compiled.from`):
 * - not song mode (or empty playlist): the first pattern, played once.
 * - song mode: every playlist clip; the pattern repeats to fill the clip.
 */
object Arrangement {
    fun barTicks(p: Project): Long = PPQ.toLong() * 4L * p.timeSigNum / p.timeSigDen

    fun segments(p: Project): List<Segment> {
        val bar = barTicks(p)
        if (p.songMode && p.playlist.isNotEmpty()) {
            return p.playlist.mapNotNull { c ->
                val pat = p.patterns.firstOrNull { it.id == c.patternId } ?: return@mapNotNull null
                if (c.lengthBars <= 0) null else Segment(pat, c.startBar * bar, c.lengthBars * bar)
            }
        }
        val pat = p.patterns.firstOrNull() ?: return emptyList()
        return listOf(Segment(pat, 0L, pat.lengthBars * bar))
    }

    fun lengthTicks(p: Project): Long = segments(p).maxOfOrNull { it.startTick + it.lengthTicks } ?: 0L

    /** Rough render length in seconds (constant tempo, ignores time effects). Used for progress only. */
    fun lengthSeconds(p: Project): Double = lengthTicks(p).toDouble() / PPQ * 60.0 / p.bpm.coerceIn(20f, 400f)
}
