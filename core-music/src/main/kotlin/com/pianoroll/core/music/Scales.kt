package com.pianoroll.core.music

import com.pianoroll.core.model.ScaleType

/** Scale helpers. Pure Kotlin, no Android. */
object Scales {
    fun intervals(scale: ScaleType): IntArray = when (scale) {
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

    fun pitchClasses(root: Int, scale: ScaleType): Set<Int> =
        intervals(scale).map { (root + it).mod(12) }.toSet()

    fun isInScale(pitch: Int, root: Int, scale: ScaleType): Boolean =
        pitch.mod(12) in pitchClasses(root, scale)

    /** Nearest in-scale pitch. Ties go down. */
    fun snap(pitch: Int, root: Int, scale: ScaleType): Int {
        val pcs = pitchClasses(root, scale)
        for (d in 0..6) {
            if ((pitch - d).mod(12) in pcs) return pitch - d
            if ((pitch + d).mod(12) in pcs) return pitch + d
        }
        return pitch
    }

    /** Absolute scale-step index of an in-scale pitch (octave * size + degree). */
    fun degreeIndex(pitch: Int, root: Int, scale: ScaleType): Int {
        val iv = intervals(scale)
        val rel = pitch - root
        val oct = rel.floorDiv(12)
        val idx = iv.indexOf(rel.mod(12)).let { if (it < 0) 0 else it }
        return oct * iv.size + idx
    }

    fun pitchAtIndex(index: Int, root: Int, scale: ScaleType): Int {
        val iv = intervals(scale)
        val n = iv.size
        return root + index.floorDiv(n) * 12 + iv[index.mod(n)]
    }

    /** Scale used to build diatonic chords (always 7 notes). */
    fun harmonyScale(scale: ScaleType): ScaleType = when (scale) {
        ScaleType.PENTATONIC_MAJOR, ScaleType.CHROMATIC -> ScaleType.MAJOR
        ScaleType.PENTATONIC_MINOR, ScaleType.BLUES -> ScaleType.MINOR
        else -> scale
    }
}
