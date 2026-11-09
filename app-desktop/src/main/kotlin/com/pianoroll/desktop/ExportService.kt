package com.pianoroll.desktop

import com.pianoroll.core.audio.AudioEngine
import com.pianoroll.core.audio.RenderSettings
import com.pianoroll.core.model.Project
import com.pianoroll.export.DefaultExporter
import com.pianoroll.export.Exporter
import com.pianoroll.export.MidiOptions
import java.io.File

/** Export to real files. Thin wrapper around [Exporter] (MIDI / WAV / stems). MP3 is not supported yet. */
class ExportService(
    private val engine: AudioEngine,
    private val settings: RenderSettings = RenderSettings(),
    private val exporterFactory: (RenderSettings, (Float) -> Unit) -> Exporter =
        { s, progress -> DefaultExporter(s, null, MidiOptions(), progress) },
) {
    suspend fun midi(project: Project, file: File, onProgress: (Float) -> Unit = {}): Result<Unit> =
        exporterFactory(settings, onProgress).toMidi(project, FileSink(file))

    suspend fun wav(project: Project, file: File, onProgress: (Float) -> Unit = {}): Result<Unit> =
        exporterFactory(settings, onProgress).toWav(project, engine, FileSink(file))

    /** Always fails for now (no Mp3Encoder on desktop yet); no file is created. */
    suspend fun mp3(project: Project, file: File, kbps: Int = 192): Result<Unit> =
        exporterFactory(settings) {}.toMp3(project, engine, kbps, FileSink(file))

    /** One `<baseName> - <track>.wav` per audible track in [dir]. Returns the files that were created. */
    suspend fun stems(project: Project, dir: File, baseName: String, onProgress: (Float) -> Unit = {}): Result<List<File>> {
        if (!dir.isDirectory) return Result.failure(IllegalArgumentException("Not a folder: ${dir.path}"))
        val used = HashSet<String>()
        val files = ArrayList<File>()
        val base = FileNames.sanitize(baseName)
        val r = exporterFactory(settings, onProgress).toStems(project, engine) { trackName ->
            val unique = FileNames.unique(FileNames.sanitize(trackName), used)
            File(dir, "$base - $unique.wav").also { files.add(it) }.let { FileSink(it) }
        }
        return r.map { files.toList() }
    }
}
