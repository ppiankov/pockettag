plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.ppiankov.pockettag"
    compileSdk = 35
    // Pinned so the build uses the installed build-tools instead of downloading AGP's default.
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "dev.ppiankov.pockettag"
        // HostApduService exists since API 19; 24 keeps the framework APIs used here simple.
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Test-only; the shipped APK has no third-party dependencies beyond the Kotlin stdlib.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
