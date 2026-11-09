package com.pianoroll.ui.pianoroll

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import com.pianoroll.core.model.Note
import com.pianoroll.core.model.PPQ
import kotlin.math.max

enum class PianoRollTool { DRAW, SELECT, ERASE, PAN }

internal const val KEYS_W_DP = 44
internal const val RULER_H_DP = 26

/** Pure UI state (not part of the Project, not undoable). Scroll/zoom are in pixels. */
@Stable
class PianoRollViewState {
    var pxPerBeat by mutableFloatStateOf(0f)
    var rowHeight by mutableFloatStateOf(0f)
    var scrollX by mutableFloatStateOf(0f)
    var scrollY by mutableFloatStateOf(0f)
    var viewW by mutableFloatStateOf(0f)   // grid area size (without keys/ruler)
    var viewH by mutableFloatStateOf(0f)

    var snapDiv by mutableIntStateOf(16)
    var tool by mutableStateOf(PianoRollTool.DRAW)
    var scaleLock by mutableStateOf(false)
    var showGhost by mutableStateOf(true)

    var patternId by mutableStateOf<String?>(null)
    var activeTrackId by mutableStateOf<String?>(null)
    var selection by mutableStateOf<Set<String>>(emptySet())

    var lastDuration by mutableLongStateOf(PPQ / 4L)
    var defaultVelocity by mutableIntStateOf(100)

    // transient gesture state
    var draft by mutableStateOf<List<Note>?>(null)          // live preview of the active track's notes
    var marquee by mutableStateOf<Rect?>(null)
    var loopDraft by mutableStateOf<Pair<Int, Int>?>(null)   // (startBar, endBarExclusive)
    var previewPitch by mutableStateOf<Int?>(null)
    internal var scrollInit = false

    internal fun initMetrics(ppb: Float, rh: Float) {
        if (pxPerBeat == 0f) pxPerBeat = ppb
        if (rowHeight == 0f) rowHeight = rh
    }

    fun clamp(totalTicks: Long) {
        val maxX = max(0f, totalTicks.toFloat() / PPQ * pxPerBeat - viewW)
        val maxY = max(0f, 128 * rowHeight - viewH)
        scrollX = scrollX.coerceIn(0f, maxX)
        scrollY = scrollY.coerceIn(0f, maxY)
    }

    fun zoomVertical(factor: Float, totalTicks: Long, minPx: Float, maxPx: Float) {
        val centerRow = (scrollY + viewH / 2) / rowHeight
        rowHeight = (rowHeight * factor).coerceIn(minPx, maxPx)
        scrollY = centerRow * rowHeight - viewH / 2
        clamp(totalTicks)
    }

    fun zoomHorizontal(factor: Float, totalTicks: Long, minPx: Float, maxPx: Float) {
        val centerBeat = (scrollX + viewW / 2) / pxPerBeat
        pxPerBeat = (pxPerBeat * factor).coerceIn(minPx, maxPx)
        scrollX = centerBeat * pxPerBeat - viewW / 2
        clamp(totalTicks)
    }
}
