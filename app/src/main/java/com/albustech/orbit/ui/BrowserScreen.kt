package com.albustech.orbit.ui

import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.OpenOnPhoneDialog
import androidx.wear.compose.material3.OpenOnPhoneDialogDefaults
import androidx.wear.compose.material3.openOnPhoneDialogCurvedText
import com.albustech.orbit.R
import com.albustech.orbit.browser.BezelMode
import com.albustech.orbit.browser.BrowserController
import com.albustech.orbit.browser.ConnectionMonitor
import com.albustech.orbit.browser.ConnectionType
import com.albustech.orbit.browser.RenderMode
import com.albustech.orbit.browser.TabManager
import com.albustech.orbit.data.BrowserRepository
import com.albustech.orbit.data.Settings
import com.albustech.orbit.data.SettingsRepository
import com.albustech.orbit.data.Suggestions
import com.albustech.orbit.data.db.SiteMode
import com.albustech.orbit.input.BezelInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** Things only the Activity can do. */
interface AppActions {
    /** Opens [url] on the paired phone; [done] reports whether the phone accepted it. */
    fun openOnPhone(url: String, done: (Boolean) -> Unit)
    fun copyLink(url: String)
    fun toast(message: String)

    /** The user did something (bezel, touch): restarts the keep-screen-on timer. */
    fun onUserActivity()
}

class OrbitDeps(
    val controller: BrowserController,
    val bezel: BezelInput,
    val tabs: TabManager,
    val repo: BrowserRepository,
    val settingsRepo: SettingsRepository,
    val geometry: RoundGeometry,
    val connection: ConnectionMonitor,
    val actions: AppActions,
)

/** Screens that open over the page. Back closes the top one. */
sealed interface Overlay {
    data object Menu : Overlay
    data object Entry : Overlay
    data object Bookmarks : Overlay
    data object History : Overlay
    data object Tabs : Overlay
    data object Settings : Overlay
    data object Site : Overlay
    data object TextSize : Overlay
    data class Link(val url: String, val title: String?) : Overlay
}

/**
 * The browser: one WebView under round-aware chrome.
 *
 * Immersive by default: time and title show while a page loads and hide on the first scroll;
 * a plain tap near the centre opens the menu. The bezel does what [BezelMode] says unless an
 * overlay is open (then the overlay's list takes rotary focus). Long-press the centre to switch
 * bezel mode; long-press a link for its radial menu. In link mode a tap anywhere opens the
 * focused link.
 */
