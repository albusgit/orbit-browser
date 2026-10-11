package com.albustech.mikraot

import android.view.HapticFeedbackConstants
import android.view.ViewConfiguration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One row of the picker: a part (or all of them) and how much of it is mastered. */
class PickerEntry(val number: Int, val title: String, val size: Int, val mastered: Int) {
    val label: String get() = if (number == QuestionBank.ALL) "תרגול מעורב" else "שער $number"
}

private val PickAccent = Color(0xFFF6C453)
private val PickMuted = Color(0xFF9AA0A6)
private val PickCard = Color(0xFF2E2A1C)
private val PickTrack = Color(0xFF3C4043)

/**
 * Choose a part with the bezel, like a dial: the current part fills the middle of the circle,
 * its neighbours peek above and below, and a scroll notch on the right edge shows where you
 * are. Tap the middle to start; tap a neighbour (or swipe up/down) to move to it.
 */
@Composable
fun PartPicker(
    entries: List<PickerEntry>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onOpen: (Int) -> Unit,
    onInteract: () -> Unit = {},
) {
    val view = LocalView.current
    val accumulator = remember(view) {
        DetentAccumulator(ViewConfiguration.get(view.context).scaledVerticalScrollFactor * 0.9f)
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val density = LocalDensity.current
    val dragStep = with(density) { 36.dp.toPx() }

    fun move(by: Int) {
        val to = (selected + by).coerceIn(0, entries.lastIndex)
        if (to != selected) {
            onSelect(to)
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        } else if (by != 0) {
            view.performHapticFeedback(HapticFeedbackConstants.REJECT)
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onRotaryScrollEvent {
                    onInteract()
                    move(accumulator.add(it.verticalScrollPixels))
                    true
                }
                .focusRequester(focus)
                .focusable()
                .pointerInput(entries.size, selected) {
                    var dragged = 0f
                    detectVerticalDragGestures(
                        onDragStart = { dragged = 0f },
                    ) { _, dy ->
                        onInteract()
                        dragged += dy
                        // Drag up = move down the list, like scrolling.
                        while (dragged <= -dragStep) { dragged += dragStep; move(1) }
                        while (dragged >= dragStep) { dragged -= dragStep; move(-1) }
                    }
                },
        ) {
            val d = minOf(maxWidth, maxHeight)
            val current = entries[selected]
            ScrollNotch(d, selected, entries.size)
            PickerCap("אלוף המקראות", Modifier.fillMaxWidth().offset(y = d * 0.055f), PickAccent)
            entries.getOrNull(selected - 1)?.let { Neighbour(it, d, top = true) { onInteract(); move(-1) } }
            Card(current, d) { onInteract(); onOpen(selected) }
            entries.getOrNull(selected + 1)?.let { Neighbour(it, d, top = false) { onInteract(); move(1) } }
            PickerCap("הקש להתחלה", Modifier.fillMaxWidth().offset(y = d * 0.885f), PickMuted)
        }
    }
}

@Composable
private fun Card(entry: PickerEntry, d: Dp, onTap: () -> Unit) {
    val width = d * 0.76f
    val shape = RoundedCornerShape(28.dp)
    Box(
        Modifier
            .offset(x = (d - width) / 2, y = d * 0.27f)
            .width(width)
            .height(d * 0.46f)
            .background(PickCard, shape)
            .border(2.dp, PickAccent, shape)
            .pointerInput(entry.number) { detectTapGestures { onTap() } }
            .semantics(mergeDescendants = true) { contentDescription = "${entry.label}, ${entry.title}" }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(entry.label, style = TextStyle(color = PickAccent, fontSize = 12.sp, fontWeight = FontWeight.Medium))
            BasicText(
                entry.title,
                Modifier.fillMaxWidth(),
                style = TextStyle(
                    color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, lineHeight = 19.sp,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(5.dp))
            MasteryBar(entry, Modifier.width(width * 0.6f))
            Spacer(Modifier.height(3.dp))
            BasicText(
                "${entry.mastered}/${entry.size} שאלות",
                style = TextStyle(color = PickMuted, fontSize = 11.sp),
            )
        }
    }
}

/** How much of the part is mastered: a thin bar, filled from the right (RTL). */
@Composable
private fun MasteryBar(entry: PickerEntry, modifier: Modifier) {
    Canvas(modifier.height(4.dp)) {
        val r = size.height / 2
        drawLine(PickTrack, Offset(r, r), Offset(size.width - r, r), size.height, StrokeCap.Round)
        val f = if (entry.size == 0) 0f else entry.mastered.toFloat() / entry.size
        if (f > 0f) {
            val start = size.width - r
            drawLine(PickAccent, Offset(start, r), Offset(start - (size.width - 2 * r) * f, r), size.height, StrokeCap.Round)
        }
    }
}

@Composable
private fun Neighbour(entry: PickerEntry, d: Dp, top: Boolean, onTap: () -> Unit) {
    val width = d * 0.62f
    Box(
        Modifier
            .offset(x = (d - width) / 2, y = if (top) d * 0.145f else d * 0.735f)
            .width(width)
            .height(d * 0.12f)
            .pointerInput(entry.number, top) { detectTapGestures { onTap() } },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            "${entry.label} · ${entry.title}",
            Modifier.fillMaxWidth(),
            style = TextStyle(color = PickMuted, fontSize = 13.sp, textAlign = TextAlign.Center),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PickerCap(text: String, modifier: Modifier, color: Color) {
    BasicText(
        text,
        modifier,
        style = TextStyle(color = color, fontSize = 12.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold),
        maxLines = 1,
    )
}

/** A scroll track on the right edge with a thumb at the selected part. */
@Composable
private fun ScrollNotch(d: Dp, selected: Int, count: Int) {
    Canvas(Modifier.size(d)) {
        val stroke = 4.dp.toPx()
        val inset = stroke / 2 + 2.dp.toPx()
        val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
        val topLeft = Offset(inset, inset)
        drawArc(PickTrack, -TRACK / 2, TRACK, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        val thumb = TRACK / count.coerceAtLeast(1)
        val start = -TRACK / 2 + thumb * selected
        drawArc(PickAccent, start, thumb, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

private const val TRACK = 60f
