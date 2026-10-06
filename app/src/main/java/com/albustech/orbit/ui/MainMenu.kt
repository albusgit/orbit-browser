package com.albustech.orbit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import com.albustech.orbit.R
import com.albustech.orbit.browser.BezelMode
import com.albustech.orbit.browser.ConnectionType
import com.albustech.orbit.browser.RenderMode
import com.albustech.orbit.data.db.SiteMode

/** Everything the main menu shows and can do. */
data class MenuState(
    val title: String?,
    val host: String?,
    val hasPage: Boolean,
    val isLoading: Boolean,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
    val siteMode: SiteMode,
    val renderMode: RenderMode,
    val bezelMode: BezelMode,
    val bookmarked: Boolean,
    val connection: ConnectionType,
    val blocked: Int,
    val tabCount: Int,
    val maxTabs: Int,
)

class MenuActions(
    val goTo: () -> Unit,
    val speak: () -> Unit,
    val back: () -> Unit,
    val forward: () -> Unit,
    val reloadOrStop: () -> Unit,
    val cycleView: () -> Unit,
    val cycleBezel: () -> Unit,
    val toggleBookmark: () -> Unit,
    val bookmarks: () -> Unit,
    val history: () -> Unit,
    val tabs: () -> Unit,
    val textSize: () -> Unit,
    val openOnPhone: () -> Unit,
    val site: () -> Unit,
    val settings: () -> Unit,
    val home: () -> Unit,
)

@Composable
fun connectionLabel(c: ConnectionType): String = stringResource(
    when (c) {
        ConnectionType.WIFI -> R.string.conn_wifi
        ConnectionType.CELLULAR -> R.string.conn_cellular
        ConnectionType.BLUETOOTH -> R.string.conn_bluetooth
        ConnectionType.OTHER -> R.string.conn_other
        ConnectionType.NONE -> R.string.conn_none
    },
)

@Composable
fun siteModeLabel(m: SiteMode): String = stringResource(
    when (m) {
        SiteMode.AUTO -> R.string.view_auto
        SiteMode.READER -> R.string.view_reader
        SiteMode.SCROLL -> R.string.view_scroll
        SiteMode.ZOOM -> R.string.view_zoom
    },
)

@Composable
fun bezelLabel(b: BezelMode, render: RenderMode): String = stringResource(
    when (b) {
        BezelMode.SCROLL -> if (render == RenderMode.READER) R.string.bezel_pages else R.string.bezel_scroll
        BezelMode.LINKS -> R.string.bezel_links
        BezelMode.ZOOM -> if (render == RenderMode.READER) R.string.bezel_text else R.string.bezel_zoom
    },
)

/** The main menu: a curved list the bezel scrolls, opened by a tap in the centre. */
@Composable
fun MainMenu(s: MenuState, a: MenuActions) {
    OrbitList {
        header { s.title ?: s.host ?: stringResource(R.string.app_name) }
        note {
            buildList {
                add(connectionLabel(s.connection))
                if (s.blocked > 0) add(pluralStringResource(R.plurals.blocked_count, s.blocked, s.blocked))
            }.joinToString(" · ")
        }
        item { MenuButton(R.drawable.ic_search, stringResource(R.string.action_go_to), a.goTo, primary = true, secondary = s.host) }
        item { MenuButton(R.drawable.ic_mic, stringResource(R.string.action_voice), a.speak) }
        if (s.hasPage) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                ) {
                    NavIcon(R.drawable.ic_back, stringResource(R.string.action_back), s.canGoBack, a.back)
                    NavIcon(
                        if (s.isLoading) R.drawable.ic_close else R.drawable.ic_reload,
                        stringResource(if (s.isLoading) R.string.action_stop else R.string.action_reload),
                        true,
                        a.reloadOrStop,
                    )
                    NavIcon(R.drawable.ic_forward, stringResource(R.string.action_forward), s.canGoForward, a.forward)
                }
            }
            item {
                MenuButton(R.drawable.ic_article, stringResource(R.string.menu_view), a.cycleView, secondary = siteModeLabel(s.siteMode))
            }
            item {
                MenuButton(R.drawable.ic_bezel, stringResource(R.string.menu_bezel), a.cycleBezel, secondary = bezelLabel(s.bezelMode, s.renderMode))
            }
            item {
                MenuButton(
                    if (s.bookmarked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border,
                    stringResource(if (s.bookmarked) R.string.action_bookmarked else R.string.action_bookmark),
                    a.toggleBookmark,
                )
            }
        }
        item { MenuButton(R.drawable.ic_bookmark, stringResource(R.string.menu_bookmarks), a.bookmarks) }
        item { MenuButton(R.drawable.ic_history, stringResource(R.string.menu_history), a.history) }
        item { MenuButton(R.drawable.ic_tabs, stringResource(R.string.menu_tabs_count, s.tabCount, s.maxTabs), a.tabs) }
        if (s.hasPage) {
            item { MenuButton(R.drawable.ic_text_size, stringResource(R.string.menu_text_size), a.textSize) }
            item { MenuButton(R.drawable.ic_phone, stringResource(R.string.action_open_on_phone), a.openOnPhone) }
            item { MenuButton(R.drawable.ic_tune, stringResource(R.string.menu_site), a.site, secondary = s.host) }
        }
        item { MenuButton(R.drawable.ic_settings, stringResource(R.string.menu_settings), a.settings) }
        if (s.hasPage) item { MenuButton(R.drawable.ic_home, stringResource(R.string.action_home), a.home) }
    }
}

@Composable
private fun NavIcon(icon: Int, description: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, enabled = enabled) {
        Icon(painterResource(icon), contentDescription = description)
    }
}
