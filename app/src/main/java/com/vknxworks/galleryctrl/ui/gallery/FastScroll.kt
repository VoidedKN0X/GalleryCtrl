package com.vknxworks.galleryctrl.ui.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.vknxworks.galleryctrl.ui.theme.CtrlColors
import com.vknxworks.galleryctrl.ui.theme.CtrlType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A handle on the right edge of a photo grid: drag it to jump through the library, with a bubble showing the month
 * and year you're at. It shows while the grid scrolls (and a moment after) and only for long grids.
 *
 * [dates] holds the date of every grid item after the grid's own header items, in grid order (a day heading counts
 * with the date of its day), so the bubble can tell where any position is.
 */
@Composable
fun FastScroller(state: LazyGridState, dates: LongArray, modifier: Modifier = Modifier) {
    if (dates.size < MIN_ITEMS) return
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var lastTarget by remember { mutableIntStateOf(-1) }
    var visible by remember { mutableStateOf(false) }
    val scrolling = state.isScrollInProgress
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) visible = true else { delay(1500); visible = false }
    }
    val fraction by remember(state) {
        derivedStateOf {
            val total = state.layoutInfo.totalItemsCount
            val shown = state.layoutInfo.visibleItemsInfo.size
            val last = (total - shown).coerceAtLeast(1)
            (state.firstVisibleItemIndex.toFloat() / last).coerceIn(0f, 1f)
        }
    }
    val zone = remember { ZoneId.systemDefault() }
    val fmt = remember { DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()) }

    AnimatedVisibility(visible, modifier.fillMaxHeight(), enter = fadeIn(), exit = fadeOut()) {
        BoxWithConstraints(Modifier.fillMaxHeight().width(TRACK_WIDTH)) {
            val density = LocalDensity.current
            val thumbPx = with(density) { THUMB_HEIGHT.toPx() }
            val travel = (constraints.maxHeight - thumbPx).coerceAtLeast(1f)
            val shownFraction = if (dragging) dragFraction else fraction

            fun jumpTo(f: Float) {
                dragFraction = f.coerceIn(0f, 1f)
                val total = state.layoutInfo.totalItemsCount
                if (total <= 0) return
                val target = (dragFraction * (total - 1)).roundToInt()
                if (target != lastTarget) {
                    lastTarget = target
                    scope.launch { state.scrollToItem(target) }
                }
            }

            Box(
                Modifier.fillMaxHeight().width(TRACK_WIDTH).pointerInput(state, travel) {
                    detectVerticalDragGestures(
                        onDragStart = { at -> dragging = true; lastTarget = -1; jumpTo((at.y - thumbPx / 2) / travel) },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                        onVerticalDrag = { change, dy -> change.consume(); jumpTo(dragFraction + dy / travel) },
                    )
                },
            )
            val y = (shownFraction * travel).roundToInt()
            Box(
                Modifier.align(Alignment.TopEnd).offset { IntOffset(0, y) }.padding(end = 4.dp)
                    .size(width = 8.dp, height = THUMB_HEIGHT).clip(RoundedCornerShape(4.dp))
                    .background(if (dragging) CtrlColors.Accent else CtrlColors.Muted.copy(alpha = 0.7f)),
            )
            if (dragging) {
                val total = state.layoutInfo.totalItemsCount
                val headerItems = (total - dates.size).coerceAtLeast(0)
                val at = ((dragFraction * (total - 1)).roundToInt() - headerItems).coerceIn(0, dates.size - 1)
                val label = remember(at) { Instant.ofEpochMilli(dates[at]).atZone(zone).format(fmt).replaceFirstChar { it.titlecase(Locale.getDefault()) } }
                Row(
                    Modifier.align(Alignment.TopEnd)
                        .offset { IntOffset(-with(density) { (TRACK_WIDTH + 4.dp).roundToPx() }, (y + thumbPx / 2 - with(density) { 20.dp.toPx() }).roundToInt()) }
                        .wrapBubble(),
                ) {
                    Text(label, style = CtrlType.Status, color = CtrlColors.OnAccent)
                }
            }
        }
    }
}

@Composable
private fun Modifier.wrapBubble(): Modifier = this.clip(RoundedCornerShape(14.dp)).background(CtrlColors.Accent).padding(horizontal = 14.dp, vertical = 10.dp)

private val TRACK_WIDTH = 28.dp
private val THUMB_HEIGHT = 48.dp
private const val MIN_ITEMS = 120

/** The date of every grid item in [groups] order: each heading (when shown) with its group's first date, then each item. */
fun gridDates(groups: List<List<Long>>, headings: Boolean): LongArray {
    var n = 0
    for (g in groups) n += g.size + if (headings && g.isNotEmpty()) 1 else 0
    val out = LongArray(n)
    var i = 0
    for (g in groups) {
        if (g.isEmpty()) continue
        if (headings) out[i++] = g.first()
        for (d in g) out[i++] = d
    }
    return out
}
