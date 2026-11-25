package com.juntosplay.app

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.res.ColorStateList
import android.content.Context
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** Estilo visual oscuro de la app: colores del logo y piezas reutilizables. */
object Ui {
    /**
     * Temas: [base] es el color de fondo oscuro; las tarjetas y bordes se calculan mezclándolo
     * con el color principal, así todo combina. OLED = fondo negro puro.
     */
    private enum class Theme(val label: String, val accent: String, val companion: String, val oled: Boolean,
                             val base: String? = null, val tone: String? = null, val pure: Boolean = false,
                             val glass: Boolean = false) {
        ORIGINAL("TogetherPlay", "#22D3EE", "#4F8CFF", false),
        MIDNIGHT("Medianoche · Índigo", "#9AA8FF", "#6D72F3", false, "#11132A"),
        OCEAN("Océano · Turquesa", "#2DD4BF", "#38BDF8", false, "#0A1A22"),
        FOREST("Bosque · Esmeralda", "#4ADE9B", "#10B981", false, "#0C1B16"),
        SUNSET("Atardecer · Coral", "#FF8F70", "#F06292", false, "#1E1215"),
        ORCHID("Orquídea · Rosa", "#F48FC8", "#B98CFF", false, "#1B1020"),
        COFFEE("Café · Dorado", "#EBC07A", "#C8925A", false, "#1A1510"),
        GRAPHITE("Grafito · Plata", "#D2DAE6", "#94A3B8", false, "#15171B"),
        OLED_BLUE("OLED · Azul", "#78B7FF", "#587BFF", true),
        OLED_VIOLET("OLED · Violeta", "#CAAAFF", "#9170F3", true),
        OLED_GREEN("OLED · Verde", "#69DFBC", "#329C90", true),
        OLED_AMBER("OLED · Ámbar", "#FBC876", "#C3824C", true),
        OLED_RED("OLED · Rojo", "#FF8A8A", "#E05555", true),
        OLED_WHITE("OLED · Blanco", "#E8ECF2", "#9AA4B2", true),
        OLED_YELLOW("OLED · Amarillo", "#FFD93D", "#F2A900", true),
        OLED_GOLD_WHITE("OLED · Oro y blanco", "#F5D27A", "#E8ECF2", true),
        OLED_ORANGE("OLED · Naranja", "#FFA25C", "#F97316", true),
        OLED_PINK("OLED · Rosa", "#FF8DC7", "#EC4899", true),
        OLED_MAGENTA("OLED · Magenta y violeta", "#F472D0", "#A855F7", true),
        OLED_CYAN("OLED · Cian", "#5CE1E6", "#06B6D4", true),
        OLED_LIME("OLED · Lima", "#C6F36B", "#84CC16", true),
        // Fondo negro con tarjetas grises (del blanco) y el realce en azul o en rojo. Antes el realce era el
        // blanco en los dos y por eso se veían iguales.
        OLED_WHITE_BLUE("OLED · Blanco y azul", "#3B9BFF", "#F4F7FB", true, tone = "#F4F7FB"),
        OLED_WHITE_RED("OLED · Blanco y rojo", "#FF4D4D", "#F4F7FB", true, tone = "#F4F7FB"),
        // Negro puro (para pantallas OLED): también el menú, las tarjetas y los botones son negros de verdad, sin el
        // gris de los otros OLED. El color solo va en los bordes, el realce y lo elegido.
        PURE_WHITE("Negro puro · Blanco", "#F2F4F8", "#9AA4B2", true, pure = true),
        PURE_BLUE("Negro puro · Azul", "#4DA3FF", "#2F6BFF", true, pure = true),
        PURE_CYAN("Negro puro · Cian", "#3FE0E8", "#06B6D4", true, pure = true),
        PURE_GREEN("Negro puro · Verde", "#4BE3A4", "#16A34A", true, pure = true),
        PURE_AMBER("Negro puro · Ámbar", "#FFC857", "#F59E0B", true, pure = true),
        PURE_ORANGE("Negro puro · Naranja", "#FF9A4D", "#F97316", true, pure = true),
        PURE_RED("Negro puro · Rojo", "#FF5C5C", "#DC2626", true, pure = true),
        PURE_PINK("Negro puro · Rosa", "#FF7AC0", "#EC4899", true, pure = true),
        PURE_VIOLET("Negro puro · Violeta", "#B892FF", "#7C3AED", true, pure = true),
        // Cristal: fondo azul noche con un brillo de color y botones, barras y tarjetas transparentes.
        GLASS_BLUE("Cristal · Azul", "#8FC2FF", "#8E8CFF", false, "#0D1430", glass = true),
        GLASS_VIOLET("Cristal · Violeta", "#C9AEFF", "#8FB4FF", false, "#150F2C", glass = true),
        GLASS_TEAL("Cristal · Turquesa", "#7BE8DA", "#7FB3FF", false, "#0A1C26", glass = true),
        GLASS_ROSE("Cristal · Rosa", "#FFA8D2", "#B9A0FF", false, "#1C0F22", glass = true),
        GLASS_GRAPHITE("Cristal · Grafito", "#E6EAF2", "#9FB0C8", false, "#12151C", glass = true),
        // Cristal negro (para OLED): lo transparente igual que en Cristal, pero el fondo, el menú, las ventanas y los
        // bordes que eran grises van en negro puro.
        GLASS_BLACK_WHITE("Cristal negro · Blanco", "#E6EAF2", "#9FB0C8", true, pure = true, glass = true),
        GLASS_BLACK_BLUE("Cristal negro · Azul", "#8FC2FF", "#8E8CFF", true, pure = true, glass = true),
        GLASS_BLACK_VIOLET("Cristal negro · Violeta", "#C9AEFF", "#8FB4FF", true, pure = true, glass = true),
        GLASS_BLACK_TEAL("Cristal negro · Turquesa", "#7BE8DA", "#7FB3FF", true, pure = true, glass = true),
        GLASS_BLACK_ROSE("Cristal negro · Rosa", "#FFA8D2", "#B9A0FF", true, pure = true, glass = true),
        GLASS_BLACK_RED("Cristal negro · Rojo", "#FF8A8A", "#FF6BA8", true, pure = true, glass = true),
        GLASS_BLACK_AMBER("Cristal negro · Ámbar", "#FFCF70", "#FF9F5A", true, pure = true, glass = true),
        GLASS_BLACK_GREEN("Cristal negro · Verde", "#7EEBA8", "#5ED6D0", true, pure = true, glass = true),
        GLASS_BLACK_GOLD("Cristal negro · Oro y blanco", "#F5D27A", "#E8ECF2", true, pure = true, glass = true),
        GLASS_BLACK_MAGENTA("Cristal negro · Magenta y violeta", "#F472D0", "#A855F7", true, pure = true, glass = true)
    }

