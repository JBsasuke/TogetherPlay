package com.juntosplay.app

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

/**
 * Dónde guarda el relay la caché del video: memoria interna o un disco/USB/tarjeta
 * conectado. Android da a cada app una carpeta propia en cada disco, sin permisos.
 */
object CacheLocation {
    data class Option(val key: String, val label: String, val dir: File)

    const val PREF = "cache_location"
    const val INTERNAL = "internal"

    /** Opciones disponibles ahora mismo (los discos aparecen solo si están conectados). */
    fun options(context: Context): List<Option> {
        val app = context.applicationContext
        val list = ArrayList<Option>()
        list.add(Option(INTERNAL, "Memoria interna · ${freeGb(app.cacheDir)}", app.cacheDir))
        val sm = app.getSystemService(StorageManager::class.java)
        app.externalCacheDirs.forEachIndexed { index, dir ->
            if (dir == null) return@forEachIndexed
            if (Environment.getExternalStorageState(dir) != Environment.MEDIA_MOUNTED) return@forEachIndexed
            // La primera suele ser la propia memoria interna vista como "externa": no se repite.
            if (index == 0 && Environment.isExternalStorageEmulated(dir)) return@forEachIndexed
            val name = (if (Build.VERSION.SDK_INT >= 24) sm?.getStorageVolume(dir)?.getDescription(app) else null)
                ?: "Disco externo"
            list.add(Option(dir.absolutePath, "$name · ${freeGb(dir)}", dir))
        }
        return list
    }

    /** Carpeta de caché del relay según lo elegido; si el disco no está, la memoria interna. */
    fun root(context: Context): File {
        val app = context.applicationContext
        val chosen = RoomSession.prefs(app).getString(PREF, INTERNAL) ?: INTERNAL
        if (chosen != INTERNAL) {
            val dir = File(chosen)
            val mounted = try { Environment.getExternalStorageState(dir) == Environment.MEDIA_MOUNTED } catch (_: Exception) { false }
            if (mounted && (dir.isDirectory || dir.mkdirs()) && dir.canWrite()) return File(dir, "relay")
        }
        return File(app.cacheDir, "relay")
    }

    /** Si lo elegido es un disco que ahora no está conectado. */
    fun usingFallback(context: Context): Boolean {
        val chosen = RoomSession.prefs(context).getString(PREF, INTERNAL) ?: INTERNAL
        return chosen != INTERNAL && root(context).parentFile?.absolutePath != chosen
    }

    /** Todas las carpetas de caché posibles (para medir y limpiar en todas). */
    fun allRoots(context: Context): List<File> =
        options(context).map { File(it.dir, "relay") }.distinctBy { it.absolutePath }

    private fun freeGb(dir: File): String {
        val gb = dir.usableSpace / (1024.0 * 1024 * 1024)
        return if (gb >= 10) "${gb.toInt()} GB libres" else String.format(java.util.Locale.US, "%.1f GB libres", gb)
    }
}
