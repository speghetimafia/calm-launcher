plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.calm.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.calm.launcher"
        minSdk = 29
        targetSdk = 29          // Android 10 head unit; avoids newer background limits
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")   // personal unit: debug-signed is fine
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
    lint {
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
        abortOnError = false
        checkReleaseBuilds = false
    }
    androidResources { noCompress += listOf("png", "json") }
}
