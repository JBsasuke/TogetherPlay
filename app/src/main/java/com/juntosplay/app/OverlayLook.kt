package com.juntosplay.app

import android.content.Context
import androidx.core.graphics.ColorUtils

/**
 * Transparencia de lo que se pinta encima del canal, elegida en Configuración (de 0 % = sólido a 100 % = solo el texto):
 * las franjas del canal (arriba y abajo), la lista de canales y la guía encima del video, cada una por separado.
 */
internal object OverlayLook {
    const val STRIP = "tr_strip"
    const val LIST = "tr_list"
    const val GRID = "tr_grid"
    val areas = listOf(STRIP to "Franjas del canal (arriba y abajo)", LIST to "Lista de canales", GRID to "Guía encima del video")
    private val defaults = mapOf(STRIP to 45, LIST to 10, GRID to 20)

    /** Cuánto de transparente, en %, tiene esa parte. */
    fun percent(context: Context, area: String): Int =
        RoomSession.prefs(context).getInt(area, defaults[area] ?: 30).coerceIn(0, 100)

    fun set(context: Context, area: String, percent: Int) =
        RoomSession.prefs(context).edit().putInt(area, percent.coerceIn(0, 100)).apply()

    /** El color con la transparencia de esa parte. [scale] < 1 lo deja todavía más transparente (bordes de un degradado). */
    fun tint(context: Context, area: String, color: Int, scale: Float = 1f): Int =
        ColorUtils.setAlphaComponent(color, (255 * (100 - percent(context, area)) / 100f * scale).toInt().coerceIn(0, 255))

    fun summary(context: Context): String = areas.joinToString(" · ") { (key, _) -> percent(context, key).toString() + "%" }
}
