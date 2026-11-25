package com.juntosplay.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import androidx.core.graphics.ColorUtils

/**
 * Estilo de los menús de la TV: el elemento enfocado es una pastilla clara con
 * letras oscuras, el elegido una pastilla gris del tema y el resto sin fondo. Los colores salen
 * del tema de la app, así que cambian con él.
 */
internal object TvStyle {
    private val focused = intArrayOf(android.R.attr.state_focused)
    private val pressed = intArrayOf(android.R.attr.state_pressed)
    private val selected = intArrayOf(android.R.attr.state_selected)
    private val normal = intArrayOf()

    /** Pastilla del elemento enfocado: blanca (con un toque del tema) o del color del realce. */
    val focusFill: Int get() =
        // Cristal negro: negro transparente (con letras blancas y borde blanco), no el blanco donde las letras blancas no se leían. Va
        // primero: con «Color de selección: del realce» el realce de Cristal negro · Blanco también es casi blanco.
        if (Ui.glassBlack) 0xA6000000.toInt()
        else if (Ui.selectionAccent) Ui.cyan
        else ColorUtils.blendARGB(Color.WHITE, Ui.cyan, 0.12f)
    /** Letras sobre la pastilla: oscuras si la pastilla es clara, blancas si es oscura. */
    val focusInk: Int get() = if (Ui.glassBlack) Color.WHITE else if (ColorUtils.calculateLuminance(focusFill) > 0.42)
        ColorUtils.blendARGB(Ui.bgBottom, Color.BLACK, 0.45f) else Color.WHITE
    /** Fondo del menú lateral. */
    val railFill: Int get() = ColorUtils.blendARGB(Ui.surface, Ui.bgBottom, 0.25f)

    /** Lo elegido en las listas y la guía: en Cristal negro, vidrio negro transparente con borde blanco (sin gris). */
    fun focusRound(c: Context, radius: Float): Drawable =
        if (Ui.glassBlack) Ui.round(focusFill, radius, Ui.dp(c, 2), 0xE6FFFFFF.toInt()) else Ui.round(focusFill, radius)

    fun menuBackground(c: Context, radius: Int = 10): Drawable {
        val r = Ui.dp(c, radius).toFloat()
        // Cristal negro: el cursor es vidrio negro con borde blanco (no la pastilla clara ni la gris).
        val lit = if (Ui.glassBlack) Ui.round(Ui.glassFocus, r, Ui.dp(c, 2), Color.WHITE) else Ui.round(focusFill, r)
        return StateListDrawable().apply {
            addState(pressed, lit)
            addState(focused, lit)
            // Cristal negro: lo elegido es vidrio negro transparente con un reflejo en el borde (no la pastilla gris).
            addState(selected, if (Ui.glassBlack) Ui.round(0xB3000000.toInt(), r, Ui.dp(c, 1), 0x59FFFFFF)
                else Ui.round(ColorUtils.setAlphaComponent(Ui.surfaceHigh, 0xE6), r))
            addState(normal, Ui.round(Color.TRANSPARENT, r))
        }
    }

    fun menuText(): ColorStateList = ColorStateList(
        arrayOf(pressed, focused, selected, normal),
        intArrayOf(focusInk, focusInk, Color.WHITE, ColorUtils.setAlphaComponent(Color.WHITE, 0xE6))
    )
}
