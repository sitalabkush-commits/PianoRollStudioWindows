package com.pianoroll.core.music

import com.pianoroll.core.model.KeyResult
import com.pianoroll.core.model.Note
import com.pianoroll.core.model.ScaleType
import kotlin.math.roundToInt

/**
 * Transpose notes. Locked notes never move.
 *
 * scaleAware = false: plain chromatic shift by [semitones].
 * scaleAware = true : diatonic shift. The shift is converted to scale steps
 * (round(semitones * scaleSize / 12), at least one step) so +12 is exactly one octave,
 * +7 is a 5th in a major scale, and every note stays inside the key.
 */
class DefaultTransposeEngine : TransposeEngine {

    override fun transpose(notes: List<Note>, semitones: Int, scaleAware: Boolean, key: KeyResult): List<Note> {
        if (semitones == 0) return notes
        return notes.map { n ->
            if (n.locked) n else n.copy(pitch = shiftPitch(n.pitch, semitones, scaleAware, key))
        }
    }

    fun shiftPitch(pitch: Int, semitones: Int, scaleAware: Boolean, key: KeyResult): Int {
        var p = if (!scaleAware || key.scale == ScaleType.CHROMATIC) {
            pitch + semitones
        } else {
            val iv = Scales.intervals(key.scale)
            var steps = (semitones * iv.size / 12.0).roundToInt()
            if (steps == 0) steps = if (semitones > 0) 1 else -1
            val snapped = Scales.snap(pitch, key.root, key.scale)
            Scales.pitchAtIndex(Scales.degreeIndex(snapped, key.root, key.scale) + steps, key.root, key.scale)
        }
        while (p > 127) p -= 12
        while (p < 0) p += 12
        return p
    }

    /** New key after a plain (non scale-aware) transpose. */
    fun transposeKey(key: KeyResult, semitones: Int): KeyResult = key.copy(root = (key.root + semitones).mod(12))
}
