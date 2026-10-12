package com.albustech.orbit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import com.albustech.orbit.browser.PhonePage
import com.albustech.orbit.link.Box
import kotlin.math.roundToInt

/**
 * A page the phone rendered: its strips drawn at the page's scroll position and zoom, scrolled
 * by touch (with fling) and the bezel, entirely on the watch. Taps and holds report screen px.
 * The focused link in link mode gets a ring.
 */
@Composable
fun TilePage(
    page: PhonePage,
    focused: Box?,
    onTap: (x: Float, y: Float) -> Unit,
    onHold: (x: Float, y: Float) -> Unit,
    onScroll: (dx: Float, dy: Float) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val tap by rememberUpdatedState(onTap)
    val hold by rememberUpdatedState(onHold)
    val scroll by rememberUpdatedState(onScroll)
    val accent = MaterialTheme.colorScheme.primary

    // Decode what's near the screen whenever it moves or new strips arrive.
    LaunchedEffect(page) {
        snapshotFlow { Triple(page.offsetY, page.zoom, page.version) }.collect { page.decodeAround(scope) }
    }

    Canvas(
        Modifier
            .fillMaxSize()
            .scrollable(rememberScrollableState { d -> scroll(0f, -d); d }, Orientation.Vertical)
            .scrollable(rememberScrollableState { d -> scroll(-d, 0f); d }, Orientation.Horizontal, enabled = page.zoom > 1f)
            .pointerInput(page) {
                detectTapGestures(onTap = { tap(it.x, it.y) }, onLongPress = { hold(it.x, it.y) })
            },
    ) {
        page.version // redraw as strips arrive and decode
        val s = page.scale
        for ((tile, image) in page.visible()) {
            if (image == null) continue
            drawImage(
                image,
                dstOffset = IntOffset(((0 - page.offsetX) * s).roundToInt(), ((tile.y - page.offsetY) * s).roundToInt()),
                dstSize = IntSize((tile.width * s).roundToInt(), (tile.height * s).roundToInt() + 1),
                filterQuality = if (s == 1f) FilterQuality.None else FilterQuality.Low,
            )
        }
        focused?.let { b ->
            val pad = 3.dp.toPx()
            drawRoundRect(
                accent,
                topLeft = Offset((b.x - page.offsetX) * s - pad, (b.y - page.offsetY) * s - pad),
                size = Size(b.w * s + 2 * pad, b.h * s + 2 * pad),
                cornerRadius = CornerRadius(6.dp.toPx()),
                style = Stroke(2.5.dp.toPx()),
            )
            drawRoundRect(
                Color.Black.copy(alpha = 0.35f),
                topLeft = Offset((b.x - page.offsetX) * s - pad - 1.5f, (b.y - page.offsetY) * s - pad - 1.5f),
                size = Size(b.w * s + 2 * pad + 3f, b.h * s + 2 * pad + 3f),
                cornerRadius = CornerRadius(7.dp.toPx()),
                style = Stroke(1f),
            )
        }
    }
}
