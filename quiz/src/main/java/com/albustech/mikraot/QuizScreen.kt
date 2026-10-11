package com.albustech.mikraot

import android.view.HapticFeedbackConstants
import android.view.ViewConfiguration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val Accent = Color(0xFFF6C453)
private val Row = Color(0xFF1F2023)
private val RowLit = Color(0xFF2E2A1C)
private val Right = Color(0xFF5FB68A)
private val RightRim = Color(0xFF81C995)
private val Wrong = Color(0xFFA8231F)
private val WrongRim = Color(0xFFF28B82)
private val Muted = Color(0xFF9AA0A6)

private const val AUTO_NEXT_MS = 900L

/**
 * The round quiz screen. The circle is split into bands:
 *  - the top cap: score and streak;
 *  - the middle: the question, then the four answers as pills. The pill the bezel is on
 *    opens to its full text (up to three lines); the rest stay one line, so four answers
 *    always fit. All text is sized once per question so that the worst case fits the circle;
 *  - the right edge: four notches showing which answer the bezel is on;
 *  - the bottom cap: what to do next.
 *
 * Bezel: move between answers (a click each). Tap: answer with the lit one. After a wrong
 * answer the right one is shown until the next bezel click or tap; a right answer moves on
 * by itself. Long press: stats.
 */
