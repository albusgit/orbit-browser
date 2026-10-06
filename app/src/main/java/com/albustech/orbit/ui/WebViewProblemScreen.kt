package com.albustech.orbit.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.albustech.orbit.R
import com.albustech.orbit.browser.WebViewDiagnosis
import com.albustech.orbit.browser.WebViewProblem

/**
 * Why the WebView couldn't start, with the one-tap fix that applies, then Retry and the
 * technical detail (provider, version, error). A list, so the bezel scrolls it on either size.
 */
@Composable
fun WebViewProblemScreen(
    problem: WebViewProblem,
    onOpenSettings: (packageName: String) -> Unit,
    onOpenStore: (packageName: String) -> Unit,
    onRetry: () -> Unit,
) {
    val title: String
    val explanation: String?
    val action: String
    val onAction: () -> Unit
    val detail: String?
    when (problem) {
        is WebViewProblem.Disabled -> {
            title = stringResource(R.string.webview_disabled_title)
            explanation = stringResource(R.string.webview_disabled_detail)
            action = stringResource(R.string.webview_action_turn_on)
            onAction = { onOpenSettings(problem.packageName) }
            detail = problem.packageName
        }
        is WebViewProblem.NoProvider -> {
            title = stringResource(R.string.webview_none_title)
            explanation = stringResource(R.string.webview_none_detail)
            action = stringResource(R.string.webview_action_get)
            onAction = { onOpenStore(WebViewDiagnosis.DEFAULT_PROVIDER) }
            detail = problem.error
        }
        is WebViewProblem.Crashed -> {
            title = stringResource(R.string.webview_crashed_title)
            explanation = stringResource(R.string.webview_crashed_detail)
            action = stringResource(R.string.webview_action_update)
            onAction = { onOpenStore(problem.packageName ?: WebViewDiagnosis.DEFAULT_PROVIDER) }
            detail = listOfNotNull(
                listOfNotNull(problem.packageName, problem.versionName).joinToString(" ").ifEmpty { null },
                problem.error,
            ).joinToString("\n").ifEmpty { null }
        }
    }
    OrbitList {
        header { title }
        if (explanation != null) note { explanation }
        item { MenuButton(R.drawable.ic_open, action, onAction, primary = true) }
        item { MenuButton(R.drawable.ic_reload, stringResource(R.string.action_retry), onRetry) }
        if (detail != null) note { detail }
    }
}
