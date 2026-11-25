package com.juntosplay.app

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextClock
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import coil.load
import java.util.Locale

internal data class PlaybackInfo(
    val title: String,
    val detail: String = "",
    val technical: List<String> = emptyList(),
    val next: String = "",
    val logo: String = "",
    val station: String = "",
    val progress: Int? = null,
    val frameRate: Float = 0f,
    /** Descripción del programa (aviso de abajo al cambiar de canal). */
    val description: String = "",
    /** Canales: horario del programa y lo que falta, con la rayita en medio ("20:02 – 20:57 ▬▬ 10 min"). */
    val timeLabel: String = "",
    val remaining: String = "",
    /** Sección del canal en el proveedor (p. ej. "Premium Cinema"), al lado de lo que falta. */
    val category: String = "",
    /** Se está grabando este canal: sale la marca roja «GRABANDO» junto a «EN VIVO». */
    val recording: Boolean = false
)

internal data class PlaybackAction(
    val label: String,
    val caption: String = "",
    val icon: Int = R.drawable.catalog_more,
    val logo: String = "",
    val showFrameRate: Boolean = false,
    /** Avance del programa del canal (0–100): rayita debajo del botón. */
    val progress: Int? = null,
    /** Nombre fijo para ordenar los botones (si el texto cambia, como Grabar/Detener). Vacío: el texto. */
    val key: String = "",
    val run: () -> Unit
) {
    val orderId: String get() = key.ifBlank { label }
}

/**
 * Barra de reproducción de abajo: raya de tiempo, botones y, en los canales, EN VIVO.
 * Películas y series: posición y duración de la película. Canales: lo que va del programa y lo que dura.
 */
internal interface PlaybackTimeline {
    fun positionMs(): Long
    fun durationMs(): Long
    fun seekTo(positionMs: Long)
    fun togglePlay()
    fun isPlaying(): Boolean
    /** Canal en vivo: sale EN VIVO y la raya solo muestra cuánto va del programa. */
    fun isLive(): Boolean = false
    /** Se puede atrasar y adelantar (películas y series; en vivo solo si la señal lo permite). */
    fun canSeek(): Boolean = !isLive() && durationMs() > 0
    fun seekBy(deltaMs: Long) {
        val duration = durationMs()
        seekTo((positionMs() + deltaMs).coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE))
    }
    /** En vivo: se está viendo lo último (no se pausó ni se atrasó). */
    fun atLiveEdge(): Boolean = true
    fun goLive() {}
    /** Hay canal anterior y siguiente (botones |◀ y ▶|). */
    fun hasChannels(): Boolean = false
    fun previous() {}
    fun next() {}
}

