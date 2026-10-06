package com.albustech.orbit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.albustech.orbit.R
import com.albustech.orbit.browser.UrlResolver
import com.albustech.orbit.data.Suggestion
import com.albustech.orbit.data.Suggestions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Address entry: speak or type, then pick. As soon as there's text, matches from bookmarks
 * and history are listed under a "Go to"/"Search" row. Wear keyboards are full-screen, so the
 * list fills in when the keyboard closes (and inside the keyboard, recent sites appear as
 * quick choices). If nothing matches, the text is opened straight away.
 */
@Composable
fun UrlEntryScreen(
    searchTemplate: String,
    recentSites: List<String>,
    suggest: suspend (String) -> List<Suggestion>,
    onOpen: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var matches by remember { mutableStateOf(emptyList<Suggestion>()) }
    val scope = rememberCoroutineScope()

    val entry = rememberUrlEntry(choices = recentSites) { text ->
        scope.launch {
            val found = suggest(text)
            if (found.isEmpty()) onOpen(text) else {
                query = text
                matches = found
            }
        }
    }

    LaunchedEffect(query) {
        if (query.isBlank()) {
            matches = emptyList()
            return@LaunchedEffect
        }
        delay(120)
        matches = suggest(query)
    }

    OrbitList {
        header { stringResource(R.string.entry_title) }
        item { MenuButton(R.drawable.ic_mic, stringResource(R.string.action_voice), entry::speak, primary = true) }
        item { MenuButton(R.drawable.ic_keyboard, stringResource(R.string.action_keyboard), { entry.type(query) }) }
        if (query.isNotBlank()) {
            val target = UrlResolver.resolve(query, searchTemplate)
            val isSearch = target != null && target == UrlResolver.searchUrl(query.trim(), searchTemplate)
            item {
                MenuButton(
                    if (isSearch) R.drawable.ic_search else R.drawable.ic_open,
                    if (isSearch) stringResource(R.string.search_for, query.trim()) else stringResource(R.string.go_to, Suggestions.displayUrl(target.orEmpty())),
                    { onOpen(query) },
                    primary = true,
                )
            }
        }
        matches.forEach { s ->
            item {
                MenuButton(
                    if (s.kind == Suggestion.Kind.BOOKMARK) R.drawable.ic_bookmark else R.drawable.ic_history,
                    s.title,
                    { onOpen(s.url) },
                    secondary = Suggestions.displayUrl(s.url),
                )
            }
        }
    }
}
