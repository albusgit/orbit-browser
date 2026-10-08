# Orbit builds

Signed release APKs of Orbit for Wear OS, built from branch `ccr-4c26f560-wq84ec`.
This branch holds only the files to download; the source lives on the other branch.

| File | Built from | Notes |
|---|---|---|
| `Orbit-0.3.0.apk` | d7b3f8e | Mobile sites by default (Desktop sites in Settings), Cursor mode, hold for the ring. GeckoView 157 with uBlock Origin 1.75.0; 32-bit ARM (`armeabi-v7a`), which Galaxy Watches need; 85 MiB |

Install with Wear Installer 2 or `adb install -r Orbit-0.3.0.apk`. The signing certificate's
SHA-256 is `50bc17e59427c0b684f871d0be83810dae10b041aa9827c437f59e0b104b512b`, so it updates
earlier installs in place. Older APKs stay in this branch's history.
