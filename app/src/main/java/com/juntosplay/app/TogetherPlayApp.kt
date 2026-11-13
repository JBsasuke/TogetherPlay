package com.juntosplay.app

import android.app.Application
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Si la app se cierra por un error, guarda el detalle para mostrarlo al volver a abrirla
 * (Ajustes → copiar), y así poder corregirlo sin adivinar.
 */
class TogetherPlayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val text = buildString {
                    append("TogetherPlay ").append(versionName()).append(" · ")
                    append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" · Android ").append(Build.VERSION.RELEASE).append('\n')
                    append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append(" · hilo ").append(thread.name).append("\n\n")
                    append(sw.toString().take(6000))
                }
                crashFile(this).writeText(text)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (_: Exception) { "" }

    companion object {
        fun crashFile(app: android.content.Context) = File(app.filesDir, "last_crash.txt")
        /** Los errores graves de la parte de Tailscale (Go) se escriben aquí. */
        fun nativeCrashFile(app: android.content.Context) = File(app.filesDir, "last_crash_native.txt")

        /** Texto del último cierre inesperado, o null si no hubo. */
        fun lastCrash(app: android.content.Context): String? {
            val parts = listOf(crashFile(app), nativeCrashFile(app))
                .filter { it.exists() && it.length() > 0 }
                .map { it.readText().take(6000) }
            return parts.joinToString("\n\n----\n\n").ifBlank { null }
        }

        fun clearCrash(app: android.content.Context) {
            crashFile(app).delete()
            nativeCrashFile(app).delete()
        }
    }
}
