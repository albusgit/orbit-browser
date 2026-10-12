# Porter

A small phone app that talks to the watch over ADB (Wireless debugging), using the same IP and
port that `adb connect` uses. It does four things:

- **Keeps the connection up.** "Start port" connects and holds the connection in a foreground
  service with a Wi-Fi lock. A keep-alive runs every 15 s. If the connection drops, Porter
  reconnects with backoff (2 s up to 15 s). It also follows the watch over mDNS when Wireless
  debugging comes back on a new port.
- **Sets the watch's screen timeout** (Off / 5 / 10 / 30 min) when the port starts, and puts the
  watch's own value back when it stops. The original value is saved on the phone, so if Porter is
  killed mid-session, the next Stop still restores it.
- **Installs APKs.** "Install APK" streams the file straight into `pm install` (nothing is left on
  the watch). Pick several to install them in turn.
- **Sends files.** "Send file" copies any file to the watch's `Download` folder (`adb push`).
  You can also share files to Porter from another app.

- **Runs adb commands.** Type in the Commands box as you would after `adb`, with or without the
  `adb` prefix: `shell <cmd>`, `install [-r -d -t -g]` and `push [dir]` (both open the file
  picker), `uninstall`, `reboot`, `logcat`, `devices` and `help`. Anything else runs as a shell
  command on the watch. Output streams live, and Stop ends long-running commands like `logcat`.

Files picked before the port starts wait until it's connected. If the connection drops during a
transfer, Porter retries it once after reconnecting.

## First use

1. On the watch: **Developer options → ADB debugging** and **Wireless debugging** on. The watch
   and the phone need to be on the same Wi-Fi.
2. In Porter, enter the IP and port from the Wireless debugging screen (or tap **Find watch**).
3. Pair once: on the watch, tap **Pair new device**, then enter its pairing port and 6-digit code
   under **Pair this phone**.
4. Tap **Start port**.

## Build

```
./gradlew :porter:assembleRelease   # porter/build/outputs/apk/release/porter-release.apk (~5 MB)
```

The release APK is signed with the debug key so it can be sideloaded as-is. The ADB client is
[libadb-android](https://github.com/MuntashirAkon/libadb-android) (GPL-3.0-or-later OR Apache-2.0),
with Conscrypt for TLS 1.3 and BouncyCastle.

`./gradlew :porter:testDebugUnitTest` runs the unit tests and renders the screen to
`porter/build/screenshots/`.

Not yet tried against a real watch: the build container has no device.
