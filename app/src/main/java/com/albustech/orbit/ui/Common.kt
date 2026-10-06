package com.albustech.orbit.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text

/**
 * A full-screen list over the page. The ScalingLazyColumn takes rotary focus itself, so the
 * bezel scrolls the list while it's open. Items are full-width buttons (52dp tall), well inside
 * the 48dp minimum, and the column's padding keeps them off the bezel edge.
 */
@Composable
fun OrbitList(
    modifier: Modifier = Modifier,
    initialCenterItem: Int = 1,
    content: ScalingLazyListScope.() -> Unit,
) {
    val state = rememberScalingLazyListState(initialCenterItemIndex = initialCenterItem)
    ScreenScaffold(
        scrollState = state,
        modifier = modifier.fillMaxSize().background(Color.Black),
    ) { padding ->
        ScalingLazyColumn(
            state = state,
            contentPadding = padding,
            modifier = Modifier.fillMaxSize(),
            content = content,
        )
    }
}

fun ScalingLazyListScope.header(text: @Composable () -> String) {
    item {
        ListHeader {
            Text(text(), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

fun ScalingLazyListScope.note(text: @Composable () -> String) {
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
fun MenuButton(
    @DrawableRes icon: Int?,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    primary: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val iconContent: (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? =
        icon?.let { { Icon(painterResource(it), contentDescription = null) } }
    val secondaryContent: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? =
        secondary?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
    if (primary) {
        Button(
            onClick = onClick,
            onLongClick = onLongClick,
            enabled = enabled,
            modifier = modifier.fillMaxWidth(),
            icon = iconContent,
            secondaryLabel = secondaryContent,
            label = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        )
    } else {
        FilledTonalButton(
            onClick = onClick,
            onLongClick = onLongClick,
            enabled = enabled,
            modifier = modifier.fillMaxWidth(),
            icon = iconContent,
            secondaryLabel = secondaryContent,
            label = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        )
    }
}

@Composable
fun MenuSwitch(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    secondary: String? = null,
) {
    SwitchButton(
        checked = checked,
        onCheckedChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        secondaryLabel = secondary?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        label = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
    )
}
