package com.albustech.orbit.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Text
import com.albustech.orbit.R
import com.albustech.orbit.browser.TabManager
import com.albustech.orbit.data.Suggestions
import com.albustech.orbit.data.db.Bookmark
import com.albustech.orbit.data.db.HistoryEntry
import com.albustech.orbit.data.db.TabRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun BookmarksScreen(bookmarks: List<Bookmark>, onOpen: (String) -> Unit, onRemove: (String) -> Unit) {
    OrbitList {
        header { stringResource(R.string.menu_bookmarks) }
        if (bookmarks.isEmpty()) note { stringResource(R.string.empty_bookmarks) }
        bookmarks.forEach { b ->
            item {
                MenuButton(
                    R.drawable.ic_bookmark,
                    b.title,
                    { onOpen(b.url) },
                    secondary = Suggestions.displayUrl(b.url),
                    onLongClick = { onRemove(b.url) },
                )
            }
        }
    }
}

@Composable
fun HistoryScreen(history: List<HistoryEntry>, onOpen: (String) -> Unit, onClear: () -> Unit) {
    OrbitList {
        header { stringResource(R.string.menu_history) }
        if (history.isEmpty()) note { stringResource(R.string.empty_history) }
        history.forEach { h ->
            item(key = h.id) {
                MenuButton(
                    null,
                    h.title?.takeIf { it.isNotBlank() } ?: Suggestions.displayUrl(h.url),
                    { onOpen(h.url) },
                    secondary = Suggestions.displayUrl(h.url),
                )
            }
        }
        if (history.isNotEmpty()) {
            item { MenuButton(R.drawable.ic_delete, stringResource(R.string.action_clear_history), onClear) }
        }
    }
}

/** Up to three tabs, each a snapshot and a title; tap to switch, long-press to close. */
@Composable
fun TabsScreen(manager: TabManager, onDone: () -> Unit) {
    OrbitList {
        header { stringResource(R.string.menu_tabs_count, manager.tabs.size, TabManager.MAX_TABS) }
        manager.tabs.forEach { tab ->
            item(key = tab.id) { TabRow(manager, tab, onDone) }
        }
        if (manager.canAdd) {
            item {
                MenuButton(R.drawable.ic_add, stringResource(R.string.action_new_tab), {
                    manager.newTab()
                    onDone()
                }, primary = true)
            }
        }
        note { stringResource(R.string.tabs_hint) }
    }
}

@Composable
private fun TabRow(manager: TabManager, tab: TabRecord, onDone: () -> Unit) {
    var thumb by remember(tab.snapshotFile) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(tab.snapshotFile) {
        thumb = withContext(Dispatchers.IO) {
            manager.snapshotFile(tab)?.let { f: File -> BitmapFactory.decodeFile(f.path)?.asImageBitmap() }
        }
    }
    val current = tab.id == manager.currentId
    FilledTonalButton(
        onClick = {
            manager.switchTo(tab.id)
            onDone()
        },
        onLongClick = { manager.close(tab.id) },
        modifier = Modifier,
        icon = thumb?.let { img ->
            {
                Image(
                    bitmap = img,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(32.dp).clip(CircleShape),
                )
            }
        },
        secondaryLabel = {
            Text(
                (if (current) "● " else "") + (tab.url?.let(Suggestions::displayUrl) ?: stringResource(R.string.new_tab)),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        label = {
            Text(tab.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.new_tab), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
    )
}
