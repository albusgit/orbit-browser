plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Plain JVM code both apps share, unit-tested without Android: search-results parsing, the
// EasyList engine (phone) and the watch ↔ phone protocol.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Android has org.json built in; plain JVM tests need the real one.
    compileOnly(libs.json)
    testImplementation(libs.json)
    testImplementation(libs.junit)
}
