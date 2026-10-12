pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // GeckoView (Firefox's engine): Mozilla publishes it only here.
        maven("https://maven.mozilla.org/maven2/") {
            content { includeGroup("org.mozilla.geckoview") }
        }
        // libadb-android (Porter's ADB client) and its SPAKE2 pairing library: JitPack only.
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\.MuntashirAkon(\\..*)?") }
        }
    }
}

rootProject.name = "Orbit"
include(":app")
include(":porter")
