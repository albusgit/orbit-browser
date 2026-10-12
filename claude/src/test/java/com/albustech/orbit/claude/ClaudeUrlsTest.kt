package com.albustech.orbit.claude

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeUrlsTest {

    @Test
    fun claudeIsHttpsClaudeAiOnly() {
        assertTrue(ClaudeUrls.isClaude("https://claude.ai/new"))
        assertTrue(ClaudeUrls.isClaude("https://CLAUDE.AI/chat/abc"))
        assertFalse(ClaudeUrls.isClaude("http://claude.ai/new"))
        assertFalse(ClaudeUrls.isClaude("https://claude.ai.evil.com/new"))
        assertFalse(ClaudeUrls.isClaude("https://notclaude.ai/new"))
        assertFalse(ClaudeUrls.isClaude("https://accounts.google.com/o/oauth2"))
        assertFalse(ClaudeUrls.isClaude(null))
    }

    @Test
    fun chatPages() {
        assertTrue(ClaudeUrls.isChat("https://claude.ai/new"))
        assertTrue(ClaudeUrls.isChat("https://claude.ai/new?q=hi"))
        assertTrue(ClaudeUrls.isChat("https://claude.ai/chat/0b7c1f0e-1a2b"))
        assertTrue(ClaudeUrls.isChat("https://claude.ai/project/42"))
        assertFalse(ClaudeUrls.isChat("https://claude.ai/recents"))
        assertFalse(ClaudeUrls.isChat("https://claude.ai/newsroom"))
        assertFalse(ClaudeUrls.isChat("https://claude.ai/login"))
    }

    @Test
    fun signInPages() {
        assertTrue(ClaudeUrls.isSignIn("https://claude.ai/login"))
        assertTrue(ClaudeUrls.isSignIn("https://claude.ai/login?returnTo=%2Fnew"))
        assertTrue(ClaudeUrls.isSignIn("https://claude.ai/magic-link#token"))
        assertFalse(ClaudeUrls.isSignIn("https://claude.ai/new"))
        assertFalse(ClaudeUrls.isSignIn("https://claude.ai/loginhelp"))
    }

    @Test
    fun popupHandsBackOnlyFinishedClaudePages() {
        assertTrue(ClaudeUrls.popupFinished("https://claude.ai/new"))
        assertFalse(ClaudeUrls.popupFinished("https://claude.ai/login"))
        assertFalse(ClaudeUrls.popupFinished("https://claude.ai/api/auth/callback/google?code=x"))
        assertFalse(ClaudeUrls.popupFinished("https://accounts.google.com/signin"))
    }

    @Test
    fun hosts() {
        assertEquals("accounts.google.com", ClaudeUrls.host("https://accounts.google.com/o/oauth2/v2/auth?x=1"))
        assertNull(ClaudeUrls.host("about:blank"))
        assertNull(ClaudeUrls.host("https://exa mple.com/"))
    }
}
