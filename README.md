# Orbit

A web browser for round watch screens, built for the Samsung Galaxy Watch6 Classic
(43mm 432×432 and 47mm 480×480, rotating bezel) on Wear OS 4 and later.

Other Wear OS browsers show a rectangular phone page through a round hole. Orbit treats the
circle as the layout surface. Text stays inside the circle, the chrome follows the curved
edge, and the bezel is the main control.

**Status: all four phases are built.** The app builds (debug and minified release), and unit
tests, lint and the three headless rendering checks pass. It has **not yet been run on an
emulator or a watch**: the build container has no KVM. See [Testing on a device](#testing-on-a-device).

---

## What it does

### Three ways to draw a page (remembered per site)

| Mode | When | How |
|---|---|---|
| **Paged Reader** | Default for articles (site mode *Auto*), or forced with *Reader* | Mozilla Readability extracts the article, then Orbit sets it as **circular pages**: two floats whose `shape-outside` polygons cover everything outside the circle, so lines are short at the top and bottom and full width in the middle. Each bezel click turns one page, with a dial-swing animation and a haptic tick. Images get their own page, scaled into the inscribed square. Tables and code get a page whose square scrolls. A progress arc runs around the rim, and "4 / 12" appears for a moment at the bottom. Pure `#000` background. |
| **Round Scroll** | Pages that aren't articles, or *Scroll* | The content column is capped to the inscribed square (d/√2) and padded top and bottom by the circle-to-square gap. The viewport is forced to `device-width`. Fixed headers and cookie bars go back into the flow. Only tables too wide for the column get a horizontal scroll box. Code wraps. A radial vignette fades the rim on purpose. A scroll arc sits on the right edge. |
| **Zoom view** | Complex layouts, or *Zoom* | The page's own layout in overview, with pinch zoom. Double-tap zooms the tapped block to fit the **inscribed square**, not the full width. The bezel zooms by default. |

Switch with **Menu → View** (Auto → Reader → Round scroll → Zoom). The choice is saved for the site.

### The bezel

Long-press the **centre** of the screen to switch what the bezel does. A curved label shows the new mode:

1. **Scroll**: smooth steps with momentum (a fast spin flings) in Round Scroll; **page turns** in Reader.
2. **Links**: steps through the visible links and controls in reading order, with a glowing ring around the focused one. **A tap anywhere opens it.** Past the last visible link the page scrolls (or turns) and focus continues. Back leaves link mode.
3. **Zoom**: zooms the page. In Reader it changes the text size and re-paginates on the same word.

Every bezel click gives a haptic tick. The end of a page or article gives a firmer bump.

### The rest

- **Curved UI.** Time and the page title curve along the top edge, a loading ring runs around the whole edge, and position arcs show where you are. It's immersive: everything hides while you scroll, and a tap in the centre opens the menu.
- **Menu** (a list the bezel scrolls):
  - Address/search, voice, and Back/Reload/Forward
  - View, Bezel mode, and a Bookmark toggle
  - Bookmarks, History, Tabs (n/3), Text size
  - Open on phone, This site, Settings, Home
  - The current connection (Wi-Fi / LTE / Phone over Bluetooth) and the number of ad/tracker requests blocked on the page
- **Radial link menu.** Long-press a link (or long-press away from the centre in link mode) to get Open, Open on phone, Bookmark and Copy link, placed around the centre.
- **Entering addresses.**
  - Voice is the primary method ("wikipedia dot org" works). The RemoteInput keyboard is the backup and shows recent sites as quick choices.
  - Matches from bookmarks and history are listed under "Go to"/"Search". When nothing matches, the page opens directly.
  - Search defaults to DuckDuckGo HTML. You can switch to DuckDuckGo Lite, Google or Bing.
- **Home screen.** Voice and keyboard buttons, a "continue reading" card, and quick-launch bookmarks.
- **Per site:** view mode, block images, JavaScript on/off, and a *Lite version* toggle (an Opera Mini user agent that many sites answer with basic HTML).
- **Reading positions** are saved for every URL. Reader stores a word anchor that survives re-pagination; scroll pages store a fraction. Both are restored on reopen.
- **Typography:** size (11–26 px), line height, serif or sans, and hyphenation in the page's language.
- **Tabs:** up to 3, kept as URL, title, snapshot and position, never as live WebViews.
- **Ambient mode:** a dim black screen with the title and page number; waking restores the page instantly at the same spot.
- **Keep screen on while reading** (setting): each bezel turn buys another 2 minutes.
- **Open on phone** uses `RemoteActivityHelper` and Wear's confirmation animation. `mailto:`, `tel:` and downloads are handed to the phone too.
- **Tile:** the last page read (with its page number) plus 3 bookmarks, each one tap away.
- **Complication:** opens Orbit from the watch face.

### Battery and memory (2 GB RAM)

- One WebView only. `onStop` and ambient pause the WebView and all JS timers, and `onTrimMemory` drops the RAM cache.
- If the system kills the renderer for memory, Orbit rebuilds with a fresh WebView and reopens the page. If the same page kills it again, it lands on the home screen instead.
- A bundled 70-host ad/tracker list is applied in `shouldInterceptRequest` (on by default).
- Media autoplay is off. Reader images load lazily (the page about to be shown is preloaded).
- Mobile user agent (the WebView default minus the `wv` marker).
- AMOLED black: algorithmic darkening on every page, and `#000` in Reader.
- A stall watchdog gives Bluetooth-via-phone connections 45 s (others 25 s) before showing a clear, connection-specific error ("Too slow over Bluetooth: connect the watch to Wi-Fi, or try Reader view").
- `adb logcat -s OrbitMem` logs PSS, heap and system free memory after every page load.

---

## Architecture

There is one Gradle module, `:app` (package `com.albustech.orbit`):

```
app/src/main/
  assets/
    round.css, round.js                 Round Scroll (document-start script)
    Readability.js, Readability-readerable.js   Mozilla Readability 0.6.0 (Apache 2.0)
    extract.js                          runs Readability, sanitises the article (allow-list), posts it
    reader.html, reader.css, reader.js  reader page template + circular pagination engine
    links.js                            link-focus mode
    blocklist.txt                       ad/tracker hosts
  java/com/albustech/orbit/
    OrbitApp, MainActivity              singletons; lifecycle, ambient, keep-on, phone hand-off
    browser/   BrowserController        the WebView and everything in it; state for Compose
               Injector, Bridge         assets in, JSON messages out (WebMessageListener)
               TabManager, Blocklist, Connection, PageError, UserAgents, UrlResolver, MemoryLog
    reader/    ReaderTemplate           builds the reader page (pure Kotlin, unit-tested)
    input/     BezelInput               rotary + touch gestures → detents, taps, long-presses
               DetentAccumulator, MomentumTracker, WebViewScroller, Haptics
    ui/        BrowserScreen            root: WebView + overlays + curved chrome
               MainMenu, UrlEntryScreen, ListScreens, SettingsScreens, LinkMenu,
               HomeScreen, AmbientScreen, EdgeOverlay, Curved, RoundGeometry, Common
    data/      SettingsRepository (DataStore), BrowserRepository + db/ (Room: bookmarks,
               history, per-site settings, reading positions, tabs), Suggestions
    tile/      OrbitTileService (ProtoLayout Material3)
    complication/ LaunchComplicationService
app/src/test/   JVM unit tests (geometry, URLs, UA, detents, momentum, blocklist, template, …)
tools/round-check/  headless-Chromium checks (see below)
```

### How the reader is wired

1. A page loads in Round Scroll.
2. If the site is *Auto* or *Reader*, `extract.js` runs Readability in the page. It sanitises the article against an allow-list in an inert document and posts it over the `orbitBridge` WebMessageListener (main frame only).
3. `ReaderTemplate` fills `reader.html` (strict CSP with a per-page nonce, so no page script can run) and loads it with `loadDataWithBaseURL(url#orbit-reader)`.
4. History becomes `[… A, A-reader]`. Back from the reader skips `A`, which would only reopen the reader.
5. Messages from the page are treated as hints and checked against the controller's own state. They are never commands.

### Key decisions

| Area | Choice | Why |
|---|---|---|
| Geometry | `RoundGeometry` from `WindowMetrics` and density at runtime | No hardcoded pixels; works on 43mm, 47mm and anything else |
| Injection | `addDocumentStartJavaScript` for the round layout; `evaluateJavascript` on demand for Readability and links | Layout before first paint; ordinary pages don't parse 90 KB of Readability |
| Pagination | Pages rather than a scrolling circular column | `shape-outside` shapes text relative to the document, not the viewport |
| Text split | Binary search on word count, using DOM `Range` cloning | Keeps inline markup across page breaks; O(log n) layouts per paragraph |
| Positions | Anchor = (block, word) | Survives font and size changes |
| Bezel | Compose `onRotaryScrollEvent` on the focused root, plus a WebView listener when a field has focus | One handler per event |
| Haptics | `SEGMENT_FREQUENT_TICK` (API 34+) / `CLOCK_TICK`, `REJECT` at edges | **Horologist is not used**: its latest release (0.7.15) still pins Wear Compose 1.5/M2, and the rotary haptics are now in Wear Compose |
| Tabs | State + snapshot, not live WebViews | 2 GB RAM |

### Versions (stable releases, checked 2026-10-06)

| | |
|---|---|
| AGP / Gradle / Kotlin / KSP | 9.4.1 / 9.8.0 / 2.4.20 / 2.3.12 |
| compileSdk / targetSdk / minSdk | 37 / 37 / 30 |
| Compose BOM / Wear Compose Material 3 | 2026.09.00 / 1.7.0 |
| androidx.webkit | 1.17.1 (1.18 is still alpha) |
| wear / wear-input / wear-remote-interactions | 1.4.0 / 1.2.0 / 1.2.0 |
| Tiles / ProtoLayout Material3 / complications | 1.6.2 / 1.4.2 / 1.3.0 |
| Room / DataStore / coroutines | 2.8.5 / 1.2.1 / 1.11.0 |

### Device assumptions I couldn't confirm

1. **Density.** I couldn't confirm the Watch6 Classic's density (probably about 2.0). Orbit reads it at runtime; the checks cover 1.75, 2.0 and 2.25.
2. **Bezel events.** I expect one `ACTION_SCROLL` per click. Debug builds log every delta (`adb logcat -s OrbitBezel`) so the detent threshold (`DetentAccumulator`) can be calibrated.
3. **WebView.** I'm assuming a WebView provider is present and updated on current firmware. If one isn't, Orbit shows an error screen. Every webkit feature is gated by `WebViewFeature`.
4. **Back button.** I'm assuming the lower hardware key arrives as a normal back event.
5. **Bluetooth.** I'm assuming the Bluetooth-proxied network reports `TRANSPORT_BLUETOOTH`.

---

## Building

You need JDK 17 or later and an Android SDK with `platforms;android-37.0`.

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
./gradlew assembleRelease        # minified (R8); sign it with your own key
```

### Headless checks (`tools/round-check`)

```sh
cd tools/round-check && npm install
node check.mjs          # Round Scroll: every word inside the inscribed square, top/bottom lines clear
node reader-check.mjs   # Reader: every word inside the circle, images in the square, nothing lost,
                        #   position kept across re-pagination, timing with CPU throttled 6×
node links-check.mjs    # Link focus: reading order, scroll/page continuation, back, activate
```

They run the same assets the app injects, at 432 and 480 px and three densities, and they
write screenshots with a circle mask to `./out`. Latest results:

- 18/18 Round Scroll cases.
- 12/12 reader cases. With the CPU throttled 6×, the first page shows in about 0.3–0.5 s and a page turn takes one or two frames.
- 4/4 link-focus cases.

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
3. In **Wireless debugging**, tap **Pair new device**:
   ```sh
   adb pair 192.168.1.50:37123 123456     # pairing port and code from the pairing screen
   adb connect 192.168.1.50:41234         # the port on the main Wireless debugging screen
   ./gradlew installDebug
   ```
4. To inspect pages, open `chrome://inspect/#devices` in desktop Chrome. Debug builds enable `WebView.setWebContentsDebuggingEnabled`.

### Test pages and what to check

| Page | Check |
|---|---|
| A long Wikipedia article (`en.wikipedia.org/wiki/Orbital_mechanics`) | Reader opens automatically, pages turn per bezel click without lag, "n / N" label, Text size keeps the position, reopening restores the page |
| A news site full of ads | Blocked count in the menu, Reader skips the clutter, memory stays flat |
| Hacker News (`news.ycombinator.com`) | Round Scroll with no clipping; link mode steps in reading order; a tap opens the focused link |
| Wide tables or code (`en.wikipedia.org/wiki/Comparison_of_programming_languages`, a GitHub file view) | Tables scroll inside the column; code wraps; Zoom view's double-tap fits a block to the square |
| A login form (`github.com/login`) | Link mode → tap a field → keyboard; the bezel still works afterwards |

Also check on both sizes:
- **Bezel and tap only:** can you read and navigate every page with just the bezel and one tap?
- **Back** goes through history, steps out of the reader cleanly, and at the first page leaves the app.
- **Ambient:** the screen dims, then wakes at the same spot.
- **Tile** shows the last article read; the **complication** opens Orbit; **Open on phone** works.
- **Memory:** `adb logcat -s OrbitMem` over 10 page loads. App PSS excludes the WebView renderer, which runs in its own process, so also watch `sysAvailMb`.

### Known limitations

- **Mid-scroll clipping in Round Scroll.** While scrolling, lines passing the top and bottom bands are partly clipped; the rim vignette fades that band on purpose. Text never clips at rest at the top or bottom of the page. Reader mode has no such band.
- **Positioned elements.** Only shallow fixed and sticky elements go back into the flow, to keep the pass cheap.
- **Pages with JavaScript turned off** (per-site toggle) get neither the round layout nor Reader nor link focus, because all three are injected JS.
- **No live suggestions while typing.** Wear keyboards are full-screen, so suggestions appear when the keyboard closes. Inside the keyboard, recent sites are offered as quick choices.
- **Tabs restore only their current page.** A background tab keeps its page and position, not its back history.

### Phase 2+ (documented, not built): companion phone app

This would be a small Android phone app with a **"Send to watch"** share target. Sharing a URL
from any phone app sends it to the watch over the Wearable **Data Layer API**
(`MessageClient`, path `/orbit/open`). A `WearableListenerService` in Orbit receives it and
opens the page through the existing `ACTION_VIEW` / `EXTRA_OPEN_URL` handling. Both apps need
the same `applicationId` and signing key so the Data Layer pairs them.

### Licences

Readability.js © Arc90 Inc / Mozilla, Apache License 2.0 (`assets/licenses/`). The icons are
Material Symbols paths (Apache 2.0).
