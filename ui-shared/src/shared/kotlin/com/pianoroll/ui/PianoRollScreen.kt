package com.pianoroll.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.pianoroll.core.audio.AudioEngine
import com.pianoroll.core.model.ProjectStore
import com.pianoroll.ui.pianoroll.PianoRollContent

/**
 * Entry point (same package/name as the stub, so MainActivity keeps compiling).
 * `audio` is optional with a default, so the original 2-argument call still works.
 */
@Composable
fun PianoRollScreen(store: ProjectStore, modifier: Modifier = Modifier, audio: AudioEngine? = null) {
    PianoRollContent(store, modifier, audio)
}
