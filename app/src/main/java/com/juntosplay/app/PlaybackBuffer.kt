package com.juntosplay.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import androidx.media3.exoplayer.DefaultLoadControl

/**
 * Búfer de reproducción (Configuración → Pantalla y reproducción): cuánto video se guarda por
 * adelantado. Sin búfer arranca y cambia de canal más rápido, pero se puede parar si el internet baja;
 * el grande tarda un poco más en empezar, pero casi nunca se para.
 */
internal object PlaybackBuffer {
    private const val PREF = "playback_buffer"

    enum class Level(
        val key: String, val label: String, val detail: String,
        /** Mínimo y máximo guardado, lo que hace falta para empezar y para seguir después de pararse (ms). */
        val minMs: Int, val maxMs: Int, val startMs: Int, val rebufferMs: Int,
        /** Lo mismo para películas REMUX 4K (archivos enormes). */
        val remuxMinMs: Int, val remuxMaxMs: Int, val remuxStartMs: Int, val remuxRebufferMs: Int,
        /** Colchón de VLC (decodificador por software), en ms. */
        val vlcMs: Int,
        /** Memoria máxima para el búfer (se baja sola si la app tiene poca libre). */
        val memoryMiB: Int
    ) {
        NONE("none", "Sin búfer", "arranca al instante; se puede parar si el internet baja",
            2_000, 8_000, 500, 1_000, 12_000, 40_000, 1_500, 4_000, 1_000, 48),
        SMALL("small", "Búfer pequeño", "arranca rápido y aguanta bajones cortos",
            6_000, 25_000, 1_000, 2_500, 16_000, 50_000, 2_500, 5_000, 3_000, 64),
        MEDIUM("medium", "Búfer medio (recomendado)", "equilibrio entre arranque rápido y que no se pare",
            15_000, 60_000, 1_800, 4_000, 22_000, 65_000, 5_000, 8_000, 8_000, 96),
        LARGE("large", "Búfer grande", "tarda un poco más en empezar; casi nunca se para",
            30_000, 120_000, 4_000, 8_000, 30_000, 120_000, 6_000, 10_000, 15_000, 160)
    }

    fun current(context: Context): Level {
        val key = RoomSession.prefs(context).getString(PREF, null)
        return Level.values().firstOrNull { it.key == key } ?: Level.MEDIUM
    }

    /** "Búfer medio", sin el "(recomendado)". */
    fun label(context: Context): String = current(context).label.substringBefore(" (")

    /** Para el botón de Configuración: "Búfer · medio". */
    fun buttonLabel(context: Context): String = "Búfer · " + when (current(context)) {
        Level.NONE -> "sin búfer"
        Level.SMALL -> "pequeño"
        Level.MEDIUM -> "medio"
        Level.LARGE -> "grande"
    }

    fun choose(activity: Activity, update: () -> Unit) {
        val levels = Level.values()
        val labels = levels.map { it.label + " · " + it.detail }.toTypedArray()
        val dialog = AlertDialog.Builder(activity).setTitle("Búfer de reproducción")
            .setSingleChoiceItems(labels, levels.indexOf(current(activity))) { popup, index ->
                RoomSession.prefs(activity).edit().putString(PREF, levels[index].key).apply()
                popup.dismiss()
                update()
            }.setNegativeButton("Cerrar", null).create()
        Ui.showDialog(dialog)
    }

    /** Memoria para el búfer sin pasarse de lo que de verdad queda libre en la app. */
    fun fittedBytes(desiredMiB: Int): Int {
        val runtime = Runtime.getRuntime()
        val freeBytes = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        return minOf(desiredMiB * 1024L * 1024L, (freeBytes * 0.45).toLong()).coerceAtLeast(32L * 1024 * 1024).toInt()
    }

    /** Búfer de la TV en vivo (guía, pantalla completa y multipantalla). */
    fun liveLoadControl(context: Context): DefaultLoadControl {
        val level = current(context)
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(level.minMs, level.maxMs, level.startMs, level.rebufferMs)
            .setTargetBufferBytes(fittedBytes(level.memoryMiB))
            .build()
    }
}
