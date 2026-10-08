package com.albustech.orbit.browser

import com.albustech.orbit.data.db.SiteMode
import org.junit.Assert.assertEquals
import org.junit.Test

class RenderModeTest {

    @Test
    fun `sites get their mobile version by default`() {
        assertEquals(RenderMode.MOBILE, RenderMode.forSite(SiteMode.AUTO, desktopSites = false))
        assertEquals(RenderMode.MOBILE, RenderMode.forSite(SiteMode.READER, desktopSites = false))
    }

    @Test
    fun `the Desktop sites setting switches the default`() {
        assertEquals(RenderMode.ZOOM, RenderMode.forSite(SiteMode.AUTO, desktopSites = true))
        assertEquals(RenderMode.ZOOM, RenderMode.forSite(SiteMode.READER, desktopSites = true))
    }

    @Test
    fun `a site's own choice wins over the setting`() {
        for (desktop in listOf(false, true)) {
            assertEquals(RenderMode.SCROLL, RenderMode.forSite(SiteMode.SCROLL, desktop))
            assertEquals(RenderMode.ZOOM, RenderMode.forSite(SiteMode.ZOOM, desktop))
        }
    }
}
