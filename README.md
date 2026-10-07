# Orbit builds

Signed release APKs of Orbit for Wear OS, built from branch `ccr-4c26f560-wq84ec`.
This branch holds only the files to download; the source lives on the other branch.

| File | Built from | Notes |
|---|---|---|
| `Orbit-0.2.1.apk` | f050466 | GeckoView 157 engine with uBlock Origin 1.75.0; arm64 only; 88 MiB |

Install with Wear Installer 2 or `adb install -r Orbit-0.2.1.apk`. The signing certificate's
SHA-256 is `50bc17e59427c0b684f871d0be83810dae10b041aa9827c437f59e0b104b512b`, so it updates
earlier installs in place. Older APKs stay in this branch's history.
