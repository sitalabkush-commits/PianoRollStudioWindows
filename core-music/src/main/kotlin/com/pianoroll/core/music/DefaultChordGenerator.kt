package com.pianoroll.core.music

import com.pianoroll.core.model.ChordBlock
import com.pianoroll.core.model.ChordOptions
import com.pianoroll.core.model.ChordStyle
import com.pianoroll.core.model.Complexity
import com.pianoroll.core.model.KeyResult
import com.pianoroll.core.model.Note
import com.pianoroll.core.model.NoteSource
import com.pianoroll.core.model.PPQ
import com.pianoroll.core.model.ScaleType
import com.pianoroll.core.model.Voicing
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random
import com.pianoroll.core.model.ChordQuality as Q

/**
 * Rule-based auto chord (v1): key detection (Krumhansl-Schmuckler), diatonic candidates per slot,
 * Viterbi search over a small progression-prior table, then style/complexity colouring.
 * Deterministic for a given seed. Pure Kotlin.
 */
class DefaultChordGenerator : ChordGenerator {

    // ---------------------------------------------------------------- key detection

    override fun detectKey(melody: List<Note>): KeyResult {
        val w = DoubleArray(12)
        for (n in melody) w[n.pitch.mod(12)] += max(1L, n.durationTick).toDouble()
        val total = w.sum()
        if (total <= 0.0) return KeyResult(0, ScaleType.MAJOR, 0f)
        val ordered = melody.sortedBy { it.startTick }
        w[ordered.last().pitch.mod(12)] += total * 0.10   // pieces tend to end on the tonic
        w[ordered.first().pitch.mod(12)] += total * 0.05

        var best = -2.0
        var second = -2.0
        var bestRoot = 0
        var bestScale = ScaleType.MAJOR
        for (root in 0 until 12) {
            for (minor in listOf(false, true)) {
                val prof = if (minor) MINOR_PROFILE else MAJOR_PROFILE
                val rotated = DoubleArray(12) { pc -> prof[(pc - root).mod(12)] }
                val c = correlation(w, rotated)
                if (c > best) {
                    second = best
                    best = c
                    bestRoot = root
                    bestScale = if (minor) ScaleType.MINOR else ScaleType.MAJOR
                } else if (c > second) {
                    second = c
                }
            }
        }
        val conf = 0.5 * (0.5 + 0.5 * best) + 0.5 * min(1.0, (best - second) * 4.0)
        return KeyResult(bestRoot, bestScale, conf.coerceIn(0.0, 1.0).toFloat())
    }

    private fun correlation(a: DoubleArray, b: DoubleArray): Double {
        val ma = a.average()
        val mb = b.average()
        var num = 0.0
        var da = 0.0
        var db = 0.0
        for (i in a.indices) {
            val x = a[i] - ma
            val y = b[i] - mb
            num += x * y
            da += x * x
            db += y * y
        }
        val den = sqrt(da * db)
        return if (den == 0.0) 0.0 else num / den
    }

    // ---------------------------------------------------------------- generation