@Composable
fun QuizScreen(state: QuizState, onInteract: () -> Unit = {}) {
    val view = LocalView.current
    val accumulator = remember(view) {
        DetentAccumulator(ViewConfiguration.get(view.context).scaledVerticalScrollFactor * 0.9f)
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    // The rim flashes the verdict, and a right answer moves on by itself.
    val flash = remember { Animatable(0f) }
    LaunchedEffect(state.answerCount) {
        if (state.answerCount == 0) return@LaunchedEffect
        view.performHapticFeedback(
            if (state.lastCorrect) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.REJECT,
        )
        flash.snapTo(1f)
        flash.animateTo(0.35f, tween(600))
        if (state.lastCorrect) {
            delay(AUTO_NEXT_MS - 600)
            if (state.answered) state.next()
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onRotaryScrollEvent {
                    onInteract()
                    if (state.showStats) return@onRotaryScrollEvent true
                    if (state.rotate(accumulator.add(it.verticalScrollPixels))) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    true
                }
                .focusRequester(focus)
                .focusable()
                .pointerInput(state) {
                    detectTapGestures(
                        onTap = { onInteract(); state.tapBackground() },
                        onLongPress = { onInteract(); state.showStats = true },
                    )
                },
        ) {
            val d = minOf(maxWidth, maxHeight)
            val verdict = when {
                !state.answered -> null
                state.lastCorrect -> RightRim
                else -> WrongRim
            }
            Rim(d, state, verdict, flash.value)
            Header(d, state)
            Body(d, state, onInteract)
            Footer(d, state)
            if (state.showStats) Stats(state, onInteract)
        }
    }
}

/** Thin verdict ring around the edge, and the four answer notches on the right. */
@Composable
private fun Rim(d: Dp, state: QuizState, verdict: Color?, flash: Float) {
    Canvas(Modifier.size(d)) {
        val stroke = 3.dp.toPx()
        val inset = stroke / 2 + 1.dp.toPx()
        val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
        val topLeft = Offset(inset, inset)
        if (verdict != null) {
            drawArc(verdict.copy(alpha = flash), 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
        }
        // Slot 0 is the top notch, at -NOTCH_SPAN*1.5 from 3 o'clock (angles grow clockwise).
        for (slot in 0 until QuizState.SLOTS) {
            val center = (slot - 1.5f) * NOTCH_STEP
            val color = when {
                state.answered && state.isCorrect(slot) -> RightRim
                state.answered && state.chosen == slot -> WrongRim
                state.highlighted == slot -> Accent
                else -> Color(0xFF3C4043)
            }
            val width = if (state.highlighted == slot || (state.answered && state.isCorrect(slot))) 5.dp else 3.dp
            drawArc(
                color, center - NOTCH_SPAN / 2, NOTCH_SPAN, false, topLeft, arcSize,
                style = Stroke(width.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}

private const val NOTCH_STEP = 11f
private const val NOTCH_SPAN = 7f

@Composable
private fun Header(d: Dp, state: QuizState) {
    state.answerCount // recompose when the score changes
    val p = state.progress
    val text = buildString {
        append("${p.mastered}/${p.size}")
        if (p.streak > 0) append("  ·  רצף ${p.streak}")
    }
    CapText(text, Modifier.fillMaxWidth().offset(y = d * 0.055f), Muted)
}

@Composable
private fun Footer(d: Dp, state: QuizState) {
    val text = when {
        state.answered && !state.lastCorrect -> "הקש להמשך"
        state.answered -> "נכון!"
        state.highlighted != null -> "הקש לאישור"
        else -> "סובב את הבזל"
    }
    val color = when {
        state.answered && state.lastCorrect -> RightRim
        state.highlighted != null && !state.answered -> Accent
        else -> Muted
    }
    CapText(text, Modifier.fillMaxWidth().offset(y = d * 0.885f), color)
}

@Composable
private fun CapText(text: String, modifier: Modifier, color: Color) {
    BasicText(
        text,
        modifier,
        style = TextStyle(color = color, fontSize = 11.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Medium),
        maxLines = 1,
    )
}

/** Font sizes for one question, scaled down together until the worst case fits. */
private class Fit(val scale: Float) {
    val question: TextUnit = (QUESTION_SP * scale).sp
    val answer: TextUnit = (ANSWER_SP * scale).sp
}

private const val QUESTION_SP = 15.5f
private const val ANSWER_SP = 14f
private const val OPEN_LINES = 3
private val RowPadH = 10.dp
private val RowPadV = 5.dp
private val RowGap = 4.dp
private val QuestionGap = 8.dp

@Composable
private fun Body(d: Dp, state: QuizState, onInteract: () -> Unit) {
    val width = d * 0.70f
    val top = d * 0.13f
    val height = d * 0.74f
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val fit = remember(state.index, d) {
        with(density) {
            val q = state.question
            val textW = width.roundToPx()
            val rowW = (width - RowPadH * 2).roundToPx()
            fun h(text: String, sp: Float, bold: Boolean, w: Int, lines: Int) = measurer.measure(
                text,
                TextStyle(fontSize = sp.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal),
                maxLines = lines,
                constraints = Constraints(maxWidth = w),
            ).size.height
            val scale = SCALES.firstOrNull { s ->
                // Worst case: after a wrong answer two pills are open (the chosen and the right one).
                val closed = q.answers.map { h(it, ANSWER_SP * s, false, rowW, 1) }
                val open = q.answers.map { h(it, ANSWER_SP * s, true, rowW - (MarkSize * 1.5f + 2.dp).roundToPx(), OPEN_LINES) }
                val growth = q.answers.indices.map { open[it] - closed[it] }.sortedDescending().take(2).sum()
                val total = h(q.text, QUESTION_SP * s, true, textW, Int.MAX_VALUE) + QuestionGap.roundToPx() +
                    closed.sum() + growth + (RowPadV * 2 * 4 + RowGap * 3).roundToPx()
                total <= height.roundToPx()
            } ?: SCALES.last()
            Fit(scale)
        }
    }

    Column(
        Modifier
            .offset(x = (d - width) / 2, y = top)
            .width(width)
            .height(height),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(
            state.question.text,
            Modifier.fillMaxWidth(),
            style = TextStyle(
                color = Color.White, fontSize = fit.question, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, lineHeight = fit.question * 1.15f,
            ),
        )
        Spacer(Modifier.height(QuestionGap))
        for (slot in 0 until QuizState.SLOTS) {
            if (slot > 0) Spacer(Modifier.height(RowGap))
            AnswerPill(state, slot, fit, onInteract)
        }
    }
}

@Composable
private fun AnswerPill(state: QuizState, slot: Int, fit: Fit, onInteract: () -> Unit) {
    val lit = state.highlighted == slot
    val right = state.answered && state.isCorrect(slot)
    val wrong = state.answered && state.chosen == slot && !right
    val open = lit || right || wrong
    val background = when {
        right -> Right
        wrong -> Wrong
        lit -> RowLit
        else -> Row
    }
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .fillMaxWidth()
            .alpha(if (state.answered && !open) 0.4f else 1f)
            .background(background, shape)
            .then(if (lit && !state.answered) Modifier.border(2.dp, Accent, shape) else Modifier)
            .pointerInput(state, slot) {
                detectTapGestures(
                    onTap = { onInteract(); state.tapSlot(slot) },
                    onLongPress = { onInteract(); state.showStats = true },
                )
            }
            .semantics(mergeDescendants = true) {
                selected = lit
                if (right) contentDescription = "תשובה נכונה: ${state.answer(slot)}"
            }
            .padding(horizontal = RowPadH, vertical = RowPadV),
        contentAlignment = Alignment.Center,
    ) {
        val mark = when {
            right -> Mark.Right
            wrong -> Mark.Wrong
            else -> null
        }
        BasicText(
            state.answer(slot),
            Modifier.fillMaxWidth().padding(start = if (mark != null) MarkSize / 2 else 0.dp, end = if (mark != null) MarkSize + 2.dp else 0.dp),
            style = TextStyle(
                color = Color.White,
                fontSize = fit.answer,
                fontWeight = if (open) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                lineHeight = fit.answer * 1.15f,
            ),
            maxLines = if (open) OPEN_LINES else 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (mark != null) MarkIcon(mark, Modifier.align(Alignment.CenterEnd))
    }
}

private enum class Mark { Right, Wrong }

private val MarkSize = 18.dp

/** The verdict at the pill's end: a ringed check or cross, white on the pill's colour. */
@Composable
private fun MarkIcon(mark: Mark, modifier: Modifier) {
    Canvas(modifier.size(MarkSize)) {
        val w = size.width
        val stroke = Stroke(w * 0.09f, cap = StrokeCap.Round)
        drawCircle(Color.White, radius = w / 2 - stroke.width / 2, style = stroke)
        val path = androidx.compose.ui.graphics.Path()
        if (mark == Mark.Right) {
            path.moveTo(w * 0.28f, w * 0.52f)
            path.lineTo(w * 0.44f, w * 0.68f)
            path.lineTo(w * 0.73f, w * 0.34f)
        } else {
            path.moveTo(w * 0.34f, w * 0.34f); path.lineTo(w * 0.66f, w * 0.66f)
            path.moveTo(w * 0.66f, w * 0.34f); path.lineTo(w * 0.34f, w * 0.66f)
        }
        drawPath(path, Color.White, style = stroke)
    }
}

private val SCALES = listOf(1f, 0.93f, 0.86f, 0.8f, 0.74f, 0.68f)

/** Long press: totals, and a two-tap reset. */
@Composable
private fun Stats(state: QuizState, onInteract: () -> Unit) {
    val p = state.progress
    var armed by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { onInteract(); state.showStats = false } },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val line = TextStyle(color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center)
            BasicText("שער 1 · מבנה וסמלים", style = line.copy(color = Accent, fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(8.dp))
            BasicText("שולטים: ${p.mastered} מתוך ${p.size}", style = line)
            val pct = if (p.answered == 0) 0 else p.correct * 100 / p.answered
            BasicText("נענו: ${p.answered} · נכון $pct%", style = line)
            BasicText("רצף שיא: ${p.best}", style = line)
            Spacer(Modifier.height(12.dp))
            BasicText(
                if (armed) "הקש שוב לאיפוס" else "איפוס התקדמות",
                Modifier
                    .background(if (armed) Wrong else Row, RoundedCornerShape(50))
                    .pointerInput(Unit) {
                        detectTapGestures {
                            onInteract()
                            if (armed) state.reset() else armed = true
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                style = line.copy(fontSize = 13.sp),
            )
            Spacer(Modifier.height(8.dp))
            BasicText("הקש לחזרה", style = line.copy(color = Muted, fontSize = 11.sp))
        }
    }
}
