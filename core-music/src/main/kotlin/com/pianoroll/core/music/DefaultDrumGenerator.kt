package com.pianoroll.core.music

import com.pianoroll.core.model.DrumGenre
import com.pianoroll.core.model.DrumOptions
import com.pianoroll.core.model.Note
import com.pianoroll.core.model.NoteSource
import com.pianoroll.core.model.PPQ
import kotlin.random.Random

/**
 * Step-pattern drum generator (4/4, 16 steps per bar, GM drum map).
 * Each line has a base pattern plus optional layers that turn on as intensity rises
 * (mid >= 0.35, high >= 0.70). Pattern chars: X strong, x normal, g ghost, r hat roll, . rest.
 */
class DefaultDrumGenerator : DrumGenerator {

    override fun generate(options: DrumOptions, bars: Int): List<Note> {
        if (bars <= 0) return emptyList()
        val rnd = Random(options.seed)
        val step = PPQ / 4
        val barTicks = PPQ * 4
        val inten = options.intensity.coerceIn(0f, 1f)
        val velScale = 0.65f + 0.35f * inten
        val swingTicks = (options.swing.coerceIn(0f, 1f) * step * 0.5f).toLong()
        val lines = DRUM_PATTERNS.getValue(options.genre)
        val out = ArrayList<Note>()

        fun add(pitch: Int, tick: Long, dur: Long, vel: Int) {
            out.add(
                Note(
                    pitch = pitch,
                    startTick = tick,
                    durationTick = dur,
                    velocity = vel.coerceIn(1, 127),
                    source = NoteSource.AUTO_DRUM,
                ),
            )
        }

        for (bar in 0 until bars) {
            for (line in lines) {
                for (s in 0 until 16) {
                    val ch = line.charAtStep(s, inten)
                    if (ch == '.') continue
                    val baseVel = when (ch) {
                        'X' -> 110
                        'x' -> 90
                        'g' -> 52
                        'r' -> 75
                        else -> 80
                    }
                    var v = (baseVel * velScale).toInt() + rnd.nextInt(-4, 5)
                    if (s % 4 == 0 && ch != 'g') v += 6
                    val tick = (bar * barTicks + s * step).toLong() + (if (s % 2 == 1) swingTicks else 0L)
                    if (ch == 'r') {
                        add(line.pitch, tick, step / 2L, v)
                        add(line.pitch, tick + step / 2, step / 2L, v - 10)
                    } else {
                        add(line.pitch, tick, step.toLong(), v)
                    }
                }
            }
            // Occasional kick pickup on odd bars (not in EDM: keep four-on-the-floor clean).
            if (bar % 2 == 1 && rnd.nextFloat() < inten * 0.6f && options.genre != DrumGenre.EDM) {
                val t = (bar * barTicks + 14 * step).toLong()
                if (out.none { it.pitch == 36 && it.startTick == t }) add(36, t, step.toLong(), 85)
            }
            if (bar == 0 && inten >= 0.6f && options.genre != DrumGenre.LOFI) {
                add(49, 0L, (PPQ * 2).toLong(), 100)
            }
        }

        if (options.fill) {
            val fillStart = ((bars - 1) * barTicks + 12 * step).toLong()
            val fillEnd = (bars * barTicks).toLong()
            out.removeAll { it.startTick >= fillStart && it.startTick < fillEnd }
            val toms = if (options.genre == DrumGenre.BENGALI_FOLK) intArrayOf(64, 63, 62, 60) else intArrayOf(38, 50, 47, 45)
            for (i in 0 until 4) add(toms[i], fillStart + i * step, step.toLong(), 80 + i * 8 + (inten * 10f).toInt())
        }
        return out.sortedWith(compareBy({ it.startTick }, { it.pitch }))
    }
}

private class DrumLine(val pitch: Int, val base: String = "", val mid: String = "", val high: String = "") {
    init {
        for (p in listOf(base, mid, high)) require(p.isEmpty() || p.length == 16) { "drum pattern must have 16 steps: '$p'" }
    }

    fun charAtStep(s: Int, intensity: Float): Char {
        var c = if (base.isEmpty()) '.' else base[s]
        if (intensity >= 0.35f && mid.isNotEmpty() && mid[s] != '.') c = mid[s]
        if (intensity >= 0.70f && high.isNotEmpty() && high[s] != '.') c = high[s]
        return c
    }
}

private val DRUM_PATTERNS: Map<DrumGenre, List<DrumLine>> = mapOf(
    DrumGenre.POP to listOf(
        DrumLine(36, "X.......X.......", "..........x.....", "......x.......x."),
        DrumLine(38, "....X.......X...", "", ".............g.."),
        DrumLine(42, "x.x.x.x.x.x.x.x.", "xgxgxgxgxgxgxgxg", "xxxxxxxxxxxxxxxx"),
    ),
    DrumGenre.LOFI to listOf(
        DrumLine(36, "X.......x.x.....", "......x.........", "..............x."),
        DrumLine(38, "....X.......X...", ".......g.......g", ".g.....g........"),
        DrumLine(42, "x.x.x.x.x.x.x.x.", "x.g.x.g.x.g.x.g.", "xgxgxgxgxgxgxgxg"),
    ),
    DrumGenre.TRAP to listOf(
        DrumLine(36, "X.......X.x.....", "..x...........x.", "......x.....x..."),
        DrumLine(38, "........X.......", "", ""),
        DrumLine(39, "", "........x.......", ""),
        DrumLine(42, "x.x.x.x.x.x.x.x.", "xxxxxxxxxxxxxxxx", "xxxxxxxxxxxxrxrx"),
        DrumLine(46, "", "", "......x.......x."),
    ),
    DrumGenre.EDM to listOf(
        DrumLine(36, "X...X...X...X..."),
        DrumLine(39, "....X.......X..."),
        DrumLine(46, "..x...x...x...x."),
        DrumLine(42, "", "x.x.x.x.x.x.x.x.", "xxxxxxxxxxxxxxxx"),
        DrumLine(38, "", "", "....x.......x..."),
    ),
    DrumGenre.BENGALI_FOLK to listOf(
        DrumLine(36, "X.....x...X.....", "..........x.....", ""),
        DrumLine(64, "......X.......x.", "..x.............", ""),
        DrumLine(60, "..x...x...x...x.", "x.....x.....x...", ""),
        DrumLine(54, "x.x.x.x.x.x.x.x.", "xgxgxgxgxgxgxgxg", ""),
        DrumLine(37, "", "", "....x.......x..."),
    ),
)
