# Reglas de ofuscación de TogetherPlay (R8 en release).
# Conservan lo que se usa por reflexión o por JNI, que de otro modo R8 renombraría o borraría.

# --- Tailscale integrado: TailnetBridge llama a tsbridge.Tsbridge por reflexión ---
-keep class tsbridge.** { *; }
-keep class go.** { *; }
-keep class org.gomobile.** { *; }

# --- WebRTC (compartir pantalla): usa JNI, los nombres deben quedarse ---
-keep class org.webrtc.** { *; }
-keepclassmembers class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# --- Media3 / ExoPlayer y el decodificador FFmpeg de Jellyfin (JNI) ---
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-keep class org.jellyfin.media3.** { *; }
-dontwarn androidx.media3.**

# --- OkHttp / Okio ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# --- Coil (imágenes) ---
-dontwarn coil.**

# --- ZXing (código QR) ---
-dontwarn com.google.zxing.**

# BuildConfig lo lee la app (servidor); se conserva.
-keep class com.juntosplay.app.BuildConfig { *; }

# Nombres de línea para que, si algo falla, el informe de cierre siga siendo legible.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
