package com.juntosplay.app

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.Base64
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import com.juntosplay.app.relay.XtreamAccount
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Guía de canales estilo TiviMate encima del reproductor (solo en el equipo que comparte un
 * canal de su cuenta IPTV):
 *  - Izquierda: lista de canales transparente con la programación del canal enfocado.
 *  - Botón Guía: cuadrícula de programación transparente.
 *  - Atrás: guía con el video en una ventanita arriba a la izquierda (otra vez Atrás = salir).
 *  - OK: encabezado arriba con el programa actual y fila de canales abajo.
 *  - Arriba/Abajo o CH+/CH-: cambia de canal y muestra el aviso de abajo.
 */
class LiveTv(
    private val activity: Activity,
    private val root: FrameLayout,
    private val playerView: View,
    private val account: XtreamAccount,
    private val player: () -> ExoPlayer?,
    private val onPlay: (Channel) -> Unit,
    private val onSync: () -> Unit,
    private val onExit: () -> Unit
) {
    data class Channel(val id: Int, val num: Int, val name: String, val icon: String, val categoryId: String)

    class Program(val title: String, val description: String, val start: Long, val end: Long) {
        fun isNow(t: Long = System.currentTimeMillis()) = t in start until end
    }

    private class Category(val id: String, val name: String)

    private enum class Mode { NONE, BANNER, MINI, LIST, GUIDE }

    private val prefs = RoomSession.prefs(activity)
    private val handler = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 4 })
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", UserAgents.current(prefs)).build())
        }
        .build()
    private var released = false

    // --- datos ---

    var current: Channel? = null
        private set
    private var categories: List<Category> = emptyList()
    private var channels: List<Channel> = emptyList()
    private var channelsCategory: String? = null
    private val epg = HashMap<Int, Pair<Long, List<Program>>>()
    private val epgWaiting = HashMap<Int, MutableList<(List<Program>) -> Unit>>()

    // --- pantalla ---

    private var mode = Mode.NONE
    private var guidePreview = false
    private val layer = FrameLayout(activity)
    private val hideRunnable = Runnable { hide() }
    private val zapRunnable = Runnable { pendingZap?.let { ch -> pendingZap = null; play(ch) } }
    private var pendingZap: Channel? = null
    /** Teclas cuya pulsación ya se usó: su "soltar" también se consume. */
    private val consumedDown = HashSet<Int>()

    private val dm = activity.resources.displayMetrics
    private val is24 = DateFormat.is24HourFormat(activity)
    private val timeFmt = SimpleDateFormat(if (is24) "HH:mm" else "h:mm", Locale.getDefault())
    private val clockFmt = SimpleDateFormat(if (is24) "EEE, d MMM HH:mm" else "EEE, d MMM h:mm a", Locale("es"))

    init {
        root.addView(layer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        loadCategories()
    }

    val isInteractive: Boolean get() = mode == Mode.MINI || mode == Mode.LIST || mode == Mode.GUIDE

    /** El reproductor abrió este canal. */
    fun setPlaying(streamId: Int, categoryId: String, name: String) {
        val known = channels.firstOrNull { it.id == streamId }
        val ch = known ?: Channel(streamId, current?.takeIf { it.id == streamId }?.num ?: 0, name, "", categoryId)
        current = ch
        if (categoryId.isNotBlank()) prefs.edit().putString(PREF_LAST_CATEGORY, categoryId).apply()
        if (known != null) addToHistory(known)
        val wanted = categoryId.ifBlank { null }
        if (channelsCategory == null || (wanted != null && wanted != channelsCategory)) {
            loadChannels(wanted) { list ->
                list.firstOrNull { it.id == streamId }?.let { full ->
                    current = full
                    addToHistory(full)
                    if (mode == Mode.BANNER) showBanner()
                }
            }
        }
        if (mode == Mode.NONE || mode == Mode.BANNER) showBanner()
        else if (mode == Mode.LIST) listChannels.adapter?.notifyDataSetChanged()
        else if (mode == Mode.GUIDE) guideRows.adapter?.notifyDataSetChanged()
    }

    fun release() {
        released = true
        handler.removeCallbacksAndMessages(null)
        http.dispatcher.cancelAll()
        http.dispatcher.executorService.shutdown()
        restorePlayerSize()
        root.removeView(layer)
    }

    // ------------------------------------------------------------------
    // Control remoto
    // ------------------------------------------------------------------

    /** Devuelve true si la tecla se usó aquí. */
    fun onKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_UP) {
            return consumedDown.remove(code)
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val used = handleDown(code, event.repeatCount)
        if (used) consumedDown.add(code) else consumedDown.remove(code)
        if (mode != Mode.NONE) scheduleHide()
        return used
    }

    private fun handleDown(code: Int, repeat: Int): Boolean {
        val isBack = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE
        val isGuide = code == KeyEvent.KEYCODE_GUIDE || code == KeyEvent.KEYCODE_TV || code == KeyEvent.KEYCODE_PROG_BLUE
        when (mode) {
            Mode.NONE, Mode.BANNER -> {
                if (repeat > 0 && code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_DPAD_DOWN &&
                    code != KeyEvent.KEYCODE_CHANNEL_UP && code != KeyEvent.KEYCODE_CHANNEL_DOWN) return isBack
                when {
                    isBack -> { showGuide(preview = true); return true }
                    isGuide -> { showGuide(preview = false); return true }
                    code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
                        code == KeyEvent.KEYCODE_NUMPAD_ENTER -> { showMini(); return true }
                    code == KeyEvent.KEYCODE_DPAD_LEFT -> { showList(); return true }
                    code == KeyEvent.KEYCODE_DPAD_RIGHT -> { showMini(); return true }
                    code == KeyEvent.KEYCODE_INFO -> { if (mode == Mode.BANNER) hide() else showBanner(); return true }
                    code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_CHANNEL_UP -> { zap(+1); return true }
                    code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); return true }
                }
                return false
            }
            Mode.MINI -> {
                if (isBack) { hide(); return true }
                if (isGuide) { showGuide(preview = false); return true }
                // Arriba desde la fila de abajo: se cierra (el encabezado no se puede seleccionar).
                if (code == KeyEvent.KEYCODE_DPAD_UP) { hide(); return true }
                return false
            }
            Mode.LIST -> {
                if (isBack) {
                    if (listCats.visibility == View.VISIBLE) closeCategories() else hide()
                    return true
                }
                if (isGuide) { showGuide(preview = false); return true }
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && listChannels.hasFocus()) { openCategories(); return true }
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && listCats.hasFocus()) return true
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT && listCats.hasFocus()) { closeCategories(); return true }
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT && listPrograms.hasFocus()) return true
                return false
            }
            Mode.GUIDE -> {
                if (isBack) {
                    if (guidePreview) onExit() else hide()
                    return true
                }
                if (isGuide) { hide(); return true }
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT) return guideEdge(+1)
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) return guideEdge(-1)
                return false
            }
        }
    }

    // ------------------------------------------------------------------
    // Mostrar / ocultar
    // ------------------------------------------------------------------

    fun hide() {
        handler.removeCallbacks(hideRunnable)
        mode = Mode.NONE
        layer.removeAllViews()
        restorePlayerSize()
        root.requestFocus()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideRunnable)
        val ms = when (mode) {
            Mode.BANNER -> 6_000L
            Mode.MINI -> 12_000L
            Mode.LIST -> 30_000L
            Mode.GUIDE -> if (guidePreview) 0L else 60_000L
            Mode.NONE -> 0L
        }
        if (ms > 0) handler.postDelayed(hideRunnable, ms)
    }

    private fun switchTo(newMode: Mode, content: View) {
        layer.removeAllViews()
        if (newMode != Mode.GUIDE || !guidePreview) restorePlayerSize()
        mode = newMode
        layer.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        scheduleHide()
    }

    private fun restorePlayerSize() {
        val lp = playerView.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.width == FrameLayout.LayoutParams.MATCH_PARENT && lp.height == FrameLayout.LayoutParams.MATCH_PARENT) return
        playerView.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
    }

    // ------------------------------------------------------------------
    // Cambiar de canal
    // ------------------------------------------------------------------

    private fun play(ch: Channel) {
        handler.removeCallbacks(zapRunnable)
        pendingZap = null
        if (ch.id == current?.id) return
        current = ch
        addToHistory(ch)
        onPlay(ch)
    }

    /** Arriba/abajo: muestra el canal siguiente y lo abre cuando se deja de pulsar (una sola conexión). */
    private fun zap(step: Int) {
        if (channels.isEmpty()) return
        val from = pendingZap ?: current ?: return
        val i = channels.indexOfFirst { it.id == from.id }
        val next = channels[((if (i < 0) 0 else i + step) % channels.size + channels.size) % channels.size]
        pendingZap = next
        showBanner(next)
        handler.removeCallbacks(zapRunnable)
        handler.postDelayed(zapRunnable, 800)
    }

    // ------------------------------------------------------------------
    // Aviso de abajo (al cambiar de canal) y encabezado de arriba (OK)
    // ------------------------------------------------------------------

    private fun showBanner(ch: Channel? = null) {
        val target = ch ?: pendingZap ?: current ?: return
        val box = InfoBox(withDescription = true)
        val wrap = FrameLayout(activity)
        wrap.addView(box.view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(dp(28), 0, dp(28), dp(22))
        })
        switchTo(Mode.BANNER, wrap)
        box.fill(target)
    }

    private lateinit var miniButtons: LinearLayout
    private lateinit var miniCards: RecyclerView
    private var miniShowingHistory = false

    private fun showMini() {
        val ch = current ?: return
        val wrap = FrameLayout(activity)
        val top = InfoBox(withDescription = false)
        wrap.addView(top.view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            setMargins(dp(28), dp(20), dp(28), 0)
        })
        top.fill(ch)

        // Fila de abajo: Guía de TV, Historial, Sincronizar y los canales.
        val bottom = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xE6000000.toInt(), 0x99000000.toInt(), 0x00000000))
            setPadding(dp(28), dp(36), dp(12), dp(18))
        }
        miniButtons = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        miniShowingHistory = false
        val cardAdapter = CardAdapter()
        lateinit var historyTile: LinearLayout
        miniButtons.addView(tile("☷", "Guía de TV") { showGuide(preview = false) })
        historyTile = tile("↺", "Historial") {
            miniShowingHistory = !miniShowingHistory
            (historyTile.getChildAt(1) as TextView).text = if (miniShowingHistory) "Canales" else "Historial"
            cardAdapter.submit(if (miniShowingHistory) history() else channels)
            miniCards.scrollToPosition(0)
        }
        miniButtons.addView(historyTile)
        miniButtons.addView(tile("⟳", "Sincronizar") { onSync() })
        bottom.addView(miniButtons)
        miniCards = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)
            adapter = cardAdapter
            clipToPadding = false
            setPadding(dp(4), dp(4), dp(28), dp(4))
            itemAnimator = null
        }
        bottom.addView(miniCards, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        wrap.addView(bottom, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        switchTo(Mode.MINI, wrap)
        cardAdapter.submit(channels)
        val i = channels.indexOfFirst { it.id == ch.id }
        if (i > 0) (miniCards.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(i, dp(40))
        miniButtons.getChildAt(0).requestFocus()
    }

    private fun tile(icon: String, label: String, onClick: () -> Unit): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(8), dp(10), dp(8))
        addView(text(icon, 26f, Color.WHITE).apply { gravity = Gravity.CENTER })
        addView(text(label, 14f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER })
        layoutParams = LinearLayout.LayoutParams(dp(112), dp(96)).apply { rightMargin = dp(8) }
        focusFrame(this)
        setOnClickListener { onClick() }
    }

    /** Recuadro de información del canal: número, logo, programa actual, siguiente, hora. */
    private inner class InfoBox(private val withDescription: Boolean) {
        val view = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.round(PANEL, dp(18).toFloat())
            setPadding(dp(20), dp(14), dp(22), dp(14))
        }
        private val num = text("", 28f, Color.WHITE)
        private val logo = logoView(dp(56), dp(38))
        private val name = text("", 14f, Color.WHITE, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        private val title = text("", 19f, Color.WHITE, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        private val nowTime = text("", 14f, SOFT)
        private val bar = Bar(activity)
        private val left = text("", 14f, SOFT)
        private val category = text("", 14f, SOFT).apply { maxLines = 1 }
        private val badges = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        private val desc = text("", 14f, SOFT).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        private val next = text("", 15f, SOFT).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        private val clock = text("", 20f, Color.WHITE)
        private var shownId = -1

        init {
            val leftCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val numRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            numRow.addView(num, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(12) })
            numRow.addView(logo)
            leftCol.addView(numRow)
            leftCol.addView(name, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            view.addView(leftCol, LinearLayout.LayoutParams(dp(180), LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(18); gravity = Gravity.CENTER_VERTICAL })

            val mid = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            mid.addView(title)
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(nowTime, wrapLp(right = 12))
            row.addView(bar, LinearLayout.LayoutParams(dp(64), dp(4)).apply { rightMargin = dp(12) })
            row.addView(left, wrapLp(right = 22))
            row.addView(category, wrapLp(right = 18))
            row.addView(badges)
            mid.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            if (withDescription) mid.addView(desc, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            mid.addView(next, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            view.addView(mid, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            view.addView(clock, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(16); gravity = Gravity.TOP
            })
        }

        fun fill(ch: Channel) {
            shownId = ch.id
            num.text = if (ch.num > 0) ch.num.toString() else ""
            setLogo(logo, ch.icon)
            name.text = ch.name
            clock.text = clockFmt.format(Date())
            category.text = categoryName(ch.categoryId)
            fillBadges(badges, ch.id == current?.id)
            showPrograms(emptyList())
            epgFor(ch.id) { list -> if (shownId == ch.id) showPrograms(list) }
        }

        private fun showPrograms(list: List<Program>) {
            val now = System.currentTimeMillis()
            val cur = list.firstOrNull { it.isNow(now) }
            val nxt = list.firstOrNull { it.start >= (cur?.end ?: now) }
            title.text = cur?.title ?: if (list.isEmpty()) shownName() else "Sin información"
            nowTime.text = cur?.let { range(it) } ?: ""
            bar.visibility = if (cur != null) View.VISIBLE else View.GONE
            bar.fraction = cur?.let { progress(it, now) } ?: 0f
            left.text = cur?.let { minutesLeft(it, now) } ?: ""
            desc.text = cur?.description.orEmpty()
            desc.visibility = if (desc.text.isNullOrBlank()) View.GONE else View.VISIBLE
            next.text = nxt?.let { "${range(it)}    ${it.title}" } ?: ""
            next.visibility = if (nxt == null) View.GONE else View.VISIBLE
        }

        private fun shownName() = name.text?.toString().orEmpty()
    }

    /** FHD · 30 FPS · STEREO, según lo que se está reproduciendo. */
    private fun fillBadges(box: LinearLayout, isPlaying: Boolean) {
        box.removeAllViews()
        if (!isPlaying) return
        val p = player() ?: return
        val labels = ArrayList<String>()
        p.videoFormat?.let { v ->
            val h = minOf(v.height, v.width).takeIf { it > 0 } ?: v.height
            when {
                h >= 2000 -> labels.add("4K")
                h >= 1000 -> labels.add("FHD")
                h >= 700 -> labels.add("HD")
                h > 0 -> labels.add("SD")
            }
            if (v.frameRate > 0) labels.add("${Math.round(v.frameRate)} FPS")
        }
        p.audioFormat?.let { a ->
            when {
                a.channelCount == 1 -> labels.add("MONO")
                a.channelCount == 2 -> labels.add("STEREO")
                a.channelCount > 2 -> labels.add("${a.channelCount - 1}.1")
            }
        }
        for (l in labels) {
            box.addView(text(l, 10f, Color.WHITE).apply {
                background = Ui.round(Color.TRANSPARENT, dp(4).toFloat(), dp(1), 0xCCFFFFFF.toInt())
                setPadding(dp(6), dp(1), dp(6), dp(1))
            }, wrapLp(right = 6))
        }
    }

    private inner class CardAdapter : RecyclerView.Adapter<CardAdapter.VH>() {
        private var items: List<Channel> = emptyList()
        fun submit(list: List<Channel>) { items = list; notifyDataSetChanged() }

        inner class VH(root: LinearLayout, val logo: ImageView, val name: TextView, val now: TextView, val bar: Bar) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val logo = logoView(dp(72), dp(46))
            val name = text("", 14f, Color.WHITE, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            val now = text("", 13f, LILAC).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            val bar = Bar(activity)
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                addView(logo, LinearLayout.LayoutParams(dp(72), dp(46)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(6) })
                addView(name)
                addView(now)
                addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)).apply { topMargin = dp(5) })
                layoutParams = RecyclerView.LayoutParams(dp(190), RecyclerView.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) }
            }
            focusFrame(card)
            val vh = VH(card, logo, name, now, bar)
            card.setOnClickListener {
                val pos = vh.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) { play(items[pos]); hide() }
            }
            return vh
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ch = items[position]
            setLogo(holder.logo, ch.icon)
            holder.name.text = ch.name
            holder.name.setTextColor(if (ch.id == current?.id) LILAC else Color.WHITE)
            holder.now.text = ""
            holder.bar.fraction = 0f
            holder.itemView.tag = ch.id
            epgFor(ch.id) { list ->
                if (holder.itemView.tag != ch.id) return@epgFor
                val cur = list.firstOrNull { it.isNow() }
                holder.now.text = cur?.title.orEmpty()
                holder.bar.fraction = cur?.let { progress(it) } ?: 0f
            }
        }

        override fun getItemCount() = items.size
    }

    // ------------------------------------------------------------------
    // Lista de canales (izquierda)
    // ------------------------------------------------------------------

    private lateinit var listCats: RecyclerView
    private lateinit var listChannels: RecyclerView
    private lateinit var listPrograms: RecyclerView
    private lateinit var listHeader: TextView
    private lateinit var epgHeaderLogo: ImageView
    private lateinit var epgHeaderName: TextView
    private lateinit var epgHeaderCat: TextView
    private lateinit var infoTitle: TextView
    private lateinit var infoTime: TextView
    private lateinit var infoBar: Bar
    private lateinit var infoLeft: TextView
    private lateinit var infoDesc: TextView
    private lateinit var infoBox: LinearLayout
    private var focusedChannel: Channel? = null
    private val focusChannelRunnable = Runnable { focusedChannel?.let { showChannelPrograms(it) } }

    private fun showList() {
        val wrap = FrameLayout(activity).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xF0000000.toInt(), 0xCC000000.toInt(), 0x66000000, 0x10000000))
        }
        val cols = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(24), dp(18), 0, 0)
        }

        listCats = verticalList().apply { visibility = View.GONE }
        cols.addView(listCats, LinearLayout.LayoutParams(dp(230), LinearLayout.LayoutParams.MATCH_PARENT).apply { rightMargin = dp(10) })

        val chCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        listHeader = text(categoryName(channelsCategory.orEmpty()).ifBlank { "Canales" }, 17f, Color.WHITE, bold = true)
        chCol.addView(listHeader, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)).apply { leftMargin = dp(6) })
        listChannels = verticalList()
        val chAdapter = ChannelRowAdapter()
        listChannels.adapter = chAdapter
        chCol.addView(listChannels, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        cols.addView(chCol, LinearLayout.LayoutParams(dp(390), LinearLayout.LayoutParams.MATCH_PARENT).apply { rightMargin = dp(14) })

        val epgCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val epgHead = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        epgHeaderLogo = logoView(dp(50), dp(34))
        epgHead.addView(epgHeaderLogo, LinearLayout.LayoutParams(dp(50), dp(34)).apply { rightMargin = dp(12) })
        val headTexts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        epgHeaderName = text("", 16f, Color.WHITE, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        epgHeaderCat = text("", 13f, SOFT).apply { maxLines = 1 }
        headTexts.addView(epgHeaderName)
        headTexts.addView(epgHeaderCat)
        epgHead.addView(headTexts)
        epgCol.addView(epgHead, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        listPrograms = verticalList()
        listPrograms.adapter = ProgramRowAdapter()
        epgCol.addView(listPrograms, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        cols.addView(epgCol, LinearLayout.LayoutParams(dp(400), LinearLayout.LayoutParams.MATCH_PARENT))
        wrap.addView(cols, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // Recuadro de arriba a la derecha con el programa enfocado.
        infoBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.round(PANEL, dp(16).toFloat())
            setPadding(dp(20), dp(14), dp(20), dp(16))
            visibility = View.GONE
        }
        infoTitle = text("", 19f, Color.WHITE, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        infoBox.addView(infoTitle)
        val infoRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        infoTime = text("", 14f, SOFT)
        infoBar = Bar(activity)
        infoLeft = text("", 14f, SOFT)
        infoRow.addView(infoTime, wrapLp(right = 12))
        infoRow.addView(infoBar, LinearLayout.LayoutParams(dp(64), dp(4)).apply { rightMargin = dp(12) })
        infoRow.addView(infoLeft)
        infoBox.addView(infoRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        infoDesc = text("", 14f, SOFT).apply { maxLines = 4; ellipsize = TextUtils.TruncateAt.END }
        infoBox.addView(infoDesc, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        val usedLeft = dp(24 + 390 + 14 + 400 + 24)
        val infoWidth = (dm.widthPixels - usedLeft - dp(28)).coerceAtLeast(dp(260))
        wrap.addView(infoBox, FrameLayout.LayoutParams(infoWidth, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            setMargins(0, dp(20), dp(28), 0)
        })

        switchTo(Mode.LIST, wrap)
        chAdapter.submit(channels)
        val cur = current
        val i = channels.indexOfFirst { it.id == cur?.id }.coerceAtLeast(0)
        focusRow(listChannels, i)
        channels.getOrNull(i)?.let { showChannelPrograms(it) }
    }

    private fun verticalList() = RecyclerView(activity).apply {
        layoutManager = LinearLayoutManager(activity)
        clipToPadding = false
        isVerticalScrollBarEnabled = false
        itemAnimator = null
        setPadding(0, dp(2), 0, dp(40))
    }

    private fun focusRow(list: RecyclerView, index: Int) {
        (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(index, (dm.heightPixels / 3))
        list.post {
            val v = list.findViewHolderForAdapterPosition(index)?.itemView
            if (v != null) v.requestFocus() else list.getChildAt(0)?.requestFocus()
        }
    }

    private fun showChannelPrograms(ch: Channel) {
        if (!::listPrograms.isInitialized || mode != Mode.LIST) return
        setLogo(epgHeaderLogo, ch.icon)
        epgHeaderName.text = if (ch.num > 0) "${ch.num}  ${ch.name}" else ch.name
        epgHeaderCat.text = categoryName(ch.categoryId)
        val adapter = listPrograms.adapter as ProgramRowAdapter
        adapter.submit(ch, emptyList())
        showInfo(null)
        epgFor(ch.id) { list ->
            if (mode != Mode.LIST || focusedChannel?.id != ch.id) return@epgFor
            adapter.submit(ch, list)
            val now = System.currentTimeMillis()
            val idx = list.indexOfFirst { it.isNow(now) }
            if (idx >= 0) (listPrograms.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(idx, dp(120))
            showInfo(list.getOrNull(idx))
        }
    }

    private fun showInfo(p: Program?) {
        if (!::infoBox.isInitialized) return
        if (p == null) { infoBox.visibility = View.GONE; return }
        infoBox.visibility = View.VISIBLE
        val now = System.currentTimeMillis()
        infoTitle.text = p.title
        infoTime.text = range(p)
        val live = p.isNow(now)
        infoBar.visibility = if (live) View.VISIBLE else View.GONE
        infoBar.fraction = progress(p, now)
        infoLeft.text = if (live) minutesLeft(p, now) else "${((p.end - p.start) / 60_000)} min"
        infoDesc.text = p.description
        infoDesc.visibility = if (p.description.isBlank()) View.GONE else View.VISIBLE
    }

    private fun openCategories() {
        if (categories.isEmpty()) { toast("Cargando categorías…"); loadCategories(); return }
        listCats.visibility = View.VISIBLE
        val adapter = (listCats.adapter as? CategoryRowAdapter) ?: CategoryRowAdapter().also { listCats.adapter = it }
        adapter.notifyDataSetChanged()
        val i = categories.indexOfFirst { it.id == channelsCategory }.coerceAtLeast(0)
        focusRow(listCats, i)
    }

    private fun closeCategories() {
        listCats.visibility = View.GONE
        val i = channels.indexOfFirst { it.id == (focusedChannel ?: current)?.id }.coerceAtLeast(0)
        focusRow(listChannels, i)
    }

    private fun pickCategory(cat: Category) {
        listHeader.text = "Cargando…"
        loadChannels(cat.id) { list ->
            if (mode != Mode.LIST) return@loadChannels
            listHeader.text = cat.name
            (listChannels.adapter as ChannelRowAdapter).submit(list)
            listCats.visibility = View.GONE
            focusRow(listChannels, 0)
            list.firstOrNull()?.let { focusedChannel = it; showChannelPrograms(it) }
        }
    }

    private inner class ChannelRowAdapter : RecyclerView.Adapter<ChannelRowAdapter.VH>() {
        private var items: List<Channel> = emptyList()
        fun submit(list: List<Channel>) { items = list; notifyDataSetChanged() }

        inner class VH(root: LinearLayout, val num: TextView, val logo: ImageView, val name: TextView,
                       val now: TextView, val bar: Bar, val playing: TextView) : RecyclerView.ViewHolder(root)

        private fun paint(vh: VH, focused: Boolean) {
            val pos = vh.bindingAdapterPosition
            val ch = items.getOrNull(pos)
            val isCurrent = ch != null && ch.id == current?.id
            vh.itemView.background = if (focused) Ui.round(0xF2FFFFFF.toInt(), dp(10).toFloat()) else null
            vh.num.setTextColor(if (focused) DARK else if (isCurrent) LILAC else Color.WHITE)
            vh.name.setTextColor(if (focused) DARK else if (isCurrent) LILAC else Color.WHITE)
            vh.now.setTextColor(if (focused) 0xFF4A4A60.toInt() else LILAC)
            vh.playing.visibility = if (isCurrent) View.VISIBLE else View.INVISIBLE
            vh.playing.setTextColor(if (focused) DARK else LILAC)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val num = text("", 15f, Color.WHITE).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END }
            val logo = logoView(dp(46), dp(32))
            val name = text("", 15f, Color.WHITE, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            val now = text("", 13f, LILAC).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            val bar = Bar(activity)
            val playing = text("▶", 14f, LILAC)
            val texts = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(name)
                addView(now)
                addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(2)).apply { topMargin = dp(4) })
            }
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(8), dp(10), dp(8))
                isFocusable = true
                isClickable = true
                addView(num, LinearLayout.LayoutParams(dp(42), LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(10) })
                addView(logo, LinearLayout.LayoutParams(dp(46), dp(32)).apply { rightMargin = dp(12) })
                addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(playing, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
                layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(2) }
            }
            val vh = VH(row, num, logo, name, now, bar, playing)
            row.setOnFocusChangeListener { _, has ->
                paint(vh, has)
                if (has) items.getOrNull(vh.bindingAdapterPosition)?.let { ch ->
                    if (focusedChannel?.id != ch.id) {
                        focusedChannel = ch
                        handler.removeCallbacks(focusChannelRunnable)
                        handler.postDelayed(focusChannelRunnable, 250)
                    }
                }
            }
            row.setOnClickListener {
                val ch = items.getOrNull(vh.bindingAdapterPosition) ?: return@setOnClickListener
                if (ch.id == current?.id) hide() else { play(ch); notifyDataSetChanged() }
            }
            return vh
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ch = items[position]
            holder.num.text = if (ch.num > 0) ch.num.toString() else ""
            setLogo(holder.logo, ch.icon)
            holder.name.text = ch.name
            holder.now.text = ""
            holder.bar.fraction = 0f
            holder.bar.visibility = View.INVISIBLE
            paint(holder, holder.itemView.isFocused)
            holder.itemView.tag = ch.id
            epgFor(ch.id) { list ->
                if (holder.itemView.tag != ch.id) return@epgFor
                val cur = list.firstOrNull { it.isNow() }
                holder.now.text = cur?.title.orEmpty()
                holder.bar.visibility = if (cur != null) View.VISIBLE else View.INVISIBLE
                holder.bar.fraction = cur?.let { progress(it) } ?: 0f
            }
        }

        override fun getItemCount() = items.size
    }

    private inner class ProgramRowAdapter : RecyclerView.Adapter<ProgramRowAdapter.VH>() {
        private var channel: Channel? = null
        private var items: List<Program> = emptyList()
        fun submit(ch: Channel, list: List<Program>) { channel = ch; items = list; notifyDataSetChanged() }

        inner class VH(root: LinearLayout, val time: TextView, val title: TextView) : RecyclerView.ViewHolder(root)

        private fun paint(vh: VH, focused: Boolean) {
            val p = items.getOrNull(vh.bindingAdapterPosition)
            val live = p?.isNow() == true
            vh.itemView.background = if (focused) Ui.round(0xF2FFFFFF.toInt(), dp(10).toFloat()) else null
            vh.time.setTextColor(if (focused) DARK else SOFT)
            vh.title.setTextColor(if (focused) DARK else if (live) LILAC else Color.WHITE)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val time = text("", 14f, SOFT)
            val title = text("", 15f, Color.WHITE).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(12), dp(10), dp(12))
                isFocusable = true
                isClickable = true
                addView(time, LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
            }
            val vh = VH(row, time, title)
            row.setOnFocusChangeListener { _, has ->
                paint(vh, has)
                if (has) showInfo(items.getOrNull(vh.bindingAdapterPosition))
            }
            row.setOnClickListener {
                val p = items.getOrNull(vh.bindingAdapterPosition) ?: return@setOnClickListener
                val ch = channel ?: return@setOnClickListener
                when {
                    p.isNow() && ch.id == current?.id -> hide()
                    p.isNow() -> { play(ch); listChannels.adapter?.notifyDataSetChanged() }
                    p.start > System.currentTimeMillis() -> toast("Empieza a las ${timeFmt.format(Date(p.start))}")
                    else -> toast("Ese programa ya pasó")
                }
            }
            return vh
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val p = items[position]
            holder.time.text = timeFmt.format(Date(p.start))
            holder.title.text = p.title
            holder.title.setTypeface(null, if (p.isNow()) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            paint(holder, holder.itemView.isFocused)
        }

        override fun getItemCount() = items.size
    }

    private inner class CategoryRowAdapter : RecyclerView.Adapter<CategoryRowAdapter.VH>() {
        inner class VH(val t: TextView) : RecyclerView.ViewHolder(t)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val t = text("", 15f, Color.WHITE).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(14), dp(12), dp(14), dp(12))
                isFocusable = true
                isClickable = true
                layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
            }
            val vh = VH(t)
            fun paint(focused: Boolean) {
                val cat = categories.getOrNull(vh.bindingAdapterPosition)
                val sel = cat != null && cat.id == channelsCategory
                t.background = if (focused) Ui.round(0xF2FFFFFF.toInt(), dp(10).toFloat()) else null
                t.setTextColor(if (focused) DARK else if (sel) LILAC else Color.WHITE)
            }
            t.setOnFocusChangeListener { _, has -> paint(has) }
            t.tag = Runnable { paint(t.isFocused) }
            t.setOnClickListener { categories.getOrNull(vh.bindingAdapterPosition)?.let { pickCategory(it) } }
            return vh
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.t.text = categories[position].name
            (holder.t.tag as? Runnable)?.run()
        }

        override fun getItemCount() = categories.size
    }

    // ------------------------------------------------------------------
    // Guía (cuadrícula)
    // ------------------------------------------------------------------

    private lateinit var guideRows: RecyclerView
    private lateinit var guideTicks: FrameLayout
    private lateinit var guideNowLine: View
    private lateinit var guideDate: TextView
    private lateinit var gTitle: TextView
    private lateinit var gTime: TextView
    private lateinit var gBar: Bar
    private lateinit var gLeft: TextView
    private lateinit var gDesc: TextView
    private lateinit var gCat: TextView
    private var windowStart = 0L
    private var stripWidth = 0
    private var focusedCell: View? = null
    private val chColW get() = dp(290)

    private fun baseWindow(): Long {
        val now = System.currentTimeMillis()
        return now - now % HALF_HOUR
    }

    private fun showGuide(preview: Boolean) {
        guidePreview = preview
        windowStart = baseWindow()
        val side = dp(24)
        stripWidth = dm.widthPixels - side * 2 - chColW
        val wrap = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, dp(16), side, 0)
            // Con el video en ventanita el fondo ya es negro; en pantalla completa, transparente.
            if (!preview) background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xCC000000.toInt(), 0xB3000000.toInt(), 0xCC000000.toInt()))
        }

        // Arriba: ventanita del video (solo con Atrás) y detalles del programa enfocado.
        val top = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val previewW = (dm.widthPixels * 0.30f).toInt()
        val previewH = previewW * 9 / 16
        if (preview) {
            top.addView(View(activity), LinearLayout.LayoutParams(previewW, previewH).apply { rightMargin = dp(22) })
            playerView.layoutParams = FrameLayout.LayoutParams(previewW, previewH, Gravity.TOP or Gravity.START).apply {
                setMargins(side, dp(16), 0, 0)
            }
        }
        val details = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val titleRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        gTitle = text("", 21f, Color.WHITE, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        gCat = text("", 16f, Color.WHITE, bold = true).apply { maxLines = 1 }
        titleRow.addView(gTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(gCat, wrapLp(right = 0))
        details.addView(titleRow)
        val tRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        gTime = text("", 14f, SOFT)
        gBar = Bar(activity)
        gLeft = text("", 14f, SOFT)
        tRow.addView(gTime, wrapLp(right = 12))
        tRow.addView(gBar, LinearLayout.LayoutParams(dp(64), dp(4)).apply { rightMargin = dp(12) })
        tRow.addView(gLeft)
        details.addView(tRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        gDesc = text("", 14f, SOFT).apply { maxLines = if (preview) 4 else 2; ellipsize = TextUtils.TruncateAt.END }
        details.addView(gDesc, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        top.addView(details, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        wrap.addView(top, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            if (preview) previewH else LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) })

        // Línea de horas.
        val timeline = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        guideDate = text(clockFmt.format(Date()), 14f, LILAC)
        timeline.addView(guideDate, LinearLayout.LayoutParams(chColW, LinearLayout.LayoutParams.WRAP_CONTENT))
        guideTicks = FrameLayout(activity)
        timeline.addView(guideTicks, LinearLayout.LayoutParams(stripWidth, dp(26)))
        wrap.addView(timeline, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        wrap.addView(View(activity).apply { setBackgroundColor(0x66FFFFFF) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { bottomMargin = dp(6) })

        // Filas de canales con su programación.
        val rowsBox = FrameLayout(activity)
        guideRows = verticalList().apply { adapter = GuideRowAdapter() }
        rowsBox.addView(guideRows, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        guideNowLine = View(activity).apply { setBackgroundColor(0xCCFFFFFF.toInt()) }
        rowsBox.addView(guideNowLine, FrameLayout.LayoutParams(dp(2), FrameLayout.LayoutParams.MATCH_PARENT))
        wrap.addView(rowsBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        switchTo(Mode.GUIDE, wrap)
        drawTimeline()
        val i = channels.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
        (guideRows.adapter as GuideRowAdapter).focusOnBind = current?.id
        (guideRows.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(i, dp(110))
        current?.let { ch -> epgFor(ch.id) { list -> if (mode == Mode.GUIDE && focusedCell == null) showGuideDetails(ch, list.firstOrNull { it.isNow() }) } }
    }

    private fun drawTimeline() {
        guideTicks.removeAllViews()
        val pxPerMs = stripWidth.toFloat() / GUIDE_SPAN
        var t = windowStart
        while (t < windowStart + GUIDE_SPAN) {
            val x = ((t - windowStart) * pxPerMs).toInt()
            guideTicks.addView(text(timeFmt.format(Date(t)), 15f, Color.WHITE), FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START
            ).apply { leftMargin = x + dp(4) })
            t += HALF_HOUR
        }
        val now = System.currentTimeMillis()
        val inside = now in windowStart until windowStart + GUIDE_SPAN
        guideNowLine.visibility = if (inside) View.VISIBLE else View.GONE
        (guideNowLine.layoutParams as FrameLayout.LayoutParams).leftMargin = chColW + ((now - windowStart) * pxPerMs).toInt()
        guideNowLine.requestLayout()
    }

    private fun showGuideDetails(ch: Channel, p: Program?) {
        if (!::gTitle.isInitialized) return
        val now = System.currentTimeMillis()
        gCat.text = categoryName(ch.categoryId)
        gTitle.text = p?.title ?: ch.name
        gTime.text = p?.let { range(it) } ?: ""
        val live = p?.isNow(now) == true
        gBar.visibility = if (live) View.VISIBLE else View.GONE
        gBar.fraction = p?.let { progress(it, now) } ?: 0f
        gLeft.text = when {
            p == null -> ""
            live -> minutesLeft(p, now)
            else -> "${(p.end - p.start) / 60_000} min"
        }
        gDesc.text = p?.description.orEmpty()
    }

    private class CellTag(val channel: Channel, val program: Program?, val row: Int)

    /** Derecha/izquierda en el borde de la cuadrícula: se mueve una hora. */
    private fun guideEdge(dir: Int): Boolean {
        val cell = focusedCell ?: return false
        if (!cell.hasFocus() && !cell.isFocused) return false
        val tag = cell.tag as? CellTag ?: return false
        val end = windowStart + GUIDE_SPAN
        val p = tag.program
        if (dir > 0) {
            val atEdge = p == null || p.end >= end || nextCell(cell, +1) == null
            if (!atEdge) return false
            windowStart += HOUR
        } else {
            val atEdge = p == null || p.start <= windowStart || nextCell(cell, -1) == null
            if (!atEdge) return false
            if (windowStart <= baseWindow()) return true // no hay guía hacia atrás
            windowStart = maxOf(baseWindow(), windowStart - HOUR)
        }
        drawTimeline()
        val adapter = guideRows.adapter as GuideRowAdapter
        adapter.focusOnBind = tag.channel.id
        adapter.focusFromRight = dir < 0
        adapter.notifyDataSetChanged()
        return true
    }

    private fun nextCell(cell: View, dir: Int): View? {
        val strip = cell.parent as? ViewGroup ?: return null
        val i = strip.indexOfChild(cell) + dir
        return if (i in 0 until strip.childCount) strip.getChildAt(i) else null
    }

    private inner class GuideRowAdapter : RecyclerView.Adapter<GuideRowAdapter.VH>() {
        /** Al dibujar la fila de este canal, se le pone el foco. */
        var focusOnBind: Int? = null
        var focusFromRight = false

        inner class VH(root: LinearLayout, val num: TextView, val logo: ImageView, val name: TextView,
                       val playing: TextView, val strip: FrameLayout) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val num = text("", 15f, Color.WHITE).apply { gravity = Gravity.END }
            val logo = logoView(dp(42), dp(30))
            val name = text("", 15f, Color.WHITE, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            val playing = text("▶", 14f, LILAC)
            val chCell = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(num, LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(10) })
                addView(logo, LinearLayout.LayoutParams(dp(42), dp(30)).apply { rightMargin = dp(12) })
                addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(playing, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6); rightMargin = dp(10) })
            }
            val strip = FrameLayout(activity)
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(chCell, LinearLayout.LayoutParams(chColW, LinearLayout.LayoutParams.MATCH_PARENT))
                addView(strip, LinearLayout.LayoutParams(stripWidth, LinearLayout.LayoutParams.MATCH_PARENT))
                layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, dp(58))
            }
            return VH(row, num, logo, name, playing, strip)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ch = channels[position]
            val isCurrent = ch.id == current?.id
            holder.num.text = if (ch.num > 0) ch.num.toString() else ""
            holder.num.setTextColor(if (isCurrent) LILAC else Color.WHITE)
            setLogo(holder.logo, ch.icon)
            holder.name.text = ch.name
            holder.name.setTextColor(if (isCurrent) LILAC else Color.WHITE)
            holder.playing.visibility = if (isCurrent) View.VISIBLE else View.INVISIBLE
            holder.itemView.tag = ch.id
            val cached = epg[ch.id]?.second
            fillStrip(holder, ch, position, cached ?: emptyList(), loading = cached == null)
            if (cached == null) epgFor(ch.id) { list ->
                if (holder.itemView.tag == ch.id && holder.bindingAdapterPosition == position) fillStrip(holder, ch, position, list, loading = false)
            }
        }

        private fun fillStrip(holder: VH, ch: Channel, row: Int, list: List<Program>, loading: Boolean) {
            val hadFocus = holder.strip.hasFocus()
            holder.strip.removeAllViews()
            val start = windowStart
            val end = windowStart + GUIDE_SPAN
            val pxPerMs = stripWidth.toFloat() / GUIDE_SPAN
            val visible = list.filter { it.end > start && it.start < end }
            if (visible.isEmpty()) {
                addCell(holder.strip, ch, null, row, 0, stripWidth, if (loading) "…" else "Sin información")
            } else {
                var cursor = start
                for (p in visible) {
                    val s = maxOf(p.start, start)
                    val e = minOf(p.end, end)
                    // Hueco sin programación: un recuadro vacío para poder seguir navegando.
                    if (s - cursor > 5 * 60_000) {
                        addCell(holder.strip, ch, null, row, ((cursor - start) * pxPerMs).toInt(), ((s - cursor) * pxPerMs).toInt(), "")
                    }
                    addCell(holder.strip, ch, p, row, ((s - start) * pxPerMs).toInt(), ((e - s) * pxPerMs).toInt(), p.title)
                    cursor = e
                }
                if (end - cursor > 5 * 60_000) {
                    addCell(holder.strip, ch, null, row, ((cursor - start) * pxPerMs).toInt(), ((end - cursor) * pxPerMs).toInt(), "")
                }
            }
            if (focusOnBind == ch.id || hadFocus) {
                val target = if (focusOnBind == ch.id && focusFromRight) holder.strip.getChildAt(holder.strip.childCount - 1)
                    else if (focusOnBind == ch.id) {
                        val now = System.currentTimeMillis()
                        (0 until holder.strip.childCount).map { holder.strip.getChildAt(it) }
                            .firstOrNull { (it.tag as? CellTag)?.program?.isNow(now) == true } ?: holder.strip.getChildAt(0)
                    } else holder.strip.getChildAt(0)
                if (focusOnBind == ch.id && !loading) { focusOnBind = null; focusFromRight = false }
                target?.post { target.requestFocus() }
            }
        }

        private fun addCell(strip: FrameLayout, ch: Channel, p: Program?, row: Int, x: Int, w: Int, label: String) {
            if (w < dp(6)) return
            val now = System.currentTimeMillis()
            val live = p?.isNow(now) == true
            val rest = if (guidePreview) 0xFF2E2E2E.toInt() else 0x803C3C3C.toInt()
            val restLive = if (guidePreview) 0xFF3D3D3D.toInt() else 0x99505050.toInt()
            val normal = Ui.round(if (live) restLive else rest, dp(4).toFloat())
            val focused = Ui.round(0xF2FFFFFF.toInt(), dp(4).toFloat())
            val cell = text(label, 14f, Color.WHITE, bold = live).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
                background = normal
                isFocusable = true
                isClickable = true
                tag = CellTag(ch, p, row)
            }
            cell.setOnFocusChangeListener { v, has ->
                v.background = if (has) focused else normal
                (v as TextView).setTextColor(if (has) DARK else Color.WHITE)
                if (has) {
                    focusedCell = v
                    showGuideDetails(ch, p)
                }
            }
            cell.setOnClickListener {
                val prog = p
                val nowMs = System.currentTimeMillis()
                when {
                    prog != null && prog.start > nowMs -> toast("Empieza a las ${timeFmt.format(Date(prog.start))}")
                    prog != null && prog.end <= nowMs -> toast("Ese programa ya pasó")
                    ch.id == current?.id -> hide()                  // el que ya se ve: a pantalla completa
                    guidePreview -> { focusOnBind = ch.id; play(ch); notifyDataSetChanged() } // se ve en la ventanita
                    else -> { play(ch); hide() }
                }
            }
            strip.addView(cell, FrameLayout.LayoutParams(w - dp(3), FrameLayout.LayoutParams.MATCH_PARENT).apply {
                leftMargin = x
                topMargin = dp(2)
                bottomMargin = dp(2)
            })
        }

        override fun getItemCount() = channels.size
    }

    // ------------------------------------------------------------------
    // Datos de la cuenta (categorías, canales, programación)
    // ------------------------------------------------------------------

    private fun loadCategories() {
        fetch(account.apiUrl("get_live_categories")) { body ->
            if (body == null) return@fetch
            categories = array(body).map { Category(it.optString("category_id"), it.optString("category_name", "Sin nombre")) }
            if (mode == Mode.LIST && ::listHeader.isInitialized) listHeader.text = categoryName(channelsCategory.orEmpty()).ifBlank { listHeader.text.toString() }
        }
    }

    private fun loadChannels(categoryId: String?, onDone: (List<Channel>) -> Unit) {
        val cat = categoryId ?: prefs.getString(PREF_LAST_CATEGORY, null)?.takeIf { it.isNotBlank() }
        val url = if (cat == null) account.apiUrl("get_live_streams") else account.apiUrl("get_live_streams", "category_id" to cat)
        fetch(url) { body ->
            if (body == null) { toast("No se pudo cargar la lista de canales."); return@fetch }
            var list = array(body).mapNotNull { o ->
                val id = o.optString("stream_id").toIntOrNull() ?: return@mapNotNull null
                Channel(id, o.optString("num").toIntOrNull() ?: 0, o.optString("name").trim(),
                    o.optString("stream_icon"), o.optString("category_id"))
            }
            // Sin categoría conocida: se usa la del canal que se está viendo.
            if (cat == null) {
                val curCat = list.firstOrNull { it.id == current?.id }?.categoryId
                if (!curCat.isNullOrBlank()) list = list.filter { it.categoryId == curCat }
                channelsCategory = curCat ?: ""
            } else {
                channelsCategory = cat
            }
            channels = list
            onDone(list)
        }
    }

    private fun categoryName(id: String): String = categories.firstOrNull { it.id == id }?.name.orEmpty()

    private fun epgFor(id: Int, cb: (List<Program>) -> Unit) {
        val now = System.currentTimeMillis()
        epg[id]?.let { (fetched, list) ->
            val fresh = now - fetched < EPG_TTL && (list.isEmpty() || list.last().end > now)
            if (fresh) { cb(list); return }
        }
        epgWaiting[id]?.let { it.add(cb); return }
        epgWaiting[id] = mutableListOf(cb)
        fetch(account.apiUrl("get_short_epg", "stream_id" to id.toString(), "limit" to "30")) { body ->
            val list = body?.let { parseEpg(it) } ?: emptyList()
            epg[id] = System.currentTimeMillis() to list
            epgWaiting.remove(id)?.forEach { it(list) }
        }
    }

    private fun parseEpg(body: String): List<Program> {
        val arr = try { JSONObject(body).optJSONArray("epg_listings") } catch (_: Exception) { null } ?: return emptyList()
        val out = ArrayList<Program>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val start = o.optString("start_timestamp").toLongOrNull()?.times(1000) ?: parseDate(o.optString("start")) ?: continue
            val end = o.optString("stop_timestamp").toLongOrNull()?.times(1000) ?: parseDate(o.optString("end")) ?: parseDate(o.optString("stop")) ?: continue
            if (end <= start) continue
            out.add(Program(decode(o.optString("title")).trim(), decode(o.optString("description")).trim(), start, end))
        }
        return out.sortedBy { it.start }.distinctBy { it.start }
    }

    private fun parseDate(s: String): Long? = try {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s)?.time
    } catch (_: Exception) { null }

    /** Los paneles Xtream mandan título y descripción en Base64. */
    private fun decode(s: String): String {
        if (s.isBlank()) return ""
        if (!BASE64.matches(s) || s.length % 4 != 0) return s
        return try {
            val text = String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)
            if (text.any { it == '�' }) s else text
        } catch (_: Exception) { s }
    }

    private fun array(body: String): List<JSONObject> {
        val arr = try { JSONArray(body) } catch (_: Exception) { return emptyList() }
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    /** Descarga [url] y entrega el texto en el hilo principal (null si falló). */
    private fun fetch(url: String, onDone: (String?) -> Unit) {
        if (released) return
        http.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                handler.post { if (!released) onDone(null) }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.use { if (it.isSuccessful) it.body?.string() else null }
                handler.post { if (!released) onDone(body) }
            }
        })
    }

    // --- historial ---

    private fun history(): List<Channel> {
        val arr = try { JSONArray(prefs.getString(PREF_HISTORY, "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
            Channel(it.optInt("id"), it.optInt("num"), it.optString("name"), it.optString("icon"), it.optString("cat"))
        }
    }

    private fun addToHistory(ch: Channel) {
        if (ch.name.isBlank()) return
        val list = listOf(ch) + history().filter { it.id != ch.id }
        val arr = JSONArray()
        list.take(20).forEach {
            arr.put(JSONObject().put("id", it.id).put("num", it.num).put("name", it.name).put("icon", it.icon).put("cat", it.categoryId))
        }
        prefs.edit().putString(PREF_HISTORY, arr.toString()).apply()
    }

    // ------------------------------------------------------------------
    // Piezas visuales
    // ------------------------------------------------------------------

    private fun dp(v: Int) = Ui.dp(activity, v)

    private fun wrapLp(right: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .apply { rightMargin = dp(right) }

    private fun text(value: String, sp: Float, color: Int, bold: Boolean = false) = TextView(activity).apply {
        text = value
        textSize = sp
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setShadowLayer(4f, 0f, 1f, 0x99000000.toInt())
    }

    private fun logoView(w: Int, h: Int) = ImageView(activity).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        background = Ui.round(Color.WHITE, dp(5).toFloat())
        setPadding(dp(3), dp(3), dp(3), dp(3))
        layoutParams = LinearLayout.LayoutParams(w, h)
    }

    private fun setLogo(view: ImageView, url: String) {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            view.visibility = View.VISIBLE
            view.load(url) { crossfade(false) }
        } else {
            view.dispose()
            view.setImageDrawable(null)
            view.visibility = View.INVISIBLE
        }
    }

    private fun focusFrame(v: View) {
        val r = dp(14).toFloat()
        val normal = Ui.round(0x33000000, r)
        val focused = Ui.round(0x33FFFFFF, r, dp(3), Color.WHITE)
        v.background = normal
        v.isFocusable = true
        v.isClickable = true
        v.setOnFocusChangeListener { view, has -> view.background = if (has) focused else normal }
    }

    private fun range(p: Program) = "${timeFmt.format(Date(p.start))} – ${timeFmt.format(Date(p.end))}"
    private fun progress(p: Program, now: Long = System.currentTimeMillis()) =
        ((now - p.start).toFloat() / (p.end - p.start).coerceAtLeast(1)).coerceIn(0f, 1f)
    private fun minutesLeft(p: Program, now: Long) = "${((p.end - now) / 60_000).coerceAtLeast(0)} min"

    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()

    /** Barrita de progreso del programa. */
    private class Bar(c: Context) : View(c) {
        var fraction = 0f
            set(v) { field = v.coerceIn(0f, 1f); invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val r = height / 2f
            paint.color = 0x55FFFFFF
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, paint)
            paint.color = Color.WHITE
            canvas.drawRoundRect(0f, 0f, width * fraction, height.toFloat(), r, r, paint)
        }
    }

    private companion object {
        const val PREF_LAST_CATEGORY = "live_last_category"
        const val PREF_HISTORY = "live_history"
        const val HALF_HOUR = 30 * 60_000L
        const val HOUR = 60 * 60_000L
        const val GUIDE_SPAN = 2 * HOUR
        const val EPG_TTL = 20 * 60_000L
        val PANEL = 0xB3121216.toInt()
        val LILAC = 0xFFA8B4FF.toInt()
        val SOFT = 0xFFE0E0E8.toInt()
        val DARK = 0xFF111118.toInt()
        val BASE64 = Regex("^[A-Za-z0-9+/=\\r\\n]+$")
    }
}
