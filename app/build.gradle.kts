import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val armOnly = providers.gradleProperty("togetherplayArmOnly").map(String::toBoolean).orElse(false)

android {
    namespace = "com.juntosplay.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.juntosplay.app"
        minSdk = 23
        targetSdk = 35
        versionCode = 375
        versionName = "3.4.5"
    }

    // Un APK por arquitectura, mucho más liviano: el de arm64-v8a sirve para la Shield, el Samsung y el Pixel; el de
    // armeabi-v7a, para equipos de 32 bits (algunos Onn y TV viejas). El «universal» trae las dos, como antes.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            if (!armOnly.get()) include("x86", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/versions/9/previous-compilation-data.bin")
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
        getByName("release") {
            if (fixedKey != null) signingConfig = fixedKey
            // Ofuscación y reducción del código con R8 (solo en release).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    implementation("org.videolan.android:libvlc-all:3.6.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media3:media3-exoplayer:1.5.0")
    implementation("androidx.media3:media3-ui:1.5.0")
    implementation("androidx.media3:media3-datasource:1.5.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.0")
    // Audio DTS/TrueHD/AC3 por software cuando el equipo no lo trae.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")
    // Pósters y logos.
    implementation("io.coil-kt:coil:2.7.0")
    implementation("io.github.webrtc-sdk:android:150.7871.04")
    implementation("com.google.zxing:core:3.5.3")
    // Tailscale integrado: AAR hecho con gomobile desde tailscale/ (lo genera la compilación de GitHub).
    val tsBridge = file("libs/tsbridge.aar")
    if (tsBridge.exists()) implementation(files(tsBridge))
}
