package com.albustech.orbit.browser

/** What the package manager says about one WebView provider. */
data class ProviderInfo(
    val packageName: String,
    val installed: Boolean,
    val enabled: Boolean,
    val versionName: String? = null,
)

/** Why Orbit couldn't get a WebView, in terms the user can act on. */
sealed interface WebViewProblem {
    /** [packageName] is installed but turned off: the user can turn it back on. */
    data class Disabled(val packageName: String) : WebViewProblem

    /** No provider at all. [error] is what WebView reported, for the detail line. */
    data class NoProvider(val error: String?) : WebViewProblem

    /** A provider is there but WebView still failed to start: usually fixed by updating it. */
    data class Crashed(val packageName: String?, val versionName: String?, val error: String?) : WebViewProblem
}

/**
 * Turns package facts plus the startup exception into a [WebViewProblem]. Pure, so it's
 * unit-tested; [OrbitWebView] gathers the facts on the device.
 */
object WebViewDiagnosis {

    /** Packages Android may use as the WebView provider, most likely first on Wear OS. */
    val PROVIDERS = listOf("com.google.android.webview", "com.android.webview", "com.android.chrome")

    /** The package to send the user to when there's nothing installed to fix. */
    const val DEFAULT_PROVIDER = "com.google.android.webview"

    /**
     * [current] is the provider WebView picked (null when it found none), [candidates] the
     * known providers as installed on the device.
     */
    fun diagnose(current: ProviderInfo?, candidates: List<ProviderInfo>, error: Throwable?): WebViewProblem {
        val summary = error?.let(::summarize)
        if (current != null && current.enabled) return WebViewProblem.Crashed(current.packageName, current.versionName, summary)
        val disabled = (listOfNotNull(current) + candidates).firstOrNull { it.installed && !it.enabled }
        if (disabled != null) return WebViewProblem.Disabled(disabled.packageName)
        val present = candidates.firstOrNull { it.installed && it.enabled }
        if (present != null) return WebViewProblem.Crashed(present.packageName, present.versionName, summary)
        return WebViewProblem.NoProvider(summary)
    }

    /** "MissingWebViewPackageException: Failed to load WebView provider: No WebView installed" */
    fun summarize(error: Throwable): String {
        var root = error
        while (root.cause != null && root.cause !== root) root = root.cause!!
        val message = root.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        val text = if (message.isEmpty()) root.javaClass.simpleName else "${root.javaClass.simpleName}: $message"
        return if (text.length <= MAX_SUMMARY) text else text.take(MAX_SUMMARY - 1) + "…"
    }

    private const val MAX_SUMMARY = 140
}
