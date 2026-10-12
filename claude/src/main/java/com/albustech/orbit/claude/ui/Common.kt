package com.albustech.orbit.claude.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight

/**
 * A full-screen list, as Orbit's (app/…/ui/Common.kt): rows morph to the circle near the
 * edges and the list takes rotary focus itself, so the bezel scrolls it.
 */
@Composable
fun ClaudeList(content: TransformingLazyColumnScope.() -> Unit) {
    val state = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = state, modifier = Modifier.fillMaxSize().background(Color.Black)) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding, modifier = Modifier.fillMaxSize(), content = content)
    }
}

@Composable
fun TransformingLazyColumnItemScope.MenuButton(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
) {
    val spec = rememberTransformationSpec()
    val iconContent: @Composable BoxScope.() -> Unit = { Icon(painterResource(icon), contentDescription = null) }
    val mod = Modifier.fillMaxWidth().transformedHeight(this, spec)
    val labelContent: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit =
        { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    if (primary) {
        Button(onClick = onClick, modifier = mod, transformation = SurfaceTransformation(spec), icon = iconContent, label = labelContent)
    } else {
        FilledTonalButton(onClick = onClick, modifier = mod, transformation = SurfaceTransformation(spec), icon = iconContent, label = labelContent)
    }
}
