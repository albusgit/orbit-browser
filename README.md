# Orbit

A web browser for round watch screens, built for the Samsung Galaxy Watch6 Classic
(43mm 432×432 and 47mm 480×480, rotating bezel) on Wear OS 4 and later.

Other Wear OS browsers show a rectangular phone page through a round hole. Orbit treats the
circle as the layout surface. Text stays inside the circle, the chrome follows the curved
edge, and the bezel is the main control.

**Engine: GeckoView** (Firefox's engine, bundled), with the **real uBlock Origin** built in.
Wear OS ships no WebView: the Watch6 Classic has none, and the Play Store won't install it on
watches. So Orbit brings its own engine, which makes it run on any Wear OS watch. See
[The engine](#the-engine-geckoview).

**Status: all four phases are built, plus the round-native redesign and the GeckoView engine.**
The app builds (debug and minified release). Unit tests, lint, the Robolectric screenshot
renders, the extension's unit tests and the headless Chromium checks of its page scripts all
pass. It has **not yet run on an emulator or a watch**, because the build container has no KVM.
In particular, Gecko-specific behaviour is unverified until it runs on the watch. See
[Testing on a device](#testing-on-a-device).

---

## What it does

### Four ways to draw a page (remembered per site)

| Mode | When | How |
|---|---|---|
| **Mobile** | The default (site mode *Mobile*) | The site's own **mobile version**, exactly as a phone would show it: Gecko's mobile user agent and viewport, nothing restyled. Pinch zooms. With **Settings → Desktop sites** on, sites get their desktop version instead (see *Desktop*). |
| **Paged Reader** | Articles (site mode *Mobile*), or forced with *Reader* | Mozilla Readability extracts the article, then Orbit sets it as **circular pages**: two floats whose `shape-outside` polygons cover everything outside the circle, so lines are short at the top and bottom and full width in the middle. Each bezel click turns one page, with a dial-swing animation and a haptic tick. Images get their own page, scaled into the inscribed square. Tables and code get a page whose square scrolls. A progress arc runs around the rim, and "4 / 12" appears for a moment at the bottom. Pure `#000` background. |
| **Round fit** | Opt-in, with *Round fit* | The content column is capped to the inscribed square (d/√2) and padded top and bottom by the circle-to-square gap. The viewport is forced to `device-width`. Fixed headers and cookie bars go back into the flow. Only tables too wide for the column get a horizontal scroll box. Code wraps. A radial vignette fades the rim on purpose. A scroll arc sits on the right edge. |
| **Desktop** | *Desktop*, or every site with **Settings → Desktop sites** on | The site's desktop version (desktop user agent and width) in overview. Pinch zooms, or pick *Zoom* on the bezel arc to zoom with the bezel. Double-tap zooms the tapped block. |

Round fit restyles the page to fit the circle. It reads well on simple pages but breaks some
sites, so it is no longer the default.

Switch with **More → View** (Mobile → Reader → Round fit → Desktop). The choice is saved for the site
and wins over the Desktop sites setting.

### Search results as native cards

Orbit recognises results pages from **Google, DuckDuckGo and Bing** (web results only, not
images or news). It never shows these pages as web pages:

- **While loading**, a curved query and "Searching…" appear at once. The page itself stays hidden, and images on results pages are not fetched (less data over Bluetooth).
- **`serp.js` reads the organic results** in page order: title, site and snippet, plus Google's featured answer when one is clearly marked. Ads, "People also ask", and links back into the search engine are left out.
- **Tracking redirects are removed** (Google `/url?q=`, DuckDuckGo `/l/?uddg=`, Bing `/ck/a`), so opening a result goes straight to the site, one hop shorter.
- **One result per screen.**
  - The top shows "2 of 10 · query" on a curve, with a letter avatar and the site name. No favicons are fetched.
  - Below that come the title in the accent colour, the snippet, and a large **Open** button.
  - Each bezel click snaps to the next card. Dots on the left rim show the position.
  - The last card loads the next page of results.
- **Overview**: turn back past the first result, or pinch, to get a list whose rows follow the circle. The query pill at the top is for editing the search, and its mic button starts a new voice search.
- **Back** from a result returns to the same card.
- **Fallback**: if a page yields no results (a CAPTCHA, a consent page, a new layout), Orbit shows the real page. Nothing gets stuck.

DuckDuckGo HTML stays the default engine. Google can't be tested live from the build machine
(it serves a CAPTCHA there), so its extractor is checked against saved pages. Bing is also
checked live.

### Round-native controls

The look: black, one blue accent (`#0a84ff`), quiet greys and the Geist font, all set once in
`ui/Theme.kt`. Floating chrome (the top pill, the mode bar, counters) is dark glass that reads on
any page. Lists follow the circle, and rows dim and blur as they move away from the centre.

- **Launcher** (the home screen):
  - The mic fills the centre.
  - Up to six actions orbit it: keyboard, continue reading, three bookmarks (as letter avatars), and More.
  - The bezel moves a highlight ring around them, and the highlighted name curves along the bottom edge.
- **On a page**, a **tap** (anywhere that isn't a link) brings back the chrome: a pill with the time and the site at the top, and the bezel-mode bar at the bottom. A **hold** opens the ring menu.
- **Ring menu.** Hold anywhere on a page to open it. Eight round buttons over the dimmed page: Search, Forward, Reload, Bookmark, Tabs, History, More, Back.
  - The bezel moves the highlight and a centre tap confirms; you can also tap a button directly.
  - Unavailable items are dimmed, never removed, so positions stay put.
  - **More** is a second ring: View, Text size, This site, Open on phone, Bookmarks, Settings, Home, Close. Its centre shows the connection and the blocked count.
- **Link options.** Long-press a link (or use *Hold for options* in link mode): a short list with Open, Open on phone, Copy link and Bookmark.
- **Lists** (bookmarks, history, tabs, settings) use rows that follow the circle's chord.

### The bezel

What the bezel does is shown as **four tappable segments along the bottom edge** whenever the
chrome is up: *Pages · Links · Cursor · Text* in Reader, *Scroll · Links · Cursor · Zoom*
elsewhere. Tap the page to bring them back, then tap one. Every page starts in *Scroll*.
**Pinch zooms in every mode.**

1. **Scroll**: smooth steps with momentum (a fast spin flings); **page turns** in Reader.
2. **Links**: steps through the visible links and controls in reading order, with a glowing ring around the focused one.
   - "Link 3 of 9" curves along the top, and a pill at the bottom says **Open link · Hold for options**.
   - Only the pill opens the link. A tap elsewhere just shows the chrome, so nothing opens by accident.
   - Past the last visible link the page scrolls (or turns) and focus continues. Back leaves link mode.
3. **Cursor**: the screen becomes a trackpad. Drag anywhere to move a soft grey pointer (it moves 1.6× the finger, so the finger never hides the target), and **tap to click** under it. Pushing the cursor past the top or bottom of the circle scrolls the page. The bezel still scrolls, a hold still opens the ring, and two fingers still pinch. Moving the cursor brings back the mode bar.
4. **Zoom**: zooms the page with the bezel. In Reader it changes the text size and re-paginates on the same word.

Every bezel click gives a haptic tick. The end of a page or article gives a firmer bump.

### The rest

- **Curved UI.**
  - Time and the page title curve along the top edge, and a loading ring runs around the whole edge.
  - In Reader, a progress arc runs around the rim. Elsewhere, a short scroll arc sits at the upper right, clear of your thumb.
  - It's immersive: everything hides while you scroll.
- **Entering addresses.**
  - Voice is the primary method ("wikipedia dot org" works). The RemoteInput keyboard is the backup and shows recent sites as quick choices.
  - Matches from bookmarks and history are listed under "Go to"/"Search". When nothing matches, the page opens directly.
  - Search defaults to DuckDuckGo HTML. You can switch to DuckDuckGo Lite, Google or Bing.
- **Per site (This site):**
  - view mode, block images, and JavaScript on/off;
  - a *Lite version* toggle: an Opera Mini user agent that many sites answer with basic HTML;
  - **Rich graphics**: WebGL, off unless you turn it on here.
- **Reading positions** are saved for every URL. Reader stores a word anchor that survives re-pagination; scroll pages store a fraction. Both are restored on reopen.
- **Typography:** size (11–26 px), line height, serif or sans, and hyphenation in the page's language.
- **Tabs:** up to 3, kept as URL, title, snapshot and position, never as live sessions.
- **Ambient mode:** a dim black screen with the title and page number; waking restores the page instantly at the same spot.
- **Keep screen on while reading** (setting): each bezel turn buys another 2 minutes.
- **Open on phone** uses `RemoteActivityHelper` and Wear's confirmation animation. `mailto:`, `tel:` and downloads are handed to the phone too.
- **Tile:** the last page read (with its page number) plus 3 bookmarks, each one tap away.
- **Complication:** opens Orbit from the watch face.

### Battery and memory (2 GB RAM)

- One live GeckoSession only. `onStop` and ambient make it inactive, which stops painting and throttles the page's timers.
- If the system kills the content process for memory, Orbit rebuilds with a fresh session and reopens the page. If the same page kills it again, it lands on the home screen instead.
- **uBlock Origin blocks ads and trackers** (on by default). See [Content blocking](#content-blocking-ublock-origin).
- **Safe Browsing is off** (`ContentBlocking.SafeBrowsing.NONE`): no lookups per navigation. Firefox's own tracking protection stays at its standard level.
- **WebGL is off by default.** A content script in every frame refuses WebGL contexts at document start, and pages fall back to 2D. That keeps the GPU busy less and removes a fingerprinting surface. Turn it on per site with *This site → Rich graphics* (maps, 3D).
- Media autoplay is off, and media pauses when the session is inactive. Reader images load lazily (the page about to be shown is preloaded).
- Gecko's mobile user agent. The per-site *Lite version* switch sends Opera Mini's instead.
- AMOLED black: pages are asked for their dark theme (`prefers-color-scheme: dark`), the view stays black until the first paint, and Reader is `#000`.
- A stall watchdog gives Bluetooth-via-phone connections 45 s (others 25 s) before showing a clear, connection-specific error ("Too slow over Bluetooth: connect the watch to Wi-Fi, or try Reader view").
- `adb logcat -s OrbitMem` logs PSS, heap and system free memory after every page load (debug builds only). `adb logcat -s OrbitGecko` logs the engine and extension start-up.
- Settings live in SharedPreferences, not DataStore: one dependency and its native library fewer.
- `profileinstaller` installs the baseline profiles that ship with Compose, so the UI starts faster.
- Scrolling and loading progress are read inside the draw phase, so they redraw one canvas without recomposing the screen.

### The engine: GeckoView

Wear OS doesn't include a WebView, so Orbit bundles **GeckoView** (Firefox's engine, MPL 2.0,
release channel 157, 32-bit ARM only: Galaxy Watches run a 32-bit userspace on their 64-bit
CPUs, so an arm64 build doesn't install). The UI above it is unchanged. Orbit's page scripts run
through a small built-in extension, `assets/extensions/orbit/`:

```
app (BrowserController, OrbitExtension) ⇄ native port "orbit" ⇄ background.js ⇄ pages
```

- **`background.js`** is the hub, kept stateless apart from a few settings. It:
  - registers the Round fit layout (when a site uses it) and the WebGL guard as document-start content scripts;
  - injects Readability, `serp.js` and `links.js` on request (`tabs.executeScript`, main frame only);
  - relays page messages to the app and app commands to pages, one at a time, in order;
  - blocks images for the per-site *Block images* setting and on search-results pages;
  - holds the last few reader payloads.
- **`bridge.js`** runs at document start in every web page. It gives Orbit's scripts their `orbitBridge` and reports the scroll position (≤10 times a second). It also reports whether a tap hit a link or control, since Gecko has no synchronous hit test: a tap on nothing shows Orbit's chrome, a hold on nothing opens the ring (and clears any word selection the hold started).
- **Isolated from pages.** Everything runs in the extension's sandbox, so pages can neither see Orbit's scripts nor post messages as them. With WebView they could.
- **The reader is an extension page** (`moz-extension://…/reader.html#u=<article>`). The app sanitises the article with jsoup and hands it to `background.js`; the page asks for it by token. The extension's CSP stops anything in the article from running. Back from it skips the article's source page as before. Opened from history after a restart, it fetches the article again.
- **Scrolling and zoom** go through Gecko's own async pan/zoom:
  - each bezel detent asks for a smooth scroll of one step, and a fast spin becomes one long smooth scroll;
  - in *Zoom* bezel mode the bezel sends a short pinch, and Cursor mode clicks with a synthetic tap;
  - a double tap uses Gecko's own block zoom.
- **Long press on a link** comes from Gecko's context-menu callback. Alerts, confirms and `<select>` menus use system dialogs.

**Size:** the release APK is 85 MiB, almost all of it Gecko's native code. Native libraries and
the dex stay compressed in the APK, which makes the Wi-Fi sideload faster; they are unpacked at
install. Gecko's crash-reporting libraries are left out, since Orbit sets no crash handler and so
never starts them. So are uBlock's dashboard-only fonts and editor.

### Content blocking: uBlock Origin

Orbit installs the unmodified, Mozilla-signed **uBlock Origin 1.75.0** as a built-in extension
(`third_party/ublock/`, unpacked into the APK at build time). That gives you uBlock's real
engine:
- its default lists (uBlock filters, EasyList, EasyPrivacy, Peter Lowe's list, URLhaus);
- network blocking, element hiding, procedural filters and scriptlets.

- **Lists:** bundled copies work offline from the first start. uBlock then updates them in the background every few days, a few MB each time.
- **The toggle:** *Settings → Block ads and trackers* enables or disables uBlock Origin.
- **Blocked count:** the count in the More ring is uBlock's own badge for the page.
- **uBlock's UI** (popup and dashboard) isn't shown on the watch, so its defaults apply.
- **Cost:** uBlock keeps its filters in memory in Gecko's extension process, roughly tens of MB. That's the price of its full engine; Orbit's own EasyList engine, which used about 4 MB, is gone.
- **Updating uBlock:** run `third_party/ublock/update.sh`, then change the file name in `app/build.gradle.kts`.

---

## Architecture

There is one Gradle module, `:app` (package `com.albustech.orbit`):

```
app/src/main/
  assets/extensions/orbit/              Orbit's built-in GeckoView extension
    manifest.json, background.js        the hub: app port, content scripts, injection, image blocking
    bridge.js                           page side: orbitBridge, scroll metrics, taps, commands
    webgl.js                            WebGL guard (exportFunction into the page)
    round.css, round.js                 Round fit (registered at document start)
    Readability.js, Readability-readerable.js   Mozilla Readability 0.6.0 (Apache 2.0)
    extract.js                          runs Readability, sanitises the article (allow-list), posts it
    reader.html, reader-boot.js,
    reader.css, reader.js               reader extension page + circular pagination engine
    links.js                            link-focus mode
    serp.js                             reads Google / DuckDuckGo / Bing results for the card view
  (generated) assets/extensions/ublock/ uBlock Origin, unpacked from third_party/ublock at build
  java/com/albustech/orbit/
    OrbitApp, MainActivity              singletons; lifecycle, ambient, keep-on, phone hand-off
    browser/   BrowserController        the GeckoSession and everything in it; state for Compose
               OrbitRuntime             the GeckoRuntime, built-in extensions, uBlock on/off
               OrbitExtension           the native port to background.js
               GeckoHistory, Prompts    Back over Gecko's history; alert/confirm/select dialogs
               Serp                     results-page detection, redirect decoding, next page
               TabManager, Domains, Connection, PageError, UserAgents, UrlResolver, MemoryLog
    reader/    ReaderTemplate           the reader payload and page URL (pure Kotlin, unit-tested)
               ArticleSanitizer         app-side jsoup allow-list pass over the article
    input/     BezelInput               rotary + touch gestures → detents, taps, long-presses
               GeckoScroller            smooth scroll and pinch zoom through Gecko's pan/zoom
               DetentAccumulator, MomentumTracker, Haptics
    ui/        BrowserScreen            root: GeckoView + overlays + curved chrome
               Launcher, Ring (ring menus), SerpScreens, PageChrome
               (bezel-mode bar, link-mode pill), Avatar, UrlEntryScreen, ListScreens,
               SettingsScreens, AmbientScreen, EdgeOverlay, Curved, RoundGeometry, Common
    data/      SettingsRepository (SharedPreferences), BrowserRepository + db/ (Room:
               bookmarks, history, per-site settings, reading positions, tabs), Suggestions
    tile/      OrbitTileService (ProtoLayout Material3)
    complication/ LaunchComplicationService
app/src/test/   JVM unit tests (geometry, ring hit-testing, URLs, results pages, reader payload,
                Gecko history and error mapping, Room migration, …) and Robolectric screenshots
tools/round-check/  headless-Chromium checks of the page scripts + the extension's unit tests
third_party/ublock/ uBlock Origin XPI (signed, from addons.mozilla.org), README, update script
```

### How the reader is wired

1. A page loads (Mobile, or whichever view the site uses).
2. If the site is *Auto* or *Reader*, the extension injects Readability and `extract.js` into the page's sandbox. The article is sanitised against an allow-list in an inert document and posted to the app.
3. The app sanitises the article again with a jsoup allow-list (`ArticleSanitizer`), outside the page's reach.
4. `ReaderTemplate` builds the payload (header, content, layout config). The app hands it to `background.js` and loads `moz-extension://…/reader.html#u=<article>&t=<token>`, which asks for it by token. The extension's CSP allows no inline script.
5. History becomes `[… A, A-reader]`. Back from the reader skips every entry of the same article behind it, which would only reopen the reader.
6. Messages from pages are treated as hints and checked against the controller's own state. They are never commands.

### Key decisions

| Area | Choice | Why |
|---|---|---|
| Geometry | `RoundGeometry` from `WindowMetrics` and density at runtime | No hardcoded pixels; works on 43mm, 47mm and anything else |
| Engine | GeckoView, bundled | Wear OS has no WebView; works on any watch; runs the real uBlock Origin |
| Injection | A built-in extension: document-start content scripts for the round layout and WebGL guard; `tabs.executeScript` on demand for Readability, links and results | Layout before first paint; ordinary pages don't parse 90 KB of Readability; pages can't touch Orbit's scripts |
| Pagination | Pages rather than a scrolling circular column | `shape-outside` shapes text relative to the document, not the viewport |
| Text split | Binary search on word count, using DOM `Range` cloning | Keeps inline markup across page breaks; O(log n) layouts per paragraph |
| Positions | Anchor = (block, word) | Survives font and size changes |
| Bezel | Compose `onRotaryScrollEvent` on the focused root, plus a GeckoView listener when a field has focus | One handler per event |
| Haptics | `SEGMENT_FREQUENT_TICK` (API 34+) / `CLOCK_TICK`, `REJECT` at edges | **Horologist is not used**: its latest release (0.7.15) still pins Wear Compose 1.5/M2, and the rotary haptics are now in Wear Compose |
| Tabs | State + snapshot, not live sessions | 2 GB RAM |

### Versions (stable releases, checked 2026-10-06)

| | |
|---|---|
| AGP / Gradle / Kotlin / KSP | 9.4.1 / 9.8.0 / 2.4.20 / 2.3.12 |
| compileSdk / targetSdk / minSdk | 37 / 37 / 30 |
| Compose BOM / Wear Compose Material 3 | 2026.09.00 / 1.7.0 |
| androidx.webkit | 1.17.1 (1.18 is still alpha) |
| wear / wear-input / wear-remote-interactions | 1.4.0 / 1.2.0 / 1.2.0 |
| Tiles / ProtoLayout Material3 / complications | 1.6.2 / 1.4.2 / 1.3.0 |
| Room / coroutines / jsoup / profileinstaller | 2.8.5 / 1.11.0 / 1.23.2 / 1.4.1 |
| Robolectric / Roborazzi (tests only) | 4.17 / 1.76.0 |

### Device assumptions I couldn't confirm

1. **Density.** I couldn't confirm the Watch6 Classic's density (probably about 2.0). Orbit reads it at runtime; the checks cover 1.75, 2.0 and 2.25.
2. **Bezel events.** I expect one `ACTION_SCROLL` per click. Debug builds log every delta (`adb logcat -s OrbitBezel`) so the detent threshold (`DetentAccumulator`) can be calibrated.
3. **GeckoView on Wear OS.** GeckoView targets phones. I'm assuming its GPU path (WebRender on GLES 3) and its content processes behave on the Watch6's Exynos W930. These parts are untested here:
   - the extension APIs Orbit relies on (`tabs.executeScript`, `contentScripts.register`, `exportFunction`);
   - the feel of Gecko's pan/zoom under the bezel;
   - the synthetic pinch zoom.

   `adb logcat -s OrbitGecko` shows the start-up of the runtime and both extensions.
4. **Back button.** I'm assuming the lower hardware key arrives as a normal back event.
5. **Bluetooth.** I'm assuming the Bluetooth-proxied network reports `TRANSPORT_BLUETOOTH`.

---

## Building

You need JDK 17 or later and an Android SDK with `platforms;android-37.2`, because GeckoView 157 compiles against API 37.1 or later. Gradle fetches GeckoView from `maven.mozilla.org`.

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
./gradlew assembleRelease        # minified (R8); sign it with your own key
```

The release APK is **85 MiB** (`armeabi-v7a` only). Almost all of that is Gecko:
- `libxul.so` is 61 MB compressed (117 MB on disk). It is already stripped, and APKs only allow deflate.
- `omni.ja` is 15 MB.
- The other Gecko libraries are about 6 MB.

Orbit's compressed dex is about 3 MB and uBlock Origin about 3 MB. To get here from 95 MiB in
0.2.0 (arm64), the dex is now compressed, and the unused crash-reporting libraries and uBlock's
dashboard-only files are left out. A smaller build would need a custom Gecko build. The
WebView build was 5 MB, but this watch can't run it.

### Screenshots without a device

`ScreenshotTest` renders the round-native screens on the JVM with Robolectric's native
graphics and Roborazzi, at 480 and 432 px, clipped to the circle:

```sh
./gradlew testDebugUnitTest --tests '*Screenshot4*'   # → app/build/screenshots/<screen>_<size>.png
```

It covers the launcher, the ring menu, link options, a result card, the results overview,
results loading, the page chrome with the bezel-mode bar, and link mode. The images are for
review, not pixel comparison.

### Headless checks (`tools/round-check`)

```sh
cd tools/round-check && npm install
node check.mjs          # Round fit: every word inside the inscribed square, top/bottom lines clear
node reader-check.mjs   # Reader: every word inside the circle, images in the square, nothing lost,
                        #   position kept across re-pagination, timing with CPU throttled 6×
node links-check.mjs    # Link focus: reading order, scroll/page continuation, back, activate
node serp-check.mjs     # Search results: Google/DDG/Bing fixtures + live Bing (--no-live to skip)
node --test extension.test.mjs   # the extension's background.js and bridge.js, with a fake browser API
```

They run the same page scripts the extension injects, in Chromium. The checks cover script
logic and layout; GeckoView itself only runs on the watch. They run at 432 and 480 px and
three densities, and write screenshots with a circle mask to `./out`. Latest results:

- 18/18 Round fit cases.
- 12/12 reader cases. With the CPU throttled 6×, the first page shows in about 0.3–0.5 s and a page turn takes one or two frames.
- 4/4 link-focus cases.
- Search results:
  - Google, DuckDuckGo and Bing fixtures come out in order, with ads and "People also ask" excluded and redirects decoded.
  - The CAPTCHA page yields nothing, so Orbit falls back to showing it.
  - Live Bing works.
- 7/7 extension unit tests: message routing, in-order commands, document-start registration (round layout, WebGL guard with per-site exclusions), injection order, reader payloads, image blocking, and `bridge.js` taps and metrics.

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
4. To inspect pages, use desktop Firefox's `about:debugging` with ADB. Debug builds turn on Gecko's remote debugging.

### Test pages and what to check

| Page | Check |
|---|---|
| A long Wikipedia article (`en.wikipedia.org/wiki/Orbital_mechanics`) | Reader opens automatically, pages turn per bezel click without lag, "n / N" label, Text size keeps the position, reopening restores the page |
| A Google, DuckDuckGo and Bing search | Result cards appear (not the page); the bezel snaps card to card; Open goes to the site directly; Back returns to the same card. **Google can't be checked live from the build machine**, so check it here first. |
| A news site full of ads | Blocked count in the More ring, ad slots hidden rather than left blank, Reader skips the clutter, memory stays flat |
| Hacker News (`news.ycombinator.com`) | Mobile version renders; in Round fit, no clipping; link mode steps in reading order; the *Open link* pill opens the focused link |
| A WebGL map (`maps.google.com` or similar) | Falls back to 2D or a message; turning on *This site → Rich graphics* brings WebGL back |
| Wide tables or code (`en.wikipedia.org/wiki/Comparison_of_programming_languages`, a GitHub file view) | Tables scroll inside the column; code wraps; Zoom view's double-tap fits a block to the square |
| A login form (`github.com/login`) | Link mode → tap a field → keyboard; the bezel still works afterwards |

Also check on both sizes:
- **Bezel and tap only:** can you read and navigate every page with just the bezel and one tap?
- **Back** goes through history, steps out of the reader cleanly, and at the first page leaves the app.
- **Ambient:** the screen dims, then wakes at the same spot.
- **Tile** shows the last article read; the **complication** opens Orbit; **Open on phone** works.
- **Memory:** `adb logcat -s OrbitMem` over 10 page loads. App PSS excludes Gecko's content and extension processes, so also watch `sysAvailMb`.

### Known limitations

- **Mid-scroll clipping in Round fit.** While scrolling, lines passing the top and bottom bands are partly clipped; the rim vignette fades that band on purpose. Text never clips at rest at the top or bottom of the page. Reader mode has no such band.
- **Positioned elements.** Only shallow fixed and sticky elements go back into the flow, to keep the pass cheap.
- **Pages with JavaScript turned off** (per-site toggle) get neither the round layout nor Reader nor link focus, because all three are injected JS.
- **No live suggestions while typing.** Wear keyboards are full-screen, so suggestions appear when the keyboard closes. Inside the keyboard, recent sites are offered as quick choices.
- **Tabs restore only their current page.** A background tab keeps its page and position, not its back history.
- **Search results depend on the engines' markup.** The extractors anchor on long-stable structure (Google's `#rso` with `h3` titles, DuckDuckGo's `.result`, Bing's `.b_algo`). When that changes, Orbit falls back to showing the page until `serp.js` is updated.
- **Forced dark pages are gone.** WebView could darken any page; Gecko can't. Sites with a dark theme show it, and others stay light (Reader is always black).
- **Gecko's double-tap zoom** fits a block to the screen width, not the inscribed square as the WebView build did.
- **Prompts:** `alert`, `confirm` and `<select>` work. Text prompts, logins, file pickers and date/colour pickers are dismissed.
- **Extension pages:** the reader is a `moz-extension:` page, so its URL is not the article's. Orbit shows and bookmarks the article's URL.
- **APK size:** 85 MiB, the cost of bringing an engine (see above). Install takes a few minutes on the watch.

### Phase 2+ (documented, not built): companion phone app

This would be a small Android phone app with a **"Send to watch"** share target. Sharing a URL
from any phone app sends it to the watch over the Wearable **Data Layer API**
(`MessageClient`, path `/orbit/open`). A `WearableListenerService` in Orbit receives it and
opens the page through the existing `ACTION_VIEW` / `EXTRA_OPEN_URL` handling. Both apps need
the same `applicationId` and signing key so the Data Layer pairs them.

### Licences

Readability.js © Arc90 Inc / Mozilla, Apache License 2.0 (`assets/licenses/`). The icons are
Material Symbols paths (Apache 2.0).

GeckoView © Mozilla, Mozilla Public License 2.0 (https://mozilla.org/MPL/2.0/), used unmodified
from maven.mozilla.org.

uBlock Origin © Raymond Hill and contributors, GNU GPLv3. It is bundled unmodified as a
separate program (its signed XPI). Its source for the bundled version is at
https://github.com/gorhill/uBlock/tree/1.75.0 (see `third_party/ublock/README.md`). Its bundled
filter lists keep their own licences, such as EasyList's GPLv3 / CC BY-SA 3.0.

Geist © The Geist Project Authors, SIL Open Font License 1.1 (`third_party/geist/OFL.txt`).

The app's About line credits all of them.