    override fun generate(melody: List<Note>, key: KeyResult, options: ChordOptions, ppqBarTicks: Long): List<ChordBlock> {
        if (melody.isEmpty() || ppqBarTicks <= 0L) return emptyList()
        val slotTicks = ppqBarTicks * options.barsPerChord.coerceAtLeast(1)
        val end = melody.maxOf { it.startTick + it.durationTick }
        val slots = max(1L, (end + slotTicks - 1) / slotTicks).toInt()
        val cands = candidates(key)
        val n = cands.size
        val scalePcs = Scales.pitchClasses(key.root, key.scale)
        val noiseRnd = Random(options.seed)
        val noiseAmp = if (options.seed == 0L) 0.0 else 0.25

        val score = Array(slots) { t ->
            val w = slotWeights(melody, t * slotTicks, (t + 1) * slotTicks)
            DoubleArray(n) { c ->
                val cand = cands[c]
                emission(w, ChordTheory.pcs(cand.rootPc, cand.triad), scalePcs) * 2.0 +
                    styleBias(options.style, cand.degree) + noiseRnd.nextDouble() * noiseAmp
            }
        }

        val dp = Array(slots) { DoubleArray(n) }
        val back = Array(slots) { IntArray(n) }
        for (c in 0 until n) dp[0][c] = score[0][c] + (if (cands[c].degree == 0) 0.5 else 0.0)
        for (t in 1 until slots) {
            for (c in 0 until n) {
                var bestP = 0
                var bestV = Double.NEGATIVE_INFINITY
                for (p in 0 until n) {
                    val v = dp[t - 1][p] + transition(options.style, cands[p], cands[c])
                    if (v > bestV) {
                        bestV = v
                        bestP = p
                    }
                }
                dp[t][c] = bestV + score[t][c]
                back[t][c] = bestP
            }
        }
        var lastC = 0
        var lastV = Double.NEGATIVE_INFINITY
        for (c in 0 until n) {
            val v = dp[slots - 1][c] + (if (slots > 1 && cands[c].degree == 0) 0.7 else 0.0)
            if (v > lastV) {
                lastV = v
                lastC = c
            }
        }
        val path = IntArray(slots)
        path[slots - 1] = lastC
        for (t in slots - 1 downTo 1) path[t - 1] = back[t][path[t]]

        val colorRnd = Random(options.seed xor 0x5DEECE66DL)
        return (0 until slots).map { t ->
            val c = cands[path[t]]
            ChordBlock(
                startTick = t * slotTicks,
                durationTick = slotTicks,
                rootPc = c.rootPc,
                quality = colorize(c, options, colorRnd),
            )
        }
    }

    override fun suggest(melody: List<Note>, barStartTick: Long, barTicks: Long, key: KeyResult, count: Int): List<ChordBlock> {
        val w = slotWeights(melody, barStartTick, barStartTick + barTicks)
        val scalePcs = Scales.pitchClasses(key.root, key.scale)
        val seen = HashSet<Pair<Int, Q>>()
        val scored = ArrayList<Triple<Double, Int, Q>>()
        candidates(key).forEachIndexed { rank, c ->
            val dom = c.degree == 4 && c.triad == Q.MAJOR
            val variants = listOf(c.triad, seventhOf(c.triad, dom)).distinct()
            for ((i, q) in variants.withIndex()) {
                if (!seen.add(c.rootPc to q)) continue
                val s = emission(w, ChordTheory.pcs(c.rootPc, q), scalePcs) - i * 0.05 +
                    (if (c.degree == 0) 0.03 else 0.0) - rank * 0.001
                scored.add(Triple(s, c.rootPc, q))
            }
        }
        return scored.sortedByDescending { it.first }
            .take(count.coerceAtLeast(0))
            .map { ChordBlock(startTick = barStartTick, durationTick = barTicks, rootPc = it.second, quality = it.third) }
    }

    // ---------------------------------------------------------------- voicing / notes

    /** Sounding pitches of a block (ascending, slash bass first). */
    fun voicedPitches(block: ChordBlock, voicing: Voicing): List<Int> {
        val base = 12 * (voicing.octave + 1) + block.rootPc.mod(12)
        var p = ChordTheory.intervals(block.quality).map { base + it }
        val size = p.size
        val inv = voicing.inversion.mod(size)
        repeat(inv) { p = p.drop(1) + (p.first() + 12) }
        if (voicing.open && p.size >= 3) {
            val m = p.toMutableList()
            m[1] = m[1] + 12
            p = m.sorted()
        }
        while (p.max() > 127) p = p.map { it - 12 }
        while (p.min() < 0) p = p.map { it + 12 }
        val bass = block.bassPc
        if (bass != null) {
            var b = p.min() - 1
            while (b.mod(12) != bass.mod(12)) b--
            while (b < 0) b += 12
            p = listOf(b) + p
        }
        return p
    }

