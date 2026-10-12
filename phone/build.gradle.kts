plugins {
    alias(libs.plugins.android.application)
}

/**
 * Orbit Phone: the helper that renders pages for the watch. Same applicationId and signing key
 * as the watch app, which is what lets the two talk over the Wear Data Layer (and nothing else).
 */
android {
    namespace = "com.albustech.orbit.phone"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "com.albustech.orbit"
        minSdk = 29 // Android 10+: any phone a Wear OS 4 watch pairs with
        targetSdk = 37
        versionCode = 9
        versionName = "0.6.0"
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

    lint {
        warningsAsErrors = false
        abortOnError = true
    }
}

/**
 * The page scripts the watch's extension runs (search results, Readability) run on the phone
 * too, unchanged: copied from the watch app at build time so there is one copy to maintain.
 */
abstract class CopyPageScripts : DefaultTask() {
    @get:InputDirectory
    abstract val source: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:javax.inject.Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun copy() {
        files.sync {
            from(source) { include("serp.js", "extract.js", "Readability.js", "Readability-readerable.js") }
            into(outputDir.dir("page"))
        }
    }
}

val copyPageScripts = tasks.register<CopyPageScripts>("copyPageScripts") {
    source.set(rootProject.layout.projectDirectory.dir("app/src/main/assets/extensions/orbit"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyPageScripts, CopyPageScripts::outputDir)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)
}
