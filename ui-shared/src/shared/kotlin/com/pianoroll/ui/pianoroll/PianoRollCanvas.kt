package com.pianoroll.ui.pianoroll

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pianoroll.core.audio.AudioEngine
import com.pianoroll.core.model.*
import com.pianoroll.ui.pianoroll.PianoRollMath as M
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

internal object RollColors {
    val bg = Color(0xFF1B1E23)
    val rowWhite = Color(0xFF2C3037)
    val rowBlack = Color(0xFF23262C)
    val dim = Color(0x88000000)
    val lineBar = Color(0xFF6B7280)
    val lineBeat = Color(0xFF454A53)
    val lineSub = Color(0xFF353941)
    val ruler = Color(0xFF15171B)
    val loop = Color(0xFF4FC3F7)
    val playhead = Color(0xFFFF5252)
    val keyWhite = Color(0xFFE9E9EC)
    val keyBlack = Color(0xFF16181C)
    val keyPressed = Color(0xFFFFD54F)
    val accent = Color(0xFFFFB74D)
    val palette = listOf(
        Color(0xFF4FC3F7), Color(0xFFFFB74D), Color(0xFF81C784), Color(0xFFE57373),
        Color(0xFFBA68C8), Color(0xFFFFF176), Color(0xFF4DB6AC),
    )
    fun forTrack(p: Pattern, trackId: String): Color =
        palette[(p.tracks.indexOfFirst { it.id == trackId }.coerceAtLeast(0)) % palette.size]
}

internal class RollGeom(val keysW: Float, val rulerH: Float, val edgeW: Float, val minHitW: Float)
internal class RollCtx(val project: Project, val pattern: Pattern, val track: Track?)

private fun PianoRollViewState.tickAt(x: Float, g: RollGeom): Long =
    ((x - g.keysW + scrollX) / pxPerBeat * PPQ).toLong().coerceAtLeast(0L)

private fun PianoRollViewState.rowAt(y: Float, g: RollGeom): Int =
    floor((y - g.rulerH + scrollY) / rowHeight).toInt()

private fun PianoRollViewState.pitchAt(y: Float, g: RollGeom): Int = (127 - rowAt(y, g)).coerceIn(0, 127)

private fun PianoRollViewState.xOf(tick: Long, g: RollGeom): Float = g.keysW + tick.toFloat() / PPQ * pxPerBeat - scrollX

@Composable
internal fun PianoRollCanvas(
    project: Project,
    pattern: Pattern,
    track: Track?,
    vs: PianoRollViewState,
    store: ProjectStore,
    audio: AudioEngine?,
    playTick: Long,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val g = remember(density) {
        with(density) { RollGeom(KEYS_W_DP.dp.toPx(), RULER_H_DP.dp.toPx(), 18.dp.toPx(), 14.dp.toPx()) }
    }
    remember(density) { with(density) { vs.initMetrics(72.dp.toPx(), 26.dp.toPx()) }; 0 }
    val textMeasurer = rememberTextMeasurer()
    val ctx by rememberUpdatedState(RollCtx(project, pattern, track))
    var size by remember { mutableStateOf(IntSize.Zero) }
    val barTicks = M.ticksPerBar(project.timeSigNum, project.timeSigDen)
    val totalTicks = pattern.lengthBars * barTicks

    LaunchedEffect(size) {
        if (size == IntSize.Zero) return@LaunchedEffect
        vs.viewW = size.width - g.keysW
        vs.viewH = size.height - g.rulerH
        if (!vs.scrollInit) {
            vs.scrollInit = true
            val ns = track?.notes.orEmpty()
            val mid = if (ns.isEmpty()) 66 else ns.map { it.pitch }.average().toInt()
            vs.scrollY = (127 - mid) * vs.rowHeight - vs.viewH / 2
        }
        vs.clamp(totalTicks)
    }

    Canvas(
        modifier
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val c = ctx
                    when {
                        down.position.y < g.rulerH -> rulerGesture(down, c, vs, g, store, audio)
                        down.position.x < g.keysW -> keysGesture(down, c, vs, g, audio)
                        else -> gridGesture(down, c, vs, g, store, audio)
                    }
                }
            },
    ) {
        drawRoll(vs, project, pattern, track, playTick, g, textMeasurer)
    }
}