    override fun toNotes(block: ChordBlock, voicing: Voicing): List<Note> =
        voicedPitches(block, voicing).map {
            Note(
                pitch = it,
                startTick = block.startTick,
                durationTick = block.durationTick,
                velocity = 80,
                locked = block.locked,
                source = NoteSource.AUTO_CHORD,
            )
        }

    /**
     * Like [toNotes] for a whole progression, picking each inversion (and octave, base or one lower)
     * so the chord is as close as possible to the previous one (smooth voice leading).
     */
    fun toNotesVoiceLed(blocks: List<ChordBlock>, voicing: Voicing = Voicing()): List<Note> {
        val out = ArrayList<Note>()
        var prevCenter = Double.NaN
        for (b in blocks) {
            val size = ChordTheory.intervals(b.quality).size
            var v = voicing
            if (!prevCenter.isNaN()) {
                var bestDist = Double.MAX_VALUE
                for (oct in intArrayOf(voicing.octave - 1, voicing.octave)) {
                    for (inv in 0 until size) {
                        val cand = voicing.copy(inversion = inv, octave = oct)
                        val d = abs(voicedPitches(b, cand).average() - prevCenter)
                        if (d < bestDist) {
                            bestDist = d
                            v = cand
                        }
                    }
                }
            }
            prevCenter = voicedPitches(b, v).average()
            out.addAll(toNotes(b, v))
        }
        return out
    }

    // ---------------------------------------------------------------- internals

    private class Cand(val degree: Int, val rootPc: Int, val triad: Q)

    private fun candidates(key: KeyResult): List<Cand> {
        val base = ChordTheory.diatonicTriads(key.root, key.scale).map { Cand(it.degree, it.rootPc, it.triad) }
        // Natural minor: also allow the major dominant (harmonic-minor V).
        return if (Scales.harmonyScale(key.scale) == ScaleType.MINOR) base + Cand(4, base[4].rootPc, Q.MAJOR) else base
    }

    private fun slotWeights(melody: List<Note>, from: Long, to: Long): DoubleArray {
        val w = DoubleArray(12)
        for (n in melody) {
            val s = max(n.startTick, from)
            val e = min(n.startTick + n.durationTick, to)
            if (e <= s) continue
            var weight = (e - s).toDouble()
            if (n.startTick >= from && n.startTick < to) {
                if (n.startTick % (PPQ * 2L) == 0L) weight *= 1.5
                else if (n.startTick % PPQ.toLong() == 0L) weight *= 1.2
            }
            w[n.pitch.mod(12)] += weight
        }
        val sum = w.sum()
        if (sum > 0.0) for (i in 0 until 12) w[i] = w[i] / sum
        return w
    }

    private fun emission(weights: DoubleArray, chordPcs: Set<Int>, scalePcs: Set<Int>): Double {
        var s = 0.0
        for (pc in 0 until 12) {
            val w = weights[pc]
            if (w <= 0.0) continue
            s += w * when {
                pc in chordPcs -> 1.0
                pc in scalePcs -> -0.2
                else -> -0.4
            }
        }
        return s
    }

    private fun styleBias(style: ChordStyle, degree: Int): Double = when (style) {
        ChordStyle.POP -> if (degree in intArrayOf(0, 3, 4, 5)) 0.10 else 0.0
        ChordStyle.LOFI -> if (degree in intArrayOf(1, 2, 3, 5)) 0.12 else 0.0
        ChordStyle.JAZZ -> if (degree in intArrayOf(0, 1, 4)) 0.10 else 0.0
        ChordStyle.CINEMATIC -> if (degree in intArrayOf(0, 3, 5)) 0.12 else 0.0
        ChordStyle.FOLK -> if (degree in intArrayOf(0, 3, 4)) 0.15 else 0.0
    }

