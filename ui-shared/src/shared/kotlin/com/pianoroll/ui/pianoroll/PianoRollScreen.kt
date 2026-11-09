package com.pianoroll.ui.pianoroll

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pianoroll.core.audio.AudioEngine
import com.pianoroll.core.model.*
import com.pianoroll.ui.pianoroll.PianoRollMath as M

private val SNAPS = listOf(4, 8, 16, 32)

@Composable
internal fun PianoRollContent(store: ProjectStore, modifier: Modifier, audio: AudioEngine?) {
    val project by store.state.collectAsState()
    val canUndo by store.canUndo.collectAsState()
    val canRedo by store.canRedo.collectAsState()
    val vs = remember { PianoRollViewState() }

    val pattern = project.patterns.firstOrNull { it.id == vs.patternId } ?: project.patterns.firstOrNull()
    if (pattern == null) {
        Box(modifier.fillMaxSize(), Alignment.Center) { Text("No pattern") }
        return
    }
    val track = pattern.tracks.firstOrNull { it.id == vs.activeTrackId }
        ?: pattern.tracks.firstOrNull { it.type == TrackType.MELODY }
        ?: pattern.tracks.firstOrNull()
    val totalTicks = pattern.lengthBars * M.ticksPerBar(project.timeSigNum, project.timeSigDen)

    // drop selection ids that no longer exist (after undo / erase)
    LaunchedEffect(track?.notes) {
        val alive = track?.notes?.map { it.id }?.toSet().orEmpty()
        if (!alive.containsAll(vs.selection)) vs.selection = vs.selection.filter { it in alive }.toSet()
    }

    // engine sync + playhead polling (UI never owns time; it only reads the engine)
    var playTick by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    LaunchedEffect(project, audio) { audio?.setProject(project) }
    val totalState by rememberUpdatedState(totalTicks)
    LaunchedEffect(audio) {
        if (audio == null) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val p = audio.isPlaying()
            if (p != playing) playing = p
            val t = audio.positionTick()
            if (t != playTick) playTick = t
            if (p && vs.pxPerBeat > 0f) {   // follow playhead
                val px = t.toFloat() / PPQ * vs.pxPerBeat
                if (px < vs.scrollX || px > vs.scrollX + vs.viewW - 40f) {
                    vs.scrollX = px - 40f
                    vs.clamp(totalState)
                }
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Column(modifier.fillMaxSize().background(RollColors.bg)) {
            // toolbar
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Btn("↶", enabled = canUndo) { store.undo() }
                Btn("↷", enabled = canRedo) { store.redo() }
                if (audio != null) {
                    Btn(if (playing) "⏸" else "▶") { if (playing) audio.pause() else audio.play() }
                    Btn("■") { audio.stop() }
                }
                Chip("✎", vs.tool == PianoRollTool.DRAW) { vs.tool = PianoRollTool.DRAW }
                Chip("▭", vs.tool == PianoRollTool.SELECT) { vs.tool = PianoRollTool.SELECT }
                Chip("⌫", vs.tool == PianoRollTool.ERASE) { vs.tool = PianoRollTool.ERASE }
                Chip("✋", vs.tool == PianoRollTool.PAN) { vs.tool = PianoRollTool.PAN }
                Btn("Del ${vs.selection.size}", enabled = track != null && vs.selection.isNotEmpty()) {
                    if (track != null) store.execute(Commands.removeNotes(pattern.id, track.id, vs.selection))
                    vs.selection = emptySet()
                }
                Chip("1/${vs.snapDiv}", false) { vs.snapDiv = SNAPS[(SNAPS.indexOf(vs.snapDiv) + 1) % SNAPS.size] }
                Chip("Scale", vs.scaleLock) { vs.scaleLock = !vs.scaleLock }
                Chip("Ghost", vs.showGhost) { vs.showGhost = !vs.showGhost }
                Btn("↔−") { vs.zoomHorizontal(0.8f, totalTicks, 24f, 900f) }
                Btn("↔+") { vs.zoomHorizontal(1.25f, totalTicks, 24f, 900f) }
                Btn("↕−") { vs.zoomVertical(0.8f, totalTicks, 14f, 120f) }
                Btn("↕+") { vs.zoomVertical(1.25f, totalTicks, 14f, 120f) }
            }
            // pattern + track row
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (project.patterns.size > 1) project.patterns.forEach { p ->
                    Chip(p.name, p.id == pattern.id) { vs.patternId = p.id; vs.activeTrackId = null; vs.selection = emptySet() }
                }
                pattern.tracks.forEach { t ->
                    val col = RollColors.forTrack(pattern, t.id)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(col))
                        Chip(t.name, t.id == track?.id) { vs.activeTrackId = t.id; vs.selection = emptySet() }
                        Mini("M", t.mute) { store.execute(Commands.updateTrack(pattern.id, t.id) { it.copy(mute = !it.mute) }) }
                        Mini("S", t.solo) { store.execute(Commands.updateTrack(pattern.id, t.id) { it.copy(solo = !it.solo) }) }
                    }
                }
            }
            PianoRollCanvas(project, pattern, track, vs, store, audio, playTick, Modifier.weight(1f).fillMaxWidth())
            VelocityLane(pattern, track, vs, store, Modifier.fillMaxWidth().height(72.dp))
        }
    }
}

@Composable
private fun Btn(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp), modifier = Modifier.height(34.dp)) {
        Text(label, fontSize = 15.sp)
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label, fontSize = 13.sp) })
}

@Composable
private fun Mini(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(24.dp).clip(RoundedCornerShape(5.dp))
            .background(if (on) RollColors.accent else Color0).clickable(onClick = onClick),
        Alignment.Center,
    ) { Text(label, fontSize = 11.sp, color = if (on) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.LightGray) }
}

private val Color0 = androidx.compose.ui.graphics.Color(0xFF3A3F47)
