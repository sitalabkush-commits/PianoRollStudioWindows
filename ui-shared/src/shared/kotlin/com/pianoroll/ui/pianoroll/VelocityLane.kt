package com.pianoroll.ui.pianoroll

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.pianoroll.core.model.*
import com.pianoroll.ui.pianoroll.PianoRollMath as M
import kotlin.math.abs
import kotlin.math.roundToInt

/** Velocity bars under the grid. Shares horizontal scroll/zoom with the canvas. */
@Composable
internal fun VelocityLane(
    pattern: Pattern, track: Track?, vs: PianoRollViewState, store: ProjectStore, modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val keysW = with(density) { KEYS_W_DP.dp.toPx() }
    val hitR = with(density) { 16.dp.toPx() }
    val barW = with(density) { 6.dp.toPx() }
    val cur by rememberUpdatedState(Pair(pattern, track))

    Canvas(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val (pat, tr) = cur
                if (tr == null || down.position.x < keysW) return@awaitEachGesture
                val notes = tr.notes
                fun x(n: Note) = keysW + n.startTick.toFloat() / PPQ * vs.pxPerBeat - vs.scrollX
                val target = notes.minByOrNull { abs(x(it) - down.position.x) }
                    ?.takeIf { abs(x(it) - down.position.x) <= hitR } ?: return@awaitEachGesture
                val ids = if (target.id in vs.selection) vs.selection else setOf(target.id)
                val h = size.height.toFloat()
                fun velAt(y: Float) = ((1f - y / h) * 127f).roundToInt().coerceIn(1, 127)
                var lastV = velAt(down.position.y)
                vs.draft = M.setVelocity(notes, ids, lastV)
                try {
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        lastV = velAt(ch.position.y)
                        vs.draft = M.setVelocity(notes, ids, lastV)
                        ch.consume()
                    }
                    vs.draft?.let { store.execute(Commands.replaceNotes(pat.id, tr.id, it)) }
                    vs.defaultVelocity = lastV
                } finally {
                    vs.draft = null
                }
            }
        },
    ) {
        drawRect(Color(0xFF15171B))
        drawLine(Color(0xFF000000), Offset(keysW, 0f), Offset(keysW, size.height), 2f)
        if (track == null) return@Canvas
        val col = RollColors.forTrack(pattern, track.id)
        clipRect(keysW, 0f, size.width, size.height) {
            for (q in intArrayOf(32, 64, 96, 127)) {
                val y = size.height * (1f - q / 127f)
                drawLine(Color(0xFF2A2E35), Offset(keysW, y), Offset(size.width, y), 1f)
            }
            for (n in (vs.draft ?: track.notes)) {
                val x = keysW + n.startTick.toFloat() / PPQ * vs.pxPerBeat - vs.scrollX
                if (x < keysW - barW || x > size.width) continue
                val bh = size.height * n.velocity / 127f
                drawRect(col, Offset(x, size.height - bh), Size(barW, bh))
                if (n.id in vs.selection) drawRect(Color.White, Offset(x, size.height - bh), Size(barW, bh), style = Stroke(2f))
            }
        }
    }
}
