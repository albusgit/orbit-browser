# Orbit keeps no reflection-based APIs yet. JS bridges (Phase 2+) will need
# -keepclassmembers rules for @JavascriptInterface methods.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
