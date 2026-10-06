package com.albustech.orbit.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.albustech.orbit.R
import com.albustech.orbit.browser.SerpData
import com.albustech.orbit.browser.SerpResult
import com.albustech.orbit.browser.SerpState
import com.albustech.orbit.input.DetentAccumulator
import com.albustech.orbit.input.Haptics
import kotlin.math.cos
import kotlin.math.sin

/** One card on the results screen. */
private sealed interface SerpCard {
    data class Answer(val text: String, val source: SerpResult?) : SerpCard
    data class Result(val result: SerpResult, val number: Int) : SerpCard
    data class More(val url: String) : SerpCard
}

private fun cardsOf(data: SerpData): List<SerpCard> = buildList {
    val first = data.results.firstOrNull()
    if (!data.answer.isNullOrBlank() && data.answer != first?.snippet) add(SerpCard.Answer(data.answer, first))
    data.results.forEachIndexed { i, r -> add(SerpCard.Result(r, i + 1)) }
    data.nextUrl?.let { add(SerpCard.More(it)) }
}

/**
 * Search results drawn natively (designs 3e/3f): one result per card, each bezel click snaps
 * to the next, dots on the left rim show where you are; turning back past the first result
 * (or pinching out) shows the overview list, whose rows follow the circle's chord.
 */
@Composable
fun SerpScreen(
    geometry: RoundGeometry,
    state: SerpState,
    startIndex: Int,
    onIndex: (Int) -> Unit,
    onOpen: (String) -> Unit,
    onEdit: () -> Unit,
    onVoice: () -> Unit,
    startInOverview: Boolean = false,
) {
    when (state) {
        is SerpState.Loading -> SearchingScreen(geometry, state.page.query)
        is SerpState.Ready -> SerpResults(geometry, state.data, startIndex, startInOverview, onIndex, onOpen, onEdit, onVoice)
    }
}

@Composable
private fun SearchingScreen(geometry: RoundGeometry, query: String) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(Modifier.width((geometry.squareDp).dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(R.drawable.ic_search), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Text(query, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.searching), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SerpResults(
    geometry: RoundGeometry,
    data: SerpData,
    startIndex: Int,
    startInOverview: Boolean,
    onIndex: (Int) -> Unit,
    onOpen: (String) -> Unit,
    onEdit: () -> Unit,
    onVoice: () -> Unit,
) {
    val cards = remember(data) { cardsOf(data) }
    var index by rememberSaveable(data.pageUrl) { mutableIntStateOf(startIndex.coerceIn(0, (cards.size - 1).coerceAtLeast(0))) }
    var overview by rememberSaveable(data.pageUrl) { mutableStateOf(startInOverview) }
    BackHandler(enabled = overview) { overview = false }

    if (overview) {
        SerpOverview(data, cards, index, onPick = { i ->
            index = i
            onIndex(i)
            overview = false
        }, onOpen = onOpen, onEdit = onEdit, onVoice = onVoice)
    } else {
        SerpCards(geometry, data.page.query, cards, index, onIndex = { i ->
            index = i
            onIndex(i)
        }, onOverview = { overview = true }, onOpen = onOpen)
    }
}

@Composable
private fun SerpCards(
    geometry: RoundGeometry,
    query: String,
    cards: List<SerpCard>,
    index: Int,
    onIndex: (Int) -> Unit,
    onOverview: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val view = LocalView.current
    val haptics = remember(view) { Haptics(view) }
    val accumulator = remember(view) {
        DetentAccumulator.forScrollFactor(android.view.ViewConfiguration.get(view.context).scaledVerticalScrollFactor)
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val results = cards.count { it is SerpCard.Result }
    val compact = geometry.diameterDp < COMPACT_BELOW_DP

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onRotaryScrollEvent {
                val n = accumulator.add(it.verticalScrollPixels)
                if (n != 0) {
                    val next = index + n
                    when {
                        next < 0 -> onOverview()
                        next > cards.lastIndex -> haptics.edge()
                        else -> {
                            haptics.tick()
                            onIndex(next)
                        }
                    }
                }
                true
            }
            .focusRequester(focus)
            .focusable()
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ -> if (zoom < PINCH_OUT) onOverview() }
            },
        contentAlignment = Alignment.Center,
    ) {
        val card = cards.getOrNull(index)
        val header = when (card) {
            is SerpCard.Result -> stringResource(R.string.results_position, query, card.number, results)
            is SerpCard.Answer -> stringResource(R.string.top_answer)
            is SerpCard.More, null -> query
        }
        // Kept short so its ends don't run down the sides into the card.
        TopCurvedText(header, MaterialTheme.colorScheme.onSurfaceVariant, maxSweepAngle = HEADER_SWEEP)
        PositionDots(geometry, cards.size, index)

        AnimatedContent(
            targetState = index,
            transitionSpec = {
                val down = targetState > initialState
                (slideInVertically { h -> if (down) h / 4 else -h / 4 } + fadeIn())
                    .togetherWith(slideOutVertically { h -> if (down) -h / 4 else h / 4 } + fadeOut())
            },
            label = "serpCard",
        ) { i ->
            when (val c = cards.getOrNull(i)) {
                is SerpCard.Result -> ResultCard(geometry, c.result, compact, onOpen)
                is SerpCard.Answer -> AnswerCard(geometry, c, compact, onOpen)
                is SerpCard.More -> MoreCard(geometry, c.url, onOpen)
                null -> Unit
            }
        }
    }
}

