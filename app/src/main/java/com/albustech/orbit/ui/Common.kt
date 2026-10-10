package com.albustech.orbit.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight

/**
 * A full-screen list over the page. Rows morph to the circle's chord as they near the top
 * and bottom edges (TransformingLazyColumn + SurfaceTransformation), the list takes rotary
 * focus itself so the bezel scrolls it, and rows are 52dp tall, inside the 48dp minimum.
 */
@Composable
fun OrbitList(
    modifier: Modifier = Modifier,
    content: TransformingLazyColumnScope.() -> Unit,
) {
    val state = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = state, modifier = modifier.fillMaxSize().background(Color.Black)) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding, modifier = Modifier.fillMaxSize(), content = content)
    }
}

/**
 * Rows soften with distance from the screen centre: they dim toward the edges and the
 * outermost (about three rows from focus) also blur, so the row in the middle reads as focused.
 * Headers stay sharp.
 */
fun Modifier.focusFade(item: TransformingLazyColumnItemScope): Modifier = graphicsLayer {
    val p = with(item) { scrollProgress }
    if (p.isUnspecified) return@graphicsLayer
    val off = kotlin.math.abs((p.topOffsetFraction + p.bottomOffsetFraction) - 1f).coerceIn(0f, 1f)
    alpha = 1f - 0.5f * off
    val blur = ((off - 0.72f) / 0.28f).coerceIn(0f, 1f) * 2f * density
    renderEffect = if (blur > 0.5f) BlurEffect(blur, blur, TileMode.Decal) else null
}

fun TransformingLazyColumnScope.header(text: @Composable () -> String) {
    item {
        val spec = rememberTransformationSpec()
        ListHeader(modifier = Modifier.transformedHeight(this, spec), transformation = SurfaceTransformation(spec)) {
            Text(text(), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

fun TransformingLazyColumnScope.note(text: @Composable () -> String) {
    item {
        Text(
            text(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun TransformingLazyColumnItemScope.MenuButton(
    @DrawableRes icon: Int?,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    primary: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val spec = rememberTransformationSpec()
    val iconContent: (@Composable BoxScope.() -> Unit)? =
        icon?.let { { Icon(painterResource(it), contentDescription = null) } }
    val secondaryContent: (@Composable RowScope.() -> Unit)? =
        secondary?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
    val mod = modifier.focusFade(this).fillMaxWidth().transformedHeight(this, spec)
    if (primary) {
        Button(
            onClick = onClick,
            onLongClick = onLongClick,
            enabled = enabled,
            modifier = mod,
            transformation = SurfaceTransformation(spec),
            icon = iconContent,
            secondaryLabel = secondaryContent,
            label = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        )
    } else {
        FilledTonalButton(
            onClick = onClick,
            onLongClick = onLongClick,
            enabled = enabled,
            modifier = mod,
            transformation = SurfaceTransformation(spec),
            icon = iconContent,
            secondaryLabel = secondaryContent,
            label = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        )
    }
}

@Composable
fun TransformingLazyColumnItemScope.MenuSwitch(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    secondary: String? = null,
) {
    val spec = rememberTransformationSpec()
    SwitchButton(
        checked = checked,
        onCheckedChange = onChange,
        modifier = Modifier.focusFade(this).fillMaxWidth().transformedHeight(this, spec),
        transformation = SurfaceTransformation(spec),
        secondaryLabel = secondary?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        label = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
    )
}
