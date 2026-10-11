package com.albustech.orbit.browser

import com.albustech.orbit.data.db.SiteMode
import org.junit.Assert.assertEquals
import org.junit.Test

class RenderModeTest {

    @Test
    fun `sites on Auto follow the global View setting`() {
        for (view in RenderMode.VIEWS) {
            assertEquals(view, RenderMode.forSite(SiteMode.AUTO, view))
            assertEquals(view, RenderMode.forSite(SiteMode.READER, view))
        }
    }

    @Test
    fun `a site's own choice wins over the setting`() {
        for (view in RenderMode.VIEWS) {
            assertEquals(RenderMode.MOBILE, RenderMode.forSite(SiteMode.MOBILE, view))
            assertEquals(RenderMode.COMPACT, RenderMode.forSite(SiteMode.SCROLL, view))
            assertEquals(RenderMode.ZOOM, RenderMode.forSite(SiteMode.ZOOM, view))
        }
    }
}
