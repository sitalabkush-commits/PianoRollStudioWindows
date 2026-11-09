package com.pianoroll.core.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Every edit is a Command: a pure function Project -> Project. Gives undo/redo for free. */
fun interface Command {
    fun apply(project: Project): Project
}

class ProjectStore(initial: Project, private val maxHistory: Int = 200) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<Project> = _state.asStateFlow()

    private val undoStack = ArrayDeque<Project>()
    private val redoStack = ArrayDeque<Project>()
    private val _canUndo = MutableStateFlow(false)
    private val _canRedo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    @Synchronized
    fun execute(command: Command) {
        val before = _state.value
        val after = command.apply(before)
        if (after == before) return
        undoStack.addLast(before)
        if (undoStack.size > maxHistory) undoStack.removeFirst()
        redoStack.clear()
        _state.value = after
        refresh()
    }

    @Synchronized
    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(_state.value)
        _state.value = prev
        refresh()
    }

    @Synchronized
    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(_state.value)
        _state.value = next
        refresh()
    }

    /** Replace project without history (e.g. after load). */
    @Synchronized
    fun reset(project: Project) {
        undoStack.clear(); redoStack.clear()
        _state.value = project
        refresh()
    }

    private fun refresh() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }
}

/** Starter commands. Other modules add their own in their own packages. */
object Commands {
    private fun Project.mapTrack(patternId: String, trackId: String, f: (Track) -> Track) = copy(
        patterns = patterns.map { p ->
            if (p.id != patternId) p else p.copy(tracks = p.tracks.map { t -> if (t.id == trackId) f(t) else t })
        }
    )

    fun addNote(patternId: String, trackId: String, note: Note) = Command { it.mapTrack(patternId, trackId) { t -> t.copy(notes = t.notes + note) } }

    fun removeNotes(patternId: String, trackId: String, ids: Set<String>) =
        Command { it.mapTrack(patternId, trackId) { t -> t.copy(notes = t.notes.filterNot { n -> n.id in ids }) } }

    fun replaceNotes(patternId: String, trackId: String, notes: List<Note>) =
        Command { it.mapTrack(patternId, trackId) { t -> t.copy(notes = notes) } }

    fun updateTrack(patternId: String, trackId: String, f: (Track) -> Track) = Command { it.mapTrack(patternId, trackId, f) }

    fun setBpm(bpm: Float) = Command { it.copy(bpm = bpm.coerceIn(20f, 400f)) }

    fun setKey(root: Int, scale: ScaleType) = Command { it.copy(keyRoot = ((root % 12) + 12) % 12, scale = scale) }

    fun setLoop(startBar: Int?, endBar: Int?) = Command { it.copy(loopStartBar = startBar, loopEndBar = endBar) }
}
