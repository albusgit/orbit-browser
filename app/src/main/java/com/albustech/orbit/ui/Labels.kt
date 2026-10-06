package com.albustech.orbit.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.albustech.orbit.R
import com.albustech.orbit.browser.BezelMode
import com.albustech.orbit.browser.ConnectionType
import com.albustech.orbit.browser.RenderMode
import com.albustech.orbit.data.db.SiteMode

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
