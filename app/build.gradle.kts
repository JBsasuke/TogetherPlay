import java.util.Properties

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

    // Firma fija: con la misma llave siempre, las versiones nuevas se instalan encima sin desinstalar.
    // En GitHub viene de los secretos (KEYSTORE_*); en la computadora, de keystore.properties (no se sube).
    val localKeys = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
        Properties().apply { f.inputStream().use { load(it) } }
    }
    val keystorePath = System.getenv("KEYSTORE_FILE") ?: localKeys?.getProperty("storeFile")
    val fixedKey = keystorePath?.let { path ->
        signingConfigs.create("fixed") {
            storeFile = rootProject.file(path)
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: localKeys?.getProperty("storePassword")
            keyAlias = System.getenv("KEY_ALIAS") ?: localKeys?.getProperty("keyAlias") ?: "togetherplay"
            keyPassword = System.getenv("KEY_PASSWORD") ?: System.getenv("KEYSTORE_PASSWORD")
                ?: localKeys?.getProperty("keyPassword") ?: localKeys?.getProperty("storePassword")
        }
    }
    buildTypes {
        getByName("debug") { if (fixedKey != null) signingConfig = fixedKey }
        getByName("release") { if (fixedKey != null) signingConfig = fixedKey }
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
