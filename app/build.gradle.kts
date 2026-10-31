plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: the build workflow writes your upload key to app/upload.jks from GitHub secrets.
// Without it, only the debug APK is built (for testing on your phone).
val uploadKey = file("upload.jks")

android {
    namespace = "com.example.spray"
    compileSdk = 36

    defaultConfig {
        // The Play Store ID. It can never change once the app is uploaded.
        applicationId = "com.betmor.lilbetito"
        minSdk = 26
        targetSdk = 36
        versionCode = 23
        versionName = "1.0.0"
    }
    buildFeatures {
        buildConfig = true
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            if (uploadKey.exists()) {
                storeFile = uploadKey
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (uploadKey.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        abortOnError = false
        textReport = true
        textOutput = file("build/reports/lint-results.txt")
    }
}
