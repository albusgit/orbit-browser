package com.albustech.orbit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import com.albustech.orbit.R
import com.albustech.orbit.data.Suggestions
import com.albustech.orbit.data.db.Bookmark
import com.albustech.orbit.data.db.ReadingPosition

/**
 * Start screen: voice (primary) and keyboard at the top, then "continue reading" and the
 * bookmark quick-launch list. The bezel scrolls it like any list.
 */
@Composable
fun HomeScreen(
    lastRead: ReadingPosition?,
    bookmarks: List<Bookmark>,
    onSpeak: () -> Unit,
    onType: () -> Unit,
    onOpen: (String) -> Unit,
    onMenu: () -> Unit,
) {
    OrbitList(initialCenterItem = 1) {
        header { stringResource(R.string.app_name) }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(onClick = onType) {
                    Icon(painterResource(R.drawable.ic_keyboard), contentDescription = stringResource(R.string.action_keyboard))
                }
                FilledIconButton(onClick = onSpeak, modifier = Modifier.size(IconButtonDefaults.LargeButtonSize)) {
                    Icon(
                        painterResource(R.drawable.ic_mic),
                        contentDescription = stringResource(R.string.action_voice),
                        modifier = Modifier.size(IconButtonDefaults.LargeIconSize),
                    )
                }
                FilledTonalIconButton(onClick = onMenu) {
                    Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.menu_settings))
                }
            }
        }
        if (lastRead != null) {
            item {
                val where = if (lastRead.reader && lastRead.pageCount > 0) {
                    stringResource(R.string.page_of, lastRead.page + 1, lastRead.pageCount)
                } else {
                    Suggestions.displayUrl(lastRead.url)
                }
                MenuButton(
                    R.drawable.ic_article,
                    lastRead.title?.takeIf { it.isNotBlank() } ?: Suggestions.displayUrl(lastRead.url),
                    { onOpen(lastRead.url) },
                    secondary = "${stringResource(R.string.action_continue)} · $where",
                    primary = true,
                )
            }
        }
        bookmarks.take(QUICK_LAUNCH).forEach { b ->
            item { MenuButton(R.drawable.ic_bookmark, b.title, { onOpen(b.url) }, secondary = Suggestions.displayUrl(b.url)) }
        }
    }
}

private const val QUICK_LAUNCH = 6
