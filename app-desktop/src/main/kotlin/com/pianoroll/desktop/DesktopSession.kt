package com.pianoroll.desktop

import com.pianoroll.core.audio.AudioEngine
import com.pianoroll.core.model.Project
import com.pianoroll.core.model.ProjectStore
import com.pianoroll.export.ProjectFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Platform file dialogs. The real one uses AWT; tests use a fake. All methods return null when the user cancels. */
interface FileChooser {
    fun chooseOpenProject(): File?
    fun chooseSave(title: String, suggestedName: String, extension: String): File?
    fun chooseDirectory(title: String): File?
}

data class StatusLine(val message: String = "", val progress: Float? = null, val isError: Boolean = false)

/**
 * Everything the desktop window does besides drawing: new / open / save / export, current file, "unsaved changes".
 * The UI only calls these methods. Edits to the project still go through ProjectStore.execute (CONTRACTS rule 2).
 */
class DesktopSession(
    val store: ProjectStore,
    val engine: AudioEngine,
    private val chooser: FileChooser,
    private val exports: ExportService,
) {
    private val _status = MutableStateFlow(StatusLine("Ready"))
    val status: StateFlow<StatusLine> = _status.asStateFlow()

    private val _file = MutableStateFlow<File?>(null)
    val currentFile: StateFlow<File?> = _file.asStateFlow()

    private val _saved = MutableStateFlow(store.state.value)
    /** The project as last saved / created / loaded. `store.state.value != savedProject.value` means unsaved changes. */
    val savedProject: StateFlow<Project> = _saved.asStateFlow()

    private var exporting = false

    val isDirty: Boolean get() = store.state.value != _saved.value

    fun newProject() {
        engine.stop()
        val p = Project.new("Untitled")
        store.reset(p)
        _file.value = null
        _saved.value = p
        info("New project")
    }

    /** Returns true when a project was loaded. */
    fun openProject(): Boolean {
        val f = chooser.chooseOpenProject() ?: return false
        val r = ProjectIO.load(f)
        val p = r.getOrNull()
        if (p == null) {
            fail("Could not open ${f.name}: ${r.exceptionOrNull()?.message ?: "unknown error"}")
            return false
        }
        engine.stop()
        store.reset(p)
        _file.value = f
        _saved.value = p
        info("Opened ${f.name}")
        return true
    }

    /** Save to the current file, or ask for one (always asks when [saveAs]). Returns true when the project is on disk. */
    fun saveProject(saveAs: Boolean = false): Boolean {
        val project = store.state.value
        val existing = _file.value
        val target: File = if (saveAs || existing == null) {
            val chosen = chooser.chooseSave("Save project", FileNames.sanitize(project.name), ProjectFile.EXTENSION) ?: return false
            FileNames.withExtension(chosen, ProjectFile.EXTENSION)
        } else existing
        val r = ProjectIO.save(project, target)
        if (r.isFailure) {
            fail("Could not save ${target.name}: ${r.exceptionOrNull()?.message ?: "unknown error"}")
            return false
        }
        _file.value = target
        _saved.value = project
        info("Saved ${target.name}")
        return true
    }

    suspend fun exportMidi(): Boolean = exportToFile("Export MIDI", "mid") { p, f, prog -> exports.midi(p, f, prog) }

    suspend fun exportWav(): Boolean = exportToFile("Export WAV", "wav") { p, f, prog -> exports.wav(p, f, prog) }

    suspend fun exportStems(): Boolean {
        if (exporting) { fail("An export is already running"); return false }
        val project = store.state.value
        val dir = chooser.chooseDirectory("Choose a folder for the stems") ?: return false
        exporting = true
        try {
            progress("Exporting stems...", 0f)
            val r = exports.stems(project, dir, baseNameOf(project)) { progress("Exporting stems...", it) }
            val files = r.getOrNull()
            if (files == null) { fail("Stem export failed: ${r.exceptionOrNull()?.message ?: "unknown error"}"); return false }
            info("Exported ${files.size} stems to ${dir.name}")
            return true
        } finally { exporting = false }
    }

    private suspend fun exportToFile(
        title: String,
        ext: String,
        run: suspend (Project, File, (Float) -> Unit) -> Result<Unit>,
    ): Boolean {
        if (exporting) { fail("An export is already running"); return false }
        val project = store.state.value
        val chosen = chooser.chooseSave(title, baseNameOf(project), ext) ?: return false
        val file = FileNames.withExtension(chosen, ext)
        exporting = true
        try {
            progress("Exporting ${file.name}...", 0f)
            val r = run(project, file) { progress("Exporting ${file.name}...", it) }
            if (r.isFailure) { fail("Export failed: ${r.exceptionOrNull()?.message ?: "unknown error"}"); return false }
            info("Exported ${file.name}")
            return true
        } finally { exporting = false }
    }

    private fun baseNameOf(p: Project): String =
        _file.value?.nameWithoutExtension ?: FileNames.sanitize(p.name)

    private fun info(m: String) { _status.value = StatusLine(m) }
    private fun fail(m: String) { _status.value = StatusLine(m, isError = true) }
    private fun progress(m: String, p: Float) { _status.value = StatusLine(m, p.coerceIn(0f, 1f)) }
}
