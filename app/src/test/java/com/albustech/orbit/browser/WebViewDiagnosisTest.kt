package com.albustech.orbit.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class WebViewDiagnosisTest {

    private val google = "com.google.android.webview"
    private val missing = WebViewDiagnosis.PROVIDERS.map { ProviderInfo(it, installed = false, enabled = false) }
    private fun with(vararg p: ProviderInfo) = missing.map { m -> p.firstOrNull { it.packageName == m.packageName } ?: m }

    @Test
    fun `nothing installed`() {
        val e = RuntimeException("android.webkit.WebViewFactory\$MissingWebViewPackageException: Failed to load WebView provider: No WebView installed")
        assertEquals(
            WebViewProblem.NoProvider("RuntimeException: android.webkit.WebViewFactory\$MissingWebViewPackageException: Failed to load WebView provider: No WebView installed"),
            WebViewDiagnosis.diagnose(null, missing, e),
        )
    }

    @Test
    fun `installed but disabled`() {
        val problem = WebViewDiagnosis.diagnose(null, with(ProviderInfo(google, installed = true, enabled = false, versionName = "140.0")), RuntimeException())
        assertEquals(WebViewProblem.Disabled(google), problem)
    }

    @Test
    fun `selected provider that still fails is a crash with its version`() {
        val current = ProviderInfo(google, installed = true, enabled = true, versionName = "119.0.6045.66")
        val problem = WebViewDiagnosis.diagnose(current, with(current), IllegalStateException("boom"))
        assertEquals(WebViewProblem.Crashed(google, "119.0.6045.66", "IllegalStateException: boom"), problem)
    }

    @Test
    fun `an enabled provider that wasn't picked is a crash too`() {
        val chrome = ProviderInfo("com.android.chrome", installed = true, enabled = true, versionName = "140")
        assertEquals(WebViewProblem.Crashed("com.android.chrome", "140", null), WebViewDiagnosis.diagnose(null, with(chrome), null))
    }

    @Test
    fun `summary reports the root cause, first line, bounded`() {
        val e = RuntimeException("outer", IllegalArgumentException("inner reason\nstack noise"))
        assertEquals("IllegalArgumentException: inner reason", WebViewDiagnosis.summarize(e))
        val long = WebViewDiagnosis.summarize(RuntimeException("x".repeat(500)))
        assertEquals(140, long.length)
    }
}