    private const val PREF_THEME = "ui_theme"
    private const val CUSTOM = "CUSTOM"
    private const val PREF_BG = "ui_custom_background"
    private const val PREF_ACCENT = "ui_custom_accent"
    private const val PREF_SELECTION = "ui_selection_accent"
    private const val PREF_FONT = "ui_font_size"
    private const val PREF_GUIDE = "ui_guide_colors"

    /** Colores de los recuadros de programas en la guía (con cualquier tema). */
    private class GuidePalette(val key: String, val label: String, val color: Int)
    private val guidePalettes = listOf(
        GuidePalette("theme", "Del tema", 0),
        GuidePalette("accent", "Color del realce", 0),
        GuidePalette("rainbow", "Arcoíris (cada programa de un color)", 0),
        GuidePalette("blue", "Azul", 0xFF3B82F6.toInt()),
        GuidePalette("purple", "Morado", 0xFF9B5CF6.toInt()),
        GuidePalette("pink", "Rosado", 0xFFEC4899.toInt()),
        GuidePalette("red", "Rojo", 0xFFEF4444.toInt()),
        GuidePalette("orange", "Naranja", 0xFFF97316.toInt()),
        GuidePalette("yellow", "Amarillo", 0xFFEAB308.toInt()),
        GuidePalette("green", "Verde", 0xFF22C55E.toInt()),
        GuidePalette("teal", "Turquesa", 0xFF14B8A6.toInt()),
        GuidePalette("grey", "Gris", 0xFF9CA3AF.toInt())
    )
    private val rainbow = intArrayOf(0xFF3B82F6.toInt(), 0xFF9B5CF6.toInt(), 0xFFEC4899.toInt(), 0xFFEF4444.toInt(),
        0xFFF97316.toInt(), 0xFFEAB308.toInt(), 0xFF22C55E.toInt(), 0xFF14B8A6.toInt())
    private var guideKey = "theme"

    /**
     * Color del recuadro de un programa en la guía. [seed]: el título (en "Arcoíris" cada programa tiene su
     * color y los capítulos de una misma serie salen iguales). Se mezcla con negro para que el texto blanco
     * se lea: el programa de ahora más vivo y los que ya pasaron más apagados.
     */
    fun guideBlock(live: Boolean, past: Boolean, seed: Int): Int {
        val tone = when (guideKey) {
            // En «Negro puro» las tarjetas son negras: los programas llevan un toque del color para que se distingan.
            "theme" -> return if (oled && pure) tint(if (live) 0.22f else if (past) 0.06f else 0.12f) else when {
                live -> surfaceHigh
                past -> mix(surface, bgBottom, 0.55f)
                else -> surface
            }
            "accent" -> accent
            "rainbow" -> rainbow[(seed and Int.MAX_VALUE) % rainbow.size]
            else -> guidePalettes.firstOrNull { it.key == guideKey }?.color ?: accent
        }
        return mix(tone, Color.BLACK, when {
            live -> 0.42f
            past -> 0.80f
            else -> 0.62f
        })
    }

    /** Parte ya emitida del programa de ahora (sombreado encima del recuadro). */
    val guideElapsed: Int get() = if (guideKey == "theme") androidx.core.graphics.ColorUtils.setAlphaComponent(cyan, 46)
        else androidx.core.graphics.ColorUtils.setAlphaComponent(Color.WHITE, 0x2E)

    /** La guía usa colores propios (no los del tema). */
    val guideColored: Boolean get() = guideKey != "theme"
    private var theme = Theme.ORIGINAL
    private var custom = false
    private var oled = theme.oled
    /** Tema «Negro puro»: menú, tarjetas y campos en negro de verdad (no el gris teñido de los otros OLED). */
    private var pure = false
    private var glassOn = false
    /** Tema «Cristal»: barras, botones y tarjetas transparentes sobre el fondo. */
    val glass: Boolean get() = glassOn
    /** «Cristal negro»: lo transparente es negro (vidrio oscuro) en vez de blanco, con un reflejo claro en el borde. */
    val glassBlack: Boolean get() = glassOn && oled
    /** Relleno de pastillas y botones de Cristal: blanco transparente, o negro transparente en Cristal negro. */
    val glassFill: Int get() = if (glassBlack) 0x99000000.toInt() else 0x24FFFFFF
    /** Borde (reflejo) de esas pastillas. */
    val glassEdge: Int get() = if (glassBlack) 0x38FFFFFF else 0x40FFFFFF
    /** Cristal negro: donde está el cursor, vidrio negro casi opaco con borde blanco (sin el gris). */
    val glassFocus: Int = 0xD9000000.toInt()
    /** Cristal negro: lo elegido, vidrio negro transparente con un reflejo en el borde. */
    val glassPicked: Int = 0x99000000.toInt()
    /** Fondo de menús y ventanas en el tema Cristal: el color del tema muy oscuro y un poco transparente. */
    private val glassPanel: Int get() = if (oled) androidx.core.graphics.ColorUtils.setAlphaComponent(Color.BLACK, 0xF0)
        else androidx.core.graphics.ColorUtils.setAlphaComponent(mix(base ?: 0xFF0D1430.toInt(), Color.BLACK, 0.35f), 0xE0)
    /** Borde de las ventanas y tarjetas de Cristal (en Cristal negro, negro puro). */
    private val glassBorder: Int get() = if (oled) Color.BLACK else 0x4DFFFFFF
    private var accent = Color.parseColor(theme.accent)
    private var companion = Color.parseColor(theme.companion)

