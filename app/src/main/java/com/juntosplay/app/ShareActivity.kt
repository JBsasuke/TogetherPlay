package com.juntosplay.app

import android.app.Activity
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import com.juntosplay.app.relay.XtreamAccount
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Catálogo de la cuenta Xtream Codes, al estilo de las apps de películas: canales con
 * logo, películas y series con póster en cuadrícula, y fichas con descripción.
 * Al elegir algo, arranca el relay, avisa al otro equipo y abre el reproductor.
 */
class ShareActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("togetherplay_settings", Context.MODE_PRIVATE) }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", UserAgents.current(prefs)).build())
        }
        .build()

    private class Item(val id: String, val name: String, val image: String, val rating: String, val data: JSONObject)
    private class Category(val id: String, val name: String)

    /** Cómo volver a la pantalla anterior al pulsar Atrás. */
    private val backStack = ArrayList<() -> Unit>()
    private var current: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (account() == null) showAccountForm() else go { showBrowse(lastKind()) }
    }

    private fun account(): XtreamAccount? {
        val server = prefs.getString("xtream_server", "").orEmpty()
        val user = prefs.getString("xtream_user", "").orEmpty()
        val pass = prefs.getString("xtream_pass", "").orEmpty()
        return if (server.isBlank() || user.isBlank()) null else XtreamAccount(server, user, pass)
    }

    private fun lastKind() = prefs.getString("catalog_kind", "vod") ?: "vod"

    // --- navegación ---

    private fun go(screen: () -> Unit) {
        current?.let { backStack.add(it) }
        current = screen
        screen()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (backStack.isEmpty()) { finish(); return }
        val previous = backStack.removeAt(backStack.size - 1)
        current = previous
        previous()
    }

    private fun dp(value: Int) = Ui.dp(this, value)
    private val wide get() = resources.configuration.screenWidthDp >= 700

    // --- piezas visuales ---

    /** Imagen con proporción fija (póster 2:3, miniatura 16:9...). */
    private class AspectImageView(context: Context, private val ratio: Float) : ImageView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(w, (w * ratio).toInt())
        }
    }

    private fun roundedImage(ratio: Float, crop: Boolean): AspectImageView = AspectImageView(this, ratio).apply {
        scaleType = if (crop) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        background = Ui.round(Ui.inputBg, dp(10).toFloat())
        clipToOutline = true
    }

    private fun setImage(view: ImageView, url: String) {
        if (url.isBlank() || !(url.startsWith("http://") || url.startsWith("https://"))) {
            view.dispose()
            view.setImageDrawable(null)
        } else {
            view.load(url) { crossfade(true) }
        }
    }

    /** Borde cian al enfocar con el control remoto (sin agrandar: así no se corta). */
    private fun focusFrame(v: View, radius: Int = 14) {
        val r = dp(radius).toFloat()
        val normal = Ui.round(android.graphics.Color.TRANSPARENT, r)
        val focused = Ui.round(Ui.surfaceHigh, r, dp(3), Ui.cyan)
        v.background = normal
        v.isFocusable = true
        v.isClickable = true
        v.setOnFocusChangeListener { view, hasFocus -> view.background = if (hasFocus) focused else normal }
    }

    private fun initials(name: String) = name.split(' ', '-', ':').filter { it.isNotBlank() }.take(2)
        .joinToString("") { it.first().uppercase() }

    private fun backdropScreen(): Pair<FrameLayout, ImageView> {
        val root = FrameLayout(this).apply { setBackgroundColor(Ui.bgBottom) }
        val backdrop = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.5f }
        root.addView(backdrop, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val shade = View(this).apply {
            background = GradientDrawable(
                if (wide) GradientDrawable.Orientation.LEFT_RIGHT else GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xF705060F.toInt(), 0xD905060F.toInt(), 0x8005060F.toInt())
            )
        }
        root.addView(shade, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        return root to backdrop
    }

    private fun page(title: String, subtitle: String = ""): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.pageBackground()
            val side = dp(if (wide) 40 else 18)
            setPadding(side, dp(22), side, dp(20))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply { setImageResource(R.drawable.logo) }, LinearLayout.LayoutParams(dp(38), dp(38)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        titles.addView(Ui.label(this, title, 20f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        if (subtitle.isNotBlank()) titles.addView(Ui.label(this, subtitle, 13f, Ui.muted))
        header.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header, Ui.matchWrap(bottomMargin = dp(20)))
        Ui.applySystemBarsPadding(root)
        setContentView(root)
        return root
    }

    private fun showAccountForm() {
        val root = page("Cuenta IPTV", "Xtream Codes")
        val card = Ui.card(this)
        card.addView(Ui.sectionTitle(this, "Datos de acceso"))
        fun field(hint: String, value: String, password: Boolean = false): EditText {
            val input = Ui.input(this, hint)
            input.setText(value)
            if (password) input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            card.addView(input, Ui.matchWrap(bottomMargin = dp(12)))
            return input
        }
        val server = field("Servidor, p. ej. http://proveedor.com:8080", prefs.getString("xtream_server", "").orEmpty())
        val user = field("Usuario", prefs.getString("xtream_user", "").orEmpty())
        val pass = field("Contraseña", prefs.getString("xtream_pass", "").orEmpty(), password = true)
        card.addView(Ui.label(this, "Identificarse ante el proveedor como", 14f, Ui.muted), Ui.matchWrap(topMargin = dp(4), bottomMargin = dp(8)))
        var agentKey = prefs.getString(UserAgents.PREF, UserAgents.VLC) ?: UserAgents.VLC
        card.addView(Ui.chipGroup(this, UserAgents.options, agentKey) { agentKey = it }, Ui.matchWrap(bottomMargin = dp(6)))
        card.addView(
            Ui.label(this, "Si las películas o algunos canales no abren y en TiviMate sí, prueba otra opción.", 13f, Ui.hint),
            Ui.matchWrap(bottomMargin = dp(14))
        )
        val save = Ui.button(this, "Guardar") {
            var address = server.text.toString().trim()
            if (address.isNotEmpty() && !address.startsWith("http://") && !address.startsWith("https://")) {
                address = "http://$address"
            }
            if (address.isBlank() || user.text.isBlank()) {
                toast("Escribe el servidor y el usuario.")
            } else {
                prefs.edit()
                    .putString("xtream_server", address)
                    .putString("xtream_user", user.text.toString().trim())
                    .putString("xtream_pass", pass.text.toString())
                    .putString(UserAgents.PREF, agentKey)
                    .apply()
                backStack.clear()
                current = null
                go { showBrowse(lastKind()) }
            }
        }
        card.addView(save, Ui.matchWrap(topMargin = dp(4)))
        card.addView(
            Ui.label(this, "Los datos se guardan solo en este televisor. El otro lado nunca los recibe.", 13f, Ui.hint),
            Ui.matchWrap(topMargin = dp(14))
        )
        val scroll = ScrollView(this)
        scroll.addView(card)
        val width = if (wide) dp(560) else LinearLayout.LayoutParams.MATCH_PARENT
        root.addView(scroll, LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT))
        if (server.text.isNotBlank()) save.post { save.requestFocus() }
    }

    // --- catálogo ---

    private var allItems: List<Item> = emptyList()
    private var searchText = ""
    private var loadSeq = 0

    private fun showBrowse(kind: String) {
        prefs.edit().putString("catalog_kind", kind).apply()
        val acc = account() ?: run { showAccountForm(); return }
        searchText = ""
        allItems = emptyList()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.pageBackground()
            val side = dp(if (wide) 32 else 14)
            setPadding(side, dp(16), side, dp(8))
        }
        Ui.applySystemBarsPadding(root)

        // Encabezado: logo, pestañas, búsqueda y cuenta.
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply { setImageResource(R.drawable.logo) }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(14) })
        val tabs = Ui.chipGroup(this, listOf("live" to "En vivo", "vod" to "Películas", "series" to "Series"), kind) { k ->
            if (k != kind) {
                current = { showBrowse(k) }
                showBrowse(k)
            }
        }
        header.addView(tabs, if (wide) LinearLayout.LayoutParams(dp(380), LinearLayout.LayoutParams.WRAP_CONTENT)
            else LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val search = Ui.input(this, "Buscar…")
        if (wide) {
            header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            header.addView(search, LinearLayout.LayoutParams(dp(260), LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(12) })
        }
        header.addView(Ui.button(this, "Cuenta", Ui.Style.GHOST) { go { showAccountForm() } },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
        root.addView(header, Ui.matchWrap(bottomMargin = dp(12)))
        if (!wide) root.addView(search, Ui.matchWrap(bottomMargin = dp(10)))

        // Cuerpo: categorías (a la izquierda en TV, arriba en el teléfono) y cuadrícula.
        val body = LinearLayout(this).apply { orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        val categories = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ShareActivity, if (wide) RecyclerView.VERTICAL else RecyclerView.HORIZONTAL, false)
            clipToPadding = false
            isVerticalScrollBarEnabled = false
        }
        val gridBox = FrameLayout(this)
        val status = Ui.label(this, "Cargando…", 16f, Ui.muted).apply { gravity = Gravity.CENTER }
        val cellDp = when {
            kind == "live" -> if (wide) 150 else 110
            else -> if (wide) 140 else 108
        }
        val availableDp = resources.configuration.screenWidthDp - (if (wide) 64 + 230 else 28)
        val span = (availableDp / cellDp).coerceAtLeast(2)
        val grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@ShareActivity, span)
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(24))
            setItemViewCacheSize(30)
        }
        val posters = PosterAdapter(kind) { item -> open(kind, item, acc) }
        grid.adapter = posters
        gridBox.addView(grid, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        gridBox.addView(status, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        if (wide) {
            body.addView(categories, LinearLayout.LayoutParams(dp(220), LinearLayout.LayoutParams.MATCH_PARENT).apply { rightMargin = dp(16) })
            body.addView(gridBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        } else {
            body.addView(categories, Ui.matchWrap(bottomMargin = dp(10)))
            body.addView(gridBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        root.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        fun applyFilter() {
            val q = searchText.trim().lowercase()
            val list = if (q.isBlank()) allItems else allItems.filter { it.name.lowercase().contains(q) }
            posters.submit(list)
            status.text = if (list.isEmpty()) "No hay nada aquí." else ""
            status.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { searchText = s?.toString().orEmpty(); applyFilter() }
        })

        var firstLoad = true
        fun loadItems(cat: Category) {
            val seq = ++loadSeq
            status.text = "Cargando…"
            status.visibility = View.VISIBLE
            posters.submit(emptyList())
            val action = when (kind) { "live" -> "get_live_streams"; "vod" -> "get_vod_streams"; else -> "get_series" }
            val url = if (cat.id.isBlank()) acc.apiUrl(action) else acc.apiUrl(action, "category_id" to cat.id)
            fetch(url, onError = { msg -> if (seq == loadSeq) { status.text = "No se pudo cargar: $msg"; status.visibility = View.VISIBLE } }) { bodyText ->
                if (seq != loadSeq) return@fetch
                allItems = parseItems(kind, bodyText)
                applyFilter()
                if (firstLoad) {
                    firstLoad = false
                    grid.post { grid.getChildAt(0)?.requestFocus() }
                }
            }
        }

        val catAction = when (kind) { "live" -> "get_live_categories"; "vod" -> "get_vod_categories"; else -> "get_series_categories" }
        fetch(acc.apiUrl(catAction), onError = { msg -> status.text = "No se pudo cargar la lista: $msg" }) { bodyText ->
            val cats = listOf(Category("", "Todas")) + array(bodyText).map {
                Category(it.optString("category_id"), it.optString("category_name", "Sin nombre"))
            }
            val start = if (cats.size > 1) 1 else 0
            categories.adapter = CategoryAdapter(cats, start, horizontal = !wide) { loadItems(cats[it]) }
            categories.scrollToPosition(start)
            loadItems(cats[start])
        }
    }

    private fun parseItems(kind: String, body: String): List<Item> = array(body).mapNotNull { o ->
        val name = o.optString("name").trim()
        if (name.isBlank()) return@mapNotNull null
        when (kind) {
            "live" -> Item(o.optString("stream_id"), name, o.optString("stream_icon"), "", o)
            "vod" -> Item(o.optString("stream_id"), name, o.optString("stream_icon"), rating(o), o)
            else -> Item(o.optString("series_id"), name, o.optString("cover"), rating(o), o)
        }
    }

    private fun rating(o: JSONObject): String {
        val r = o.optString("rating").toDoubleOrNull()?.takeIf { it > 0 }
            ?: o.optString("rating_5based").toDoubleOrNull()?.takeIf { it > 0 }?.times(2)
            ?: return ""
        return String.format(java.util.Locale.US, "%.1f", r.coerceAtMost(10.0))
    }

    private fun open(kind: String, item: Item, acc: XtreamAccount) {
        when (kind) {
            "live" -> item.id.toIntOrNull()?.let { share("live", acc.liveUrl(it), item.name) }
            "vod" -> go { showMovie(item) }
            else -> go { showSeries(item) }
        }
    }

    // --- listas ---

    private inner class CategoryAdapter(
        private val cats: List<Category>,
        private var selected: Int,
        private val horizontal: Boolean,
        private val onPick: (Int) -> Unit
    ) : RecyclerView.Adapter<CategoryAdapter.VH>() {
        inner class VH(val text: TextView) : RecyclerView.ViewHolder(text)

        private fun paint(t: TextView, position: Int) {
            val r = dp(12).toFloat()
            val isSelected = position == selected
            t.background = when {
                isSelected -> Ui.gradient(r, if (t.isFocused) dp(3) else 0, android.graphics.Color.WHITE)
                t.isFocused -> Ui.round(Ui.surfaceHigh, r, dp(2), Ui.cyan)
                else -> Ui.round(if (horizontal) Ui.surface else android.graphics.Color.TRANSPARENT, r)
            }
            t.setTextColor(if (isSelected || t.isFocused) Ui.text else Ui.muted)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val t = TextView(parent.context).apply {
                textSize = 15f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(14), dp(10), dp(14), dp(10))
                isFocusable = true
                isClickable = true
                layoutParams = RecyclerView.LayoutParams(
                    if (horizontal) RecyclerView.LayoutParams.WRAP_CONTENT else RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(2), dp(3), dp(if (horizontal) 8 else 2), dp(3)) }
            }
            val vh = VH(t)
            t.setOnFocusChangeListener { _, _ -> paint(t, vh.bindingAdapterPosition) }
            t.setOnClickListener {
                val pos = vh.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                val old = selected
                selected = pos
                notifyItemChanged(old)
                notifyItemChanged(pos)
                onPick(pos)
            }
            return vh
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.text.text = cats[position].name
            paint(holder.text, position)
        }

        override fun getItemCount() = cats.size
    }

    private inner class PosterAdapter(
        private val kind: String,
        private val onPick: (Item) -> Unit
    ) : RecyclerView.Adapter<PosterAdapter.VH>() {
        private var items: List<Item> = emptyList()

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<Item>) {
            items = list
            notifyDataSetChanged()
        }

        inner class VH(root: View, val image: ImageView, val letters: TextView, val badge: TextView, val title: TextView) :
            RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val live = kind == "live"
            val root = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(6), dp(6), dp(8))
                layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
            }
            focusFrame(root)
            val frame = FrameLayout(parent.context)
            val letters = Ui.label(parent.context, "", if (live) 20f else 24f, Ui.hint, bold = true).apply { gravity = Gravity.CENTER }
            val image = roundedImage(if (live) 0.62f else 1.5f, crop = !live).apply {
                if (live) setPadding(dp(14), dp(10), dp(14), dp(10))
            }
            frame.addView(letters, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            frame.addView(image, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            val badge = Ui.label(parent.context, "", 11f, Ui.text, bold = true).apply {
                background = Ui.round(0xCC05060F.toInt(), dp(8).toFloat())
                setPadding(dp(6), dp(2), dp(6), dp(2))
            }
            frame.addView(badge, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                setMargins(dp(6), dp(6), dp(6), dp(6))
            })
            root.addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            val title = Ui.label(parent.context, "", 13f, Ui.text).apply {
                maxLines = if (live) 1 else 2
                ellipsize = TextUtils.TruncateAt.END
                gravity = if (live) Gravity.CENTER_HORIZONTAL else Gravity.START
                setPadding(dp(2), dp(6), dp(2), 0)
            }
            root.addView(title, Ui.matchWrap())
            val vh = VH(root, image, letters, badge, title)
            root.setOnClickListener {
                val pos = vh.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onPick(items[pos])
            }
            return vh
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.title.text = item.name
            holder.letters.text = initials(item.name)
            setImage(holder.image, item.image)
            holder.badge.text = "★ ${item.rating}"
            holder.badge.visibility = if (item.rating.isNotBlank()) View.VISIBLE else View.GONE
        }

        override fun getItemCount() = items.size
    }

    // --- ficha de película ---

    private fun showMovie(item: Item) {
        val acc = account() ?: return
        val (root, backdrop) = backdropScreen()
        setImage(backdrop, item.image)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            val pad = dp(if (wide) 48 else 18)
            setPadding(pad, dp(if (wide) 40 else 18), pad, dp(24))
            gravity = if (wide) Gravity.CENTER_VERTICAL else Gravity.TOP
        }
        scroll.addView(content)
        Ui.applySystemBarsPadding(scroll)
        val poster = roundedImage(1.5f, crop = true)
        setImage(poster, item.image)
        content.addView(poster, LinearLayout.LayoutParams(dp(if (wide) 230 else 150), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            if (wide) rightMargin = dp(36) else bottomMargin = dp(16)
        })
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(Ui.label(this, item.name, if (wide) 30f else 24f, bold = true), Ui.matchWrap(bottomMargin = dp(8)))
        val meta = Ui.label(this, if (item.rating.isNotBlank()) "★ ${item.rating}" else "", 14f, Ui.cyan)
        info.addView(meta, Ui.matchWrap(bottomMargin = dp(6)))
        val genre = Ui.label(this, "", 14f, Ui.muted)
        info.addView(genre, Ui.matchWrap(bottomMargin = dp(14)))
        val plot = Ui.label(this, "Cargando descripción…", 15f, Ui.text).apply { setLineSpacing(0f, 1.15f) }
        info.addView(plot, Ui.matchWrap(bottomMargin = dp(10)))
        val people = Ui.label(this, "", 13f, Ui.hint)
        info.addView(people, Ui.matchWrap(bottomMargin = dp(20)))
        var ext = item.data.optString("container_extension", "mp4").ifBlank { "mp4" }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
        val play = Ui.button(this, "▶  Ver juntos") {
            item.id.toIntOrNull()?.let { share("vod", acc.movieUrl(it, ext), item.name) }
        }
        buttons.addView(play, LinearLayout.LayoutParams(wrap, wrap).apply { rightMargin = dp(12) })
        buttons.addView(Ui.button(this, "Volver", Ui.Style.GHOST) { onBackPressed() }, LinearLayout.LayoutParams(wrap, wrap))
        info.addView(buttons, Ui.matchWrap())
        content.addView(info, if (wide) LinearLayout.LayoutParams(0, wrap, 1f) else Ui.matchWrap())
        root.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        play.post { play.requestFocus() }

        fetch(acc.apiUrl("get_vod_info", "vod_id" to item.id), onError = { plot.text = "" }) { body ->
            val j = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
            val i = j.optJSONObject("info") ?: JSONObject()
            j.optJSONObject("movie_data")?.optString("container_extension")?.takeIf { it.isNotBlank() }?.let { ext = it }
            firstImage(i.opt("backdrop_path"))?.let { setImage(backdrop, it) }
            val cover = i.optString("movie_image").ifBlank { i.optString("cover_big") }
            if (item.image.isBlank() && cover.isNotBlank()) setImage(poster, cover)
            val year = i.optString("releasedate").ifBlank { i.optString("release_date") }.take(4)
            val duration = i.optString("duration").takeIf { it.isNotBlank() && it != "00:00:00" }?.let { shortDuration(it) }
            val r = i.optString("rating").toDoubleOrNull()?.takeIf { it > 0 }?.let { String.format(java.util.Locale.US, "★ %.1f", it) }
                ?: meta.text.toString().takeIf { it.isNotBlank() }
            meta.text = listOfNotNull(year.takeIf { it.isNotBlank() }, duration, r).joinToString("   ·   ")
            genre.text = i.optString("genre")
            plot.text = i.optString("plot").ifBlank { i.optString("description") }.ifBlank { "Sin descripción." }
            val director = i.optString("director").takeIf { it.isNotBlank() }?.let { "Dirección: $it" }
            val cast = i.optString("cast").ifBlank { i.optString("actors") }.takeIf { it.isNotBlank() }?.let { "Reparto: $it" }
            people.text = listOfNotNull(director, cast).joinToString("\n")
        }
    }

    private fun firstImage(v: Any?): String? = when (v) {
        is JSONArray -> (0 until v.length()).map { v.optString(it) }.firstOrNull { it.startsWith("http") }
        is String -> v.takeIf { it.startsWith("http") }
        else -> null
    }

    private fun shortDuration(d: String): String {
        val parts = d.split(":").mapNotNull { it.toIntOrNull() }
        if (parts.size == 3) {
            val (h, m) = parts[0] to parts[1]
            return if (h > 0) "${h} h ${m} min" else "${m} min"
        }
        return d
    }

    // --- ficha de serie ---

    private fun showSeries(item: Item) {
        val acc = account() ?: return
        val (root, backdrop) = backdropScreen()
        setImage(backdrop, item.image)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(if (wide) 40 else 16)
            setPadding(pad, dp(if (wide) 28 else 14), pad, dp(8))
        }
        Ui.applySystemBarsPadding(content)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val poster = roundedImage(1.5f, crop = true)
        setImage(poster, item.image)
        top.addView(poster, LinearLayout.LayoutParams(dp(if (wide) 150 else 96), LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(if (wide) 28 else 14) })
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(Ui.label(this, item.name, if (wide) 28f else 20f, bold = true), Ui.matchWrap(bottomMargin = dp(6)))
        val meta = Ui.label(this, if (item.rating.isNotBlank()) "★ ${item.rating}" else "", 14f, Ui.cyan)
        info.addView(meta, Ui.matchWrap(bottomMargin = dp(8)))
        val plot = Ui.label(this, "Cargando…", if (wide) 15f else 13f, Ui.text).apply {
            maxLines = if (wide) 5 else 4
            ellipsize = TextUtils.TruncateAt.END
        }
        info.addView(plot, Ui.matchWrap())
        top.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(top, Ui.matchWrap(bottomMargin = dp(14)))

        val seasonsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val seasonsScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(seasonsRow) }
        content.addView(seasonsScroll, Ui.matchWrap(bottomMargin = dp(10)))
        val episodes = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ShareActivity)
            clipToPadding = false
            setPadding(0, 0, 0, dp(20))
        }
        val episodeAdapter = EpisodeAdapter { ep, label ->
            share("vod", acc.episodeUrl(ep.optString("id"), ep.optString("container_extension", "mkv").ifBlank { "mkv" }), "${item.name} · $label")
        }
        episodes.adapter = episodeAdapter
        content.addView(episodes, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)

        fetch(acc.apiUrl("get_series_info", "series_id" to item.id), onError = { msg -> plot.text = "No se pudo cargar: $msg" }) { body ->
            val j = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
            val i = j.optJSONObject("info") ?: JSONObject()
            firstImage(i.opt("backdrop_path"))?.let { setImage(backdrop, it) }
            val year = i.optString("releaseDate").ifBlank { i.optString("release_date") }.take(4)
            val r = meta.text.toString().takeIf { it.isNotBlank() }
            meta.text = listOfNotNull(year.takeIf { it.isNotBlank() }, i.optString("genre").takeIf { it.isNotBlank() }, r).joinToString("   ·   ")
            plot.text = i.optString("plot").ifBlank { "Sin descripción." }

            // Temporadas: "episodes" suele ser { "1": [...], "2": [...] }; algunos paneles mandan una lista.
            val seasons = LinkedHashMap<String, List<JSONObject>>()
            j.optJSONObject("episodes")?.let { obj ->
                obj.keys().asSequence().sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }.forEach { key ->
                    obj.optJSONArray(key)?.let { arr -> seasons[key] = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } }
                }
            } ?: j.optJSONArray("episodes")?.let { arr ->
                for (n in 0 until arr.length()) arr.optJSONArray(n)?.let { a -> seasons[(n + 1).toString()] = (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
            }
            if (seasons.isEmpty()) { plot.text = "${plot.text}\n\nEsta serie no tiene episodios disponibles."; return@fetch }
            val chips = ArrayList<TextView>()
            fun selectSeason(key: String) {
                chips.forEach { c -> c.isSelected = c.tag == key; c.background = seasonChipBg(c) }
                episodeAdapter.submit(key, seasons[key].orEmpty())
            }
            seasons.keys.forEach { key ->
                val chip = TextView(this).apply {
                    text = "Temporada $key"
                    tag = key
                    textSize = 14f
                    setPadding(dp(16), dp(9), dp(16), dp(9))
                    isFocusable = true
                    isClickable = true
                    setOnFocusChangeListener { v, _ -> v.background = seasonChipBg(v as TextView) }
                    setOnClickListener { selectSeason(key) }
                }
                chips.add(chip)
                seasonsRow.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) })
            }
            selectSeason(seasons.keys.first())
            chips.first().post { chips.first().requestFocus() }
        }
    }

    private fun seasonChipBg(c: TextView): GradientDrawable {
        val r = dp(100).toFloat()
        c.setTextColor(if (c.isSelected || c.isFocused) Ui.text else Ui.muted)
        return when {
            c.isSelected -> Ui.gradient(r, if (c.isFocused) dp(3) else 0, android.graphics.Color.WHITE)
            c.isFocused -> Ui.round(Ui.surfaceHigh, r, dp(2), Ui.cyan)
            else -> Ui.round(Ui.surface, r, dp(1), Ui.outline)
        }
    }

    private inner class EpisodeAdapter(private val onPick: (JSONObject, String) -> Unit) : RecyclerView.Adapter<EpisodeAdapter.VH>() {
        private var season = ""
        private var items: List<JSONObject> = emptyList()

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun submit(seasonKey: String, list: List<JSONObject>) {
            season = seasonKey
            items = list
            notifyDataSetChanged()
        }

        inner class VH(root: View, val thumb: ImageView, val title: TextView, val plot: TextView, val extra: TextView) : RecyclerView.ViewHolder(root)

        private fun label(ep: JSONObject, position: Int): String {
            val num = ep.optString("episode_num").ifBlank { (position + 1).toString() }
            return "T$season E$num"
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val root = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(4)
                }
            }
            focusFrame(root)
            val thumb = roundedImage(0.5625f, crop = true)
            root.addView(thumb, LinearLayout.LayoutParams(dp(if (wide) 200 else 128), LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(16) })
            val col = LinearLayout(parent.context).apply { orientation = LinearLayout.VERTICAL }
            val title = Ui.label(parent.context, "", if (wide) 17f else 15f, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
            val plot = Ui.label(parent.context, "", 13f, Ui.muted).apply { maxLines = if (wide) 3 else 2; ellipsize = TextUtils.TruncateAt.END }
            val extra = Ui.label(parent.context, "", 12f, Ui.hint)
            col.addView(title, Ui.matchWrap(bottomMargin = dp(4)))
            col.addView(plot, Ui.matchWrap(bottomMargin = dp(4)))
            col.addView(extra, Ui.matchWrap())
            root.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val vh = VH(root, thumb, title, plot, extra)
            root.setOnClickListener {
                val pos = vh.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val ep = items[pos]
                    val name = ep.optString("title")
                    onPick(ep, label(ep, pos) + if (name.isBlank()) "" else " · $name")
                }
            }
            return vh
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ep = items[position]
            val info = ep.optJSONObject("info") ?: JSONObject()
            val name = ep.optString("title")
            holder.title.text = label(ep, position) + if (name.isBlank()) "" else "  ·  $name"
            holder.plot.text = info.optString("plot")
            holder.plot.visibility = if (holder.plot.text.isNullOrBlank()) View.GONE else View.VISIBLE
            holder.extra.text = info.optString("duration").takeIf { it.isNotBlank() && it != "00:00:00" }?.let { shortDuration(it) } ?: ""
            setImage(holder.thumb, info.optString("movie_image").ifBlank { info.optString("cover_big") })
        }

        override fun getItemCount() = items.size
    }

    // --- red ---

    private fun array(body: String): List<JSONObject> {
        val arr = try { JSONArray(body) } catch (_: Exception) { return emptyList() }
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    private fun fetch(url: String, onError: (String) -> Unit = { toast("No se pudo cargar: $it") }, onOk: (String) -> Unit) {
        thread {
            try {
                val body = http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    response.body?.string().orEmpty()
                }
                runOnUiThread { if (!isFinishing && !isDestroyed) onOk(body) }
            } catch (e: Exception) {
                runOnUiThread { if (!isFinishing && !isDestroyed) onError(e.message ?: "sin detalles") }
            }
        }
    }

    // --- compartir ---

    private fun share(kind: String, upstreamUrl: String, title: String) {
        if (RoomSession.client == null) { toast("Conecta primero con el servidor y entra en una sala."); return }
        val appContext = applicationContext
        toastShort("Abriendo…")
        thread {
            try {
                // Detecta si el proveedor lo entrega en HLS (m3u8), TS o archivo, y lo comparte así.
                val p = Sharing.prepareUrl(appContext, upstreamUrl, live = kind == "live", title = title)
                runOnUiThread {
                    Sharing.announce(p)
                    startActivity(PlayerActivity.intent(this, p.localUrl, p.kind, title, host = true))
                }
            } catch (e: Throwable) {
                runOnUiThread { toast("No se pudo compartir: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun toastShort(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        http.dispatcher.executorService.shutdown()
        super.onDestroy()
    }
}
