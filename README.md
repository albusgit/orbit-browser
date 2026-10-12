# Orbit builds

Signed release APKs of Orbit, built from branch `ccr-4c26f560-wq84ec`.
This branch holds only the files to download; the source lives on the other branch.

| File | Install on | Built from | Notes |
|---|---|---|---|
| `Orbit-0.6.0.apk` | the watch | 70ffec6 | Pages rendered by the phone helper when it's nearby; Gecko starts only when needed (no phone, video/maps/games, Reader). Strict tracking protection, uBlock Origin opt-in, instant bezel scrolling. 32-bit ARM (`armeabi-v7a`); 86 MiB |
| `Orbit-Phone-0.6.0.apk` | the phone (Android 10+) | 70ffec6 | Orbit Phone: renders pages for the watch with EasyList/EasyPrivacy blocking; 1.8 MB |

Install both (same signing key, which is how they find each other):

```sh
adb -s <phone-serial> install -r Orbit-Phone-0.6.0.apk
adb -s <watch-ip>:<port> install -r Orbit-0.6.0.apk
```

Then open **Orbit Phone** on the phone once and tap **Allow in background**.

The signing certificate's SHA-256 is
`50bc17e59427c0b684f871d0be83810dae10b041aa9827c437f59e0b104b512b`, so the watch APK updates
earlier installs in place. Older APKs stay in this branch's history.