    /** Colores para elegir: sirven de fondo y de color del realce. */
    private class Swatch(val key: String, val label: String, val color: Int)
    private val palette = listOf(
        Swatch("red", "Rojo", 0xFFE57373.toInt()), Swatch("pink", "Rosado", 0xFFF06292.toInt()),
        Swatch("purple", "Morada", 0xFFBA68C8.toInt()), Swatch("indigo", "Índigo", 0xFF8C9EFF.toInt()),
        Swatch("blue", "Azul", 0xFF64B5F6.toInt()), Swatch("cyan", "Cian", 0xFF4DD0E1.toInt()),
        Swatch("teal", "Verde azulado", 0xFF4DB6AC.toInt()), Swatch("green", "Verde", 0xFF81C784.toInt()),
        Swatch("lime", "Lima", 0xFFDCE775.toInt()), Swatch("yellow", "Amarillo", 0xFFFFF176.toInt()),
        Swatch("amber", "Ámbar", 0xFFFFD54F.toInt()), Swatch("orange", "Naranja", 0xFFFFB74D.toInt()),
        Swatch("brown", "Marrón", 0xFFBCAAA4.toInt()), Swatch("grey", "Gris", 0xFFE0E0E0.toInt()),
        Swatch("bluegrey", "Gris azulado", 0xFFB0BEC5.toInt())
    )
    private val backgrounds = palette + listOf(
        Swatch("darkgrey", "Gris oscuro", 0xFF9E9E9E.toInt()), Swatch("dark", "Oscuro", 0xFFC5CAE9.toInt()),
        Swatch("black", "Negro", Color.BLACK)
    )
    private val fontLabels = arrayOf("Pequeño", "Mediano", "Grande", "Muy grande")
    private val fontScales = floatArrayOf(0.88f, 1f, 1.15f, 1.3f)

    /** El elemento enfocado se pinta con el color del realce en vez de blanco. */
    var selectionAccent = false
        private set

    /** Fondo oscuro sacado de un color: el color casi negro, para que el texto se lea bien. */
    private fun backgroundBase(swatch: Swatch): Int? = when (swatch.key) {
        "black" -> null
        "dark" -> 0xFF0E1016.toInt()
        "darkgrey" -> 0xFF161616.toInt()
        "grey" -> 0xFF1F1F1F.toInt()
        else -> mix(swatch.color, Color.BLACK, 0.84f)
    }
    /** Color con el que se tiñen tarjetas y bordes en los temas OLED (casi siempre el del realce). */
    private var surfaceTone = Color.parseColor(Theme.ORIGINAL.accent)
    private fun tint(amount: Float) = androidx.core.graphics.ColorUtils.blendARGB(Color.BLACK, surfaceTone, amount)
    private var base: Int? = null
    private fun mix(a: Int, b: Int, amount: Float) = androidx.core.graphics.ColorUtils.blendARGB(a, b, amount)
    /** Color derivado del fondo del tema: [light] lo aclara y [accentAmount] le da el tono del color principal. */
    private fun shade(light: Float, accentAmount: Float): Int? =
        base?.let { mix(mix(it, Color.WHITE, light), accent, accentAmount) }

    fun loadTheme(context: Context) {
        val prefs = RoomSession.prefs(context)
        val saved = prefs.getString(PREF_THEME, null)
        selectionAccent = prefs.getBoolean(PREF_SELECTION, false)
        guideKey = prefs.getString(PREF_GUIDE, null)?.takeIf { key -> guidePalettes.any { it.key == key } } ?: "theme"
        if (saved == CUSTOM) {
            custom = true
            val background = backgrounds.firstOrNull { it.key == prefs.getString(PREF_BG, "black") } ?: backgrounds.last()
            val highlight = palette.firstOrNull { it.key == prefs.getString(PREF_ACCENT, "indigo") } ?: palette[3]
            accent = highlight.color
            companion = mix(highlight.color, Color.BLACK, 0.25f)
            surfaceTone = accent
            base = backgroundBase(background)
            oled = base == null
            pure = false
            glassOn = false
        } else {
            custom = false
            theme = Theme.entries.firstOrNull { it.name == saved } ?: Theme.ORIGINAL
            accent = Color.parseColor(theme.accent)
            companion = Color.parseColor(theme.companion)
            surfaceTone = theme.tone?.let(Color::parseColor) ?: accent
            base = theme.base?.let(Color::parseColor)
            oled = theme.oled
            pure = theme.pure
            glassOn = theme.glass
        }
    }

    /** Escala del texto elegida en "Tamaño del texto". */
    fun fontScale(context: Context): Float =
        fontScales[RoomSession.prefs(context).getInt(PREF_FONT, 1).coerceIn(fontScales.indices)]

    /** Para attachBaseContext de cada pantalla: aplica el tamaño del texto elegido. */
    fun scaledContext(base: Context): Context {
        val scale = fontScale(base)
        if (scale == 1f) return base
        val config = android.content.res.Configuration(base.resources.configuration)
        config.fontScale = base.resources.configuration.fontScale * scale
        return base.createConfigurationContext(config)
    }