internal class PlaybackPanel(
    private val activity: Activity,
    private val parent: FrameLayout,
    private val info: () -> PlaybackInfo,
    private val quick: () -> List<PlaybackAction>,
    private val options: () -> List<PlaybackAction>,
    private val onClosed: () -> Unit,
    private val requireQuickForOptions: Boolean = false,
    private val liveOverlay: Boolean = false,
    /**
     * Barra de reproducción de abajo. Películas y series: sale con OK. Canales: sale con ▲ desde los
     * botones de abajo (Guía, Recientes…) y con ▼ se vuelve a ellos.
     */
    private val timeline: PlaybackTimeline? = null,
    /** Muestra la marca roja "EN VIVO" junto al título. */
    private val liveBadge: (() -> Boolean)? = null
) {
    private enum class Page { PLAYER, QUICK, OPTIONS }
    private var page: Page? = null
    /** Películas y series: OK abre la barra de reproducción directamente (no hay página de botones). */
    private val playerFirst = timeline != null && !liveOverlay
    private val handled = mutableSetOf<Int>()
    private val banner = column()
    private val header = column()
    private val buttons = LinearLayout(activity)
    private val scroll = HorizontalScrollView(activity).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        isFillViewport = true
        addView(buttons)
    }
    private val actions = column().apply { addView(scroll, Ui.matchWrap()) }
    /** Fecha y hora arriba a la derecha mientras está la barra de reproducción. */
    private val clock = headerClock().apply {
        textSize = 17f
        setTextColor(Ui.text)
        setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), 0xCC000000.toInt())
    }
    private var lastBanner: PlaybackInfo? = null
    private var lastHeader: PlaybackInfo? = null
    private var bannerProgress: ClipDrawable? = null
    private var headerProgress: ClipDrawable? = null
    private var frameRateCaption: TextView? = null
    private val fade = Runnable { banner.visibility = View.GONE }
    private val hideControls = Runnable(::close)
    private var touchInProgress = false

    init {
        parent.addView(banner, edge(Gravity.BOTTOM))
        parent.addView(header, edge(Gravity.TOP))
        // Los botones de los canales un poco más arriba del borde de abajo (en algunas TV la orilla los cortaba).
        parent.addView(actions, edge(Gravity.BOTTOM).apply { bottomMargin = actionsBottom })
        parent.addView(clock, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            setMargins(dp(24), dp(18), dp(30), 0)
        })
        listOf(banner, header, actions, clock).forEach { it.visibility = View.GONE }
    }

    /** Teléfono en pantalla chica: la barra se sube hasta el borde de abajo del video (0 = la pantalla entera). */
    fun bottomInset(px: Int) {
        for (view in listOf(banner, actions)) {
            val params = view.layoutParams as? FrameLayout.LayoutParams ?: continue
            params.bottomMargin = (if (view === actions) actionsBottom else dp(if (liveOverlay) 16 else 20)) + px
            view.layoutParams = params
        }
        clock.alpha = if (px > 0) 0f else 1f
    }

    private fun dp(value: Int) = Ui.dp(activity, value)
    private val actionsBottom: Int get() = dp(if (liveOverlay) 40 else 20)
    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private fun edge(gravity: Int) = FrameLayout.LayoutParams(-1, -2, gravity).apply {
        // En vivo la franja queda despegada de los bordes de la pantalla.
        val side = dp(if (liveOverlay) 28 else 20)
        val vertical = dp(if (liveOverlay) 16 else 20)
        setMargins(side, vertical, side, vertical)
    }
    // En la TV en vivo los recuadros son transparentes: se ve el video detrás.
    private fun background() = if (liveOverlay) Ui.round(OverlayLook.tint(activity, OverlayLook.STRIP, 0xFF121216.toInt()), dp(16).toFloat())
        else Ui.round((Ui.surface and 0xFFFFFF) or 0xEB000000.toInt(), dp(16).toFloat(), dp(1), Ui.outline)

    private fun paint(card: LinearLayout) {
        val value = info()
        // El porcentaje se actualiza independientemente del título, el logo y las pistas.
        val content = value.copy(progress = value.progress?.let { 0 }, frameRate = 0f, recording = false)
        val isBanner = card === banner
        val progressLevel = (value.progress ?: 0).coerceIn(0, 100) * 100
        if (content == (if (isBanner) lastBanner else lastHeader)) {
            (if (isBanner) bannerProgress else headerProgress)?.level = progressLevel
            return
        }
        if (isBanner) {
            lastBanner = content
            bannerProgress = null
        } else {
            lastHeader = content
            headerProgress = null
        }
        if (card.background == null) card.background = background()
        card.removeAllViews()
        // En la TV en vivo la franja es fina y compacta.
        if (liveOverlay) card.setPadding(dp(18), dp(11), dp(18), dp(11)) else card.setPadding(dp(16), dp(12), dp(16), dp(12))
        var inlineFill: ClipDrawable? = null
        val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        if (value.station.isNotBlank() || value.logo.isNotBlank()) {
            val identity = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
            identity.addView(icon(R.drawable.catalog_tv, value.logo),
                if (liveOverlay) LinearLayout.LayoutParams(dp(46), dp(33)) else LinearLayout.LayoutParams(dp(48), dp(36)))
            if (value.station.isNotBlank()) identity.addView(line(value.station, if (liveOverlay) 10f else 11f, Ui.cyan, true).apply {
                gravity = Gravity.CENTER
            }, Ui.matchWrap(topMargin = dp(if (liveOverlay) 2 else 4)))
            row.addView(identity, LinearLayout.LayoutParams(dp(if (liveOverlay) 76 else 82), -2).apply { rightMargin = dp(12) })
        }
        row.addView(column().apply {
            val title = line(value.title, if (liveOverlay) 16f else 18f, Ui.text, true)
            var timeRow: LinearLayout? = null
            val badge = liveBadge?.invoke() ?: liveOverlay
            if (liveOverlay && card === header) {
                addView(LinearLayout(activity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(title, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(headerClock(), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
                }, Ui.matchWrap(bottomMargin = dp(1)))
            } else {
                addView(title, Ui.matchWrap(bottomMargin = dp(if (liveOverlay) 1 else 3)))
            }
            if (liveOverlay && value.timeLabel.isNotBlank() && value.progress != null) {
                // "20:02 – 20:57 ▬▬▬ 10 min": la rayita se va llenando con lo que lleva el programa.
                addView(LinearLayout(activity).apply {
                    timeRow = this
                    gravity = Gravity.CENTER_VERTICAL
                    addView(line(value.timeLabel, 12f, Ui.muted), LinearLayout.LayoutParams(-2, -2))
                    val fill = ClipDrawable(Ui.round(Color.WHITE, dp(2).toFloat()), Gravity.LEFT, ClipDrawable.HORIZONTAL)
                    addView(FrameLayout(activity).apply {
                        background = Ui.round(0x59FFFFFF, dp(2).toFloat())
                        foreground = fill
                    }, LinearLayout.LayoutParams(dp(72), dp(4)).apply {
                        marginStart = dp(10)
                        marginEnd = dp(10)
                    })
                    fill.level = progressLevel
                    inlineFill = fill
                    if (value.remaining.isNotBlank()) addView(line(value.remaining, 12f, Ui.text, true), LinearLayout.LayoutParams(-2, -2))
                    if (value.category.isNotBlank()) addView(line(value.category, 12f, Ui.text, true),
                        LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(18) })
                }, Ui.matchWrap())
            } else if (value.detail.isNotBlank()) addView(line(value.detail, 12f, Ui.muted), Ui.matchWrap())
            if (liveOverlay && isBanner && value.description.isNotBlank()) {
                addView(line(value.description, 11f, Ui.muted).apply { maxLines = 1 }, Ui.matchWrap(topMargin = dp(1)))
            }
            if (value.technical.isNotEmpty() || badge) {
                val chips = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
                // Todas las etiquetas (calidad, FPS, códec, audio) con el mismo estilo y borde.
                value.technical.forEach { spec ->
                    val together = spec == "● JUNTOS" || spec == "● EN LA SALA" || spec == "SOLO TÚ" || spec.startsWith("✕")
                    chips.addView(line(spec, if (liveOverlay) 9.5f else 11f, if (together) Color.WHITE else Color.BLACK, true).apply {
                        gravity = Gravity.CENTER
                        background = when {
                            spec == "● JUNTOS" -> Ui.round(0xFFD32F2F.toInt(), dp(6).toFloat())
                            spec == "● EN LA SALA" -> Ui.round(0xFFB26A00.toInt(), dp(6).toFloat())
                            together -> Ui.round(0xFF55585E.toInt(), dp(6).toFloat())
                            else -> Ui.round(Ui.cyan, dp(6).toFloat(), dp(1), Ui.text)
                        }
                        elevation = dp(2).toFloat()
                        if (liveOverlay) setPadding(dp(6), dp(1), dp(6), dp(1)) else setPadding(dp(8), dp(4), dp(8), dp(4))
                    }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
                }
                // Canales: la marca roja "EN VIVO" va al final de las etiquetas.
                if (badge) chips.addView(liveMark(), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
                // En vivo las etiquetas van en la misma línea del horario, no en una fila aparte.
                val sameLine = timeRow?.takeIf { activity.resources.configuration.screenWidthDp >= 600 }
                if (sameLine != null) sameLine.addView(chips, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(16) })
                else addView(chips, Ui.matchWrap(topMargin = dp(if (liveOverlay) 3 else 6)))
            }
            if (value.next.isNotBlank()) addView(line(value.next, if (liveOverlay) 11f else 12f, Ui.muted), Ui.matchWrap(topMargin = dp(if (liveOverlay) 1 else 5)))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(row, Ui.matchWrap())
        val inline = inlineFill
        if (inline != null) {
            // Con la rayita chica no va la raya larga (esa sale con OK, encima de los botones).
            if (isBanner) bannerProgress = inline else headerProgress = inline
        } else if (value.progress != null) {
            val fill = ClipDrawable(Ui.round(Ui.cyan, dp(2).toFloat()), Gravity.LEFT, ClipDrawable.HORIZONTAL)
            card.addView(FrameLayout(activity).apply {
                background = Ui.round(Ui.surfaceHigh, dp(2).toFloat())
                foreground = fill
            }, LinearLayout.LayoutParams(-1, dp(3)).apply { topMargin = dp(9) })
            fill.level = progressLevel
            if (isBanner) bannerProgress = fill else headerProgress = fill
        }
    }

    /** Marca roja "● EN VIVO" de los canales. */
    /** «● GRABANDO» en rojo, con el punto parpadeando: solo mientras se graba el canal. */
    private fun recordingMark() = LinearLayout(activity).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = Ui.round(0xFFD32F2F.toInt(), dp(20).toFloat())
        setPadding(dp(8), dp(1), dp(9), dp(1))
        addView(line("●", if (liveOverlay) 9f else 10.5f, Color.WHITE, true).apply {
            startAnimation(android.view.animation.AlphaAnimation(1f, 0.15f).apply {
                duration = 650
                repeatMode = android.view.animation.Animation.REVERSE
                repeatCount = android.view.animation.Animation.INFINITE
            })
        }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(5) })
        addView(line("GRABANDO", if (liveOverlay) 9.5f else 11f, Color.WHITE, true).apply { letterSpacing = 0.06f },
            LinearLayout.LayoutParams(-2, -2))
    }

    private fun liveMark() = line("● EN VIVO", if (liveOverlay) 9.5f else 11f, Color.WHITE, true).apply {
        gravity = Gravity.CENTER
        if (liveOverlay) {
            // En la franja de TV: pastilla chica, del alto de las demás etiquetas, con el punto rojo y borde fino.
            text = android.text.SpannableString("●  EN VIVO").apply {
                setSpan(android.text.style.ForegroundColorSpan(0xFFFF5A52.toInt()), 0, 1, 0)
                setSpan(android.text.style.RelativeSizeSpan(0.8f), 0, 1, 0)
            }
            letterSpacing = 0.06f
            background = Ui.round(0x59B71C1C, dp(20).toFloat(), dp(1), 0xCCFF5A52.toInt())
            setPadding(dp(8), dp(1), dp(9), dp(1))
        } else {
            background = Ui.round(0xFFE53935.toInt(), dp(6).toFloat(), dp(1), Ui.text)
            elevation = dp(2).toFloat()
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
    }

    // --- OK en canales: raya del programa encima de los botones y flecha ▼ debajo ---
    private var quickLine: SeekBar? = null
    private var quickMore: ImageView? = null

    private fun addQuickExtras() {
        val line = SeekBar(activity).apply {
            isFocusable = false
            isClickable = false
            setOnTouchListener { _, _ -> true }
            max = 1000
            progressTintList = ColorStateList.valueOf(Ui.cyan)
            progressBackgroundTintList = ColorStateList.valueOf(0x80FFFFFF.toInt())
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            setPadding(dp(10), dp(4), dp(10), dp(8))
            background = null
        }
        // Aire entre la raya del programa y los botones (antes quedaban pegados a la raya).
        actions.addView(line, 0, Ui.matchWrap(bottomMargin = dp(if (liveOverlay) 7 else 14)))
        val more = moreArrow()
        actions.addView(more, LinearLayout.LayoutParams(dp(28), dp(22)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(6)
        })
        quickLine = line
        quickMore = more
        updateQuickLine()
    }

    private fun removeQuickExtras() {
        quickLine?.let { actions.removeView(it) }
        quickMore?.let { actions.removeView(it) }
        quickLine = null
        quickMore = null
    }

    private fun updateQuickLine() {
        val line = quickLine ?: return
        val source = timeline ?: return
        val duration = source.durationMs()
        line.progress = if (duration > 0) (source.positionMs().coerceIn(0L, duration) * 1000 / duration).toInt() else 1000
    }

    /** Flecha ▼: abajo hay más opciones. */
    private fun moreArrow() = ImageView(activity).apply {
        setImageResource(R.drawable.tp_bar_more)
        imageTintList = ColorStateList.valueOf(Ui.muted)
    }

    // --- Barra de reproducción de abajo ---
    private var playerBar: LinearLayout? = null
    private var playerSeek: SeekBar? = null
    private var playerProgress: ClipDrawable? = null
    private var playerTime: TextView? = null
    private var playerPlay: ImageView? = null
    private var playerLive: TextView? = null
    private var playerTitle: TextView? = null
    private var playerSubtitle: TextView? = null
    private var playerChips: LinearLayout? = null
    private var lastPlayerInfo: PlaybackInfo? = null
    private var shownPlaying: Boolean? = null
    private var shownEdge: Boolean? = null
    /** Se está moviendo la raya: el salto se hace al soltar (o al dejar de mover un momento). */
    private var playerSeeking = false
    private val focusedState = intArrayOf(android.R.attr.state_focused)
    private val pressedState = intArrayOf(android.R.attr.state_pressed)
    /** Cada segundo: la barra de reproducción o la raya del programa encima de los botones. */
    private val tick = object : Runnable {
        override fun run() {
            when (page) {
                Page.PLAYER -> {
                    updatePlayer()
                    updatePlayerInfo()
                }
                Page.QUICK -> if (quickLine != null) updateQuickLine() else return
                else -> return
            }
            // La rayita de arriba (lo que lleva el programa) también se va llenando.
            if (header.visibility == View.VISIBLE) paint(header)
            parent.postDelayed(this, 1000)
        }
    }
    private val seekCommit = Runnable {
        val bar = playerSeek
        if (bar != null) timeline?.seekTo(bar.progress * 1000L)
        playerSeeking = false
    }

    /** "00:22", "35:20" o "1:53:25". */
    private fun barClock(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
            else String.format(Locale.ROOT, "%02d:%02d", m, s)
    }

    private fun showTime(position: Long, duration: Long) {
        playerTime?.text = when {
            duration > 0 -> barClock(position) + " / " + barClock(duration)
            timeline?.isLive() == true -> "En directo"
            else -> barClock(position)
        }
    }

    private fun shadow(view: TextView) = view.apply {
        setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), 0xCC000000.toInt())
    }

    /** Botón redondo: gris transparente; con el foco, blanco con el ícono oscuro. */
    private fun roundButton(icon: Int, size: Int, description: String, onClick: () -> Unit) = ImageView(activity).apply {
        setImageResource(icon)
        scaleType = ImageView.ScaleType.FIT_CENTER
        val inset = size * 27 / 100
        setPadding(inset, inset, inset, inset)
        isFocusable = true
        isClickable = true
        contentDescription = description
        background = StateListDrawable().apply {
            addState(focusedState, Ui.oval(Color.WHITE))
            addState(pressedState, Ui.oval(Color.WHITE))
            addState(intArrayOf(), Ui.oval(0x4DFFFFFF))
        }
        imageTintList = ColorStateList(arrayOf(focusedState, pressedState, intArrayOf()),
            intArrayOf(0xFF101014.toInt(), 0xFF101014.toInt(), Color.WHITE))
        setOnClickListener {
            onClick()
            restartIdleTimer()
        }
    }

    /** Píldora "EN VIVO" de los canales: punto rojo en directo; si se pausó, "IR AL VIVO" vuelve al directo. */
    private fun livePill(onClick: () -> Unit) = TextView(activity).apply {
        text = "EN VIVO"
        textSize = 13f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        gravity = Gravity.CENTER
        setPadding(dp(14), 0, dp(16), 0)
        compoundDrawablePadding = dp(8)
        isFocusable = true
        isClickable = true
        background = StateListDrawable().apply {
            addState(focusedState, Ui.round(Color.WHITE, dp(20).toFloat()))
            addState(pressedState, Ui.round(Color.WHITE, dp(20).toFloat()))
            addState(intArrayOf(), Ui.round(0x4DFFFFFF, dp(20).toFloat()))
        }
        setTextColor(ColorStateList(arrayOf(focusedState, pressedState, intArrayOf()),
            intArrayOf(0xFF101014.toInt(), 0xFF101014.toInt(), Color.WHITE)))
        setOnClickListener {
            onClick()
            restartIdleTimer()
        }
    }

    private fun liveDot(live: Boolean) = Ui.oval(if (live) 0xFFE53935.toInt() else 0xFF9AA3AF.toInt()).apply {
        setSize(dp(10), dp(10))
    }

    /** Etiqueta chica con borde (resolución, FPS, audio…). */
    private fun barChip(text: String) = line(text, 11f, Color.WHITE, true).apply {
        gravity = Gravity.CENTER
        // Viendo juntos: rojo cuando el otro está viendo lo mismo; ámbar si solo está en la sala; gris si no hay nadie
        // o no hay conexión. Las demás etiquetas (calidad, FPS…) van como siempre.
        background = when {
            text == "● JUNTOS" -> Ui.round(0xFFD32F2F.toInt(), dp(4).toFloat())
            text == "● EN LA SALA" -> Ui.round(0xFFB26A00.toInt(), dp(4).toFloat())
            text == "SOLO TÚ" || text.startsWith("✕") -> Ui.round(0xFF55585E.toInt(), dp(4).toFloat())
            else -> Ui.round(0x66000000, dp(4).toFloat(), dp(1), 0xE6FFFFFF.toInt())
        }
        setPadding(dp(7), dp(2), dp(7), dp(3))
    }

    private fun addPlayerBar(focusSeek: Boolean) {
        val source = timeline ?: return
        val live = source.isLive()
        val bar = column()

        // 1) Título (y canal) a la izquierda, etiquetas a la derecha.
        val titleRow = LinearLayout(activity).apply {
            gravity = Gravity.BOTTOM
            setPadding(dp(10), 0, dp(10), 0)
        }
        val texts = column()
        val title = shadow(line("", 22f, Ui.text, true))
        val subtitle = shadow(line("", 13f, Ui.muted))
        texts.addView(title, Ui.matchWrap())
        texts.addView(subtitle, Ui.matchWrap(topMargin = dp(2)))
        titleRow.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val chips = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END }
        titleRow.addView(chips, LinearLayout.LayoutParams(-2, -2).apply {
            marginStart = dp(12)
            bottomMargin = dp(3)
        })
        if (!liveOverlay) bar.addView(titleRow, Ui.matchWrap())

        // 2) La raya: se mueve en películas y series; en los canales muestra cuánto va del programa.
        var seek: SeekBar? = null
        if (source.canSeek()) {
            seek = SeekBar(activity).apply {
                isFocusable = true
                // Cada toque mueve 10 s; dejándolo apretado sigue avanzando rápido.
                keyProgressIncrement = 10
                max = 1
                progressTintList = ColorStateList.valueOf(Ui.cyan)
                progressBackgroundTintList = ColorStateList.valueOf(0x80FFFFFF.toInt())
                thumbTintList = ColorStateList(arrayOf(focusedState, intArrayOf()), intArrayOf(Color.WHITE, Ui.cyan))
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = null
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        playerSeeking = true
                        showTime(progress * 1000L, source.durationMs())
                        // Se espera a que deje de mover para saltar una sola vez.
                        this@PlaybackPanel.parent.removeCallbacks(seekCommit)
                        this@PlaybackPanel.parent.postDelayed(seekCommit, 700)
                        restartIdleTimer()
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) {
                        playerSeeking = true
                        this@PlaybackPanel.parent.removeCallbacks(hideControls)
                    }

                    override fun onStopTrackingTouch(seekBar: SeekBar) {
                        this@PlaybackPanel.parent.removeCallbacks(seekCommit)
                        seekCommit.run()
                        restartIdleTimer()
                    }
                })
            }
            bar.addView(seek, Ui.matchWrap(topMargin = dp(6)))
        } else {
            val fill = ClipDrawable(Ui.round(Ui.cyan, dp(2).toFloat()), Gravity.LEFT, ClipDrawable.HORIZONTAL)
            bar.addView(FrameLayout(activity).apply {
                background = Ui.round(0x66FFFFFF, dp(2).toFloat())
                foreground = fill
            }, LinearLayout.LayoutParams(-1, dp(4)).apply {
                topMargin = dp(14)
                bottomMargin = dp(10)
                marginStart = dp(10)
                marginEnd = dp(10)
            })
            playerProgress = fill
        }

        // 3) Tiempo a la izquierda, botones al centro y EN VIVO (o "desde el principio") a la derecha.
        val small = dp(46)
        val big = dp(58)
        val controls = FrameLayout(activity).apply { setPadding(dp(10), 0, dp(10), 0) }
        val time = shadow(line("", 15f, Ui.text, true))
        controls.addView(time, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL))
        val center = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        fun add(view: View, size: Int) = center.addView(view, LinearLayout.LayoutParams(size, size).apply {
            marginStart = dp(9)
            marginEnd = dp(9)
        })
        if (live && source.hasChannels()) add(roundButton(R.drawable.tp_bar_previous, small, "Canal anterior") {
            source.previous()
        }, small)
        if (source.canSeek()) add(roundButton(R.drawable.tp_bar_rewind, small, "Atrasar 10 segundos") {
            source.seekBy(-10_000L)
            updatePlayer()
        }, small)
        val play = roundButton(if (source.isPlaying()) R.drawable.tp_bar_pause else R.drawable.tp_bar_play, big, "Pausa") {
            source.togglePlay()
            updatePlayer()
        }
        add(play, big)
        if (source.canSeek()) add(roundButton(R.drawable.tp_bar_forward, small, "Adelantar 10 segundos") {
            source.seekBy(10_000L)
            updatePlayer()
        }, small)
        if (live && source.hasChannels()) add(roundButton(R.drawable.tp_bar_next, small, "Canal siguiente") {
            source.next()
        }, small)
        controls.addView(center, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        if (live) {
            val pill = livePill {
                source.goLive()
                updatePlayer()
            }
            controls.addView(pill, FrameLayout.LayoutParams(-2, dp(40), Gravity.END or Gravity.CENTER_VERTICAL))
            playerLive = pill
        } else if (source.canSeek()) {
            controls.addView(roundButton(R.drawable.tp_bar_restart, small, "Desde el principio") {
                source.seekTo(0L)
                if (!source.isPlaying()) source.togglePlay()
                updatePlayer()
            }, FrameLayout.LayoutParams(small, small, Gravity.END or Gravity.CENTER_VERTICAL))
        }
        bar.addView(controls, LinearLayout.LayoutParams(-1, big).apply { topMargin = dp(2) })

        // 4) Flecha ▼: abajo están los ajustes (películas) o Guía, Historial y los canales (TV en vivo).
        bar.addView(moreArrow(), LinearLayout.LayoutParams(dp(28), dp(22)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(2)
        })

        playerBar = bar
        playerSeek = seek
        playerTime = time
        playerPlay = play
        if (!liveOverlay) {
            playerTitle = title
            playerSubtitle = subtitle
            playerChips = chips
        }
        lastPlayerInfo = null
        shownPlaying = null
        shownEdge = null
        playerSeeking = false
        actions.addView(bar, 0, Ui.matchWrap())
        updatePlayerInfo(force = true)
        updatePlayer()
        val first: View = (if (focusSeek) seek else null) ?: play
        first.requestFocus()
    }

    private fun removePlayerBar() {
        parent.removeCallbacks(tick)
        if (playerSeeking) {
            parent.removeCallbacks(seekCommit)
            seekCommit.run()
        }
        playerBar?.let { actions.removeView(it) }
        playerBar = null
        playerSeek = null
        playerProgress = null
        playerTime = null
        playerPlay = null
        playerLive = null
        playerTitle = null
        playerSubtitle = null
        playerChips = null
        lastPlayerInfo = null
        shownPlaying = null
        shownEdge = null
    }

    /** Tiempo, raya, pausa/reproducir y EN VIVO (cada segundo y después de cada botón). */
    private fun updatePlayer() {
        val source = timeline ?: return
        if (page != Page.PLAYER) return
        val duration = source.durationMs().coerceAtLeast(0L)
        val position = source.positionMs().coerceAtLeast(0L).let { if (duration > 0) it.coerceAtMost(duration) else it }
        if (!playerSeeking) {
            showTime(position, duration)
            playerSeek?.let { bar ->
                if (duration > 0) {
                    val max = (duration / 1000).toInt().coerceAtLeast(1)
                    if (bar.max != max) bar.max = max
                    bar.progress = (position / 1000).toInt()
                }
            }
        }
        playerProgress?.level = if (duration > 0) (position * 10_000 / duration).toInt().coerceIn(0, 10_000) else 10_000
        val playing = source.isPlaying()
        if (playing != shownPlaying) {
            shownPlaying = playing
            playerPlay?.setImageResource(if (playing) R.drawable.tp_bar_pause else R.drawable.tp_bar_play)
            playerPlay?.contentDescription = if (playing) "Pausa" else "Reproducir"
            // En pausa la barra se queda; al seguir, se esconde sola a los pocos segundos.
            restartIdleTimer()
        }
        playerLive?.let { pill ->
            val edge = source.atLiveEdge()
            if (edge != shownEdge) {
                shownEdge = edge
                pill.text = if (edge) "EN VIVO" else "IR AL VIVO"
                pill.setCompoundDrawablesRelativeWithIntrinsicBounds(liveDot(edge), null, null, null)
            }
        }
    }

    /** Título, canal y etiquetas de la barra (solo se rehacen si cambiaron). */
    private fun updatePlayerInfo(force: Boolean = false) {
        if (page != Page.PLAYER || playerBar == null) return
        val value = info().copy(progress = null, frameRate = 0f)
        if (!force && value == lastPlayerInfo) return
        lastPlayerInfo = value
        playerTitle?.text = value.title
        val subtitle = if (liveOverlay) listOf(value.station, value.detail).filter(String::isNotBlank).joinToString(" · ")
            else value.detail
        playerSubtitle?.apply {
            text = subtitle
            visibility = if (subtitle.isBlank()) View.GONE else View.VISIBLE
        }
        playerChips?.let { chips ->
            chips.removeAllViews()
            value.technical.forEach { spec ->
                chips.addView(barChip(spec), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
            }
        }
    }

    /** Películas y series: la barra va de lado a lado sobre un degradado oscuro (sin recuadro). */
    private fun styleActions(fullBleed: Boolean) {
        val params = actions.layoutParams as? FrameLayout.LayoutParams ?: return
        val side = if (fullBleed) 0 else dp(if (liveOverlay) 28 else 20)
        val vertical = if (fullBleed) 0 else dp(if (liveOverlay) 14 else 20)
        // En vivo los botones van separados del borde de abajo (casi pegados, la orilla de la TV los cortaba).
        params.setMargins(side, vertical, side, if (liveOverlay && !fullBleed) dp(if (page == Page.QUICK) 1 else 16) else vertical)
        actions.layoutParams = params
        actions.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            if (fullBleed) intArrayOf(Color.TRANSPARENT, 0x99000000.toInt(), 0xE6000000.toInt())
            else intArrayOf(Color.TRANSPARENT, if (liveOverlay) OverlayLook.tint(activity, OverlayLook.STRIP, Color.BLACK)
                else androidx.core.graphics.ColorUtils.setAlphaComponent(Ui.bgBottom, 238))).apply {
            if (!fullBleed) cornerRadius = dp(16).toFloat()
        }
        // En vivo: sin el recuadro grande detrás de los botones (se veía doble y sobresalía); cada botón ya lleva
        // su placa oscura.
        if (liveOverlay && !fullBleed) actions.background = null
        if (fullBleed) actions.setPadding(dp(44), dp(56), dp(44), dp(12))
        else actions.setPadding(dp(8), dp(if (liveOverlay) 4 else 10), dp(8), dp(if (liveOverlay) 0 else 8))
    }

    private fun headerClock() = TextClock(activity).apply {
        val locale = Locale.getDefault()
        // Con el formato de hora elegido en Configuración (12 o 24 horas).
        val pattern = DateFormat.getBestDateTimePattern(locale, if (Ui.is24h(activity)) "EEEdMMMHm" else "EEEdMMMhm")
        format12Hour = pattern
        format24Hour = pattern
        gravity = Gravity.END
        setTextColor(Ui.muted)
        textSize = 11f
        maxLines = 1
    }

    private fun line(text: String, size: Float, color: Int, bold: Boolean = false) =
        Ui.label(activity, text, size, color, bold).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

    private fun icon(resource: Int, logo: String) = ImageView(activity).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        setImageResource(resource)
        if (logo.startsWith("https://") || logo.startsWith("http://")) {
            // Fondo según los colores del logo para que no se pierda (logos blancos, oscuros…).
            setPadding(dp(2), dp(2), dp(2), dp(2))
            coil.Coil.imageLoader(activity).enqueue(coil.request.ImageRequest.Builder(activity).data(logo).target(this).build())
        }
    }

    private fun tile(action: PlaybackAction): View {
        val iconSize = dp(if (liveOverlay) 30 else 30)
        val caption = if (action.showFrameRate) currentFrameRateCaption() else action.caption
        val tile = column().apply {
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            // En la TV en vivo: cada botón es una placa oscura; el enfocado lleva borde blanco.
            background = if (liveOverlay) Ui.round(0x52000000, dp(10).toFloat()) else Ui.controlBackground(activity, 13, Color.TRANSPARENT, Color.TRANSPARENT)
            contentDescription = listOf(action.label, caption).filter(String::isNotBlank).joinToString(", ")
            if (liveOverlay) setPadding(dp(6), dp(4), dp(6), dp(1)) else setPadding(dp(6), dp(8), dp(6), dp(8))
            setOnClickListener { action.run() }
            // Sin el resalte que Android TV pone solo encima (un cuadro blanco corrido que se salía del botón): el borde
            // blanco de la placa ya marca cuál está elegido.
            if (android.os.Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
        }
        var mark: View? = null
        var glyph: ImageView? = null
        if (action.logo.isNotBlank()) {
            // Canales: el logo grande en su recuadro.
            tile.addView(icon(action.icon, action.logo).apply { setPadding(dp(3), dp(3), dp(3), dp(3)) }.also { mark = it },
                (if (liveOverlay) LinearLayout.LayoutParams(dp(54), dp(32)) else LinearLayout.LayoutParams(dp(66), dp(42))).apply { bottomMargin = dp(if (liveOverlay) 3 else 5) })
        } else if (liveOverlay) {
            // Ícono grande y suelto (sin círculo).
            val plain = icon(action.icon, action.logo).also { glyph = it }
            mark = plain
            tile.addView(plain, LinearLayout.LayoutParams(iconSize, iconSize).apply { bottomMargin = dp(4) })
        } else {
            val badge = FrameLayout(activity).apply {
                background = Ui.oval(Ui.surfaceHigh)
                addView(icon(action.icon, action.logo).also { glyph = it }, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
            }
            mark = badge
            tile.addView(badge, LinearLayout.LayoutParams(dp(42), dp(42)).apply { bottomMargin = dp(5) })
        }
        if (liveOverlay) tile.setOnFocusChangeListener { _, has ->
            tile.background = if (has) android.graphics.drawable.InsetDrawable(Ui.round(0x99000000.toInt(), dp(10).toFloat(), dp(2), Color.WHITE), dp(1))
                else Ui.round(0x52000000, dp(10).toFloat())
        }
        tile.addView(line(action.label, if (liveOverlay) 12.5f else 12f, Ui.text, true).apply { gravity = Gravity.CENTER }, Ui.matchWrap())
        if (caption.isNotBlank() || liveOverlay) {
            val subtitle = line(caption, 10f, Ui.muted).apply {
                gravity = Gravity.CENTER
                if (liveOverlay) minLines = 1
            }
            tile.addView(subtitle, Ui.matchWrap(topMargin = dp(2)))
            if (action.showFrameRate) frameRateCaption = subtitle
        }
        action.progress?.let { percent ->
            // Lo que lleva el programa de ese canal: la rayita se va llenando.
            val fill = ClipDrawable(Ui.round(Color.WHITE, dp(2).toFloat()), Gravity.LEFT, ClipDrawable.HORIZONTAL)
            fill.level = percent.coerceIn(0, 100) * 100
            tile.addView(FrameLayout(activity).apply {
                background = Ui.round(0x4DFFFFFF, dp(2).toFloat())
                foreground = fill
            }, LinearLayout.LayoutParams(-1, dp(if (liveOverlay) 2 else 3)).apply {
                topMargin = dp(if (liveOverlay) 3 else 5)
                marginStart = dp(4)
                marginEnd = dp(4)
            })
        }
        return tile
    }

    fun channelChanged() {
        close()
        paint(banner)
        banner.visibility = View.VISIBLE
        banner.postDelayed(fade, 6500)
    }

    fun refresh(ready: Boolean = false) {
        if (banner.visibility == View.VISIBLE) {
            paint(banner)
            if (ready) {
                banner.removeCallbacks(fade)
                banner.postDelayed(fade, 4500)
            }
        }
        if (header.visibility == View.VISIBLE) paint(header)
        if (page == Page.PLAYER) {
            updatePlayerInfo()
            updatePlayer()
        }
        frameRateCaption?.let {
            val caption = currentFrameRateCaption()
            if (it.text != caption) {
                it.text = caption
                (it.parent as View).contentDescription = "Frecuencia, $caption"
            }
        }
    }

    private fun currentFrameRateCaption(): String = playbackFpsLabel(info().frameRate) ?: "Sin datos"

    private fun restartIdleTimer() {
        parent.removeCallbacks(hideControls)
        val current = page ?: return
        if (touchInProgress) return
        val delay = when {
            // La barra de reproducción se esconde sola si sigue reproduciendo; en pausa se queda.
            current == Page.PLAYER -> if (timeline?.isPlaying() == true && !playerSeeking) 5_000L else return
            liveOverlay -> 3_000L
            else -> return
        }
        parent.postDelayed(hideControls, delay)
    }

    fun onTouchEvent(action: Int) {
        if (!liveOverlay) return
        when (action) {
            MotionEvent.ACTION_DOWN -> touchInProgress = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touchInProgress = false
            else -> return
        }
        restartIdleTimer()
    }

    private fun show(next: Page, focusSeek: Boolean = false) {
        if (next == Page.PLAYER && timeline == null) return
        if (liveOverlay && page == next) return
        banner.removeCallbacks(fade)
        banner.visibility = View.GONE
        page = next
        frameRateCaption = null
        buttons.removeAllViews()
        removePlayerBar()
        removeQuickExtras()
        val player = next == Page.PLAYER
        // La barra de reproducción va sola, de lado a lado y bien abajo (sin los botones).
        styleActions(fullBleed = player)
        val commands = when (next) {
            // Los botones de opciones van en el orden que elegiste (dejando apretado OK sobre uno).
            Page.OPTIONS -> options()
            Page.QUICK -> quick()
            Page.PLAYER -> emptyList()
        }
        // Más juntos para que quepan todos en el ancho de la pantalla (antes los últimos quedaban cortados).
        val room = activity.resources.configuration.screenWidthDp - (if (liveOverlay) 72 else 56)
        // Canales: botones pegados a la izquierda, de ancho fijo; si son muchos, se achican para caber.
        val tileWidth = (room / commands.size.coerceAtLeast(1) - 6).coerceIn(if (liveOverlay) 62 else 84, if (liveOverlay) 124 else 138)
        // Con logos de canales y rayitas los botones son un poco más altos.
        val tall = commands.any { it.progress != null || it.logo.isNotBlank() }
        val tileHeight = dp(if (liveOverlay) (if (tall) 86 else 78) else if (tall) 104 else 94)
        commands.forEach { action ->
            val view = tile(action)
            buttons.addView(view, LinearLayout.LayoutParams(dp(tileWidth), tileHeight).apply { rightMargin = dp(if (liveOverlay) 6 else 4) })
        }
        scroll.visibility = if (commands.isEmpty()) View.GONE else View.VISIBLE
        scroll.scrollTo(0, 0)
        // Canales: el recuadro de arriba (canal, programa, rayita, etiquetas y hora) se queda con OK y con ▲.
        val withHeader = next == Page.QUICK || (player && liveOverlay)
        header.visibility = if (withHeader) View.VISIBLE else View.GONE
        if (withHeader) paint(header)
        clock.visibility = if (player && !liveOverlay) View.VISIBLE else View.GONE
        actions.visibility = View.VISIBLE
        if (next == Page.QUICK && liveOverlay && timeline != null) addQuickExtras()
        if (player) addPlayerBar(focusSeek) else buttons.getChildAt(0)?.requestFocus()
        if (player || quickLine != null) parent.postDelayed(tick, 1000)
        restartIdleTimer()
    }

    /** OK: películas y series abren la barra de reproducción; los canales, los botones (Guía, Recientes…). */
    fun showQuick() = show(if (playerFirst) Page.PLAYER else Page.QUICK)
    fun showOptions() = show(Page.OPTIONS)
    fun isOpen() = page != null

    fun close() {
        parent.removeCallbacks(hideControls)
        banner.removeCallbacks(fade)
        banner.visibility = View.GONE
        if (page == null) return
        page = null
        frameRateCaption = null
        removePlayerBar()
        removeQuickExtras()
        clock.visibility = View.GONE
        header.visibility = View.GONE
        actions.visibility = View.GONE
        onClosed()
    }

    fun handle(event: KeyEvent): Boolean {
        val key = event.keyCode
        if (event.action == KeyEvent.ACTION_UP) return handled.remove(key)
        if (event.action != KeyEvent.ACTION_DOWN) return false
        restartIdleTimer()
        if (key in handled) {
            // Dejando apretado ◀ ▶ después de abrir la barra, la raya sigue avanzando.
            if ((key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) &&
                page == Page.PLAYER && playerSeek?.hasFocus() == true) return false
            return true
        }
        val source = timeline
        val action: () -> Unit = when (key) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> when {
                page == null -> ::showQuick
                // En la raya de tiempo, OK pausa o sigue la película.
                page == Page.PLAYER && playerSeek?.hasFocus() == true -> {
                    { source?.togglePlay(); updatePlayer() }
                }
                else -> return false
            }
            // Botones de reproducción del control remoto (los canales los manejan aparte).
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (source == null || liveOverlay) return false
                {
                    val playing = source.isPlaying()
                    if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                        (key == KeyEvent.KEYCODE_MEDIA_PLAY) != playing) source.togglePlay()
                    if (page == null) show(Page.PLAYER) else updatePlayer()
                }
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (source == null || !source.canSeek()) return false
                {
                    source.seekBy(if (key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) 10_000L else -10_000L)
                    if (page != Page.PLAYER) show(Page.PLAYER, focusSeek = true) else updatePlayer()
                }
            }
            // Película con todo escondido: ◀ ▶ atrasa o adelanta enseguida y muestra la barra en la raya.
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (page != null || !playerFirst || source == null || !source.canSeek()) return false
                {
                    show(Page.PLAYER, focusSeek = true)
                    source.seekBy(if (key == KeyEvent.KEYCODE_DPAD_RIGHT) 10_000L else -10_000L)
                    updatePlayer()
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> when (page) {
                Page.PLAYER -> when {
                    // De la raya se baja a los botones; de los botones, a los ajustes (películas) o a Guía/Recientes (canales).
                    playerSeek?.hasFocus() == true -> { { playerPlay?.requestFocus() } }
                    playerFirst -> ::showOptions
                    else -> { { show(Page.QUICK) } }
                }
                Page.QUICK -> ::showOptions
                Page.OPTIONS -> return true
                null -> if (requireQuickForOptions) return false else ::showOptions
            }
            KeyEvent.KEYCODE_DPAD_UP -> when (page) {
                Page.OPTIONS -> ::showQuick
                // Canales: ▲ desde Guía/Recientes sube a la barra de reproducción.
                Page.QUICK -> if (source != null) { { show(Page.PLAYER) } } else return false
                Page.PLAYER -> {
                    val seek = playerSeek
                    if (seek != null && !seek.hasFocus()) { { seek.requestFocus() } } else return true
                }
                null -> return false
            }
            KeyEvent.KEYCODE_BACK -> when (page) {
                Page.OPTIONS -> ::showQuick
                Page.QUICK, Page.PLAYER -> ::close
                null -> return false
            }
            else -> return false
        }
        handled.add(key)
        action()
        return true
    }
}