// ───────────────────────────── drawing ─────────────────────────────

private fun DrawScope.drawRoll(
    vs: PianoRollViewState, project: Project, pattern: Pattern, track: Track?,
    playTick: Long, g: RollGeom, tm: TextMeasurer,
) {
    val rh = vs.rowHeight
    val ppb = vs.pxPerBeat
    if (rh <= 0f || ppb <= 0f) return
    val w = size.width
    val h = size.height
    val sy = vs.scrollY
    val sx = vs.scrollX
    val barTicks = M.ticksPerBar(project.timeSigNum, project.timeSigDen)
    val totalTicks = pattern.lengthBars * barTicks
    val barPx = barTicks.toFloat() / PPQ * ppb
    fun x(t: Long) = vs.xOf(t, g)

    drawRect(RollColors.bg)

    val firstRow = floor(sy / rh).toInt().coerceAtLeast(0)
    val lastRow = ceil((sy + h - g.rulerH) / rh).toInt().coerceAtMost(127)

    clipRect(g.keysW, g.rulerH, w, h) {
        // rows
        for (r in firstRow..lastRow) {
            val pitch = 127 - r
            val y = g.rulerH + r * rh - sy
            drawRect(if (M.isBlackKey(pitch)) RollColors.rowBlack else RollColors.rowWhite, Offset(g.keysW, y), Size(w - g.keysW, rh))
            if (vs.scaleLock && !M.inScale(pitch, project.keyRoot, project.scale)) {
                drawRect(RollColors.dim, Offset(g.keysW, y), Size(w - g.keysW, rh))
            }
            drawLine(RollColors.lineSub, Offset(g.keysW, y), Offset(w, y), 1f)
        }
        // vertical grid
        var step = M.gridTicks(vs.snapDiv)
        while (step.toFloat() / PPQ * ppb < 10f) step *= 2
        var t = M.snapDown(((sx / ppb) * PPQ).toLong().coerceAtLeast(0), step)
        while (t <= totalTicks) {
            val gx = x(t)
            if (gx > w) break
            val color = when {
                t % barTicks == 0L -> RollColors.lineBar
                t % PPQ == 0L -> RollColors.lineBeat
                else -> RollColors.lineSub
            }
            drawLine(color, Offset(gx, g.rulerH), Offset(gx, h), if (t % barTicks == 0L) 2f else 1f)
            t += step
        }
        // past pattern end
        val endX = x(totalTicks)
        if (endX < w) drawRect(RollColors.dim, Offset(max(endX, g.keysW), g.rulerH), Size(w - max(endX, g.keysW), h - g.rulerH))
        // loop
        val loop = vs.loopDraft ?: project.loopStartBar?.let { s -> project.loopEndBar?.let { e -> s to e } }
        if (loop != null) {
            val lx1 = x(loop.first * barTicks)
            val lx2 = x(loop.second * barTicks)
            drawRect(RollColors.loop.copy(alpha = 0.10f), Offset(lx1, g.rulerH), Size(lx2 - lx1, h - g.rulerH))
        }
        // ghost notes
        if (vs.showGhost) {
            for (tr in pattern.tracks) {
                if (tr.id == track?.id) continue
                val col = RollColors.forTrack(pattern, tr.id).copy(alpha = 0.22f)
                for (n in tr.notes) {
                    val nx1 = x(n.startTick)
                    val nx2 = x(n.startTick + n.durationTick)
                    if (nx2 < g.keysW || nx1 > w) continue
                    val ny = g.rulerH + (127 - n.pitch) * rh - sy
                    if (ny + rh < g.rulerH || ny > h) continue
                    drawRect(col, Offset(nx1, ny + 3f), Size(max(2f, nx2 - nx1 - 1f), rh - 6f))
                }
            }
        }
        // active track notes
        if (track != null) {
            val base = RollColors.forTrack(pattern, track.id)
            val notes = vs.draft ?: track.notes
            val sel = vs.selection
            for (n in notes) {
                val nx1 = x(n.startTick)
                val nx2 = x(n.startTick + n.durationTick)
                if (nx2 < g.keysW || nx1 > w) continue
                val ny = g.rulerH + (127 - n.pitch) * rh - sy
                if (ny + rh < g.rulerH || ny > h) continue
                var col = base.copy(alpha = 0.55f + 0.45f * n.velocity / 127f)
                if (n.source != NoteSource.USER) col = lerp(col, Color.White, 0.22f)
                val topLeft = Offset(nx1, ny + 1f)
                val sz = Size(max(3f, nx2 - nx1 - 1f), rh - 2f)
                drawRoundRect(col, topLeft, sz, CornerRadius(4f))
                if (n.id in sel) drawRoundRect(Color.White, topLeft, sz, CornerRadius(4f), Stroke(3f))
                if (n.locked) drawCircle(Color.White, 3f, Offset(nx1 + 6f, ny + 7f))
                // right-edge resize handle hint
                if (sz.width > 28f) drawLine(Color(0x66000000), Offset(nx1 + sz.width - 6f, ny + 6f), Offset(nx1 + sz.width - 6f, ny + rh - 6f), 2f)
            }
        }
        // marquee
        vs.marquee?.let { r ->
            drawRect(Color(0x334FC3F7), r.topLeft, r.size)
            drawRect(Color(0xFF4FC3F7), r.topLeft, r.size, style = Stroke(2f))
        }
        // playhead
        val px = x(playTick)
        if (px in g.keysW..w) drawLine(RollColors.playhead, Offset(px, g.rulerH), Offset(px, h), 3f)
    }

    // ruler
    drawRect(RollColors.ruler, Offset.Zero, Size(w, g.rulerH))
    clipRect(g.keysW, 0f, w, g.rulerH) {
        val loop = vs.loopDraft ?: project.loopStartBar?.let { s -> project.loopEndBar?.let { e -> s to e } }
        if (loop != null) {
            val lx1 = x(loop.first * barTicks)
            val lx2 = x(loop.second * barTicks)
            drawRect(RollColors.loop.copy(alpha = 0.55f), Offset(lx1, 0f), Size(lx2 - lx1, g.rulerH))
        }
        val firstBar = floor(sx / barPx).toInt().coerceAtLeast(0)
        val lastBar = min(pattern.lengthBars - 1, ceil((sx + w - g.keysW) / barPx).toInt())
        for (b in firstBar..lastBar) {
            val bx = x(b * barTicks)
            drawLine(RollColors.lineBar, Offset(bx, g.rulerH * 0.45f), Offset(bx, g.rulerH), 2f)
            drawText(tm, "${b + 1}", Offset(bx + 6f, 2f), TextStyle(fontSize = 11.sp, color = Color(0xFFB0B6C0)))
        }
        val px = x(playTick)
        if (px in g.keysW..w) drawLine(RollColors.playhead, Offset(px, 0f), Offset(px, g.rulerH), 3f)
    }

    // keyboard (fixed on the left)
    clipRect(0f, g.rulerH, g.keysW, h) {
        for (r in firstRow..lastRow) {
            val pitch = 127 - r
            val y = g.rulerH + r * rh - sy
            val pressed = vs.previewPitch == pitch
            val black = M.isBlackKey(pitch)
            drawRect(if (pressed) RollColors.keyPressed else RollColors.keyWhite, Offset(0f, y), Size(g.keysW, rh))
            drawLine(Color(0xFF9AA0A8), Offset(0f, y), Offset(g.keysW, y), 1f)
            if (black && !pressed) drawRect(RollColors.keyBlack, Offset(0f, y + 1f), Size(g.keysW * 0.62f, rh - 2f))
            if (pitch % 12 == 0) {
                drawText(tm, M.noteName(pitch), Offset(g.keysW - 26f - 8f, y + rh / 2 - 8f), TextStyle(fontSize = 10.sp, color = Color(0xFF222222)))
            }
            if (M.inScale(pitch, project.keyRoot, project.scale) && pitch % 12 == project.keyRoot) {
                drawCircle(RollColors.accent, 4f, Offset(g.keysW - 6f, y + rh / 2))
            }
        }
    }
    drawLine(Color(0xFF000000), Offset(g.keysW, 0f), Offset(g.keysW, h), 2f)
}

