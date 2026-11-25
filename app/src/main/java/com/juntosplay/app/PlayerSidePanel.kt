package com.juntosplay.app

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import coil.load

/**
 * Panel a la derecha del reproductor (como Stremio y Nuvio):
 * - Episodios: las temporadas arriba y sus episodios (imagen, nombre, fecha y de qué trata); OK abre el elegido.
 * Se recorre con ▲▼ y OK; en Episodios ◀ ▶ cambia de temporada; Atrás lo cierra.
 */
internal class PlayerSidePanel(private val activity: Activity, parent: FrameLayout) {
    private fun dp(value: Int) = Ui.dp(activity, value)
    private val television = Ui.isTelevision(activity)

    private val scrim = FrameLayout(activity).apply {
        setBackgroundColor(0x66000000)
        isClickable = true
        visibility = View.GONE
        setOnClickListener { close() }
    }
    private val panel = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.round(0xF21B1D23.toInt(), dp(18).toFloat())
        setPadding(dp(18), dp(16), dp(18), dp(12))
        isClickable = true
    }
    private val heading = Ui.label(activity, "", 21f, Color.WHITE, bold = true)
    private val subtitle = Ui.label(activity, "", 13f, Ui.muted).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val tabs = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
    private val tabsScroll = HorizontalScrollView(activity).apply {
        isHorizontalScrollBarEnabled = false
        addView(tabs)
    }
    private val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val listScroll = ScrollView(activity).apply {
        isVerticalScrollBarEnabled = false
        isFillViewport = true
        addView(list)
    }
    private val status = Ui.label(activity, "", 13f, Ui.hint)
    private var generation = 0
    var onClosed: (() -> Unit)? = null

    init {
        panel.addView(heading, LinearLayout.LayoutParams(-1, -2))
        panel.addView(subtitle, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        panel.addView(tabsScroll, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        panel.addView(listScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        panel.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        val metrics = activity.resources.displayMetrics
        val landscape = metrics.widthPixels > metrics.heightPixels
        val width = when {
            television -> minOf((metrics.widthPixels * 0.46f).toInt(), dp(640))
            landscape -> (metrics.widthPixels * 0.6f).toInt()
            else -> metrics.widthPixels - dp(24)
        }
        scrim.addView(panel, FrameLayout.LayoutParams(width, -1, Gravity.END).apply {
            setMargins(dp(12), dp(12), dp(12), dp(12))
        })
        parent.addView(scrim, FrameLayout.LayoutParams(-1, -1))
    }

    val isOpen get() = scrim.visibility == View.VISIBLE

    fun close() {
        if (!isOpen) return
        generation++
        scrim.visibility = View.GONE
        list.removeAllViews()
        tabs.removeAllViews()
        onClosed?.invoke()
    }

    private fun open(title: String, detail: String) {
        generation++
        heading.text = title
        subtitle.text = detail
        subtitle.visibility = if (detail.isBlank()) View.GONE else View.VISIBLE
        tabs.removeAllViews()
        list.removeAllViews()
        status.text = ""
        scrim.visibility = View.VISIBLE
        scrim.bringToFront()
    }

    private fun rowBackground(current: Boolean) = StateListDrawable().apply {
        val radius = dp(14).toFloat()
        val lit = Ui.round(if (Ui.glassBlack) Ui.glassFocus else 0x40FFFFFF, radius, dp(2), Color.WHITE)
        addState(intArrayOf(android.R.attr.state_focused), lit)
        addState(intArrayOf(android.R.attr.state_pressed), lit)
        addState(intArrayOf(), if (Ui.glassBlack) Ui.round(if (current) 0xB3000000.toInt() else 0x8C000000.toInt(), radius, dp(1), if (current) 0x59FFFFFF else 0x26FFFFFF)
            else Ui.round(if (current) 0x2EFFFFFF else 0x12FFFFFF, radius))
    }

    private fun chip(text: String) = TextView(activity).apply {
        this.text = text
        textSize = 14f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        maxLines = 1
        minimumHeight = dp(36)
        setPadding(dp(16), dp(6), dp(16), dp(6))
        isFocusable = true
        isClickable = true
        background = StateListDrawable().apply {
            val radius = dp(18).toFloat()
            addState(intArrayOf(android.R.attr.state_focused), Ui.round(0x66FFFFFF, radius, dp(2), Color.WHITE))
            addState(intArrayOf(android.R.attr.state_selected), Ui.round(0x4DFFFFFF, radius))
            addState(intArrayOf(), Ui.round(0x1FFFFFFF, radius))
        }
    }

    /**
     * Episodios de la serie ([episodes], en orden; cada uno con season_num, episode_num, watch_key y, si se sabe,
     * name, plot, image y date). [currentKey]: el que se está viendo (sale marcado y elegido).
     */
    fun showEpisodes(seriesName: String, episodes: List<Bundle>, currentKey: String?, onPick: (Bundle) -> Unit) {
        open("Episodios", seriesName)
        val bySeason = episodes.groupBy { it.getInt("season_num", 0) }.toSortedMap()
        if (bySeason.isEmpty()) {
            status.text = "No hay episodios para mostrar."
            return
        }
        val current = episodes.firstOrNull { it.getString("watch_key") == currentKey }
        val history = WatchHistory(activity)
        val chips = ArrayList<TextView>()
        fun showSeason(season: Int, focusCurrent: Boolean) {
            chips.forEach { it.isSelected = it.tag == season }
            list.removeAllViews()
            var focusTarget: View? = null
            for (episode in bySeason[season].orEmpty()) {
                val key = episode.getString("watch_key")
                val playing = key != null && key == currentKey
                val row = episodeRow(episode, playing, key?.let { history.get(it) })
                row.setOnClickListener {
                    close()
                    if (!playing) onPick(episode)
                }
                list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
                if (playing) focusTarget = row
            }
            listScroll.scrollTo(0, 0)
            val target = focusTarget ?: list.getChildAt(0)
            if (focusCurrent && target != null) list.post {
                target.requestFocus()
                listScroll.smoothScrollTo(0, (target.top - dp(40)).coerceAtLeast(0))
            }
        }
        if (bySeason.size > 1) {
            for (season in bySeason.keys) {
                val chip = chip(if (season == 0) "Especiales" else "Temporada $season").apply {
                    tag = season
                    setOnClickListener { showSeason(season, true) }
                    setOnFocusChangeListener { _, focused -> if (focused && !isSelected) showSeason(season, false) }
                }
                chips.add(chip)
                tabs.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            }
        }
        tabsScroll.visibility = if (bySeason.size > 1) View.VISIBLE else View.GONE
        val startSeason = current?.getInt("season_num", 0)?.takeIf { it in bySeason } ?: bySeason.firstKey()
        showSeason(startSeason, true)
        val selected = chips.firstOrNull { it.tag == startSeason }
        if (selected != null) tabsScroll.post { tabsScroll.smoothScrollTo((selected.left - dp(20)).coerceAtLeast(0), 0) }
    }

    /** Una fila de episodio (también la usa la pantalla chica del teléfono, debajo del video). */
    fun episodeRow(episode: Bundle, playing: Boolean, watch: WatchHistory.Entry?): View {
        val season = episode.getInt("season_num", 0)
        val number = episode.getInt("episode_num", 0)
        val row = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(10), dp(8))
            isFocusable = true
            isClickable = true
            background = rowBackground(playing)
        }
        val thumbWidth = dp(if (television) 150 else 118)
        val thumb = FrameLayout(activity).apply {
            background = Ui.round(0xFF2A2D35.toInt(), dp(10).toFloat())
            clipToOutline = true
        }
        val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        episode.getString("image")?.takeIf { it.startsWith("http") }?.let { url -> image.load(url) { crossfade(true) } }
        thumb.addView(image, FrameLayout.LayoutParams(-1, -1))
        thumb.addView(Ui.label(activity, "S${season}E$number", 12f, Color.WHITE, bold = true).apply {
            setPadding(dp(7), dp(2), dp(7), dp(2))
            background = Ui.round(0xB3000000.toInt(), dp(8).toFloat())
        }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(6), 0, 0, dp(6)) })
        // Rayita de lo que llevas (o ✓ si ya lo viste).
        if (watch != null && watch.watched) {
            thumb.addView(Ui.label(activity, "✓", 12f, 0xFF15A34A.toInt(), bold = true).apply {
                gravity = Gravity.CENTER
                background = Ui.oval(Color.WHITE)
            }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(5), dp(5), 0) })
        } else if (watch != null && watch.positionMs > 0 && watch.fraction > 0f) {
            val done = watch.fraction.coerceIn(0.03f, 1f)
            thumb.addView(LinearLayout(activity).apply {
                background = Ui.round(0x59FFFFFF, dp(2).toFloat())
                addView(View(activity).apply { background = Ui.round(Ui.cyan, dp(2).toFloat()) },
                    LinearLayout.LayoutParams(0, -1, done))
                addView(View(activity), LinearLayout.LayoutParams(0, -1, 1f - done))
            }, FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM).apply { setMargins(dp(6), 0, dp(6), dp(3)) })
        }
        row.addView(thumb, LinearLayout.LayoutParams(thumbWidth, thumbWidth * 9 / 16).apply { marginEnd = dp(12) })
        val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val name = episode.getString("name")?.takeIf { it.isNotBlank() } ?: "Episodio $number"
        texts.addView(Ui.label(activity, (if (playing) "▶ " else "") + name, 15f, Color.WHITE, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2))
        val date = episode.getString("date").orEmpty()
        val state = when {
            playing -> "Viendo ahora"
            watch?.watched == true -> "Visto"
            else -> null
        }
        val line = listOfNotNull(date.takeIf { it.isNotBlank() }, state).joinToString(" · ")
        if (line.isNotBlank()) texts.addView(Ui.label(activity, line, 12.5f, Ui.muted).apply { maxLines = 1 },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(1) })
        episode.getString("plot")?.takeIf { it.isNotBlank() }?.let { plot ->
            texts.addView(Ui.label(activity, plot, 12.5f, 0xFFD5D8DE.toInt()).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    /**
     * Servidores de un video (copias del mismo video): [current] es el que se ve y [failed] los que ya no
     * abrieron (se pueden volver a probar).
     */
    fun showServers(title: String, labels: List<String>, current: Int, failed: Set<Int>, onMore: (() -> Unit)? = null, onPick: (Int) -> Unit) {
        open("Servidores", title)
        tabsScroll.visibility = View.GONE
        val run = generation
        labels.forEachIndexed { index, label ->
            val playing = index == current && index !in failed
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(9), dp(12), dp(9))
                isFocusable = true
                isClickable = true
                background = rowBackground(playing)
            }
            val note = when {
                playing -> "▶ Viendo ahora"
                index in failed -> "No abrió (toca para probar otra vez)"
                else -> null
            }
            if (note != null) row.addView(Ui.label(activity, note, 12.5f, if (playing) Ui.cyan else 0xFFFFC857.toInt(), bold = true),
                LinearLayout.LayoutParams(-1, -2))
            row.addView(Ui.label(activity, label, 14.5f, Color.WHITE, bold = true).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(-1, -2))
            row.setOnClickListener {
                close()
                if (!playing) onPick(index)
            }
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            if (index == current || (current !in labels.indices && index == 0)) row.post { if (run == generation) row.requestFocus() }
        }
        if (onMore != null) {
            val more = LinearLayout(activity).apply {
                setPadding(dp(12), dp(11), dp(12), dp(11))
                isFocusable = true
                isClickable = true
                background = rowBackground(false)
            }
            more.addView(Ui.label(activity, "Buscar más servidores…", 14.5f, Ui.cyan, bold = true), LinearLayout.LayoutParams(-1, -2))
            more.setOnClickListener {
                close()
                onMore()
            }
            list.addView(more, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
        status.text = if (labels.size == 1) "1 servidor" else "${labels.size} servidores · si uno no abre, la app pasa sola al siguiente"
    }
}
