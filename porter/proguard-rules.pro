# libadb finds Conscrypt by reflection (TLS 1.3 and exportKeyingMaterial for pairing).
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**
# The SPAKE2 pairing library calls into native code.
-keep class io.github.muntashirakon.crypto.** { *; }
-keep class io.github.muntashirakon.adb.** { *; }
# BouncyCastle is only used directly (pairing's HKDF/AES-GCM, the certificate builder), not as a
# JCA provider, so R8 can trim it.
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