// ───────────────────────────── gestures ─────────────────────────────

private enum class Mode { MOVE, RESIZE, CREATE, MARQUEE, ERASE }

private fun hitNote(notes: List<Note>, pos: Offset, vs: PianoRollViewState, g: RollGeom): Note? {
    val pitch = vs.pitchAt(pos.y, g)
    for (n in notes.asReversed()) {
        if (n.pitch != pitch) continue
        val x1 = vs.xOf(n.startTick, g)
        val x2 = max(vs.xOf(n.startTick + n.durationTick, g), x1 + g.minHitW)
        if (pos.x in x1..x2) return n
    }
    return null
}

private suspend fun AwaitPointerEventScope.transformLoop(vs: PianoRollViewState, g: RollGeom, totalTicks: Long) {
    do {
        val ev = awaitPointerEvent()
        val zoom = ev.calculateZoom()
        val pan = ev.calculatePan()
        val cen = ev.calculateCentroid()
        if (ev.changes.count { it.pressed } >= 2 && zoom != 1f && cen != Offset.Unspecified) {
            val old = vs.pxPerBeat
            val anchorBeat = (cen.x - g.keysW + vs.scrollX) / old
            vs.pxPerBeat = (old * zoom).coerceIn(24f, 900f)
            vs.scrollX = anchorBeat * vs.pxPerBeat - (cen.x - g.keysW)
        }
        vs.scrollX -= pan.x
        vs.scrollY -= pan.y
        vs.clamp(totalTicks)
        ev.changes.forEach { if (it.positionChanged()) it.consume() }
    } while (ev.changes.any { it.pressed })
}