    private fun transition(style: ChordStyle, a: Cand, b: Cand): Double {
        var v = TRANS[a.degree][b.degree] * 0.6
        if (style == ChordStyle.JAZZ && ((a.degree == 1 && b.degree == 4) || (a.degree == 4 && b.degree == 0))) v += 0.4
        if (a.degree == 4 && a.triad == Q.MAJOR && b.degree == 0 && b.triad == Q.MINOR) v += 0.2
        if (a.degree == b.degree && a.triad == b.triad) v -= 0.15
        return v
    }

    private fun seventhOf(t: Q, dom: Boolean): Q = when (t) {
        Q.MAJOR -> if (dom) Q.DOM7 else Q.MAJ7
        Q.MINOR -> Q.MIN7
        Q.DIM -> Q.M7B5
        else -> t
    }

    private fun ninthOf(t: Q, dom: Boolean): Q = when (t) {
        Q.MAJOR -> if (dom) Q.DOM9 else Q.ADD9
        Q.MINOR -> Q.MIN9
        Q.DIM -> Q.M7B5
        else -> t
    }

    private fun colorize(c: Cand, o: ChordOptions, r: Random): Q {
        val t = c.triad
        if (o.complexity == Complexity.SIMPLE) return t
        val rich = o.complexity == Complexity.RICH
        val dom = c.degree == 4 && t == Q.MAJOR
        return when (o.style) {
            ChordStyle.POP -> when {
                dom -> Q.DOM7
                t == Q.MAJOR && rich -> if (r.nextBoolean()) Q.ADD9 else Q.MAJ7
                t == Q.MAJOR -> if (r.nextFloat() < 0.3f) Q.ADD9 else t
                t == Q.MINOR -> if (rich) Q.MIN7 else t
                else -> t
            }
            ChordStyle.LOFI ->
                if (rich) { if (t == Q.MAJOR && !dom) Q.MAJ7 else ninthOf(t, dom) } else seventhOf(t, dom)
            ChordStyle.JAZZ -> when {
                dom -> if (rich) (if (r.nextBoolean()) Q.DOM13 else Q.DOM9) else Q.DOM7
                t == Q.MINOR -> if (rich) Q.MIN9 else Q.MIN7
                else -> seventhOf(t, dom)
            }
            ChordStyle.CINEMATIC -> when {
                dom -> if (rich) (if (r.nextFloat() < 0.4f) Q.SUS4 else Q.DOM7) else t
                t == Q.MAJOR -> if (c.degree == 0 && !rich && r.nextFloat() < 0.35f) Q.SUS2 else Q.ADD9
                t == Q.MINOR -> if (rich) Q.MIN9 else Q.MIN7
                else -> t
            }
            ChordStyle.FOLK -> when {
                dom -> Q.DOM7
                rich && t == Q.MINOR -> Q.MIN7
                rich && t == Q.MAJOR && r.nextFloat() < 0.3f -> Q.ADD9
                else -> t
            }
        }
    }

    private companion object {
        // Krumhansl-Schmuckler key profiles (index = semitones above the tonic).
        val MAJOR_PROFILE = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        val MINOR_PROFILE = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

        // Progression prior: rows = from degree (I ii iii IV V vi vii), cols = to degree.
        val TRANS = arrayOf(
            doubleArrayOf(0.0, 0.5, 0.3, 1.0, 1.0, 0.8, 0.0),
            doubleArrayOf(0.1, 0.0, 0.2, 0.1, 1.2, 0.1, 0.3),
            doubleArrayOf(0.1, 0.4, 0.0, 1.0, 0.2, 0.9, 0.0),
            doubleArrayOf(0.8, 0.4, 0.0, 0.0, 1.0, 0.3, 0.2),
            doubleArrayOf(1.3, 0.1, 0.1, 0.4, 0.0, 0.9, 0.0),
            doubleArrayOf(0.2, 0.9, 0.2, 1.0, 0.8, 0.0, 0.0),
            doubleArrayOf(1.0, 0.0, 0.5, 0.0, 0.2, 0.2, 0.0),
        )
    }
}
