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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalResources
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
import com.albustech.orbit.browser.TabManager
import com.albustech.orbit.data.BrowserRepository
import com.albustech.orbit.data.Settings
import com.albustech.orbit.data.SettingsRepository
import com.albustech.orbit.data.Suggestions
import com.albustech.orbit.data.db.Bookmark
import com.albustech.orbit.data.db.HistoryEntry
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

/** Screens that open over the page. Back (or a swipe on the rings) closes the top one. */
sealed interface Overlay {
    data object Ring : Overlay
    data object More : Overlay
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
 * The browser, round-native (design section 3):
 * - no page: the orbit launcher;
 * - a page: immersive, with the bezel-mode arc and "host · time" while the chrome shows;
 *   a tap shows the chrome and its bezel-mode arc, a hold opens the ring menu, a hold on a
 *   link opens its wedges;
 * - cursor mode: touch moves a cursor like a trackpad, a tap clicks under it;
 * - link mode: a counter on top and one explicit "Open link" button;
 * - a search-results page: native result cards instead of the page.
 */
@Composable
fun BrowserScreen(deps: OrbitDeps, settings: Settings, ambient: Boolean) {
    val c = deps.controller
    val bezel = deps.bezel
    val geometry = deps.geometry
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val overlays = remember { mutableStateListOf<Overlay>() }
    var chromeVisible by remember { mutableStateOf(true) }
    var label by remember { mutableStateOf<String?>(null) }
    var labelTick by remember { mutableIntStateOf(0) }
    var phoneDialog by remember { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }

    val connection by deps.connection.type.collectAsState(initial = ConnectionType.WIFI)
    val bookmarks by deps.repo.bookmarks.collectAsState(initial = emptyList())
    val history by deps.repo.history.collectAsState(initial = emptyList())
    val lastRead by deps.repo.latestPosition.collectAsState(initial = null)
    val pageUrl = c.readerUrl ?: c.url
    val host = Suggestions.siteKey(pageUrl)
    val bookmarked by remember(pageUrl) { pageUrl?.let { deps.repo.isBookmarked(it) } ?: flowOf(false) }
        .collectAsState(initial = false)
    val serp = c.serp
    val onPage = c.hasPage && serp == null

    fun show(text: String?) {
        label = text
        labelTick++
    }
    fun closeAll() = overlays.clear()
    fun open(target: String) {
        closeAll()
        c.open(target)
    }
    fun openOnPhone(url: String) = deps.actions.openOnPhone(url) { ok -> if (ok) phoneDialog = true }
    fun toggleBookmark(url: String, title: String?, isMarked: Boolean) {
        scope.launch { deps.repo.toggleBookmark(url, title, isMarked) }
        deps.actions.toast(resources.getString(if (isMarked) R.string.removed_bookmark else R.string.added_bookmark))
    }
    fun openRing() {
        bezel.haptics.confirm()
        c.clearSelection()
        overlays.add(Overlay.Ring)
    }
    DisposableEffect(bezel, c) {
        bezel.onDetents = { n, t ->
            c.onBezel(n, bezel.stepPx, t)
            if (c.bezelMode == BezelMode.ZOOM && c.readerUrl != null) show("${c.displayedFontSize} px")
        }
        bezel.onInteraction = {
            chromeVisible = false
            deps.actions.onUserActivity()
        }
        bezel.onPageTap = { chromeVisible = true }
        bezel.onHold = ::openRing
        bezel.onLinkLongPress = { url, title -> overlays.add(Overlay.Link(url, title)) }
        onDispose {
            bezel.onDetents = { _, _ -> }
            bezel.onInteraction = {}
            bezel.onPageTap = {}
            bezel.onHold = {}
            bezel.onLinkLongPress = { _, _ -> }
        }
    }
    SideEffect { bezel.enabled = onPage && overlays.isEmpty() && !ambient }

    LaunchedEffect(labelTick) {
        if (label != null) {
            delay(1400)
            label = null
        }
    }
    val reader = c.reader
    val pageLabel = reader?.let {
        if (it.done) stringResource(R.string.page_of, it.page + 1, it.total)
        else stringResource(R.string.page_of_more, it.page + 1, it.total)
    }
    LaunchedEffect(reader?.page) { if (reader != null && reader.total > 0) show(pageLabel) }
    LaunchedEffect(c.isLoading) { if (c.isLoading) chromeVisible = true }
    LaunchedEffect(overlays.size) { if (overlays.isEmpty()) chromeVisible = true }

    // Hold rotary focus on the root whenever the page itself is in front.
    LaunchedEffect(overlays.size, onPage) { if (overlays.isEmpty() && onPage) rootFocus.requestFocus() }
    LifecycleResumeEffect(Unit) {
        if (overlays.isEmpty() && onPage) rootFocus.requestFocus()
        onPauseOrDispose { c.stopScrolling() }
    }

    // Later handlers win: an open overlay closes before history goes back. With no history,
    // Back is left to the system and leaves the app.
    BackHandler(enabled = c.hasPage && (c.canGoBack || c.bezelMode == BezelMode.LINKS)) { c.back() }
    BackHandler(enabled = overlays.isNotEmpty()) { overlays.removeAt(overlays.lastIndex) }

    val entry = rememberUrlEntry(choices = bookmarks.take(5).map { Suggestions.displayUrl(it.url) }) { open(it) }
    val linkMode = onPage && c.bezelMode == BezelMode.LINKS && overlays.isEmpty()
    val top = overlays.lastOrNull()
    val listOverlay = top != null && top !is Overlay.Ring && top !is Overlay.More && top !is Overlay.Link && top !is Overlay.TextSize

    AppScaffold(
        timeText = {
            AnimatedVisibility(
                visible = !ambient && !linkMode && (listOverlay || (overlays.isEmpty() && (serp == null || !c.hasPage) && (chromeVisible || !c.hasPage))),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                TitleTimeText(if (onPage && overlays.isEmpty()) host else null)
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
                factory = { c.view.also { (it.parent as? ViewGroup)?.removeView(it) } },
                update = { it.visibility = if (onPage) View.VISIBLE else View.INVISIBLE },
                modifier = Modifier.fillMaxSize(),
            )

            if (!c.hasPage) {
                Launcher(
                    geometry = geometry,
                    slots = launcherSlots(lastRead, bookmarks, onType = { entry.type() }, onOpen = { url -> closeAll(); c.loadUrl(url) },
                        onMore = { overlays.add(Overlay.Ring) }, onTabs = { overlays.add(Overlay.Tabs) }, onHistory = { overlays.add(Overlay.History) }),
                    micLabel = stringResource(R.string.action_voice),
                    hint = if (settings.bezelHintSeen) null else stringResource(R.string.bezel_hint),
                    onSpeak = entry::speak,
                    onBezelUsed = deps.settingsRepo::setBezelHintSeen,
                )
            }

            if (serp != null && c.hasPage) {
                SerpScreen(
                    geometry = geometry,
                    state = serp,
                    startIndex = c.serpIndexFor(serp.url),
                    onIndex = { i -> c.rememberSerpIndex(serp.url, i) },
                    onOpen = { url -> c.loadUrl(url) },
                    onEdit = { entry.type() },
                    onVoice = entry::speak,
                )
            }

            if (linkMode) {
                TapToShowChrome(onTap = { chromeVisible = true }, onHold = ::openRing)
                val f = c.focusedLink
                LinkModeControls(
                    geometry = geometry,
                    counter = f?.takeIf { it.count > 0 }?.let { stringResource(R.string.link_position, it.index + 1, it.count) },
                    hasLink = f != null,
                    onOpen = c::activateFocusedLink,
                    onOptions = { f?.takeIf { it.href.isNotEmpty() }?.let { overlays.add(Overlay.Link(it.href, it.label)) } },
                )
            }

            if (onPage && overlays.isEmpty() && c.bezelMode == BezelMode.CURSOR) {
                CursorLayer(
                    geometry = geometry,
                    onClick = { x, y ->
                        bezel.haptics.tick()
                        c.clickAt(x, y)
                    },
                    onHold = ::openRing,
                    // Moving the cursor brings back the chrome, so the mode arc is in reach.
                    onMove = {
                        chromeVisible = true
                        deps.actions.onUserActivity()
                    },
                    onScroll = c::scrollByPx,
                    forward = { ev -> c.view.onTouchEvent(ev) },
                )
            }

            c.error?.let { err ->
                val (title, detail) = err.message()
                ErrorScreen(geometry = geometry, title = title, detail = detail, onRetry = c::reload)
            }

            if (c.hasPage) {
                val reading = c.reader
                EdgeOverlay(
                    geometry = geometry,
                    loadingProgress = { if (c.isLoading) c.progress / 100f else null },
                    indicator = {
                        when {
                            c.serp != null -> EdgeIndicator.None
                            reading != null -> EdgeIndicator.Pages(reading.page, reading.total)
                            else -> EdgeIndicator.Scroll(c.scrollFraction)
                        }
                    },
                    indicatorVisible = label != null || chromeVisible,
                )
            }

            if (onPage && overlays.isEmpty()) {
                val modes = BezelMode.entries
                BezelModeArc(
                    geometry = geometry,
                    labels = modes.map { bezelLabel(it, c.renderMode) },
                    selected = modes.indexOf(c.bezelMode),
                    visible = chromeVisible,
                    onSelect = { i ->
                        c.setBezel(modes[i])
                        bezel.haptics.confirm()
                    },
                )
                BottomCurvedLabel(if (!ambient && !chromeVisible) label else null)
            }

            top?.let { overlay ->
                OverlayContent(overlay, deps, settings, connection, bookmarked, bookmarks, history, overlays, entry,
                    ::open, ::openOnPhone, ::toggleBookmark)
            }

            if (ambient) {
                val r = c.reader
                AmbientScreen(
                    geometry = geometry,
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

/** Launcher satellites: keyboard, continue reading (or tabs), up to three bookmarks, More. */
@Composable
private fun launcherSlots(
    lastRead: com.albustech.orbit.data.db.ReadingPosition?,
    bookmarks: List<Bookmark>,
    onType: () -> Unit,
    onOpen: (String) -> Unit,
    onMore: () -> Unit,
    onTabs: () -> Unit,
    onHistory: () -> Unit,
): List<LauncherSlot> {
    val keyboard = LauncherSlot(stringResource(R.string.action_keyboard), icon = R.drawable.ic_keyboard, onClick = onType)
    val second = lastRead?.let {
        LauncherSlot(
            stringResource(R.string.continue_title, it.title?.takeIf { t -> t.isNotBlank() } ?: Suggestions.displayUrl(it.url)),
            icon = R.drawable.ic_article,
            accent = true,
            onClick = { onOpen(it.url) },
        )
    } ?: LauncherSlot(stringResource(R.string.menu_tabs), icon = R.drawable.ic_tabs, onClick = onTabs)
    val marks = bookmarks.take(3).map { b ->
        LauncherSlot(b.title, host = Suggestions.siteKey(b.url) ?: b.url, onClick = { onOpen(b.url) })
    }
    val fillers = listOf(
        LauncherSlot(stringResource(R.string.menu_history), icon = R.drawable.ic_history, onClick = onHistory),
        LauncherSlot(stringResource(R.string.menu_tabs), icon = R.drawable.ic_tabs, onClick = onTabs),
    ).filter { f -> f.label != second.label }
    val middle = (marks + fillers).take(3)
    return listOf(keyboard, second) + middle + LauncherSlot(stringResource(R.string.action_more), icon = R.drawable.ic_more, onClick = onMore)
}

@Composable
private fun OverlayContent(
    top: Overlay,
    deps: OrbitDeps,
    settings: Settings,
    connection: ConnectionType,
    bookmarked: Boolean,
    bookmarks: List<Bookmark>,
    history: List<HistoryEntry>,
    overlays: MutableList<Overlay>,
    entry: UrlEntry,
    open: (String) -> Unit,
    openOnPhone: (String) -> Unit,
    toggleBookmark: (String, String?, Boolean) -> Unit,
) {
    val c = deps.controller
    val scope = rememberCoroutineScope()
    val pageUrl = c.readerUrl ?: c.url
    val host = Suggestions.siteKey(pageUrl)
    val onPage = c.hasPage
    fun push(o: Overlay) = overlays.add(o)
    fun pop() { if (overlays.isNotEmpty()) overlays.removeAt(overlays.lastIndex) }
    fun close() = overlays.clear()
    val nextMode = SiteMode.entries[(c.site.mode.ordinal + 1) % SiteMode.entries.size]

    when (top) {
        Overlay.Ring -> RingMenu(
            geometry = deps.geometry,
            subtitle = host,
            hint = stringResource(R.string.tap_to_confirm),
            onDismiss = ::pop,
            items = listOf(
                RingItem(R.drawable.ic_search, stringResource(R.string.action_search)) { push(Overlay.Entry) },
                RingItem(R.drawable.ic_forward, stringResource(R.string.action_forward), enabled = onPage && c.canGoForward) { close(); c.forward() },
                RingItem(
                    if (c.isLoading) R.drawable.ic_close else R.drawable.ic_reload,
                    stringResource(if (c.isLoading) R.string.action_stop else R.string.action_reload),
                    enabled = onPage,
                ) { close(); c.reloadOrStop() },
                RingItem(
                    if (bookmarked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border,
                    stringResource(if (bookmarked) R.string.action_bookmarked else R.string.action_bookmark),
                    enabled = onPage && pageUrl != null,
                ) { pageUrl?.let { toggleBookmark(it, c.title, bookmarked) }; close() },
                RingItem(R.drawable.ic_tabs, stringResource(R.string.menu_tabs_count, deps.tabs.tabs.size, TabManager.MAX_TABS)) { push(Overlay.Tabs) },
                RingItem(R.drawable.ic_history, stringResource(R.string.menu_history)) { push(Overlay.History) },
                RingItem(R.drawable.ic_more, stringResource(R.string.action_more)) { push(Overlay.More) },
                RingItem(R.drawable.ic_back, stringResource(R.string.action_back), enabled = onPage && c.canGoBack) { close(); c.back() },
            ),
        )
        Overlay.More -> {
            val status = buildList {
                add(connectionLabel(connection))
                if (c.blockedCount > 0) add(androidx.compose.ui.res.pluralStringResource(R.plurals.blocked_count, c.blockedCount, c.blockedCount))
            }.joinToString(" · ")
            RingMenu(
                geometry = deps.geometry,
                subtitle = status,
                hint = stringResource(R.string.tap_to_confirm),
                onDismiss = ::pop,
                items = listOf(
                    RingItem(R.drawable.ic_article, stringResource(R.string.view_next, siteModeLabel(nextMode)), enabled = onPage) {
                        close()
                        c.setSiteMode(nextMode)
                    },
                    RingItem(R.drawable.ic_text_size, stringResource(R.string.menu_text_size), enabled = onPage) { push(Overlay.TextSize) },
                    RingItem(R.drawable.ic_tune, stringResource(R.string.menu_site), enabled = onPage && host != null) { push(Overlay.Site) },
                    RingItem(R.drawable.ic_phone, stringResource(R.string.action_open_on_phone), enabled = onPage && pageUrl != null) {
                        close()
                        pageUrl?.let(openOnPhone)
                    },
                    RingItem(R.drawable.ic_bookmark, stringResource(R.string.menu_bookmarks)) { push(Overlay.Bookmarks) },
                    RingItem(R.drawable.ic_settings, stringResource(R.string.menu_settings)) { push(Overlay.Settings) },
                    RingItem(R.drawable.ic_home, stringResource(R.string.action_home), enabled = onPage) { close(); c.showHome() },
                    RingItem(R.drawable.ic_close, stringResource(R.string.action_close)) { close() },
                ),
            )
        }
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
            onSearch = deps.settingsRepo::setSearchTemplate,
            onBlock = deps.settingsRepo::setBlockTrackers,
            onDesktop = deps.settingsRepo::setDesktopSites,
            onKeepOn = deps.settingsRepo::setKeepScreenOn,
            onSerif = { v -> deps.settingsRepo.setReaderStyle(settings.reader.copy(serif = v)) },
            onClearHistory = { scope.launch { deps.repo.clearHistory() } },
        )
        Overlay.Site -> SiteSettingsScreen(
            host = host ?: "",
            site = c.site,
            onCycleView = { c.setSiteMode(nextMode) },
            onUpdate = c::updateSite,
        )
        Overlay.TextSize -> TextSizeScreen(
            geometry = deps.geometry,
            reader = c.readerUrl != null,
            style = settings.reader,
            textZoom = settings.textZoom,
            onStyle = deps.settingsRepo::setReaderStyle,
            onZoom = deps.settingsRepo::setTextZoom,
        )
        is Overlay.Link -> {
            val marked by remember(top.url) { deps.repo.isBookmarked(top.url) }.collectAsState(initial = false)
            RingMenu(
                geometry = deps.geometry,
                title = top.title?.takeIf { it.isNotBlank() } ?: Suggestions.displayUrl(top.url),
                subtitle = Suggestions.displayUrl(top.url),
                hint = stringResource(R.string.tap_to_confirm),
                onDismiss = ::pop,
                items = listOf(
                    RingItem(R.drawable.ic_open, stringResource(R.string.action_open)) { close(); c.loadUrl(top.url) },
                    RingItem(R.drawable.ic_phone, stringResource(R.string.action_phone)) { close(); openOnPhone(top.url) },
                    RingItem(R.drawable.ic_copy, stringResource(R.string.action_copy)) { deps.actions.copyLink(top.url); close() },
                    RingItem(
                        if (marked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border,
                        stringResource(if (marked) R.string.action_bookmarked else R.string.action_bookmark),
                    ) { toggleBookmark(top.url, top.title, marked); close() },
                ),
            )
        }
    }
}