private suspend fun AwaitPointerEventScope.keysGesture(
    down: PointerInputChange, c: RollCtx, vs: PianoRollViewState, g: RollGeom, audio: AudioEngine?,
) {
    val total = c.pattern.lengthBars * M.ticksPerBar(c.project.timeSigNum, c.project.timeSigDen)
    val trackId = c.track?.id
    val slop = viewConfiguration.touchSlop
    var scrolling = false
    val p0 = vs.pitchAt(down.position.y, g)
    vs.previewPitch = p0
    if (trackId != null) audio?.previewNote(trackId, p0, vs.defaultVelocity)
    try {
        while (true) {
            val ev = awaitPointerEvent()
            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
            if (!ch.pressed) break
            if (!scrolling && abs(ch.position.y - down.position.y) > slop) { scrolling = true; vs.previewPitch = null }
            if (scrolling) {
                vs.scrollY -= ch.positionChange().y
                vs.clamp(total)
                ch.consume()
            }
        }
    } finally {
        vs.previewPitch = null
    }
}

private suspend fun AwaitPointerEventScope.rulerGesture(
    down: PointerInputChange, c: RollCtx, vs: PianoRollViewState, g: RollGeom,
    store: ProjectStore, audio: AudioEngine?,
) {
    val p = c.project
    val barTicks = M.ticksPerBar(p.timeSigNum, p.timeSigDen)
    val maxBar = c.pattern.lengthBars - 1
    fun barAt(x: Float) = M.floorDiv(vs.tickAt(x, g), barTicks).toInt().coerceIn(0, maxBar)
    val b0 = barAt(down.position.x)
    val slop = viewConfiguration.touchSlop
    var moved = false
    while (true) {
        val ev = awaitPointerEvent()
        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
        if (!ch.pressed) break
        if (!moved && abs(ch.position.x - down.position.x) > slop) moved = true
        if (moved) {
            val b = barAt(ch.position.x)
            vs.loopDraft = min(b0, b) to (max(b0, b) + 1)   // end bar is exclusive
            ch.consume()
        }
    }
    val draft = vs.loopDraft
    vs.loopDraft = null
    if (moved && draft != null) {
        store.execute(Commands.setLoop(draft.first, draft.second))
    } else {
        val s = p.loopStartBar
        val e = p.loopEndBar
        if (s != null && e != null && b0 in s until e) {
            store.execute(Commands.setLoop(null, null))
        } else {
            audio?.seek(M.snapDown(vs.tickAt(down.position.x, g), M.gridTicks(vs.snapDiv)))
        }
    }
}

