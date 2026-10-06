#!/bin/sh
# Downloads the latest signed uBlock Origin release from addons.mozilla.org next to this script.
set -eu
cd "$(dirname "$0")"
url=$(curl -sSIL -o /dev/null -w '%{url_effective}' https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi)
name=$(basename "$url")
curl -sSL -o "$name" "$url"
sha256sum "$name"
echo "Downloaded $name. Update app/build.gradle.kts and README.md to match."
