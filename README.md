# Orbit

A web browser for round watch screens, built for the Samsung Galaxy Watch6 Classic
(43mm 432×432 and 47mm 480×480, rotating bezel) on Wear OS 4 and later.

Other Wear OS browsers show a rectangular phone page through a round hole. Orbit treats the
circle as the layout surface. Text stays inside the square inscribed in the circle, the
chrome follows the curved edge, and the bezel is the main control.

**Status: Phase 1 (MVP) is done.** It builds, passes unit tests, lint and the round-rendering
check. It has not been run on an emulator or a watch yet; see [Testing on a device](#testing-on-a-device).

---

## Architecture

There is one Gradle module, `:app` (package `com.albustech.orbit`), split into packages:

```
app/src/main/
  assets/        round.css, round.js                 injected into pages (plain files, not strings)
                 Readability.js, reader.js            (Phase 2)
  java/com/albustech/orbit/
    OrbitApp, MainActivity                            single Activity, owns the one WebView
    browser/     OrbitWebView     WebView factory and settings
                 BrowserController state for Compose; load/back/forward/reload/mode
                 Injector         loads assets once, installs document-start scripts
                 UrlResolver      text → URL or search (pure Kotlin, unit-tested)
                 UserAgents, JsStrings, RenderMode, MemoryLog
    input/       BezelInput       routes bezel and touch; centre-tap detection
                 DetentAccumulator, MomentumTracker   pure logic, unit-tested
                 WebViewScroller  smooth steps and momentum fling
                 Haptics          one tick per detent
    ui/          RoundGeometry    diameter, inscribed square, inset (read at runtime)
                 BrowserScreen, HomeScreen, QuickMenu, ErrorScreen
                 EdgeOverlay      rim vignette and loading ring
                 UrlEntry         voice (primary) and RemoteInput keyboard
    data/        SettingsRepository (DataStore); Room arrives in Phase 4
    reader/, tile/, complication/   (later phases)
tools/round-check/   headless-Chromium test that no text is clipped by the circle
```

### Key decisions

| Area | Choice | Why |
|---|---|---|
| Geometry | `RoundGeometry` from `WindowMetrics` and density at runtime. Inscribed square = d/√2, inset = (d − d/√2)/2 | No hardcoded pixels; works on 43mm, 47mm and anything else |
| Injection | `WebViewCompat.addDocumentStartJavaScript`, main frame only. `evaluateJavascript` fallback | The round layout is in place before first paint |
| Round Scroll | `body` capped to the inscribed square, centred, with top and bottom padding equal to the inset. `box-sizing: border-box` and `max-width: 100%` on descendants. Shallow fixed/sticky bars return to the flow. Tables wider than the column get a scroll wrapper. Viewport meta forced to `width=device-width` | Text never touches the rim at rest or at either end of the page |
| Vignette | Compose overlay with a radial gradient (not CSS) | Works in every mode, doesn't touch page styles |
| Dark mode | Dark app theme (`isLightTheme=false`) plus `setAlgorithmicDarkeningAllowed` | True-black pages on AMOLED |
| Bezel | Compose `onRotaryScrollEvent` on the focused root. A WebView `OnGenericMotionListener` covers the case where a page field has focus. `DetentAccumulator` turns deltas into detents | Exactly one handler per event; the bezel's one-event-per-click and the emulator's continuous stream both work |
| Haptics | `SEGMENT_FREQUENT_TICK` on API 34+, `CLOCK_TICK` below that | Horologist is **not** used: its latest release (0.7.15) still pins Wear Compose 1.5 and M2, and rotary haptics now ship in Wear Compose |
| Back | `BackHandler(enabled = canGoBack)`. Otherwise the system handles Back and the app exits. `windowSwipeToDismiss=false` | Matches the spec; horizontal panning doesn't fight swipe-to-dismiss |
| Renderer loss | `onRenderProcessGone` returns true. The Activity rebuilds with a fresh WebView and reopens the page once | On 2 GB of RAM the renderer will be killed sometimes; that must not crash the app |
| Memory | `onStop` calls `onPause()` and `pauseTimers()`. `onTrimMemory(BACKGROUND)` calls `clearCache(false)`. Every load is logged under `OrbitMem` | Battery, plus the "10 page loads" acceptance check |

### Versions (stable releases, checked 2026-10-06)

| | |
|---|---|
| AGP / Gradle / Kotlin | 9.4.1 / 9.8.0 / 2.4.20 |
| compileSdk / targetSdk / minSdk | 37 / 37 / 30 |
| Compose BOM | 2026.09.00 |
| Wear Compose Material 3 / Foundation | 1.7.0 |
| androidx.webkit | 1.17.1 (1.18 is still alpha) |
| wear-input | 1.2.0 |
| wear-remote-interactions | 1.2.0 (Phase 4) |
| DataStore | 1.2.1 |

### Device assumptions I couldn't confirm

1. **Density.** I couldn't confirm the Watch6 Classic's density (probably about 2.0, giving a 240dp diameter on the 47mm). Orbit reads it at runtime, and round-check tests densities 1.75, 2.0 and 2.25.
2. **Bezel events.** I expect one `ACTION_SCROLL` per click with `AXIS_SCROLL = ±1`. Debug builds log every delta (`adb logcat -s OrbitBezel`) so the detent threshold can be calibrated.
3. **WebView.** I'm assuming a WebView provider is present and updated on current firmware. If one isn't, Orbit shows an error screen. Every webkit feature is gated by `WebViewFeature`.
4. **Back button.** I'm assuming the lower hardware key arrives as a normal back event.

---

## Building

You need JDK 17 or later and an Android SDK with `platforms;android-37.0`.

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
```

### Round-rendering check

This check proves that `round.css` and `round.js` keep every word inside the inscribed square.
It covers both screen sizes, three densities and three fixtures: a long article with fixed bars,
wide tables and code, and dense links.

```sh
cd tools/round-check
npm install            # Playwright
node check.mjs         # non-zero exit on failure; screenshots with a circle mask in ./out
```

Run it after any change to `round.css` or `round.js`.

---

## Testing on a device

### Round emulators (480×480 and 432×432)

1. Install a Wear OS system image, for example `system-images;android-36;android-wear-signed;x86_64` (use `arm64-v8a` on Apple silicon).
2. In Android Studio, go to **Device Manager → + → New Hardware Profile**:
   - **Watch6 Classic 47mm:** Wear OS, Round, 480 × 480 px, 1.47", "Has hardware buttons" on.
   - **Watch6 Classic 43mm:** the same, but 432 × 432 px and 1.31".

   Create an AVD from each profile.
3. Or from the command line:
   ```sh
   avdmanager create avd -n watch6_47 -k "system-images;android-36;android-wear-signed;x86_64" -d wearos_large_round
   # then edit ~/.android/avd/watch6_47.avd/config.ini:
   #   hw.lcd.width=480
   #   hw.lcd.height=480
   #   hw.lcd.density=320
   #   hw.rotaryInput=yes
   ```
   Repeat with `432` for `watch6_43`.
4. Bezel: in the emulator, open **Extended controls (⋯) → Rotary input** and drag the wheel.

### The real watch (ADB over Wi-Fi)

1. On the watch, go to **Settings → About watch → Software information** and tap **Software version** 5 times to unlock Developer options.
2. In **Settings → Developer options**, turn on **ADB debugging** and **Wireless debugging**. The watch and computer must be on the same Wi-Fi.
3. In **Wireless debugging**, tap **Pair new device**. It shows an IP:port and a pairing code:
   ```sh
   adb pair 192.168.1.50:37123 123456     # pairing port and code from the pairing screen
   adb connect 192.168.1.50:41234         # the port on the main Wireless debugging screen
   ./gradlew installDebug
   ```
4. To inspect pages, open `chrome://inspect/#devices` in desktop Chrome. Debug builds enable `WebView.setWebContentsDebuggingEnabled`.

### Phase 1 hand-off checklist

Run each item on both sizes:

- [ ] Voice input opens a site ("wikipedia dot org") and runs a search ("weather today").
- [ ] Keyboard (RemoteInput) input works.
- [ ] The bezel scrolls with one tick per click, and a fast spin flings.
- [ ] Back goes back through history, and at the first page it leaves the app.
- [ ] A tap near the centre on plain text opens the menu.
- [ ] Pages are dark (algorithmic darkening). The vignette and the loading ring look right.
- [ ] No text is clipped at the top or bottom of any page, at rest.
- [ ] `adb logcat -s OrbitMem` stays flat over 10 page loads. App PSS excludes the WebView renderer, which runs in its own process, so also watch `sysAvailMb`.

### Test pages

- A long Wikipedia article: `en.wikipedia.org/wiki/Orbital_mechanics`
- A news site full of ads, such as a big tabloid homepage
- Hacker News (dense links): `news.ycombinator.com`
- Wide tables and code: `en.wikipedia.org/wiki/Comparison_of_programming_languages`, or any GitHub file view
- A page with a login form, such as `github.com/login` (tests IME focus and bezel hand-back)

---

## Roadmap

| Phase | Scope | Status |
|---|---|---|
| 1. MVP | Compose shell, WebView, voice and keyboard URL entry, bezel scrolling with haptics, Round Scroll injection, dark mode, Back handling | ✅ built; awaiting device test |
| 2. Reader | Readability.js, circular pagination engine (`shape-outside: circle()` pages, binary search on words), bezel page turns, typography, saved reading positions | |
| 3. Navigation | Link-focus mode, bezel mode switching (long-press), curved title, page/scroll arcs, radial menus | |
| 4. Polish | Bookmarks and history (Room), per-site settings, ad/tracker blocking, ambient mode, Tile and complication, Open on phone, connection-aware errors | |

### Known limitations of Round Scroll (by design)

- **Mid-scroll clipping.** While scrolling, lines that pass through the top and bottom bands (outside the square) are partly clipped. The rim vignette fades that band on purpose. The no-clip guarantee covers the square, which includes the first line at the top of the page and the last line at the bottom. Reader mode (Phase 2) avoids this completely with circular pages.
- **Positioned elements.** Only shallow fixed and sticky elements go back into the flow, to keep the pass cheap. Deeply nested overlays may still float. Zoom view is the fallback for such pages.

### Phase 2+ (documented, not built): companion phone app

This would be a small Android phone app with a **"Send to watch"** share target. Sharing a URL
from any phone app sends it to the watch over the Wearable **Data Layer API**
(`MessageClient`, path `/orbit/open`). A `WearableListenerService` in Orbit receives it and
opens the page through the existing `ACTION_VIEW` intent filter. Both apps need the same
`applicationId` and signing key so the Data Layer pairs them.
