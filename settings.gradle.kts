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
        // NewPipe Extractor (YouTube search and streams for the Combos tab) is published on JitPack.
        maven("https://jitpack.io")
    }
}

rootProject.name = "YGOSandbox"

// The Windows build (YGO_DESKTOP_ONLY=1) leaves out the Android modules: it needs no Android SDK.
if (System.getenv("YGO_DESKTOP_ONLY") == null) {
    include(":app")

    // The play engine (rules core + search + Kotlin API), from the engine/ submodule.
    include(":ygoe")
    project(":ygoe").projectDir = file("engine/android/ygoe")
}

// The Windows app, and the engine's Kotlin API for desktop JVM apps.
include(":desktop")
include(":ygoe-jvm")
project(":ygoe-jvm").projectDir = file("engine/android/ygoe-jvm")
