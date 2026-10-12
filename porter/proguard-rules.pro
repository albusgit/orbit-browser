# libadb finds Conscrypt by reflection (TLS 1.3 and exportKeyingMaterial for pairing).
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**
# The SPAKE2 pairing library calls into native code.
-keep class io.github.muntashirakon.crypto.** { *; }
-keep class io.github.muntashirakon.adb.** { *; }
# Keep BouncyCastle's crypto (pairing's HKDF and AES-GCM) and certificate classes whole: parts of
# it are looked up by name, and a pairing that breaks only in release builds is hard to find.
-keep class org.bouncycastle.crypto.** { *; }
-keep class org.bouncycastle.util.** { *; }
-keep class org.bouncycastle.asn1.** { *; }
-keep class org.bouncycastle.cert.** { *; }
-keep class org.bouncycastle.operator.** { *; }
-dontwarn org.bouncycastle.**
# Shrink, but keep names: stack traces in the log stay readable.
-dontobfuscate
-dontwarn javax.naming.**