    private fun swatchIcon(activity: Activity, color: Int, checked: Boolean): Drawable {
        val size = dp(activity, 26)
        val circle = oval(color).apply { setStroke(dp(activity, 1), mix(color, Color.WHITE, 0.4f)) }
        val icon: Drawable = if (!checked) circle else android.graphics.drawable.LayerDrawable(arrayOf<Drawable>(
            circle, oval(Color.TRANSPARENT).apply { setStroke(dp(activity, 3), Color.WHITE) }))
        icon.setBounds(0, 0, size, size)
        return icon
    }

    /** Lista de colores con su círculo; el elegido lleva ✓. */
    private fun chooseSwatch(activity: Activity, title: String, options: List<Swatch>, current: String,
                             onPick: (Swatch) -> Unit) {
        showMenu(activity, title, closeLabel = "Cancelar") { dialog, rows ->
            var selected: View? = null
            options.forEach { option ->
                val isCurrent = option.key == current
                val button = button(activity, (if (isCurrent) "✓  " else "") + option.label,
                    if (isCurrent) Style.PRIMARY else Style.SECONDARY) {
                    dialog.dismiss()
                    onPick(option)
                }
                button.gravity = Gravity.CENTER_VERTICAL or Gravity.START
                button.setCompoundDrawablesRelative(swatchIcon(activity, option.color, isCurrent), null, null, null)
                button.compoundDrawablePadding = dp(activity, 14)
                rows.addView(button, matchWrap(bottomMargin = dp(activity, 6)))
                if (isCurrent) selected = button
            }
            if (isTelevision(activity)) rows.post { selected?.requestFocus() }
        }
    }

    private fun chooseOption(activity: Activity, title: String, labels: List<String>, current: Int, onPick: (Int) -> Unit) {
        showMenu(activity, title, closeLabel = "Cancelar") { dialog, rows ->
            var selected: View? = null
            labels.forEachIndexed { index, label ->
                val isCurrent = index == current
                val button = button(activity, (if (isCurrent) "●  " else "○  ") + label,
                    if (isCurrent) Style.PRIMARY else Style.SECONDARY) {
                    dialog.dismiss()
                    onPick(index)
                }
                button.gravity = Gravity.CENTER_VERTICAL or Gravity.START
                rows.addView(button, matchWrap(bottomMargin = dp(activity, 6)))
                if (isCurrent) selected = button
            }
            if (isTelevision(activity)) rows.post { selected?.requestFocus() }
        }
    }

    /**
     * Color del tema: color de fondo, color del realce, color de selección y
     * tamaño del texto. "Temas listos" tiene las combinaciones de antes.
     * [onFontChanged]: el tamaño del texto necesita rehacer la pantalla.
     */
    fun chooseTheme(activity: Activity, onFontChanged: (() -> Unit)? = null, onChanged: () -> Unit) {
        val prefs = RoomSession.prefs(activity)
        fun currentBackground() = if (custom) prefs.getString(PREF_BG, "black").orEmpty()
            else if (oled) "black" else ""
        fun currentAccent() = if (custom) prefs.getString(PREF_ACCENT, "indigo").orEmpty() else ""
        fun saveCustom(background: String? = null, highlight: String? = null) {
            val edit = prefs.edit().putString(PREF_THEME, CUSTOM)
            edit.putString(PREF_BG, background ?: currentBackground().ifBlank { "black" })
            edit.putString(PREF_ACCENT, highlight ?: currentAccent().ifBlank {
                // Al pasar de un tema listo, se busca el color más parecido al que tenía.
                palette.minByOrNull { colorDistance(it.color, accent) }?.key ?: "indigo"
            })
            edit.apply()
            loadTheme(activity)
            onChanged()
        }
        showMenu(activity, "Color del tema", closeLabel = "Cerrar") { dialog, rows ->
            fun row(label: String, value: String, icon: Drawable?, run: () -> Unit) {
                val button = button(activity, "$label · $value", Style.SECONDARY) {
                    dialog.dismiss()
                    run()
                }
                button.gravity = Gravity.CENTER_VERTICAL or Gravity.START
                if (icon != null) {
                    button.setCompoundDrawablesRelative(icon, null, null, null)
                    button.compoundDrawablePadding = dp(activity, 14)
                }
                rows.addView(button, matchWrap(bottomMargin = dp(activity, 6)))
            }
            val background = backgrounds.firstOrNull { it.key == currentBackground() }
            val highlight = palette.firstOrNull { it.key == currentAccent() }
            row("Color de fondo", background?.label ?: theme.label,
                swatchIcon(activity, background?.color ?: (base ?: Color.BLACK), false)) {
                chooseSwatch(activity, "Color de fondo", backgrounds, currentBackground()) { saveCustom(background = it.key) }
            }
            row("Color del realce", highlight?.label ?: "Del tema", swatchIcon(activity, accent, false)) {
                chooseSwatch(activity, "Color del realce", palette, currentAccent()) { saveCustom(highlight = it.key) }
            }
            row("Color de selección", if (selectionAccent) "Color del realce" else "Blanco",
                swatchIcon(activity, if (selectionAccent) accent else Color.WHITE, false)) {
                chooseOption(activity, "Color de selección", listOf("Blanco", "Color del realce"),
                    if (selectionAccent) 1 else 0) { index ->
                    prefs.edit().putBoolean(PREF_SELECTION, index == 1).apply()
                    loadTheme(activity)
                    onChanged()
                }
            }
            // Transparencia de lo que sale encima del canal (franjas, lista de canales y guía), todo de una vez.
            // Para ponerle un valor distinto a cada parte: Configuración > Transparencia en TV.
            val levels = (0..100 step 10).toList()
            val shown = OverlayLook.areas.map { OverlayLook.percent(activity, it.first) }.distinct()
            row("Transparencia de la interfaz", if (shown.size == 1) shown[0].toString() + " %" else "distinta en cada parte", null) {
                chooseOption(activity, "Transparencia de la interfaz, %", levels.map { it.toString() },
                    levels.indexOf(shown.singleOrNull() ?: -1)) { index ->
                    OverlayLook.areas.forEach { OverlayLook.set(activity, it.first, levels[index]) }
                    onChanged()
                }
            }
            val font = prefs.getInt(PREF_FONT, 1).coerceIn(fontLabels.indices)
            row("Tamaño del texto", fontLabels[font], null) {
                chooseOption(activity, "Tamaño del texto", fontLabels.toList(), font) { index ->
                    if (index != font) {
                        prefs.edit().putInt(PREF_FONT, index).apply()
                        if (onFontChanged != null) onFontChanged() else activity.recreate()
                    }
                }
            }
            val guide = guidePalettes.firstOrNull { it.key == guideKey } ?: guidePalettes[0]
            row("Colores de la guía", guide.label.substringBefore(" ("), guideIcon(activity, guide.key)) {
                chooseGuideColors(activity, onChanged)
            }
            row("Temas listos", if (custom) "Personalizado" else theme.label, null) { choosePreset(activity, onChanged) }
            if (isTelevision(activity)) rows.post { rows.getChildAt(0)?.requestFocus() }
        }
    }