private suspend fun AwaitPointerEventScope.gridGesture(
    down: PointerInputChange, c: RollCtx, vs: PianoRollViewState, g: RollGeom,
    store: ProjectStore, audio: AudioEngine?,
) {
    val project = c.project
    val pattern = c.pattern
    val total = pattern.lengthBars * M.ticksPerBar(project.timeSigNum, project.timeSigDen)
    val track = c.track
    if (track == null || vs.tool == PianoRollTool.PAN) {
        transformLoop(vs, g, total)
        return
    }

    val pos0 = down.position
    val grid = M.gridTicks(vs.snapDiv)
    val base = track.notes
    val hit = hitNote(base, pos0, vs, g)
    val snapP: ((Int) -> Int)? =
        if (vs.scaleLock) { p -> M.nearestInScale(p, project.keyRoot, project.scale) } else null
    val slop = viewConfiguration.touchSlop
    val lpTimeout = viewConfiguration.longPressTimeoutMillis
    val t0 = down.uptimeMillis
    val rowAt0 = vs.rowAt(pos0.y, g)

    var mode: Mode
    var ids: Set<String> = emptySet()
    when (vs.tool) {
        PianoRollTool.ERASE -> {
            mode = Mode.ERASE
            if (hit != null) vs.draft = base.filterNot { it.id == hit.id }
        }
        else -> if (hit != null) {
            val edge = vs.xOf(hit.startTick + hit.durationTick, g)
            val wpx = edge - vs.xOf(hit.startTick, g)
            mode = if (pos0.x > edge - min(g.edgeW, wpx * 0.45f) && pos0.x <= edge + g.minHitW) Mode.RESIZE else Mode.MOVE
            ids = if (hit.id in vs.selection) vs.selection else setOf(hit.id)
        } else {
            mode = if (vs.tool == PianoRollTool.SELECT) Mode.MARQUEE else Mode.CREATE
        }
    }

    var created: Note? = null
    fun create(): Note {
        val p = vs.pitchAt(pos0.y, g).let { snapP?.invoke(it) ?: it }
        val n = Note(
            pitch = p,
            startTick = M.snapDown(vs.tickAt(pos0.x, g), grid).coerceIn(0L, (total - grid).coerceAtLeast(0L)),
            durationTick = vs.lastDuration.coerceAtLeast(grid),
            velocity = vs.defaultVelocity,
            source = NoteSource.USER,
        )
        created = n
        vs.draft = base + n
        vs.selection = setOf(n.id)
        audio?.previewNote(track.id, n.pitch, n.velocity)
        return n
    }

    fun commit() {
        vs.draft?.let { store.execute(Commands.replaceNotes(pattern.id, track.id, it)) }
        vs.draft = null
    }

    var moved = false
    var longPressed = false
    var lastPreview = hit?.pitch ?: -1
    var lastUp = t0
    val canLongPress = hit != null && mode == Mode.MOVE

    try {
        while (true) {
            val remaining = lpTimeout - (lastUp - t0)
            val ev = if (canLongPress && !moved && !longPressed) {
                if (remaining <= 0) null else withTimeoutOrNull(remaining) { awaitPointerEvent() }
            } else awaitPointerEvent()

            if (ev == null) {            // long press: toggle in multi-selection
                longPressed = true
                hit?.let { h -> vs.selection = if (h.id in vs.selection) vs.selection - h.id else vs.selection + h.id }
                continue
            }
            if (ev.changes.count { it.pressed } >= 2) {   // second finger: cancel edit, switch to pan/zoom
                vs.draft = null
                vs.marquee = null
                transformLoop(vs, g, total)
                return
            }
            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
            lastUp = ch.uptimeMillis
            if (!ch.pressed) {
                // finger lifted
                when (mode) {
                    Mode.CREATE -> {
                        val n = created ?: create()
                        commit()
                        vs.lastDuration = n.durationTick
                    }
                    Mode.MOVE, Mode.RESIZE -> if (moved) {
                        commit()
                        if (mode == Mode.RESIZE && hit != null) {
                            store.state.value.patterns.firstOrNull { it.id == pattern.id }
                                ?.tracks?.firstOrNull { it.id == track.id }
                                ?.notes?.firstOrNull { it.id == hit.id }?.let { vs.lastDuration = it.durationTick }
                        }
                    } else if (!longPressed && hit != null) {
                        vs.selection = if (vs.tool == PianoRollTool.SELECT && hit.id in vs.selection) vs.selection - hit.id
                        else if (vs.tool == PianoRollTool.SELECT) vs.selection + hit.id
                        else setOf(hit.id)
                        audio?.previewNote(track.id, hit.pitch, hit.velocity)
                    }
                    Mode.MARQUEE -> { vs.marquee = null; if (!moved) vs.selection = emptySet() }
                    Mode.ERASE -> commit()
                }
                break
            }

            val pos = ch.position
            if (!moved && (pos - pos0).getDistance() > slop) {
                moved = true
                if (mode == Mode.MOVE || mode == Mode.RESIZE) vs.selection = ids
                if (mode == Mode.CREATE) create()
            }
            if (!moved) continue
            ch.consume()

            val dTick = M.snapRound(((pos.x - pos0.x) / vs.pxPerBeat * PPQ).toLong(), grid)
            when (mode) {
                Mode.MOVE -> {
                    val dPitch = -(vs.rowAt(pos.y, g) - rowAt0)
                    vs.draft = M.moveNotes(base, ids, dTick, dPitch, snapP, (total - grid).coerceAtLeast(0L))
                    if (hit != null) {
                        val np = vs.draft?.firstOrNull { it.id == hit.id }?.pitch ?: lastPreview
                        if (np != lastPreview) { lastPreview = np; audio?.previewNote(track.id, np, hit.velocity) }
                    }
                }
                Mode.RESIZE -> vs.draft = M.resizeNotes(base, ids, dTick, grid)
                Mode.CREATE -> created?.let { n ->
                    val dur = max(grid, M.snapRound(vs.tickAt(pos.x, g) - n.startTick, grid))
                    vs.draft = base + n.copy(durationTick = dur)
                    created = n.copy(durationTick = dur)
                }
                Mode.MARQUEE -> {
                    val r = Rect(min(pos0.x, pos.x), min(pos0.y, pos.y), max(pos0.x, pos.x), max(pos0.y, pos.y))
                    vs.marquee = r
                    val t1 = vs.tickAt(r.left, g)
                    val t2 = vs.tickAt(r.right, g)
                    val pHi = vs.pitchAt(r.top, g)
                    val pLo = vs.pitchAt(r.bottom, g)
                    vs.selection = M.notesInRect(base, t1, t2, pLo, pHi)
                }
                Mode.ERASE -> {
                    val cur = vs.draft ?: base
                    val h2 = hitNote(cur, pos, vs, g)
                    if (h2 != null) vs.draft = cur.filterNot { it.id == h2.id }
                }
            }
        }
    } finally {
        // never leave half-finished state behind (e.g. gesture cancelled by the system)
        if (vs.draft != null) vs.draft = null
        vs.marquee = null
    }
}
