package com.pianoroll.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.pianoroll.core.model.Project
import com.pianoroll.core.model.ProjectStore
import com.pianoroll.desktop.audio.DesktopAudioEngine
import com.pianoroll.ui.PianoRollScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Frame
import java.io.File

/** Gives [AwtFileChooser] the current window as dialog parent. */
class FrameHolder { @Volatile var frame: Frame? = null }

fun windowTitle(file: File?, projectName: String, dirty: Boolean): String =
    (if (dirty) "*" else "") + (file?.name ?: projectName) + " - PianoRoll Studio"

fun main() = application {
    val holder = remember { FrameHolder() }
    val store = remember { ProjectStore(Project.new("Untitled")) }
    val engine = remember { DesktopAudioEngine() }
    val session = remember { DesktopSession(store, engine, AwtFileChooser { holder.frame }, ExportService(engine)) }
    val scope = rememberCoroutineScope()

    val project by store.state.collectAsState()
    val saved by session.savedProject.collectAsState()
    val file by session.currentFile.collectAsState()
    val canUndo by store.canUndo.collectAsState()
    val canRedo by store.canRedo.collectAsState()
    val dirty = project != saved
    var askClose by remember { mutableStateOf(false) }

    fun quit() {
        engine.release()
        exitApplication()
    }
    fun requestClose() {
        if (dirty) askClose = true else quit()
    }

    Window(
        onCloseRequest = { requestClose() },
        title = windowTitle(file, project.name, dirty),
        state = rememberWindowState(width = 1200.dp, height = 760.dp),
    ) {
        SideEffect { holder.frame = window }

        MenuBar {
            Menu("File", mnemonic = 'F') {
                Item("New", shortcut = KeyShortcut(Key.N, ctrl = true), onClick = { session.newProject() })
                Item("Open...", shortcut = KeyShortcut(Key.O, ctrl = true), onClick = { session.openProject() })
                Item("Save", shortcut = KeyShortcut(Key.S, ctrl = true), onClick = { session.saveProject() })
                Item("Save as...", shortcut = KeyShortcut(Key.S, ctrl = true, shift = true), onClick = { session.saveProject(saveAs = true) })
                Separator()
                Menu("Export") {
                    Item("MIDI (.mid)...", onClick = { scope.launch { session.exportMidi() } })
                    Item("WAV (.wav)...", onClick = { scope.launch { session.exportWav() } })
                    Item("Stems (one WAV per track)...", onClick = { scope.launch { session.exportStems() } })
                    Item("MP3 (not supported yet)", enabled = false, onClick = {})
                }
                Separator()
                Item("Exit", onClick = { requestClose() })
            }
            Menu("Edit", mnemonic = 'E') {
                Item("Undo", enabled = canUndo, shortcut = KeyShortcut(Key.Z, ctrl = true), onClick = { store.undo() })
                Item("Redo", enabled = canRedo, shortcut = KeyShortcut(Key.Y, ctrl = true), onClick = { store.redo() })
            }
        }

        MaterialTheme(colorScheme = darkColorScheme()) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    PianoRollScreen(store, Modifier.fillMaxSize(), audio = engine)
                }
                StatusBar(session)
            }
            if (askClose) {
                AlertDialog(
                    onDismissRequest = { askClose = false },
                    title = { Text("Unsaved changes") },
                    text = { Text("Save changes to ${file?.name ?: project.name} before closing?") },
                    confirmButton = {
                        TextButton(onClick = {
                            askClose = false
                            if (session.saveProject()) quit()
                        }) { Text("Save") }
                    },
                    dismissButton = {
                        Row {
                            TextButton(onClick = { askClose = false; quit() }) { Text("Don't save") }
                            TextButton(onClick = { askClose = false }) { Text("Cancel") }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun StatusBar(session: DesktopSession) {
    val s by session.status.collectAsState()
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(session) {
        while (true) {
            err = (session.engine as? DesktopAudioEngine)?.outputError
            delay(1000)
        }
    }
    Surface(color = Color(0xFF20242A), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(s.message, fontSize = 12.sp, color = if (s.isError) Color(0xFFFF8A80) else Color.LightGray)
            s.progress?.let { p -> LinearProgressIndicator(progress = { p }, modifier = Modifier.width(160.dp)) }
            err?.let { Text("No sound device: $it", fontSize = 12.sp, color = Color(0xFFFFB74D)) }
        }
    }
}
