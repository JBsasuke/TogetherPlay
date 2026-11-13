plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.juntosplay.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.juntosplay.app"
        minSdk = 23
        targetSdk = 35
        versionCode = 5
        versionName = "0.5.0"
        // Solo ARM (TV y teléfonos): el APK pesa bastante menos.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
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
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-datasource:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("io.github.webrtc-sdk:android:150.7871.04")
    implementation("com.google.zxing:core:3.5.3")
    // Tailscale integrado: AAR hecho con gomobile desde tailscale/ (lo genera la compilación de GitHub).
    val tsBridge = file("libs/tsbridge.aar")
    if (tsBridge.exists()) implementation(files(tsBridge))
}
