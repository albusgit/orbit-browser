# uBlock Origin (bundled)

`ublock_origin-1.75.0.xpi` is the unmodified build of uBlock Origin 1.75.0, signed by Mozilla and
downloaded from addons.mozilla.org. Orbit unpacks it at build time (`unpackUblock` in
`app/build.gradle.kts`) and installs it in GeckoView as a built-in extension.

- Author: Raymond Hill and contributors, https://github.com/gorhill/uBlock
- Licence: GNU GPLv3. The source for this exact version is at
  https://github.com/gorhill/uBlock/tree/1.75.0
- SHA-256: 5b74415860456370644bd80f16125e865b0e6c356bb5dfcfb84069967eaa5287

To update, run `./update.sh`, then change the file name in `app/build.gradle.kts` and the version
and checksum above.
