package com.albustech.orbit.claude

import java.net.URI

/** Where things are on claude.ai, and what the app does with a URL. Pure, so it's unit tested. */
object ClaudeUrls {

    const val HOME = "https://claude.ai/new"
    const val CHATS = "https://claude.ai/recents"
    const val LOGOUT = "https://claude.ai/logout"

    /** Pages where you're signing in: the app's own buttons stay out of the way of the form. */
    private val SIGN_IN_PATHS = listOf("/login", "/logout", "/magic-link", "/sso", "/oauth", "/auth", "/onboarding")

    /** Pages that are a conversation, or the start of one. */
    private val CHAT_PATHS = listOf("/new", "/chat/", "/project/")

    fun isWeb(url: String?): Boolean =
        url != null && (url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true))

    fun host(url: String?): String? {
        if (!isWeb(url)) return null
        return try {
            URI(url).host?.lowercase()?.removeSuffix(".")
        } catch (_: Exception) {
            null
        }
    }

    private fun path(url: String?): String =
        try {
            URI(url).rawPath.orEmpty()
        } catch (_: Exception) {
            ""
        }

    /** claude.ai itself, over HTTPS (the extension only runs there). */
    fun isClaude(url: String?): Boolean {
        if (url == null || !url.startsWith("https://", ignoreCase = true)) return false
        val h = host(url) ?: return false
        return h == "claude.ai" || h.endsWith(".claude.ai")
    }

    fun isSignIn(url: String?): Boolean {
        if (!isClaude(url)) return false
        val p = path(url)
        return SIGN_IN_PATHS.any { p == it || p.startsWith("$it/") }
    }

    fun isChat(url: String?): Boolean {
        if (!isClaude(url)) return false
        val p = path(url)
        return CHAT_PATHS.any { if (it.endsWith("/")) p.startsWith(it) else p == it || p.startsWith("$it/") }
    }

    /**
     * A sign-in popup that ends up on claude.ai itself (a redirect-style login finishing in the
     * popup rather than in the opener) is done: the chat window should show that page instead.
     * claude.ai's own auth callbacks are left alone, so the opener can still pick them up.
     */
    fun popupFinished(url: String?): Boolean = isClaude(url) && !isSignIn(url) && !path(url).startsWith("/api/")
}
