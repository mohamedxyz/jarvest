package dev.goaltracker.app

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.goaltracker.core.CalendarBlock
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalTime

internal val LABEL_WIDTH = 64.dp
private val TOP_PAD = 8.dp
private val BOTTOM_PAD = 96.dp
private const val DEFAULT_HOUR_DP = 56f

enum class DragMode { NONE, MOVE, RESIZE, CHIP }

/**
 * What a calendar drag is doing right now, shared by the grid, the week strip and the Anytime row.
 * Positions of the finger are in screen (root) coordinates, so a block dropped on the week strip can
 * be matched to a day. The grid copies its own measurements here so a chip dragged in from outside
 * can ask "what time is under the finger".
 */
@Stable
class CalendarDragState {
    var mode by mutableStateOf(DragMode.NONE)
    var block by mutableStateOf<CalendarBlock?>(null)
    var pointer by mutableStateOf(Offset.Zero)

    /** While moving: the minutes between the top of the block and where it was grabbed. */
    var grabMinutes by mutableStateOf(0f)

    /** While resizing: the length the block would have. */
    var resizeMinutes by mutableStateOf(0)

    var gridRect by mutableStateOf(Rect.Zero)
    val dayRects = mutableStateMapOf<Int, Rect>()

    var hourPx = 0f
    var topPadPx = 0f
    var scroll: ScrollState? = null

    /** True while a block or chip is being carried (not while only resizing). */
    val carrying: Boolean get() = mode == DragMode.MOVE || mode == DragMode.CHIP

    fun pointerMinute(): Float =
        minuteAt(pointer.y - gridRect.top + (scroll?.value ?: 0), hourPx, topPadPx)

    fun overGrid(): Boolean = gridRect.contains(pointer)

    /** The index (0 = Monday) of the week-strip day under the finger, if any. */
    fun dayUnderPointer(): Int? = dayRects.entries.firstOrNull { it.value.contains(pointer) }?.key

    /** Where the carried block would start if dropped now. */
    fun liveStart(): Int {
        val b = block ?: return 0
        return clampStart(snapTo(pointerMinute() - grabMinutes, MIN_BLOCK_MINUTES), carriedMinutes(b))
    }

    fun carriedMinutes(b: CalendarBlock): Int = maxOf(b.minutes.toInt(), MIN_BLOCK_MINUTES)

    fun reset() {
        mode = DragMode.NONE
        block = null
    }
}

/**
 * The day, hour by hour. Gestures: tap an empty spot to add; press, hold and drag on empty space to
 * draw a range; press and hold a block to move it; drag its bottom edge to resize; pinch to zoom.
 * It only reports what the person did. The caller decides what to save.
 */