internal data class PlaybackTrack(val label: String, val selected: Boolean, val apply: () -> Unit)

internal fun choosePlaybackTracks(activity: Activity, player: Player?, type: Int, software: SoftwareVideoPlayer? = null,
                                  onChanged: () -> Unit = {}) {
    val audio = type == C.TRACK_TYPE_AUDIO
    val title = if (audio) "Audio" else "Subtítulos"
    val tracks = if (software != null) software.playbackTracks(type) else if (player != null) {
        val params = player.trackSelectionParameters
        fun update(disabled: Boolean, override: TrackSelectionOverride? = null) {
            val builder = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
                .setTrackTypeDisabled(type, disabled)
            if (override != null) builder.addOverride(override)
            player.trackSelectionParameters = builder.build()
        }
        val available = player.currentTracks.groups.filter { it.type == type }.flatMap { group ->
            (0 until group.length).filter(group::isTrackSupported).map { index ->
                val format = group.getTrackFormat(index)
                val language = format.language?.takeUnless { it == "und" }?.let {
                    Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()).takeIf(String::isNotBlank)
                }
                val label = format.label ?: language ?: "Pista ${index + 1}"
                val detail = if (audio) audioLayout(format.channelCount) else null
                PlaybackTrack(listOfNotNull(label, detail).distinct().joinToString(" · "),
                    group.isTrackSelected(index) && params.overrides.containsKey(group.mediaTrackGroup)) {
                    update(false, TrackSelectionOverride(group.mediaTrackGroup, index))
                }
            }
        }
        if (available.isEmpty()) emptyList()
        else if (audio) listOf(PlaybackTrack("Automático", available.none { it.selected }) { update(false) }) + available
        else listOf(
            PlaybackTrack("Desactivados", type in params.disabledTrackTypes) { update(true) },
            PlaybackTrack("Automáticos", type !in params.disabledTrackTypes && available.none { it.selected }) { update(false) }
        ) + available
    } else emptyList()
    val dialog = AlertDialog.Builder(activity).setTitle(title).setNegativeButton("Cerrar", null)
    if (tracks.isEmpty()) dialog.setMessage(if (audio) "La señal no ofrece pistas de audio." else "La señal no ofrece subtítulos.")
    else dialog.setSingleChoiceItems(tracks.map { it.label }.toTypedArray(), tracks.indexOfFirst { it.selected }) { popup, index ->
        tracks[index].apply()
        onChanged()
        popup.dismiss()
    }
    Ui.showDialog(dialog.create())
}

