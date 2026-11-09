package com.pianoroll.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.pianoroll.core.audio.AudioEngine
import com.pianoroll.core.model.ProjectStore
import com.pianoroll.ui.PianoRollScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var store: ProjectStore
    @Inject lateinit var audio: AudioEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PianoRollScreen(store, audio = audio) }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) audio.release()
    }
}