@Composable
internal fun DayGrid(
    date: String,
    isToday: Boolean,
    blocks: List<CalendarBlock>,
    placed: List<Placed>,
    drag: CalendarDragState,
    modifier: Modifier = Modifier,
    onSelect: (CalendarBlock) -> Unit,
    onQuickAdd: (start: Int, minutes: Int) -> Unit,
    onMoveEnd: (CalendarBlock, start: Int) -> Unit,
    onResizeEnd: (CalendarBlock, minutes: Int) -> Unit,
    onNudge: (CalendarBlock, startDelta: Int, minutesDelta: Int) -> Unit,
) {
    val density = LocalDensity.current
    val view = LocalView.current
    var hourDp by rememberSaveable { mutableStateOf(DEFAULT_HOUR_DP) }
    val hourPx = with(density) { hourDp.dp.toPx() }
    val topPadPx = with(density) { TOP_PAD.toPx() }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var pendingScroll by remember { mutableStateOf<Int?>(null) }
    var ghost by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    // The state object reads these when a chip from outside asks what time is under the finger.
    drag.hourPx = hourPx
    drag.topPadPx = topPadPx
    drag.scroll = scroll

    // Open near now (today) or near the first block of the day.
    val latestBlocks by rememberUpdatedState(blocks)
    LaunchedEffect(date) {
        val first = latestBlocks.mapNotNull { it.startMinutes?.toInt() }.minOrNull()
        val focusMinute = when {
            isToday -> LocalTime.now().let { it.hour * 60 + it.minute } - 60
            first != null -> first - 60
            else -> 6 * 60
        }.coerceAtLeast(0)
        snapshotFlow { scroll.maxValue }.first { it > 0 }
        scroll.scrollTo((topPadPx + focusMinute / 60f * hourPx).roundToInt())
    }

    // After a pinch the grid is taller or shorter. Wait one frame so the new size is known, then keep
    // the time under the fingers where it was.
    LaunchedEffect(hourDp) {
        pendingScroll?.let {
            withFrameNanos { }
            scroll.scrollTo(it)
            pendingScroll = null
        }
    }

    // Slide the grid while a block is held near its top or bottom edge.
    LaunchedEffect(drag.mode) {
        if (!drag.carrying) return@LaunchedEffect
        val edge = with(density) { 56.dp.toPx() }
        val maxStep = with(density) { 14.dp.toPx() }
        while (true) {
            withFrameNanos { }
            val y = drag.pointer.y
            val top = drag.gridRect.top
            val bottom = drag.gridRect.bottom
            val step = when {
                y in top..(top + edge) -> -maxStep * (1f - (y - top) / edge)
                y in (bottom - edge)..bottom -> maxStep * (1f - (bottom - y) / edge)
                else -> 0f
            }
            if (step != 0f) scroll.scrollBy(step)
        }
    }

    // A small tick each time the carried block snaps to a new time.
    val live by remember { derivedStateOf { if (drag.carrying) drag.liveStart() else -1 } }
    LaunchedEffect(live) {
        if (live >= 0 && Feel.haptics) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    fun zoom(factor: Float, centerY: Float) {
        val minute = (scroll.value + centerY - topPadPx) / hourPx
        hourDp = zoomedHourDp(hourDp, factor)
        val newPx = with(density) { hourDp.dp.toPx() }
        pendingScroll = (topPadPx + minute * newPx - centerY).roundToInt().coerceAtLeast(0)
    }

    Box(
        modifier
            .onGloballyPositioned { drag.gridRect = it.boundsInRoot() }
            // Two fingers zoom. One finger is left alone, so scrolling and taps work as normal.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            val factor = event.calculateZoom()
                            if (factor != 1f) {
                                zoom(factor, event.calculateCentroid().y)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Box(Modifier.fillMaxWidth().height(hourDp.dp * 24 + TOP_PAD + BOTTOM_PAD)) {
                Column {
                    (0..23).forEach { hour ->
                        Row(Modifier.height(hourDp.dp)) {
                            Text(
                                Reminders.formatMinutes(hour * 60),
                                Modifier.width(LABEL_WIDTH).padding(start = 8.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            HorizontalDivider(Modifier.weight(1f).padding(top = TOP_PAD), color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                    Spacer(Modifier.height(BOTTOM_PAD))
                }

                // Empty space: tap to add, or press, hold and drag to draw a range.
                Box(
                    Modifier.padding(start = LABEL_WIDTH).fillMaxSize()
                        .pointerInput(hourPx) {
                            detectTapGestures { o ->
                                val minute = floorTo(minuteAt(o.y, hourPx, topPadPx).toInt(), 30)
                                onQuickAdd(minute, 60)
                            }
                        }
                        .pointerInput(hourPx) {
                            var anchor = 0f
                            detectDragGesturesAfterLongPress(
                                onDragStart = { o ->
                                    anchor = minuteAt(o.y, hourPx, topPadPx)
                                    ghost = rangeBetween(anchor, anchor)
                                    if (Feel.haptics) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    ghost = rangeBetween(anchor, minuteAt(change.position.y, hourPx, topPadPx))
                                },
                                onDragEnd = {
                                    val range = ghost
                                    ghost = null
                                    if (range != null) {
                                        // A press with no drag has no length of its own, so it gets an hour.
                                        if (range.second <= MIN_BLOCK_MINUTES) onQuickAdd(floorTo(anchor.toInt(), 30), 60)
                                        else onQuickAdd(range.first, range.second)
                                    }
                                },
                                onDragCancel = { ghost = null },
                            )
                        },
                )

                BoxWithConstraints(Modifier.padding(start = LABEL_WIDTH, end = 8.dp).fillMaxSize()) {
                    val fullPx = constraints.maxWidth.toFloat()
                    val labelPx = with(density) { LABEL_WIDTH.toPx() }

                    ghost?.let { (start, length) ->
                        Box(
                            Modifier
                                .at(0f, topPadPx + start / 60f * hourPx)
                                .fillMaxWidth()
                                .height(with(density) { (length / 60f * hourPx).toDp() })
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                                .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)),
                        ) {
                            Text(
                                "${Reminders.formatMinutes(start)} to ${Reminders.formatMinutes(start + length)}",
                                Modifier.padding(6.dp),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }

                    placed.forEach { p ->
                        val block = p.block
                        val start = block.startMinutes!!.toInt()
                        val laneWidthPx = fullPx / p.lanes
                        val topPx = topPadPx + start / 60f * hourPx
                        val leftPx = laneWidthPx * p.lane
                        val heightPx = maxOf(block.minutes.toInt() / 60f * hourPx, with(density) { 28.dp.toPx() })
                        val beingDragged = drag.block?.id == block.id && drag.mode != DragMode.NONE && drag.mode != DragMode.CHIP
                        TimedBlock(
                            block = block,
                            modifier = Modifier
                                .at(leftPx, topPx)
                                .width(with(density) { laneWidthPx.toDp() } - 2.dp)
                                .height(with(density) { heightPx.toDp() } - 2.dp),
                            dimmed = beingDragged,
                            drag = drag,
                            hourPx = hourPx,
                            topPx = topPx,
                            leftPx = leftPx + labelPx,
                            onClick = { onSelect(block) },
                            onMoveEnd = { s -> onMoveEnd(block, s) },
                            onResizeEnd = { m -> onResizeEnd(block, m) },
                            onNudge = { sd, md -> onNudge(block, sd, md) },
                        )
                    }

                    // The lifted copy of the block being moved or resized, or the preview slot for a chip.
                    val dragged = drag.block
                    if (dragged != null && drag.mode != DragMode.NONE && (drag.mode == DragMode.RESIZE || drag.overGrid())) {
                        val lane = placed.firstOrNull { it.block.id == dragged.id }
                        val laneWidthPx = if (lane != null) fullPx / lane.lanes else fullPx
                        val leftPx = if (lane != null) laneWidthPx * lane.lane else 0f
                        val start = if (drag.mode == DragMode.RESIZE) dragged.startMinutes!!.toInt() else live.coerceAtLeast(0)
                        val length = if (drag.mode == DragMode.RESIZE) drag.resizeMinutes else drag.carriedMinutes(dragged)
                        Surface(
                            modifier = Modifier
                                .at(leftPx, topPadPx + start / 60f * hourPx)
                                .width(with(density) { laneWidthPx.toDp() } - 2.dp)
                                .height(with(density) { maxOf(length / 60f * hourPx, 28.dp.toPx()).toDp() } - 2.dp)
                                .graphicsLayer { scaleX = 1.02f; shadowElevation = 12.dp.toPx() },
                            shape = RoundedCornerShape(10.dp),
                            color = blockColor(dragged.kind),
                            border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                        ) {
                            Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                                Text(
                                    (dragged.icon?.let { "$it " } ?: "") + dragged.title,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${Reminders.formatMinutes(start)} to ${Reminders.formatMinutes(start + length)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                )
                            }
                        }
                    }

                    if (isToday) {
                        val now = LocalTime.now()
                        Box(
                            Modifier.at(0f, topPadPx + (now.hour * 60 + now.minute) / 60f * hourPx)
                                .fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.error),
                        )
                    }
                }
            }
        }
    }
}

/** Places a child at a pixel position without making the caller convert to dp first. */
private fun Modifier.at(xPx: Float, yPx: Float): Modifier =
    this.offset { IntOffset(xPx.roundToInt(), yPx.roundToInt()) }

/**
 * One block on the grid. It stays where it is while it is being dragged: the grid draws the lifted
 * copy, so this node's own coordinates never move under the finger.
 */
@Composable
private fun TimedBlock(
    block: CalendarBlock,
    modifier: Modifier,
    dimmed: Boolean,
    drag: CalendarDragState,
    hourPx: Float,
    topPx: Float,
    leftPx: Float,
    onClick: () -> Unit,
    onMoveEnd: (start: Int) -> Unit,
    onResizeEnd: (minutes: Int) -> Unit,
    onNudge: (startDelta: Int, minutesDelta: Int) -> Unit,
) {
    val view = LocalView.current
    val handleHeight = if (block.minutes < 30u) 10.dp else 18.dp
    Surface(
        modifier = modifier
            .graphicsLayer { alpha = if (dimmed) 0.35f else 1f }
            .pointerInput(block.id, block.startMinutes, block.minutes, hourPx) {
                // Where the finger is on the screen, worked out from where the block is in the grid.
                fun screen(local: Offset) = Offset(
                    drag.gridRect.left + leftPx + local.x,
                    drag.gridRect.top - (drag.scroll?.value ?: 0) + topPx + local.y,
                )
                detectDragGesturesAfterLongPress(
                    onDragStart = { o ->
                        drag.block = block
                        drag.mode = DragMode.MOVE
                        drag.grabMinutes = o.y / hourPx * 60f
                        drag.pointer = screen(o)
                        if (Feel.haptics) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        drag.pointer = screen(change.position)
                    },
                    onDragEnd = {
                        val start = drag.liveStart()
                        onMoveEnd(start)
                        drag.reset()
                    },
                    onDragCancel = { drag.reset() },
                )
            }
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("Move 15 minutes earlier") { onNudge(-15, 0); true },
                    CustomAccessibilityAction("Move 15 minutes later") { onNudge(15, 0); true },
                    CustomAccessibilityAction("Make 15 minutes longer") { onNudge(0, 15); true },
                    CustomAccessibilityAction("Make 15 minutes shorter") { onNudge(0, -15); true },
                )
            }
            .label("${kindName(block.kind)} ${block.title}, ${timeText(block)}. Press and hold, then drag to move."),
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = blockColor(block.kind).copy(alpha = if (block.done) 0.6f else 1f),
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text(
                    (block.icon?.let { "$it " } ?: "") + block.title,
                    style = MaterialTheme.typography.labelLarge,
                    textDecoration = if (block.done) TextDecoration.LineThrough else null,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (block.minutes >= 45u) {
                    Text(timeText(block), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
            // The bottom edge: drag it to change the length.
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(handleHeight)
                    .pointerInput(block.id, block.startMinutes, block.minutes, hourPx) {
                        var downY = 0f
                        detectDragGestures(
                            onDragStart = { o ->
                                downY = o.y
                                drag.block = block
                                drag.mode = DragMode.RESIZE
                                drag.resizeMinutes = block.minutes.toInt()
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val delta = (change.position.y - downY) / hourPx * 60f
                                drag.resizeMinutes = resizedMinutes(block.startMinutes!!.toInt(), block.minutes.toInt(), delta)
                            },
                            onDragEnd = {
                                onResizeEnd(drag.resizeMinutes)
                                drag.reset()
                            },
                            onDragCancel = { drag.reset() },
                        )
                    },
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    Modifier.padding(bottom = 3.dp).width(28.dp).height(3.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f), RoundedCornerShape(2.dp)),
                )
            }
        }
    }
}
