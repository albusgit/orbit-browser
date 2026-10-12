plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Orbit for Claude: claude.ai on the watch, signed in with your own account, inside the same
 * GeckoView engine as Orbit. A separate app (its own APK, cookies and profile), not a mode of
 * the browser. See README.md, "Orbit for Claude".
 */
android {
    namespace = "com.albustech.orbit.claude"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "com.albustech.orbit.claude"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        // Same reason as Orbit: Galaxy Watches run a 32-bit-only userspace.
        ndk { abiFilters += listOf("armeabi-v7a") }
    }

    packaging {
        // As in Orbit (app/build.gradle.kts): Gecko compressed, no crash-report helpers.
        jniLibs {
            useLegacyPackaging = true
            excludes += listOf("**/libcrashhelper.so", "**/libcrashtools.so")
        }
        dex { useLegacyPackaging = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.wear.compose.foundation)

    implementation(libs.geckoview)
    implementation(libs.androidx.wear.input)
    implementation(libs.androidx.wear.remote.interactions)

    testImplementation(libs.junit)
}