@Composable
fun BrowserScreen(deps: OrbitDeps, settings: Settings, ambient: Boolean) {
    val c = deps.controller
    val bezel = deps.bezel
    val scope = rememberCoroutineScope()
    val overlays = remember { mutableStateListOf<Overlay>() }
    var chromeVisible by remember { mutableStateOf(true) }
    var label by remember { mutableStateOf<String?>(null) }
    var labelTick by remember { mutableStateOf(0) }
    var phoneDialog by remember { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }

    val connection by deps.connection.type.collectAsState(initial = ConnectionType.WIFI)
    val bookmarks by deps.repo.bookmarks.collectAsState(initial = emptyList())
    val history by deps.repo.history.collectAsState(initial = emptyList())
    val lastRead by deps.repo.latestPosition.collectAsState(initial = null)
    val pageUrl = c.readerUrl ?: c.url
    val bookmarked by remember(pageUrl) { pageUrl?.let { deps.repo.isBookmarked(it) } ?: flowOf(false) }
        .collectAsState(initial = false)

    fun show(text: String) {
        label = text
        labelTick++
    }
    fun open(target: String) {
        overlays.clear()
        if (!c.open(target)) return
    }
    fun openOnPhone(url: String) {
        deps.actions.openOnPhone(url) { ok -> if (ok) phoneDialog = true }
    }
    fun toggleBookmark(url: String, title: String?, isMarked: Boolean) {
        scope.launch { deps.repo.toggleBookmark(url, title, isMarked) }
        deps.actions.toast(deps.controller.webView.context.getString(if (isMarked) R.string.removed_bookmark else R.string.added_bookmark))
    }

    val modeNames = mapOf(
        BezelMode.SCROLL to stringResource(if (c.renderMode == RenderMode.READER) R.string.bezel_pages else R.string.bezel_scroll),
        BezelMode.LINKS to stringResource(R.string.bezel_links),
        BezelMode.ZOOM to stringResource(if (c.renderMode == RenderMode.READER) R.string.bezel_text else R.string.bezel_zoom),
    )
    val currentModeNames by androidx.compose.runtime.rememberUpdatedState(modeNames)

    DisposableEffect(bezel, c) {
        bezel.onDetents = { n, t ->
            c.onBezel(n, bezel.stepPx, t)
            if (c.bezelMode == BezelMode.ZOOM && c.readerUrl != null) show("${c.displayedFontSize} px")
        }
        bezel.onInteraction = {
            chromeVisible = false
            deps.actions.onUserActivity()
        }
        bezel.onCenterTap = {
            chromeVisible = true
            overlays.add(Overlay.Menu)
        }
        bezel.onCenterLongPress = {
            val mode = c.cycleBezelMode()
            bezel.haptics.confirm()
            show(currentModeNames[mode].orEmpty())
        }
        bezel.onLinkLongPress = { url, title -> overlays.add(Overlay.Link(url, title)) }
        bezel.onDoubleTap = c::onDoubleTap
        onDispose {
            bezel.onDoubleTap = {}
            bezel.onDetents = { _, _ -> }
            bezel.onInteraction = {}
            bezel.onCenterTap = {}
            bezel.onCenterLongPress = {}
            bezel.onLinkLongPress = { _, _ -> }
        }
    }
    SideEffect { bezel.enabled = c.hasPage && overlays.isEmpty() && !ambient }

    // Labels fade after a moment.
    LaunchedEffect(labelTick) {
        if (label != null) {
            delay(1400)
            label = null
        }
    }
    // "4 / 12" after each page turn.
    val reader = c.reader
    val pageLabel = reader?.let {
        if (it.done) stringResource(R.string.page_of, it.page + 1, it.total)
        else stringResource(R.string.page_of_more, it.page + 1, it.total)
    }
    LaunchedEffect(reader?.page) {
        if (reader != null && reader.total > 0) {
            label = pageLabel
            labelTick++
        }
    }
    LaunchedEffect(c.isLoading) { if (c.isLoading) chromeVisible = true }
    LaunchedEffect(c.focusedLink) {
        val f = c.focusedLink
        if (f != null && c.bezelMode == BezelMode.LINKS) {
            label = f.label.ifBlank { Suggestions.displayUrl(f.href) }.take(40)
            labelTick++
        }
    }

    // Hold rotary focus on the root whenever no overlay needs it.
    LaunchedEffect(overlays.size, c.hasPage) { if (overlays.isEmpty() && c.hasPage) rootFocus.requestFocus() }
    LifecycleResumeEffect(Unit) {
        if (overlays.isEmpty() && c.hasPage) rootFocus.requestFocus()
        onPauseOrDispose { c.stopScrolling() }
    }

    // Later handlers win: an open overlay closes before history goes back. With no history,
    // Back is left to the system and leaves the app.
    BackHandler(enabled = c.hasPage && (c.canGoBack || c.bezelMode == BezelMode.LINKS)) {
        if (c.bezelMode == BezelMode.LINKS) show(currentModeNames[BezelMode.SCROLL].orEmpty())
        c.back()
    }
    BackHandler(enabled = overlays.isNotEmpty()) { overlays.removeAt(overlays.lastIndex) }

    val entry = rememberUrlEntry(choices = bookmarks.take(5).map { Suggestions.displayUrl(it.url) }) { open(it) }

    AppScaffold(
        timeText = {
            AnimatedVisibility(
                visible = !ambient && (chromeVisible || overlays.isNotEmpty() || !c.hasPage),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                TitleTimeText(if (c.hasPage && overlays.isEmpty()) c.title ?: Suggestions.siteKey(pageUrl) else null)
            }
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
                factory = { c.webView.also { (it.parent as? ViewGroup)?.removeView(it) } },
                update = { it.visibility = if (c.hasPage) View.VISIBLE else View.INVISIBLE },
                modifier = Modifier.fillMaxSize(),
            )

            // Link mode: a tap anywhere opens the focused link; long-press for its menu.
            if (c.hasPage && c.bezelMode == BezelMode.LINKS && overlays.isEmpty()) {
                Box(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { c.activateFocusedLink() },
                            onLongPress = { p ->
                                if (deps.geometry.isNearCenter(p.x, p.y)) {
                                    val mode = c.cycleBezelMode()
                                    bezel.haptics.confirm()
                                    show(currentModeNames[mode].orEmpty())
                                } else {
                                    c.focusedLink?.takeIf { it.href.isNotEmpty() }?.let {
                                        overlays.add(Overlay.Link(it.href, it.label))
                                    }
                                }
                            },
                        )
                    },
                )
            }

            if (!c.hasPage) {
                HomeScreen(
                    lastRead = lastRead,
                    bookmarks = bookmarks,
                    onSpeak = entry::speak,
                    onType = { entry.type() },
                    onOpen = { url -> overlays.clear(); c.loadUrl(url) },
                    onMenu = { overlays.add(Overlay.Menu) },
                )
            }

            c.error?.let { err ->
                val (title, detail) = err.message()
                ErrorScreen(
                    geometry = deps.geometry,
                    title = title,
                    detail = detail,
                    onRetry = c::reload,
                )
            }

            if (c.hasPage) {
                val reading = c.reader
                EdgeOverlay(
                    geometry = deps.geometry,
                    loadingProgress = if (c.isLoading) c.progress / 100f else null,
                    indicator = when {
                        reading != null -> EdgeIndicator.Pages(reading.page, reading.total)
                        else -> EdgeIndicator.Scroll(c.scrollFraction)
                    },
                    indicatorVisible = label != null || chromeVisible,
                )
            }

            BottomCurvedLabel(if (overlays.isEmpty() && !ambient) label else null)

            overlays.lastOrNull()?.let { top ->
                OverlayContent(top, deps, settings, connection, bookmarked, bookmarks, history, overlays, entry,
                    ::open, ::openOnPhone, ::toggleBookmark)
            }

            if (ambient) {
                val r = c.reader
                AmbientScreen(
                    geometry = deps.geometry,
                    title = if (c.hasPage) c.title else null,
                    position = r?.let { stringResource(R.string.page_of, it.page + 1, it.total) },
                )
            }
        }
    }

    val dialogText = OpenOnPhoneDialogDefaults.text
    val dialogStyle = OpenOnPhoneDialogDefaults.curvedTextStyle
    OpenOnPhoneDialog(
        visible = phoneDialog,
        onDismissRequest = { phoneDialog = false },
        curvedText = { openOnPhoneDialogCurvedText(text = dialogText, style = dialogStyle) },
    )
}

