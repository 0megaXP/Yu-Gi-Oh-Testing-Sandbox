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

    // Card pictures, cached on the phone after the first view.
    implementation("io.coil-kt:coil-compose:2.7.0")
}
