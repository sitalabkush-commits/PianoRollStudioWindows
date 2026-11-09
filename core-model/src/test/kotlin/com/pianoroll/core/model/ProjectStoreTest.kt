package com.pianoroll.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectStoreTest {
    @Test
    fun addUndoRedo() {
        val project = Project.new("t")
        val pattern = project.patterns.first()
        val track = pattern.tracks.first()
        val store = ProjectStore(project)

        store.execute(Commands.addNote(pattern.id, track.id, Note(pitch = 60, startTick = 0, durationTick = PPQ.toLong())))
        assertEquals(1, store.state.value.patterns[0].tracks[0].notes.size)
        assertTrue(store.canUndo.value)

        store.undo()
        assertEquals(0, store.state.value.patterns[0].tracks[0].notes.size)
        assertTrue(store.canRedo.value)

        store.redo()
        assertEquals(1, store.state.value.patterns[0].tracks[0].notes.size)
        assertFalse(store.canRedo.value)
    }
}
