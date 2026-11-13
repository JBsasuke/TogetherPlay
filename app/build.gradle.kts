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
        versionCode = 7
        versionName = "0.6.1"
        // Solo ARM (TV y teléfonos): el APK pesa bastante menos.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    // Firma fija: con la misma llave siempre, las versiones nuevas se instalan encima sin desinstalar.
    // En GitHub viene de los secretos (KEYSTORE_*); en la computadora, de keystore.properties (no se sube).
    // Servidor propio: sale de togetherplay.properties (en tu PC, no se sube a GitHub).
    // Sin ese archivo la app sale sin servidor y cada quien escribe el suyo.
    val localServer = rootProject.file("togetherplay.properties").takeIf { it.exists() }?.let { f ->
        Properties().apply { f.inputStream().use { load(it) } }
    }
    fun quoted(v: String?) = "\"" + (v ?: "").replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    defaultConfig {
        buildConfigField("String", "DEFAULT_SERVER", quoted(localServer?.getProperty("server.url")?.trim()))
    }
    buildFeatures { buildConfig = true }

    val localKeys = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
        Properties().apply { f.inputStream().use { load(it) } }
    }
    val keystorePath = System.getenv("KEYSTORE_FILE") ?: localKeys?.getProperty("storeFile")
    val fixedKey = keystorePath?.let { path ->
        signingConfigs.create("fixed") {
            storeFile = rootProject.file(path)
            fun env(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() }
            val password = env("KEYSTORE_PASSWORD") ?: env("KEYSTORE_PASSWORD_FALLBACK")
            storePassword = password ?: localKeys?.getProperty("storePassword")
            keyAlias = env("KEY_ALIAS") ?: localKeys?.getProperty("keyAlias") ?: "togetherplay"
            keyPassword = env("KEY_PASSWORD") ?: password
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
