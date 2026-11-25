# Reglas de ofuscación de TogetherPlay (R8 en release).
# Conservan lo que se usa por reflexión o por JNI, que de otro modo R8 renombraría o borraría.

# Quita los nombres de paquetes internos de las clases que R8 puede renombrar.
-repackageclasses

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

# Nombres de línea para que, si algo falla, el informe de cierre siga siendo legible.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile



# --- VLC (reproductor de respaldo para videos que el equipo no decodifica, p. ej. H.264 de 10 bits en 4K) ---
# libvlcjni busca estas clases y sus metodos por nombre al cargarse: si R8 las renombra o las borra,
# la carga falla y VLC cierra la app entera.
-keep class org.videolan.** { *; }
-keep interface org.videolan.** { *; }
-keepclassmembers class org.videolan.** { *; }
-dontwarn org.videolan.**
