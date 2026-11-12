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
    }
}

rootProject.name = "YGOSandbox"
include(":app")

// The play engine (rules core + search + Kotlin API), from the engine/ submodule.
include(":ygoe")
project(":ygoe").projectDir = file("engine/android/ygoe")
