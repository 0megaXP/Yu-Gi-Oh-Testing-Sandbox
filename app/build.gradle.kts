plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.ygosandbox.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ygosandbox.app"
        // The engine ships native code for phones (arm64) and the emulator (x86_64).
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        minSdk = 26
        targetSdk = 35
        // Every GitHub build gets the next version: 0.0.1, 0.0.2, ...
        val build = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0)
        versionCode = build.coerceAtLeast(1)
        versionName = "0.0.$build"
    }

    // A fixed key kept in the repo, so every build can install over the previous one.
    signingConfigs {
        create("ygosandbox") {
            storeFile = file("ygosandbox.jks")
            storePassword = "ygosandbox"
            keyAlias = "ygosandbox"
            keyPassword = "ygosandbox"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("ygosandbox")
        }
    }

    compileOptions {
        // NewPipe Extractor uses java.nio APIs that need desugaring below Android 13.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Screens and logic shared with the Windows app (desktop/); Android-only code stays in src/main/java.
    sourceSets["main"].java.srcDir("../shared/src/main/kotlin")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Play engine: best lines, end boards and side plans (engine/ submodule).
    implementation(project(":ygoe"))

    // Combos tab: YouTube search and video streams on the phone (GPL-3.0, compatible with the engine's AGPL).
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.5")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    // Free combo reading: on-device text recognition (model bundled in the app, works offline).
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Card pictures, cached after the first view (Coil 3 also runs on the desktop).
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")

    // Streaming JSON for the card database (Android's JsonReader isn't on the desktop).
    implementation("com.google.code.gson:gson:2.11.0")
}
