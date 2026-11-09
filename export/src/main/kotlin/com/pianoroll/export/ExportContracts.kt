package com.pianoroll.export

import com.pianoroll.core.audio.AudioEngine
import com.pianoroll.core.audio.AudioSink
import com.pianoroll.core.model.Project

/** Implemented by [DefaultExporter]. MIDI writer is pure Kotlin; MP3 encoder is platform specific ([Mp3Encoder]). */
interface Exporter {
    suspend fun toMidi(project: Project, out: AudioSink): Result<Unit>
    suspend fun toWav(project: Project, engine: AudioEngine, out: AudioSink): Result<Unit>
    suspend fun toMp3(project: Project, engine: AudioEngine, kbps: Int, out: AudioSink): Result<Unit>
    suspend fun toStems(project: Project, engine: AudioEngine, outFactory: (trackName: String) -> AudioSink): Result<Unit>
}
