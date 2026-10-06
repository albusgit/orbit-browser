plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.albustech.orbit"
    // GeckoView 157 compiles against Android 16 QPR (API 37.1+).
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "com.albustech.orbit"
        minSdk = 30 // Wear OS 3+; the Watch6 Classic ships Wear OS 4 (API 33)
        targetSdk = 37
        versionCode = 3
        versionName = "0.2.0"
        // GeckoView ships per-ABI; the Watch6 (and every current Wear OS watch) is arm64.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    packaging {
        // Keep Gecko's ~150 MB of native code compressed in the APK: the file is sideloaded over
        // Wi-Fi, so a smaller download wins over skipping extraction at install.
        jniLibs { useLegacyPackaging = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    testOptions {
        unitTests {
            // Screenshot tests (Robolectric + Roborazzi) render real resources.
            isIncludeAndroidResources = true
            all {
                it.systemProperty("roborazzi.test.record", "true")
                // Optional Maven mirror for Robolectric's Android jars (set orbit.mavenMirror in
                // ~/.gradle/gradle.properties where Maven Central is slow or rate-limited).
                providers.gradleProperty("orbit.mavenMirror").orNull?.let { m ->
                    it.systemProperty("robolectric.dependency.repo.url", m)
                }
                it.maxHeapSize = "2g"
                // Robolectric's SDK 36 sandbox touches JDK internals on JDK 17+.
                it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
            }
        }
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
    }
}

/**
 * uBlock Origin ships as the signed XPI from addons.mozilla.org (third_party/ublock). GeckoView
 * loads built-in extensions from an unpacked folder, so the XPI is unzipped into generated
 * assets at build time: assets/extensions/ublock/.
 */
abstract class UnpackXpi : DefaultTask() {
    @get:InputFile
    abstract val xpi: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:javax.inject.Inject
    abstract val files: FileSystemOperations

    @get:javax.inject.Inject
    abstract val archives: ArchiveOperations

    @TaskAction
    fun unpack() {
        files.sync {
            from(archives.zipTree(xpi))
            into(outputDir.dir("extensions/ublock"))
            exclude("META-INF/**") // AMO's signature: not checked for built-in extensions
        }
    }
}

val unpackUblock = tasks.register<UnpackXpi>("unpackUblock") {
    xpi.set(rootProject.layout.projectDirectory.file("third_party/ublock/ublock_origin-1.75.0.xpi"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(unpackUblock, UnpackXpi::outputDir)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
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
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.wear)
    implementation(libs.androidx.wear.remote.interactions)
    implementation(libs.androidx.wear.tiles)
    implementation(libs.androidx.wear.protolayout)
    implementation(libs.androidx.wear.protolayout.expression)
    implementation(libs.androidx.wear.protolayout.material3)
    implementation(libs.androidx.wear.complications.data.source.ktx)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.jsoup)

    testImplementation(libs.junit)
    // Real org.json for plain JVM tests (android.jar only has stubs).
    testImplementation(libs.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