internal fun audioLayout(channels: Int): String? = when (channels) {
    1 -> "Mono"
    2 -> "Estéreo"
    6 -> "5.1"
    8 -> "7.1"
    in 3..16 -> "$channels canales"
    else -> null
}

internal fun playbackFpsLabel(fps: Float): String? = fps.takeIf { it.isFinite() && it > 0f }?.let {
    String.format(Locale.US, "%.2f", it).trimEnd('0').trimEnd('.') + " FPS"
}

/** [screen]: Hz a los que está la TV ("TV 59.94 Hz"), junto a los FPS del video para ver que concuerdan. */
internal fun playbackTechnical(video: Format?, audio: Format?, width: Int, height: Int, fps: Float, screen: String? = null): List<String> {
    val resolution = when {
        width <= 0 || height <= 0 -> null
        width >= 3840 || height >= 2160 -> "4K UHD"
        width >= 2560 || height >= 1440 -> "QHD"
        width >= 1920 || height >= 1080 -> "Full HD"
        width >= 1280 || height >= 720 -> "HD"
        else -> "SD"
    }
    val codec = when (video?.sampleMimeType) {
        "video/avc" -> "H.264"
        "video/hevc" -> "HEVC"
        "video/dolby-vision" -> "Dolby Vision"
        "video/av01" -> "AV1"
        else -> null
    }
    val frames = playbackFpsLabel(fps)
    return listOfNotNull(resolution, frames, screen?.takeIf { frames != null }, codec, audioLayout(audio?.channelCount ?: 0))
}
