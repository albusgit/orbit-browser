package com.albustech.porter

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/** Renders Porter's screen for visual review: PNGs in build/screenshots. Not pixel-compared. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// conscrypt-android has no desktop native library; the screen doesn't need TLS.
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], qualifiers = "w411dp-h1200dp-night-xxhdpi")
class ScreenshotTest {
    private val porter = ApplicationProvider.getApplicationContext<PorterApp>().porter

    @Test fun off() {
        porter._state.value = PorterState(host = "192.168.1.50", port = 41234)
        captureRoboImage("build/screenshots/porter_off.png") { PorterTheme { PorterScreen(porter) } }
    }

    @Test fun porting() {
        porter._state.value = PorterState(
            phase = Phase.Connected, host = "192.168.1.50", port = 41234,
            transfers = listOf(
                Transfer(1, "orbit-0.5.0.apk", isApk = true, size = 88_000_000, sent = 52_000_000, status = Status.Sending),
                Transfer(2, "notes.txt", isApk = false, size = 1200, sent = 1200, status = Status.Done),
            ),
            log = listOf("09:41:02  Connected to 192.168.1.50:41234", "09:41:02  Screen timeout 10 min (was 15 s)"),
        )
        captureRoboImage("build/screenshots/porter_on.png") { PorterTheme { PorterScreen(porter) } }
    }
}
