package com.juntosplay.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Estilo visual oscuro de la app: colores del logo y piezas reutilizables. */
object Ui {
    val bgTop = Color.parseColor("#0B0E24")
    val bgBottom = Color.parseColor("#05060F")
    val surface = Color.parseColor("#141933")
    val surfaceHigh = Color.parseColor("#1D2550")
    val inputBg = Color.parseColor("#0C1029")
    val outline = Color.parseColor("#2A3260")
    val text = Color.WHITE
    val muted = Color.parseColor("#A9B2D0")
    val hint = Color.parseColor("#6B7499")
    val cyan = Color.parseColor("#22D3EE")
    val blue = Color.parseColor("#4F8CFF")
    val purple = Color.parseColor("#B44CFF")
    val ok = Color.parseColor("#34D399")
    val warn = Color.parseColor("#FBBF24")
    val bad = Color.parseColor("#F87171")

    enum class Style { PRIMARY, SECONDARY, GHOST }

    fun dp(c: Context, value: Int): Int = (value * c.resources.displayMetrics.density + 0.5f).toInt()

    fun pageBackground(): Drawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(bgTop, bgBottom))

    fun round(color: Int, radius: Float, strokeWidth: Int = 0, strokeColor: Int = Color.TRANSPARENT): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }

    fun oval(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    /** Degradado cian → azul → morado del logo. */
    fun gradient(radius: Float, strokeWidth: Int = 0, strokeColor: Int = Color.TRANSPARENT): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(cyan, blue, purple)).apply {
            cornerRadius = radius
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }

    fun label(c: Context, value: String, sizeSp: Float, color: Int = text, bold: Boolean = false): TextView =
        TextView(c).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }

    /** Título pequeño de sección, en mayúsculas espaciadas. */
    fun sectionTitle(c: Context, value: String): TextView =
        label(c, value.uppercase(), 12f, cyan, bold = true).apply {
            letterSpacing = 0.14f
            setPadding(0, 0, 0, dp(c, 10))
        }

    fun card(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = round(surface, dp(c, 22).toFloat(), dp(c, 1), outline)
        val p = dp(c, 24)
        setPadding(p, p, p, p)
    }

    fun input(c: Context, hintText: String): EditText = EditText(c).apply {
        val r = dp(c, 12).toFloat()
        val normal = round(inputBg, r, dp(c, 1), outline)
        val focused = round(inputBg, r, dp(c, 2), cyan)
        hint = hintText
        setHintTextColor(Ui.hint)
        setTextColor(Ui.text)
        textSize = 16f
        setSingleLine(true)
        background = normal
        setPadding(dp(c, 16), dp(c, 13), dp(c, 16), dp(c, 13))
        setOnFocusChangeListener { v, hasFocus -> v.background = if (hasFocus) focused else normal }
    }

    fun button(c: Context, value: String, style: Style = Style.PRIMARY, onClick: () -> Unit): TextView {
        val r = dp(c, 14).toFloat()
        val normal: Drawable = when (style) {
            Style.PRIMARY -> gradient(r)
            Style.SECONDARY -> round(surfaceHigh, r, dp(c, 1), outline)
            Style.GHOST -> round(Color.TRANSPARENT, r)
        }
        val focused: Drawable = when (style) {
            Style.PRIMARY -> gradient(r, dp(c, 3), Color.WHITE)
            else -> round(surfaceHigh, r, dp(c, 2), cyan)
        }
        val restColor = if (style == Style.GHOST) muted else text
        return TextView(c).apply {
            text = value
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(restColor)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = normal
            setPadding(dp(c, 22), dp(c, 14), dp(c, 22), dp(c, 14))
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { v, hasFocus ->
                v.background = if (hasFocus) focused else normal
                setTextColor(if (hasFocus) Ui.text else restColor)
                v.animate().scaleX(if (hasFocus) 1.03f else 1f).scaleY(if (hasFocus) 1.03f else 1f).setDuration(120).start()
            }
            setOnClickListener { onClick() }
        }
    }

    /** Fila de opciones donde solo una queda elegida (resaltada con el degradado). */
    fun chipGroup(c: Context, options: List<Pair<String, String>>, initial: String, onSelect: (String) -> Unit): LinearLayout {
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        val chips = ArrayList<TextView>()
        var selected = initial
        val r = dp(c, 100).toFloat()
        fun paint(chip: TextView) {
            val isSelected = chip.tag == selected
            chip.background = when {
                isSelected -> gradient(r, if (chip.isFocused) dp(c, 3) else 0, Color.WHITE)
                chip.isFocused -> round(surfaceHigh, r, dp(c, 2), cyan)
                else -> round(surfaceHigh, r, dp(c, 1), outline)
            }
            chip.setTextColor(if (isSelected || chip.isFocused) text else muted)
        }
        options.forEachIndexed { index, (key, label) ->
            val chip = TextView(c).apply {
                text = label
                tag = key
                textSize = 15f
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(dp(c, 18), dp(c, 10), dp(c, 18), dp(c, 10))
                isFocusable = true
                isClickable = true
            }
            chip.setOnFocusChangeListener { _, _ -> paint(chip) }
            chip.setOnClickListener {
                selected = key
                chips.forEach { paint(it) }
                onSelect(key)
            }
            chips.add(chip)
            paint(chip)
            row.addView(chip, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index > 0) leftMargin = dp(c, 10)
            })
        }
        return row
    }

    fun matchWrap(bottomMargin: Int = 0, topMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            this.bottomMargin = bottomMargin
            this.topMargin = topMargin
        }
}