@Composable
private fun ResultCard(geometry: RoundGeometry, r: SerpResult, compact: Boolean, onOpen: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    // Top-anchored below the curved header (not centred), so a long title or snippet grows
    // down towards the Open button instead of up into the header.
    Box(Modifier.fillMaxSize().padding(top = (geometry.diameterDp * HEADER_BAND).dp), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .width((geometry.diameterDp * CARD_WIDTH).dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onOpen(r.url) },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SiteAvatar(r.host, 20.dp)
                Spacer(Modifier.width(6.dp))
                Text(r.host, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                r.title,
                style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                color = colors.primary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (r.snippet.isNotBlank()) {
                Text(
                    r.snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = if (compact) 2 else 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            OpenButton(geometry, compact) { onOpen(r.url) }
        }
    }
}

@Composable
private fun AnswerCard(geometry: RoundGeometry, a: SerpCard.Answer, compact: Boolean, onOpen: (String) -> Unit) {
    Column(
        Modifier.width((geometry.diameterDp * CARD_WIDTH).dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            a.text,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            maxLines = if (compact) 5 else 6,
            overflow = TextOverflow.Ellipsis,
        )
        a.source?.let { s ->
            Text(s.host, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OpenButton(geometry, compact) { onOpen(s.url) }
        }
    }
}

@Composable
private fun MoreCard(geometry: RoundGeometry, next: String, onOpen: (String) -> Unit) {
    Column(
        Modifier.width((geometry.diameterDp * CARD_WIDTH).dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Button(
            onClick = { onOpen(next) },
            modifier = Modifier.fillMaxWidth(),
            icon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
            label = { Text(stringResource(R.string.more_results), maxLines = 1) },
        )
    }
}

@Composable
private fun OpenButton(geometry: RoundGeometry, compact: Boolean, onClick: () -> Unit) {
    val width = (geometry.diameterDp * OPEN_WIDTH).dp
    if (compact) {
        CompactButton(onClick = onClick, modifier = Modifier.width(width), label = { Text(stringResource(R.string.action_open), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) })
    } else {
        Button(onClick = onClick, modifier = Modifier.width(width), label = { Text(stringResource(R.string.action_open), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) })
    }
}

/** Dots along the left rim: one per card (up to [MAX_DOTS], windowed), the current one larger. */
@Composable
private fun PositionDots(geometry: RoundGeometry, count: Int, index: Int) {
    if (count <= 1) return
    val accent = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.outline
    Canvas(Modifier.fillMaxSize()) {
        val shown = minOf(count, MAX_DOTS)
        val first = (index - shown / 2).coerceIn(0, count - shown)
        val r = geometry.radiusPx - 9.dp.toPx()
        val c = Offset(geometry.centerXPx, geometry.centerYPx)
        for (k in 0 until shown) {
            val i = first + k
            // Screen angles run clockwise from 3 o'clock, so 180° is 9 o'clock and larger
            // angles sit higher on the left rim: the first dot is the top one.
            val a = Math.toRadians((180f + ((shown - 1) / 2f - k) * DOT_STEP_DEG).toDouble())
            val p = Offset(c.x + (r * cos(a)).toFloat(), c.y + (r * sin(a)).toFloat())
            drawCircle(if (i == index) accent else dim, radius = if (i == index) 3.5.dp.toPx() else 2.dp.toPx(), center = p)
        }
    }
}

@Composable
private fun SerpOverview(
    data: SerpData,
    cards: List<SerpCard>,
    current: Int,
    onPick: (Int) -> Unit,
    onOpen: (String) -> Unit,
    onEdit: () -> Unit,
    onVoice: () -> Unit,
) {
    val state = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = state, modifier = Modifier.background(Color.Black)) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { QueryPill(data.page.query, onEdit, onVoice) }
            cards.forEachIndexed { i, card ->
                item {
                    val spec = rememberTransformationSpec()
                    val mod = Modifier.fillMaxWidth().transformedHeight(this, spec)
                    when (card) {
                        is SerpCard.Result -> {
                            val onClick = { onPick(i) }
                            val label: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
                                Text(card.result.title, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                            }
                            val secondary: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
                                Text(card.result.host, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                            }
                            if (i == current) {
                                Button(onClick = onClick, modifier = mod, transformation = SurfaceTransformation(spec), label = label, secondaryLabel = secondary)
                            } else {
                                FilledTonalButton(onClick = onClick, modifier = mod, transformation = SurfaceTransformation(spec), label = label, secondaryLabel = secondary)
                            }
                        }
                        is SerpCard.Answer -> FilledTonalButton(
                            onClick = { onPick(i) },
                            modifier = mod,
                            transformation = SurfaceTransformation(spec),
                            label = { Text(stringResource(R.string.top_answer), maxLines = 1) },
                            secondaryLabel = { Text(card.text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                        is SerpCard.More -> Button(
                            onClick = { onOpen(card.url) },
                            modifier = mod,
                            transformation = SurfaceTransformation(spec),
                            colors = ButtonDefaults.outlinedButtonColors(),
                            label = { Text(stringResource(R.string.more_results), maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                        )
                    }
                }
            }
        }
    }
}

/** The query on top of the overview: tap to edit, mic to say a new search. */
@Composable
private fun QueryPill(query: String, onEdit: () -> Unit, onVoice: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth(PILL_WIDTH)
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(colors.surfaceContainer)
            .clickable(onClick = onEdit)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_search), contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(query, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        FilledIconButton(onClick = onVoice, modifier = Modifier.size(IconButtonDefaults.SmallButtonSize)) {
            Icon(painterResource(R.drawable.ic_mic), contentDescription = stringResource(R.string.action_voice), modifier = Modifier.size(20.dp))
        }
    }
}

private const val CARD_WIDTH = 0.74f

/** The query pill sits near the top of the overview, where the chord is narrower. */
private const val PILL_WIDTH = 0.8f
private const val HEADER_SWEEP = 80f
/** Top of a result card, as a fraction of the diameter: clear of the curved header. */
private const val HEADER_BAND = 0.18f
private const val OPEN_WIDTH = 0.5f
private const val COMPACT_BELOW_DP = 225f
private const val PINCH_OUT = 0.85f
private const val MAX_DOTS = 10
private const val DOT_STEP_DEG = 6f
