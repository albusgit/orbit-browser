package com.albustech.orbit.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.albustech.orbit.BuildConfig
import com.albustech.orbit.R
import com.albustech.orbit.data.ReaderStyle
import com.albustech.orbit.data.SearchEngine
import com.albustech.orbit.data.Settings
import com.albustech.orbit.data.db.SiteSettings
import com.albustech.orbit.input.DetentAccumulator
import com.albustech.orbit.input.Haptics

@Composable
fun SettingsScreen(
    settings: Settings,
    onSearch: (String) -> Unit,
    onBlock: (Boolean) -> Unit,
    onKeepOn: (Boolean) -> Unit,
    onSerif: (Boolean) -> Unit,
    onClearHistory: () -> Unit,
) {
    OrbitList {
        header { stringResource(R.string.menu_settings) }
        item {
            val engines = SearchEngine.entries
            val current = SearchEngine.of(settings.searchTemplate) ?: engines.first()
            MenuButton(R.drawable.ic_search, stringResource(R.string.settings_search), {
                onSearch(engines[(current.ordinal + 1) % engines.size].template)
            }, secondary = current.label)
        }
        item { MenuSwitch(stringResource(R.string.settings_block), settings.blockTrackers, onBlock, secondary = stringResource(R.string.settings_block_lists)) }
        item { MenuSwitch(stringResource(R.string.settings_keep_on), settings.keepScreenOn, onKeepOn) }
        item { MenuSwitch(stringResource(R.string.settings_serif), settings.reader.serif, onSerif) }
        item { MenuButton(R.drawable.ic_delete, stringResource(R.string.action_clear_history), onClearHistory) }
        note { stringResource(R.string.settings_about, BuildConfig.VERSION_NAME) }
    }
}

/** Per-site choices for the page on screen. */
@Composable
fun SiteSettingsScreen(
    host: String,
    site: SiteSettings,
    onCycleView: () -> Unit,
    onUpdate: ((SiteSettings) -> SiteSettings) -> Unit,
) {
    OrbitList {
        header { host }
        item { MenuButton(R.drawable.ic_article, stringResource(R.string.menu_view), onCycleView, secondary = siteModeLabel(site.mode)) }
        item { MenuSwitch(stringResource(R.string.site_images), site.blockImages, { v -> onUpdate { it.copy(blockImages = v) } }) }
        item { MenuSwitch(stringResource(R.string.site_js), site.javaScript, { v -> onUpdate { it.copy(javaScript = v) } }) }
        item {
            MenuSwitch(
                stringResource(R.string.site_lite),
                site.liteUserAgent,
                { v -> onUpdate { it.copy(liteUserAgent = v) } },
                secondary = stringResource(R.string.site_lite_hint),
            )
        }
        item {
            MenuSwitch(
                stringResource(R.string.site_rich_graphics),
                site.richGraphics,
                { v -> onUpdate { it.copy(richGraphics = v) } },
                secondary = stringResource(R.string.site_rich_graphics_hint),
            )
        }
    }
}

/**
 * Text size, laid out in the inscribed square: − value + with a live sample. The bezel adjusts
 * it too. Reader changes font size (and offers line height); other pages change text zoom.
 */
@Composable
fun TextSizeScreen(
    geometry: RoundGeometry,
    reader: Boolean,
    style: ReaderStyle,
    textZoom: Int,
    onStyle: (ReaderStyle) -> Unit,
    onZoom: (Int) -> Unit,
) {
    val view = LocalView.current
    val haptics = remember(view) { Haptics(view) }
    val accumulator = remember { DetentAccumulator.forScrollFactor(android.view.ViewConfiguration.get(view.context).scaledVerticalScrollFactor) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    // Step from local values: the saved settings arrive a moment later, and quick bezel
    // clicks must not collapse into one step.
    var size by remember { mutableIntStateOf(style.fontSize) }
    var zoom by remember { mutableIntStateOf(textZoom) }

    fun change(delta: Int) {
        if (reader) {
            val next = (size + delta).coerceIn(ReaderStyle.MIN_FONT_SIZE, ReaderStyle.MAX_FONT_SIZE)
            if (next != size) {
                size = next
                onStyle(style.copy(fontSize = next))
            }
        } else {
            val next = (zoom + delta * 10).coerceIn(Settings.MIN_TEXT_ZOOM, Settings.MAX_TEXT_ZOOM)
            if (next != zoom) {
                zoom = next
                onZoom(next)
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onRotaryScrollEvent {
                val n = accumulator.add(it.verticalScrollPixels)
                if (n != 0) {
                    haptics.tick()
                    change(n) // clockwise (scrolling forward) makes text bigger
                }
                true
            }
            .focusRequester(focus)
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.size(geometry.squareDp.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "Aa",
                fontSize = (if (reader) size else 15 * zoom / 100).sp,
                fontFamily = if (reader && style.serif) FontFamily.Serif else FontFamily.SansSerif,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = { change(-1) }) {
                    Icon(painterResource(R.drawable.ic_remove), contentDescription = "Smaller")
                }
                Text(
                    if (reader) stringResource(R.string.text_size_value, size) else stringResource(R.string.text_zoom_value, zoom),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(56.dp),
                )
                FilledTonalIconButton(onClick = { change(1) }) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = "Larger")
                }
            }
            if (reader) {
                Spacer(Modifier.height(4.dp))
                FilledTonalButton(onClick = {
                    val all = ReaderStyle.LINE_HEIGHTS
                    val next = all[(all.indexOfFirst { it >= style.lineHeight - 0.01f }.coerceAtLeast(0) + 1) % all.size]
                    onStyle(style.copy(fontSize = size, lineHeight = next))
                }) {
                    Text(stringResource(R.string.line_height, "%.2f".format(style.lineHeight).trimEnd('0').trimEnd('.')))
                }
            }
        }
    }
}
