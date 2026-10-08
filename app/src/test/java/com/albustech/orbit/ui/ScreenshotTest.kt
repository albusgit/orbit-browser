package com.albustech.orbit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.albustech.orbit.R
import com.albustech.orbit.browser.SearchSite
import com.albustech.orbit.browser.SerpData
import com.albustech.orbit.browser.SerpPage
import com.albustech.orbit.browser.SerpResult
import com.albustech.orbit.browser.SerpState
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the round-native screens on the JVM (Robolectric native graphics + Roborazzi) at the
 * Watch6 Classic sizes, for visual review: build/screenshots/<screen>_<size>.png.
 * Not pixel-compared; the PNGs are what to look at when the UI changes.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class ScreenshotTest(private val tag: String) {

    @Composable
    private fun Watch(content: @Composable (RoundGeometry) -> Unit) {
        val cfg = LocalConfiguration.current
        val density = LocalDensity.current.density
        val px = (cfg.screenWidthDp * density).toInt()
        val geometry = RoundGeometry(px, px, density)
        MaterialTheme {
            Box(Modifier.fillMaxSize().background(Color(0xFF1B1C1E))) {
                Box(Modifier.fillMaxSize().clip(CircleShape).background(Color.Black)) { content(geometry) }
            }
        }
    }

    private fun shot(name: String, content: @Composable (RoundGeometry) -> Unit) =
        captureRoboImage("build/screenshots/${name}_$tag.png") { Watch(content) }

    private val results = listOf(
        SerpResult("What Causes Tides? NOAA's National Ocean Service", "https://oceanservice.noaa.gov/facts/tides.html", "oceanservice.noaa.gov",
            "The tides are very long-period waves that move through the oceans in response to the forces exerted by the moon and sun."),
        SerpResult("Tide - Wikipedia", "https://en.wikipedia.org/wiki/Tide", "en.wikipedia.org",
            "Tides are the rise and fall of sea levels caused by the combined effects of the gravitational forces exerted by the Moon and the Sun."),
        SerpResult("How do tides work? BBC Bitesize", "https://www.bbc.co.uk/bitesize/tides", "bbc.co.uk",
            "The Moon's gravity pulls the ocean towards it, creating a bulge of water."),
        SerpResult("Tides | Smithsonian Ocean", "https://ocean.si.edu/tides", "ocean.si.edu", "Every day, the oceans rise and fall twice."),
    )
    private val serp = SerpState.Ready(
        SerpData(SerpPage(SearchSite.GOOGLE, "how do tides work", 0), "https://www.google.com/search?q=how+do+tides+work",
            results, answer = null, nextUrl = "https://www.google.com/search?q=how+do+tides+work&start=10"),
    )

    @Test
    fun launcher() = shot("launcher") { g ->
        Launcher(
            geometry = g,
            slots = listOf(
                LauncherSlot("Keyboard", icon = R.drawable.ic_keyboard) {},
                LauncherSlot("Continue · How tides really work", icon = R.drawable.ic_article, accent = true) {},
                LauncherSlot("Hacker News", host = "news.ycombinator.com") {},
                LauncherSlot("Wikipedia", host = "en.wikipedia.org") {},
                LauncherSlot("BBC", host = "bbc.co.uk") {},
                LauncherSlot("More", icon = R.drawable.ic_more) {},
            ),
            micLabel = "Speak",
            hint = "Turn bezel to pick",
            onSpeak = {},
            onBezelUsed = {},
        )
    }

    @Test
    fun ringMenu() = shot("ring") { g ->
        RingMenu(
            geometry = g,
            subtitle = "bbc.com",
            hint = "Tap to confirm",
            onDismiss = {},
            initial = 3,
            items = listOf(
                RingItem(R.drawable.ic_search, "Search") {},
                RingItem(R.drawable.ic_forward, "Forward", enabled = false) {},
                RingItem(R.drawable.ic_reload, "Reload") {},
                RingItem(R.drawable.ic_bookmark, "Bookmark") {},
                RingItem(R.drawable.ic_tabs, "Tabs 2/3") {},
                RingItem(R.drawable.ic_history, "History") {},
                RingItem(R.drawable.ic_more, "More") {},
                RingItem(R.drawable.ic_back, "Back") {},
            ),
        )
    }

    @Test
    fun linkWedges() = shot("link") { g ->
        RingMenu(
            geometry = g,
            title = "tidal forces",
            subtitle = "bbc.com/tides",
            hint = "Tap to confirm",
            onDismiss = {},
            items = listOf(
                RingItem(R.drawable.ic_open, "Open") {},
                RingItem(R.drawable.ic_phone, "Phone") {},
                RingItem(R.drawable.ic_copy, "Copy") {},
                RingItem(R.drawable.ic_bookmark_border, "Bookmark") {},
            ),
        )
    }

    @Test
    fun serpCard() = shot("serp_card") { g ->
        SerpScreen(g, serp, startIndex = 0, onIndex = {}, onOpen = {}, onEdit = {}, onVoice = {})
    }

    @Test
    fun serpOverview() = shot("serp_overview") { g ->
        SerpScreen(g, serp, startIndex = 1, onIndex = {}, onOpen = {}, onEdit = {}, onVoice = {}, startInOverview = true)
    }

    @Test
    fun serpLoading() = shot("serp_loading") { g ->
        SerpScreen(g, SerpState.Loading(serp.page, serp.url), 0, {}, {}, {}, {})
    }

    @Test
    fun pageChrome() = shot("page") { g ->
        Box(Modifier.fillMaxSize()) {
            FakePage(g)
            BezelModeArc(g, listOf("Pages", "Links", "Cursor", "Text"), selected = 0, visible = true, onSelect = {})
            EdgeOverlay(g, loadingProgress = { null }, indicator = { EdgeIndicator.Pages(3, 12) }, indicatorVisible = true)
        }
    }

    @Test
    fun linkMode() = shot("linkmode") { g ->
        Box(Modifier.fillMaxSize()) {
            FakePage(g)
            LinkModeControls(g, counter = "Link 3 of 9", hasLink = true, onOpen = {}, onOptions = {})
        }
    }

    @Composable
    private fun FakePage(g: RoundGeometry) {
        Text(
            "The Moon pulls hardest on the side of Earth that faces it and least on the far side. " +
                "That difference stretches the oceans into two bulges, explained in tidal forces.",
            color = Color(0xFFE6E6E6),
            fontFamily = FontFamily.Serif,
            fontSize = 15.sp,
            modifier = Modifier.padding(horizontal = (g.insetDp + 4).dp, vertical = (g.insetDp + 8).dp),
        )
    }
}

@Config(sdk = [36], qualifiers = "w240dp-h240dp-round-watch-xhdpi")
class Screenshot480 : ScreenshotTest("480")

@Config(sdk = [36], qualifiers = "w216dp-h216dp-round-watch-xhdpi")
class Screenshot432 : ScreenshotTest("432")
