package com.pianoroll.core.model

import kotlinx.serialization.Serializable
import java.util.UUID

/** Ticks per quarter note. Never change without a migration. */
const val PPQ = 480

fun newId(): String = UUID.randomUUID().toString()

@Serializable
enum class NoteSource { USER, AUTO_CHORD, AUTO_DRUM, AUTO_ARP, PHRASE }

@Serializable
enum class TrackType { MELODY, CHORD, BASS, DRUMS, PAD, AUDIO }

@Serializable
enum class ScaleType { MAJOR, MINOR, DORIAN, PHRYGIAN, LYDIAN, MIXOLYDIAN, HARMONIC_MINOR, PENTATONIC_MAJOR, PENTATONIC_MINOR, BLUES, CHROMATIC }

@Serializable
data class Note(
    val id: String = newId(),
    val pitch: Int,                 // MIDI 0..127
    val startTick: Long,
    val durationTick: Long,
    val velocity: Int = 100,        // 1..127
    val locked: Boolean = false,    // Auto generators must not touch locked notes
    val source: NoteSource = NoteSource.USER,
)

@Serializable
enum class EffectType { REVERB, DELAY, CHORUS, DISTORTION, EQ, FILTER, COMPRESSOR }

@Serializable
data class EffectSpec(val type: EffectType, val params: Map<String, Float> = emptyMap(), val bypass: Boolean = false)

@Serializable
enum class TimeEffectType { HALF_SPEED, TAPE_STOP, STUTTER, GATE, REVERSE, SCRATCH, VINYL_BRAKE }

@Serializable
data class CurvePoint(val tick: Long, val value: Float)

/** Gross Beat style effect drawn on the Effect Strip. */
@Serializable
data class TimeEffect(
    val id: String = newId(),
    val type: TimeEffectType,
    val startTick: Long,
    val endTick: Long,
    val curve: List<CurvePoint> = emptyList(),
)

@Serializable
data class Track(
    val id: String = newId(),
    val name: String,
    val type: TrackType,
    val instrumentId: String = "gm:0",   // "gm:<program>" or "sf2:<file>#<preset>"
    val notes: List<Note> = emptyList(),
    val volume: Float = 1f,
    val pan: Float = 0f,
    val mute: Boolean = false,
    val solo: Boolean = false,
    val autoMode: Boolean = false,       // true => generators may replace non-locked notes
    val effects: List<EffectSpec> = emptyList(),
)

@Serializable
data class Pattern(
    val id: String = newId(),
    val name: String,
    val lengthBars: Int = 4,
    val tracks: List<Track> = emptyList(),
    val colorArgb: Long = 0xFF4FC3F7,
)

@Serializable
data class PlaylistClip(
    val id: String = newId(),
    val patternId: String,
    val startBar: Int,
    val lengthBars: Int,
    val lane: Int = 0,
)

@Serializable
data class Project(
    val id: String = newId(),
    val name: String,
    val bpm: Float = 120f,
    val timeSigNum: Int = 4,
    val timeSigDen: Int = 4,
    val keyRoot: Int = 0,                         // pitch class 0..11 (C=0)
    val scale: ScaleType = ScaleType.MAJOR,
    val patterns: List<Pattern> = emptyList(),
    val playlist: List<PlaylistClip> = emptyList(),
    val timeEffects: List<TimeEffect> = emptyList(),
    val loopStartBar: Int? = null,
    val loopEndBar: Int? = null,
    val songMode: Boolean = false,
) {
    companion object {
        fun new(name: String): Project {
            val melody = Track(name = "Melody", type = TrackType.MELODY, instrumentId = "gm:0")
            val chord = Track(name = "Chords", type = TrackType.CHORD, instrumentId = "gm:4", autoMode = true)
            val drums = Track(name = "Drums", type = TrackType.DRUMS, instrumentId = "gm:drums", autoMode = true)
            val pattern = Pattern(name = "Pattern 1", tracks = listOf(melody, chord, drums))
            return Project(name = name, patterns = listOf(pattern))
        }
    }
}

@Serializable
data class ProjectSummary(val id: String, val name: String, val updatedAt: Long)

/** Implemented by data-android (Room) now, desktop (files) later. */
interface ProjectRepository {
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<ProjectSummary>>
    suspend fun load(id: String): Project?
    suspend fun save(project: Project)
    suspend fun delete(id: String)
}