    /** Círculo de muestra para los colores de la guía (Arcoíris: todos los colores en la rueda). */
    private fun guideIcon(activity: Activity, key: String): Drawable {
        val size = dp(activity, 26)
        val icon: Drawable = when (key) {
            "rainbow" -> GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setGradientType(GradientDrawable.SWEEP_GRADIENT)
                setColors(rainbow + rainbow[0])
            }
            "theme" -> oval(surfaceHigh).apply { setStroke(dp(activity, 1), outline) }
            "accent" -> oval(accent)
            else -> oval(guidePalettes.firstOrNull { it.key == key }?.color ?: accent)
        }
        icon.setBounds(0, 0, size, size)
        return icon
    }

    /** Colores de la guía: los del tema, el color del realce, uno fijo o Arcoíris. */
    private fun chooseGuideColors(activity: Activity, onChanged: () -> Unit) {
        showMenu(activity, "Colores de la guía", closeLabel = "Cancelar") { dialog, rows ->
            var selected: View? = null
            guidePalettes.forEach { option ->
                val current = option.key == guideKey
                val button = button(activity, (if (current) "✓  " else "") + option.label,
                    if (current) Style.PRIMARY else Style.SECONDARY) {
                    dialog.dismiss()
                    if (!current) {
                        RoomSession.prefs(activity).edit().putString(PREF_GUIDE, option.key).apply()
                        loadTheme(activity)
                        onChanged()
                    }
                }
                button.gravity = Gravity.CENTER_VERTICAL or Gravity.START
                button.setCompoundDrawablesRelative(guideIcon(activity, option.key), null, null, null)
                button.compoundDrawablePadding = dp(activity, 14)
                rows.addView(button, matchWrap(bottomMargin = dp(activity, 6)))
                if (current) selected = button
            }
            if (isTelevision(activity)) rows.post { selected?.requestFocus() }
        }
    }

    private fun colorDistance(a: Int, b: Int): Int {
        val r = Color.red(a) - Color.red(b)
        val g = Color.green(a) - Color.green(b)
        val bl = Color.blue(a) - Color.blue(b)
        return r * r + g * g + bl * bl
    }

    /** Temas listos: muestra de colores (fondo + color principal) al lado de cada nombre. */
    private fun choosePreset(activity: Activity, onChanged: () -> Unit) {
        showMenu(activity, "Temas listos", closeLabel = "Cancelar") { dialog, rows ->
            var selected: View? = null
            Theme.entries.forEach { option ->
                val background = option.base?.let(Color::parseColor)
                    ?: if (option.oled) Color.BLACK else 0xFF101725.toInt()
                val swatch = android.graphics.drawable.LayerDrawable(arrayOf<Drawable>(
                    oval(background).apply { setStroke(dp(activity, 2), Color.parseColor(option.companion)) },
                    oval(Color.parseColor(option.accent))
                )).apply {
                    val inset = dp(activity, 6)
                    setLayerInset(1, inset, inset, inset, inset)
                    setBounds(0, 0, dp(activity, 24), dp(activity, 24))
                }
                val current = option == theme && !custom
                val button = button(activity, option.label, if (current) Style.PRIMARY else Style.SECONDARY) {
                    dialog.dismiss()
                    if (!current) {
                        RoomSession.prefs(activity).edit().putString(PREF_THEME, option.name).apply()
                        loadTheme(activity)
                        onChanged()
                    }
                }
                button.gravity = Gravity.CENTER_VERTICAL or Gravity.START
                button.setCompoundDrawablesRelative(swatch, null, null, null)
                button.compoundDrawablePadding = dp(activity, 12)
                rows.addView(button, matchWrap(bottomMargin = dp(activity, 6)))
                if (current) selected = button
            }
            if (isTelevision(activity)) rows.post { selected?.requestFocus() }
        }
    }

    fun showDialog(dialog: AlertDialog) {
        dialog.show()
        val context = dialog.context
        val panel = if (glassOn) round(glassPanel, dp(context, 20).toFloat(), dp(context, 1), glassBorder)
            else round(surface, dp(context, 20).toFloat(), dp(context, 1), outline)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.InsetDrawable(panel, dp(context, 12)))
        // Ventanas más chicas: en la TV Android las abría a más de media pantalla para dos o tres botones.
        val screenWidth = context.resources.displayMetrics.widthPixels
        dialog.window?.setLayout(minOf(dp(context, 400), screenWidth - dp(context, 24)), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        // Si adentro va una tarjeta de botones, se le quita su propio recuadro (quedaba un marco dentro de otro)
        // y casi todo su margen.
        (dialog.findViewById<View>(android.R.id.custom) as? android.view.ViewGroup)?.getChildAt(0)?.let { inner ->
            if (inner is LinearLayout && inner.background is GradientDrawable) {
                inner.background = null
                inner.setPadding(dp(context, 14), dp(context, 4), dp(context, 14), 0)
            }
        }
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach { action ->
            dialog.getButton(action)?.apply {
                isAllCaps = false
                setTextColor(cyan)
                background = controlBackground(context, 12, Color.TRANSPARENT, Color.TRANSPARENT)
            }
        }
    }

    internal fun showMenu(activity: Activity, title: String, closeLabel: String = "Cerrar",
                              populate: (Dialog, LinearLayout) -> Unit) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val card = card(activity)
        val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        populate(dialog, rows)
        card.addView(label(activity, title, 20f, bold = true), Ui.matchWrap(bottomMargin = dp(activity, 12)))
        val scroll = ScrollView(activity).apply { addView(rows) }
        card.addView(scroll, Ui.matchWrap())
        card.addView(button(activity, closeLabel, Style.SECONDARY, R.drawable.catalog_close) {
            dialog.dismiss()
        }, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.END
            topMargin = dp(activity, 12)
        })
        val width = minOf(dp(activity, 400), activity.resources.displayMetrics.widthPixels - dp(activity, 36))
        val maxHeight = (activity.resources.displayMetrics.heightPixels * 0.88f).toInt()
        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val naturalHeight = card.measuredHeight
        if (naturalHeight > maxHeight) {
            scroll.layoutParams = scroll.layoutParams.apply {
                height = (scroll.measuredHeight - (naturalHeight - maxHeight)).coerceAtLeast(dp(activity, 48))
            }
        }
        dialog.setContentView(card)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setLayout(width, minOf(naturalHeight, maxHeight))
        // Si el contenido cambia después de abrir (resultados que llegan, textos que crecen), la ventana se vuelve a
        // medir: antes se quedaba con el alto del principio y el botón «Cerrar» salía cortado.
        var fitted = rows.height
        rows.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val now = bottom - top
            if (now == fitted || !dialog.isShowing) return@addOnLayoutChangeListener
            fitted = now
            rows.post {
                if (!dialog.isShowing) return@post
                scroll.layoutParams = scroll.layoutParams.apply { height = -2 }
                card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                val wanted = card.measuredHeight
                if (wanted > maxHeight) scroll.layoutParams = scroll.layoutParams.apply {
                    height = (scroll.measuredHeight - (wanted - maxHeight)).coerceAtLeast(dp(activity, 48))
                }
                dialog.window?.setLayout(width, minOf(wanted, maxHeight))
            }
        }
    }

    internal fun qrBitmap(text: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 4))
        val pixels = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            pixels[y * size + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
    }

    val bgTop get() = if (oled) Color.BLACK else shade(0.03f, 0.03f) ?: 0xFF101725.toInt()
    val bgBottom get() = if (oled) Color.BLACK else base?.let { mix(it, Color.BLACK, 0.45f) } ?: 0xFF070B12.toInt()
    val surface get() = if (oled && pure) Color.BLACK else if (oled) tint(0.09f) else shade(0.06f, 0.06f) ?: 0xFF171F2D.toInt()
    val surfaceHigh get() = if (oled && pure) tint(0.12f) else if (oled) tint(0.17f) else shade(0.12f, 0.12f) ?: 0xFF233248.toInt()
    val inputBg get() = if (oled && pure) Color.BLACK else if (oled) tint(0.055f) else base?.let { mix(it, Color.BLACK, 0.2f) } ?: 0xFF101722.toInt()
    val outline get() = if (oled && pure && glassOn) Color.BLACK else if (oled && pure) tint(0.38f) else if (oled) tint(0.32f) else shade(0.2f, 0.22f) ?: 0xFF35475E.toInt()
    val focusFill get() = androidx.core.graphics.ColorUtils.blendARGB(surfaceHigh, cyan, 0.24f)
    val text = Color.WHITE
    val muted = Color.parseColor("#B7C3D4")
    val hint = Color.parseColor("#95A4BA")
    val cyan get() = accent
    val blue get() = companion
    val purple = Color.parseColor("#B44CFF")
    val ok = Color.parseColor("#34D399")
    val warn = Color.parseColor("#FBBF24")
    val bad = Color.parseColor("#F87171")

    enum class Style { PRIMARY, SECONDARY, GHOST }

    /** «Formato de la hora» en Configuración: 24 horas o 12 horas (si no lo elegiste, el del sistema). */
    fun is24h(context: Context): Boolean =
        RoomSession.prefs(context).getString("clock_format", null)?.let { it == "24" } ?: android.text.format.DateFormat.is24HourFormat(context)

    /** Para mostrar horas (guía, programas, reloj) con el formato elegido. */
    fun timeFormat(context: Context): java.text.DateFormat =
        java.text.SimpleDateFormat(if (is24h(context)) "HH:mm" else "h:mm a", java.util.Locale.getDefault())

    fun dp(c: Context, value: Int): Int = (value * c.resources.displayMetrics.density + 0.5f).toInt()

    fun pageBackground(): Drawable = if (glassOn && oled) android.graphics.drawable.ColorDrawable(Color.BLACK)
        else if (glassOn) GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(mix(base ?: 0xFF0D1430.toInt(), accent, 0.22f), base ?: 0xFF0D1430.toInt(), mix(base ?: 0xFF0D1430.toInt(), Color.BLACK, 0.55f)))
        else if (oled) android.graphics.drawable.ColorDrawable(Color.BLACK)
        else GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(bgTop, bgBottom))

    fun isTelevision(context: Context): Boolean {
        val config = context.resources.configuration
        val features = context.packageManager
        return config.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION ||
            features.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) ||
            features.hasSystemFeature("amazon.hardware.fire_tv")
    }

    fun round(color: Int, radius: Float, strokeWidth: Int = 0, strokeColor: Int = Color.TRANSPARENT): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }

    private val focusState = intArrayOf(android.R.attr.state_focused)
    private val pressState = intArrayOf(android.R.attr.state_pressed)
    private val selectedState = intArrayOf(android.R.attr.state_selected)
    private val defaultState = intArrayOf()
    val optionText = ColorStateList(
        arrayOf(pressState, focusState, selectedState, defaultState),
        intArrayOf(text, text, text, muted)
    )

    fun controlBackground(c: Context, radius: Int, fill: Int, border: Int = outline,
                          focusBorder: Int = cyan): Drawable {
        val r = dp(c, radius).toFloat()
        val thin = dp(c, 1)
        val thick = dp(c, 2)
        // «Negro puro»: el relleno es negro también con el cursor encima o elegido; lo marca el borde de color.
        val black = oled && pure
        // Cristal: todos los botones iguales, blanco transparente con borde claro (el elegido, borde blanco).
        // Cristal negro: vidrio negro transparente con un reflejo claro en el borde (el elegido, un poco más marcado).
        if (glassOn && oled) return StateListDrawable().apply {
            addState(pressState, round(glassFocus, r, thick, Color.WHITE))
            addState(focusState, round(glassFocus, r, thick, Color.WHITE))
            addState(selectedState, round(0xB3000000.toInt(), r, thin, 0x80FFFFFF.toInt()))
            addState(defaultState, round(0x8C000000.toInt(), r, thin, 0x38FFFFFF))
        }
        if (glassOn) return StateListDrawable().apply {
            addState(pressState, round(0x4DFFFFFF, r, thick, Color.WHITE))
            addState(focusState, round(0x40FFFFFF, r, thick, Color.WHITE))
            addState(selectedState, round(0x2EFFFFFF, r, thin, 0x80FFFFFF.toInt()))
            addState(defaultState, round(0x1AFFFFFF, r, thin, 0x66FFFFFF))
        }
        return StateListDrawable().apply {
            addState(pressState, round(if (black) Color.BLACK else surfaceHigh, r, thick, focusBorder))
            addState(focusState, round(if (black) Color.BLACK else focusFill, r, if (black) dp(c, 3) else thick, focusBorder))
            addState(selectedState, round(if (black) Color.BLACK else focusFill, r, if (black) thick else thin, blue))
            addState(defaultState, round(if (black) Color.BLACK else fill, r, thin, border))
        }
    }

    fun oval(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    /** Degradado cian → azul → morado del logo. */
    fun gradient(radius: Float, strokeWidth: Int = 0, strokeColor: Int = Color.TRANSPARENT): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(cyan, blue, if (oled || base != null) blue else purple)).apply {
            cornerRadius = radius
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }

    fun label(c: Context, value: String, sizeSp: Float, color: Int = text, bold: Boolean = false): TextView =
        TextView(c).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        }

    /** Título pequeño de sección, en mayúsculas espaciadas. */
    fun sectionTitle(c: Context, value: String): TextView =
        label(c, value.uppercase(), 12f, cyan, bold = true).apply {
            letterSpacing = 0.09f
            setPadding(0, 0, 0, dp(c, 10))
        }

    fun card(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        // Cristal: vidrio oscuro transparente con borde claro (el blanco transparente encima de todo se veía gris
        // lavado en los menús y no se leía bien).
        background = if (glassOn) round(glassPanel, dp(c, 20).toFloat(), dp(c, 1), glassBorder) else GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(if (oled && pure) Color.BLACK else androidx.core.graphics.ColorUtils.blendARGB(surface, surfaceHigh, 0.35f), surface)).apply {
            cornerRadius = dp(c, 20).toFloat()
            setStroke(dp(c, 1), outline)
        }
        val p = dp(c, 20)
        setPadding(p, p, p, p)
    }

    fun input(c: Context, hintText: String): EditText = EditText(c).apply {
        hint = hintText
        setHintTextColor(Ui.muted)
        setTextColor(Ui.text)
        highlightColor = androidx.core.graphics.ColorUtils.setAlphaComponent(cyan, 92)
        if (Build.VERSION.SDK_INT >= 29) textCursorDrawable?.setTintList(ColorStateList.valueOf(cyan))
        textSize = 15f
        setSingleLine(true)
        background = controlBackground(c, 14, inputBg)
        minimumHeight = dp(c, 44)
        setPadding(dp(c, 14), dp(c, 11), dp(c, 14), dp(c, 11))
        // Dictado por voz: con el botón del micrófono o de buscar del control, o dejando apretado OK sobre el campo.
        var held = false
        setOnKeyListener { _, code, event ->
            val ok = code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER ||
                code == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
            when {
                code == android.view.KeyEvent.KEYCODE_SEARCH || code == android.view.KeyEvent.KEYCODE_VOICE_ASSIST -> {
                    if (event.action == android.view.KeyEvent.ACTION_UP && voiceFits(this)) startVoice(this)
                    voiceFits(this)
                }
                ok && event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 10 && voiceFits(this) -> {
                    held = true
                    startVoice(this)
                    true
                }
                ok && held -> {
                    // El resto de la pulsación larga no abre el teclado ni «pulsa» el campo.
                    if (event.action == android.view.KeyEvent.ACTION_UP) held = false
                    true
                }
                else -> false
            }
        }
        // En la TV se avisa en el propio campo (cuando no es clave ni dirección web).
        if (isTelevision(c)) post { if (voiceFits(this) && text.isEmpty()) hint = hint.toString() + "   🎤 deja apretado OK para dictar" }
    }

    const val VOICE_REQUEST = 74
    private var voiceTarget: java.lang.ref.WeakReference<EditText>? = null

    /** El dictado no se ofrece en claves ni en direcciones web (ahí no sirve y sería inseguro). */
    private fun voiceFits(field: EditText): Boolean {
        val variation = field.inputType and android.text.InputType.TYPE_MASK_VARIATION
        val klass = field.inputType and android.text.InputType.TYPE_MASK_CLASS
        return klass == android.text.InputType.TYPE_CLASS_TEXT && variation != android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD &&
            variation != android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD &&
            variation != android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD &&
            variation != android.text.InputType.TYPE_TEXT_VARIATION_URI && field.transformationMethod == null
    }

    /** Abre el dictado de Google: lo que digas se escribe en [field] (la pantalla que lo abrió recibe el resultado). */
    fun startVoice(field: EditText) {
        var context: Context? = field.context
        while (context is android.content.ContextWrapper && context !is Activity) context = context.baseContext
        val activity = context as? Activity ?: return
        voiceTarget = java.lang.ref.WeakReference(field)
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Di lo que quieres buscar")
        if (runCatching { activity.startActivityForResult(intent, VOICE_REQUEST) }.isFailure) {
            android.widget.Toast.makeText(activity, "Este equipo no tiene el dictado por voz de Google.", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** Lo dictado llega aquí (desde onActivityResult): se escribe en el campo que lo pidió. */
    fun voiceResult(data: android.content.Intent?) {
        val field = voiceTarget?.get() ?: return
        voiceTarget = null
        val said = data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim().orEmpty()
        if (said.isBlank() || !field.isAttachedToWindow) return
        field.setText(said)
        field.setSelection(field.text.length)
        field.requestFocus()
    }

    fun button(c: Context, value: String, style: Style = Style.PRIMARY, onClick: () -> Unit): TextView =
        button(c, value, style, 0, onClick)

    fun button(c: Context, value: String, style: Style = Style.PRIMARY, icon: Int, onClick: () -> Unit): TextView {
        val backgroundStyle = when (style) {
            // Botones en forma de píldora: más modernos y el borde de foco del mando se ve parejo.
            Style.PRIMARY -> controlBackground(c, 100, focusFill, cyan, Color.WHITE)
            Style.SECONDARY -> controlBackground(c, 100, surfaceHigh)
            Style.GHOST -> controlBackground(c, 100, Color.TRANSPARENT, Color.TRANSPARENT)
        }
        return TextView(c).apply {
            text = value
            textSize = 14f
            gravity = Gravity.CENTER
            if (style == Style.GHOST) setTextColor(optionText) else setTextColor(Ui.text)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = backgroundStyle
            minimumHeight = dp(c, 42)
            setPadding(dp(c, 18), dp(c, 9), dp(c, 18), dp(c, 9))
            if (icon != 0) {
                setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
                compoundDrawablePadding = dp(c, 8)
            }
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    /** Fila de opciones donde solo una queda elegida (resaltada con el degradado). */
    fun chipGroup(
        c: Context,
        options: List<Pair<String, String>>,
        initial: String,
        vertical: Boolean = false,
        fitLabels: Boolean = false,
        onSelect: (String) -> Unit
    ): LinearLayout {
        val row = LinearLayout(c).apply { orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
        val chips = ArrayList<TextView>()
        options.forEachIndexed { index, (key, label) ->
            val chip = TextView(c).apply {
                text = label
                tag = key
                background = controlBackground(c, 100, surfaceHigh)
                setTextColor(optionText)
                isSelected = key == initial
                if (fitLabels) minimumHeight = dp(c, 44)
                textSize = 14f
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(dp(c, 10), dp(c, 9), dp(c, 10), dp(c, 9))
                // Todas del mismo alto: una sola línea, y el texto se achica si no cabe.
                maxLines = 1
                isSingleLine = true
                if (!vertical && !fitLabels && Build.VERSION.SDK_INT >= 26) {
                    setAutoSizeTextTypeUniformWithConfiguration(10, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                } else {
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                isFocusable = true
                isClickable = true
            }
            chip.setOnClickListener {
                chips.forEach { it.isSelected = it === chip }
                onSelect(key)
            }
            chips.add(chip)
            val params = if (vertical) {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    if (index > 0) topMargin = dp(c, 8)
                }
            } else {
                LinearLayout.LayoutParams(if (fitLabels) LinearLayout.LayoutParams.WRAP_CONTENT else 0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) leftMargin = dp(c, 10)
                }
            }
            row.addView(chip, params)
        }
        return row
    }

    /**
     * Android 15 dibuja las apps detrás de la barra de notificaciones y la de navegación:
     * se añade su alto como margen interior para que nada quede tapado ni cortado.
     */
    fun applySystemBarsPadding(view: View) {
        val left = view.paddingLeft
        val top = view.paddingTop
        val right = view.paddingRight
        val bottom = view.paddingBottom
        view.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                v.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + maxOf(bars.bottom, ime.bottom))
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    left + insets.systemWindowInsetLeft, top + insets.systemWindowInsetTop,
                    right + insets.systemWindowInsetRight, bottom + insets.systemWindowInsetBottom
                )
            }
            insets
        }
        if (view.isAttachedToWindow) view.requestApplyInsets()
        else view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { v.requestApplyInsets() }
            override fun onViewDetachedFromWindow(v: View) {}
        })
    }

    /** Pantalla completa para el video: se ocultan las barras (vuelven deslizando desde el borde). */
    fun hideSystemBars(activity: Activity) {
        val window = activity.window
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    fun matchWrap(bottomMargin: Int = 0, topMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            this.bottomMargin = bottomMargin
            this.topMargin = topMargin
        }
}
