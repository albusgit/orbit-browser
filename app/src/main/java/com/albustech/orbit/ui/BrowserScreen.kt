package com.albustech.orbit.ui

import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.TimeText
import com.albustech.orbit.R
import com.albustech.orbit.browser.BrowserController
import com.albustech.orbit.browser.RenderMode
import com.albustech.orbit.input.BezelInput

/**
 * The browser: one WebView under round-aware chrome.
 *
 * Immersive by default. Time text shows while a page loads and hides on the first scroll;
 * a plain tap near the centre opens the menu. The bezel scrolls the page unless the menu
 * is open, in which case the menu's own list takes the rotary focus.
 */
@Composable
fun BrowserScreen(
    controller: BrowserController,
    bezel: BezelInput,
    geometry: RoundGeometry,
    lastUrl: String?,
) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }
    val rootFocus = remember { FocusRequester() }

    val entry = rememberUrlEntry { text ->
        menuOpen = false
        controller.open(text)
    }

    DisposableEffect(bezel) {
        bezel.onInteraction = { chromeVisible = false }
        bezel.onCenterTap = {
            chromeVisible = true
            menuOpen = true
        }
        onDispose {
            bezel.onInteraction = {}
            bezel.onCenterTap = {}
        }
    }
    SideEffect { bezel.enabled = controller.hasPage && !menuOpen }

    LaunchedEffect(controller.isLoading) {
        if (controller.isLoading) chromeVisible = true
    }

    // Hold rotary focus on the root whenever nothing else needs it: after the menu closes,
    // and when we come back from the voice or keyboard activity.
    LaunchedEffect(menuOpen) {
        if (!menuOpen) rootFocus.requestFocus()
    }
    LifecycleResumeEffect(Unit) {
        if (!menuOpen) rootFocus.requestFocus()
        onPauseOrDispose { bezel.stop() }
    }

    // Later handlers win: the open menu closes before history goes back.
    // With no history, Back is left to the system and leaves the app.
    BackHandler(enabled = controller.hasPage && controller.canGoBack) { controller.back() }
    BackHandler(enabled = menuOpen) { menuOpen = false }

    AppScaffold(
        timeText = {
            AnimatedVisibility(
                visible = chromeVisible || menuOpen || !controller.hasPage,
                enter = fadeIn(),
                exit = fadeOut(),
            ) { TimeText() }
        },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onRotaryScrollEvent { bezel.onRotary(it.verticalScrollPixels, it.uptimeMillis) }
                .focusRequester(rootFocus)
                .focusable(),
        ) {
            AndroidView(
                factory = { controller.webView.also { (it.parent as? ViewGroup)?.removeView(it) } },
                update = { it.visibility = if (controller.hasPage) View.VISIBLE else View.INVISIBLE },
                modifier = Modifier.fillMaxSize(),
            )

            if (!controller.hasPage) {
                HomeScreen(
                    geometry = geometry,
                    lastHost = BrowserController.hostOf(lastUrl),
                    onSpeak = entry::speak,
                    onType = entry::type,
                    onContinue = { lastUrl?.let(controller::loadUrl) },
                )
            }

            controller.error?.let { err ->
                ErrorScreen(
                    geometry = geometry,
                    title = stringResource(R.string.error_title),
                    detail = err.description,
                    onRetry = controller::reload,
                )
            }

            EdgeOverlay(
                geometry = geometry,
                loadingProgress = if (controller.isLoading) controller.progress / 100f else null,
            )

            if (menuOpen) {
                QuickMenu(
                    title = controller.title ?: BrowserController.hostOf(controller.url),
                    isLoading = controller.isLoading,
                    canGoBack = controller.canGoBack,
                    canGoForward = controller.canGoForward,
                    mode = controller.mode,
                    onSpeak = entry::speak,
                    onType = entry::type,
                    onBack = { menuOpen = false; controller.back() },
                    onForward = { menuOpen = false; controller.forward() },
                    onReloadOrStop = { menuOpen = false; controller.reloadOrStop() },
                    onToggleMode = {
                        menuOpen = false
                        controller.switchMode(
                            if (controller.mode == RenderMode.SCROLL) RenderMode.DESKTOP else RenderMode.SCROLL,
                        )
                    },
                    onHome = { menuOpen = false; controller.showHome() },
                    onClose = { menuOpen = false },
                )
            }
        }
    }
}
