package com.albustech.orbit.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.albustech.orbit.R
import com.albustech.orbit.browser.RenderMode

/**
 * Phase 1 menu, opened by a tap in the centre of the page. The bezel scrolls it.
 * Phase 3 replaces it with the curved/radial menu.
 */
@Composable
fun QuickMenu(
    title: String?,
    isLoading: Boolean,
    canGoBack: Boolean,
    canGoForward: Boolean,
    mode: RenderMode,
    onSpeak: () -> Unit,
    onType: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReloadOrStop: () -> Unit,
    onToggleMode: () -> Unit,
    onHome: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberScalingLazyListState(initialCenterItemIndex = 1)

    ScreenScaffold(
        scrollState = state,
        modifier = modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.94f)),
    ) { padding ->
        ScalingLazyColumn(
            state = state,
            contentPadding = padding,
            modifier = Modifier.fillMaxSize(), // takes rotary focus itself
        ) {
            item {
                ListHeader {
                    Text(title ?: stringResource(R.string.app_name), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            item { MenuButton(R.drawable.ic_mic, stringResource(R.string.action_voice), onSpeak, primary = true) }
            item { MenuButton(R.drawable.ic_keyboard, stringResource(R.string.action_keyboard), onType) }
            if (canGoBack) item { MenuButton(R.drawable.ic_back, stringResource(R.string.action_back), onBack) }
            if (canGoForward) item { MenuButton(R.drawable.ic_forward, stringResource(R.string.action_forward), onForward) }
            item {
                MenuButton(
                    if (isLoading) R.drawable.ic_close else R.drawable.ic_reload,
                    stringResource(if (isLoading) R.string.action_stop else R.string.action_reload),
                    onReloadOrStop,
                )
            }
            item {
                val other = if (mode == RenderMode.SCROLL) R.string.mode_desktop else R.string.mode_scroll
                MenuButton(R.drawable.ic_mode, stringResource(other), onToggleMode)
            }
            item { MenuButton(R.drawable.ic_home, stringResource(R.string.action_home), onHome) }
            item { MenuButton(R.drawable.ic_close, stringResource(R.string.action_close), onClose) }
        }
    }
}

@Composable
private fun MenuButton(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
) {
    val iconContent: @Composable () -> Unit = { Icon(painterResource(icon), contentDescription = null) }
    if (primary) {
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            icon = { iconContent() },
            label = { Text(label) },
        )
    } else {
        FilledTonalButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            icon = { iconContent() },
            label = { Text(label) },
        )
    }
}
