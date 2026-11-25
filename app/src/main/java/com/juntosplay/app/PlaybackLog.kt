package com.juntosplay.app

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Registro corto del reproductor (qué se abrió, qué pistas tenía, errores y por qué se cerró) en
 * Android/data/com.juntosplay.app/files/reproductor.txt. Lo saca revisar-error.bat sin depender del
 * registro de Android, que en la Shield se llena enseguida y borra lo de la app.
 */
internal object PlaybackLog {
    private const val FILE = "reproductor.txt"
    private const val MAX_BYTES = 96 * 1024
    private val writer = Executors.newSingleThreadExecutor()
    private val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun add(context: Context, message: String) {
        Log.i("TPReproductor", message)
        val app = context.applicationContext
        val line = synchronized(time) { time.format(Date()) } + "  " + message.replace('\n', ' ') + "\n"
        writer.execute {
            runCatching {
                val file = File(app.getExternalFilesDir(null) ?: app.filesDir, FILE)
                // Se queda con lo último: al pasar del tamaño máximo se borra la mitad más vieja.
                if (file.length() > MAX_BYTES) file.writeText(file.readText().takeLast(MAX_BYTES / 2).substringAfter('\n'))
                file.appendText(line)
            }
        }
    }

    /** Memoria usada por la app y el máximo que le deja Android. */
    fun memory(): String {
        val runtime = Runtime.getRuntime()
        val used = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        return "memoria ${used}/${runtime.maxMemory() / (1024 * 1024)} MB"
    }

    /** Mensaje del error y de sus causas (sin direcciones con usuario y contraseña). */
    fun cause(error: Throwable): String = generateSequence(error) { it.cause }.take(4)
        .map { (it.javaClass.simpleName + ": " + it.message.orEmpty()).take(200) }
        .joinToString(" ← ")
        .replace(Regex("""https?://\S+"""), "[dirección]")
}