@Composable
private fun OverlayContent(
    top: Overlay,
    deps: OrbitDeps,
    settings: Settings,
    connection: ConnectionType,
    bookmarked: Boolean,
    bookmarks: List<com.albustech.orbit.data.db.Bookmark>,
    history: List<com.albustech.orbit.data.db.HistoryEntry>,
    overlays: MutableList<Overlay>,
    entry: UrlEntry,
    open: (String) -> Unit,
    openOnPhone: (String) -> Unit,
    toggleBookmark: (String, String?, Boolean) -> Unit,
) {
    val c = deps.controller
    val scope = rememberCoroutineScope()
    val pageUrl = c.readerUrl ?: c.url
    fun push(o: Overlay) = overlays.add(o)
    fun close() = overlays.clear()
    fun cycleView() {
        val next = SiteMode.entries[(c.site.mode.ordinal + 1) % SiteMode.entries.size]
        c.setSiteMode(next)
    }

    when (top) {
        Overlay.Menu -> MainMenu(
            MenuState(
                title = if (c.hasPage) c.title else null,
                host = Suggestions.siteKey(pageUrl),
                hasPage = c.hasPage,
                isLoading = c.isLoading,
                canGoBack = c.canGoBack,
                canGoForward = c.canGoForward,
                siteMode = c.site.mode,
                renderMode = c.renderMode,
                bezelMode = c.bezelMode,
                bookmarked = bookmarked,
                connection = connection,
                blocked = c.blockedCount,
                tabCount = deps.tabs.tabs.size,
                maxTabs = TabManager.MAX_TABS,
            ),
            MenuActions(
                goTo = { push(Overlay.Entry) },
                speak = entry::speak,
                back = { close(); c.back() },
                forward = { close(); c.forward() },
                reloadOrStop = { close(); c.reloadOrStop() },
                cycleView = ::cycleView,
                cycleBezel = { c.cycleBezelMode(); close() },
                toggleBookmark = { pageUrl?.let { toggleBookmark(it, c.title, bookmarked) } },
                bookmarks = { push(Overlay.Bookmarks) },
                history = { push(Overlay.History) },
                tabs = { push(Overlay.Tabs) },
                textSize = { push(Overlay.TextSize) },
                openOnPhone = { pageUrl?.let(openOnPhone) },
                site = { push(Overlay.Site) },
                settings = { push(Overlay.Settings) },
                home = { close(); c.showHome() },
            ),
        )
        Overlay.Entry -> UrlEntryScreen(
            searchTemplate = settings.searchTemplate,
            recentSites = bookmarks.take(5).map { Suggestions.displayUrl(it.url) },
            suggest = deps.repo::suggestions,
            onOpen = open,
        )
        Overlay.Bookmarks -> BookmarksScreen(
            bookmarks = bookmarks,
            onOpen = { close(); c.loadUrl(it) },
            onRemove = { url -> scope.launch { deps.repo.removeBookmark(url) } },
        )
        Overlay.History -> HistoryScreen(
            history = history,
            onOpen = { close(); c.loadUrl(it) },
            onClear = { scope.launch { deps.repo.clearHistory() } },
        )
        Overlay.Tabs -> TabsScreen(deps.tabs, onDone = ::close)
        Overlay.Settings -> SettingsScreen(
            settings = settings,
            onSearch = { t -> scope.launch { deps.settingsRepo.setSearchTemplate(t) } },
            onBlock = { v -> scope.launch { deps.settingsRepo.setBlockTrackers(v) } },
            onKeepOn = { v -> scope.launch { deps.settingsRepo.setKeepScreenOn(v) } },
            onSerif = { v -> scope.launch { deps.settingsRepo.setReaderStyle(settings.reader.copy(serif = v)) } },
            onClearHistory = { scope.launch { deps.repo.clearHistory() } },
        )
        Overlay.Site -> SiteSettingsScreen(
            host = Suggestions.siteKey(pageUrl) ?: "",
            site = c.site,
            onCycleView = ::cycleView,
            onUpdate = c::updateSite,
        )
        Overlay.TextSize -> TextSizeScreen(
            geometry = deps.geometry,
            reader = c.readerUrl != null,
            style = settings.reader,
            textZoom = settings.textZoom,
            onStyle = { s -> scope.launch { deps.settingsRepo.setReaderStyle(s) } },
            onZoom = { z -> scope.launch { deps.settingsRepo.setTextZoom(z) } },
        )
        is Overlay.Link -> {
            val marked by remember(top.url) { deps.repo.isBookmarked(top.url) }.collectAsState(initial = false)
            LinkMenu(
                geometry = deps.geometry,
                url = top.url,
                title = top.title,
                bookmarked = marked,
                onOpen = { close(); c.loadUrl(top.url) },
                onOpenOnPhone = { close(); openOnPhone(top.url) },
                onBookmark = { toggleBookmark(top.url, top.title, marked); close() },
                onCopy = { deps.actions.copyLink(top.url); close() },
                onDismiss = ::close,
            )
        }
    }
}
