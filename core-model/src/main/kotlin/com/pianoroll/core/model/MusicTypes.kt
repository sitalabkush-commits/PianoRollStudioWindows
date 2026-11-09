package com.pianoroll.core.model

import kotlinx.serialization.Serializable

@Serializable
data class KeyResult(val root: Int, val scale: ScaleType, val confidence: Float = 1f)

@Serializable
enum class ChordQuality { MAJOR, MINOR, DIM, AUG, SUS2, SUS4, DOM7, MAJ7, MIN7, DIM7, M7B5, ADD9, DOM9, MIN9, DOM11, DOM13 }

/** One chord over a time span. Stored separately from notes so the UI can show/hide names and Replace it. */
@Serializable
data class ChordBlock(
    val id: String = newId(),
    val startTick: Long,
    val durationTick: Long,
    val rootPc: Int,
    val quality: ChordQuality,
    val bassPc: Int? = null,       // slash chord
    val locked: Boolean = false,
)

@Serializable enum class ChordStyle { POP, LOFI, JAZZ, CINEMATIC, FOLK }
@Serializable enum class Complexity { SIMPLE, MEDIUM, RICH }
@Serializable data class ChordOptions(val style: ChordStyle = ChordStyle.POP, val complexity: Complexity = Complexity.SIMPLE, val seed: Long = 0L, val barsPerChord: Int = 1)
@Serializable data class Voicing(val inversion: Int = 0, val open: Boolean = false, val octave: Int = 3)

@Serializable enum class ArpMode { UP, DOWN, UP_DOWN, RANDOM }
@Serializable data class ArpOptions(val mode: ArpMode = ArpMode.UP, val rateDivision: Int = 16, val octaveRange: Int = 1, val gate: Float = 0.9f, val swing: Float = 0f)

@Serializable enum class DrumGenre { POP, LOFI, TRAP, EDM, BENGALI_FOLK }
@Serializable data class DrumOptions(val genre: DrumGenre = DrumGenre.POP, val intensity: Float = 0.5f, val swing: Float = 0f, val fill: Boolean = false, val seed: Long = 0L)

@Serializable enum class ChopMode { SHUFFLE, REVERSE, REPEAT, REARRANGE }
