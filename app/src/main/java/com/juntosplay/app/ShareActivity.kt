package com.juntosplay.app

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.juntosplay.app.relay.StreamRelay
import com.juntosplay.app.relay.XtreamAccount
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Elegir qué compartir desde la cuenta Xtream Codes: canal en vivo, película o
 * episodio. Al elegir, arranca el relay, avisa al otro televisor y abre el reproductor.
 */
class ShareActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("togetherplay_settings", Context.MODE_PRIVATE) }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", UserAgents.current(prefs)).build())
        }
        .build()

    private class Item(val label: String, val data: JSONObject)

    /** Cómo volver a la pantalla anterior al pulsar Atrás. */
    private val backStack = ArrayList<() -> Unit>()
    private var current: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (account() == null) showAccountForm() else showMenu()
    }

    private fun account(): XtreamAccount? {
        val server = prefs.getString("xtream_server", "").orEmpty()
        val user = prefs.getString("xtream_user", "").orEmpty()
        val pass = prefs.getString("xtream_pass", "").orEmpty()
        return if (server.isBlank() || user.isBlank()) null else XtreamAccount(server, user, pass)
    }

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

    // --- pantallas ---

    private fun dp(value: Int) = Ui.dp(this, value)
    private val wide get() = resources.configuration.screenWidthDp >= 700

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
                go { showMenu() }
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

    /** Tarjeta grande del menú, con borde cian y un poco más grande al enfocarla. */
    private fun tile(title: String, subtitle: String, onClick: () -> Unit): LinearLayout {
        val r = dp(18).toFloat()
        val normal = Ui.round(Ui.surface, r, dp(1), Ui.outline)
        val focused = Ui.round(Ui.surfaceHigh, r, dp(3), Ui.cyan)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            background = normal
            setPadding(dp(18), dp(18), dp(18), dp(18))
            minimumHeight = dp(if (wide) 112 else 88)
            isFocusable = true
            isClickable = true
            addView(View(context).apply { background = Ui.gradient(dp(3).toFloat()) },
                LinearLayout.LayoutParams(dp(36), dp(5)).apply { bottomMargin = dp(14) })
            addView(Ui.label(context, title, 18f, bold = true))
            addView(Ui.label(context, subtitle, 13f, Ui.muted))
            setOnFocusChangeListener { v, hasFocus -> v.background = if (hasFocus) focused else normal }
            setOnClickListener { onClick() }
        }
    }

    private fun showMenu() {
        current = current ?: { showMenu() }
        val root = page("Compartir desde mi IPTV", "Elige qué quieren ver juntos")
        val tiles = listOf(
            tile("En vivo", "Canales de TV") { browseCategories("live") },
            tile("Películas", "Catálogo bajo demanda") { browseCategories("vod") },
            tile("Series", "Temporadas y episodios") { browseCategories("series") }
        )
        val agentName = UserAgents.options.firstOrNull { it.first == prefs.getString(UserAgents.PREF, UserAgents.VLC) }?.second ?: "VLC"
        val row = LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            // Sitio para que la tarjeta enfocada crezca sin recortarse.
            clipChildren = false
            clipToPadding = false
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        root.clipChildren = false
        tiles.forEachIndexed { index, tile ->
            val last = index == tiles.size - 1
            val params = if (wide) {
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (!last) rightMargin = dp(18) }
            } else {
                Ui.matchWrap(bottomMargin = if (last) 0 else dp(14))
            }
            row.addView(tile, params)
        }
        root.addView(row, Ui.matchWrap(bottomMargin = dp(18)))
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
        root.addView(
            Ui.button(this, "Cuenta IPTV · se identifica como $agentName", Ui.Style.GHOST) { go { showAccountForm() } },
            LinearLayout.LayoutParams(wrap, wrap)
        )
        tiles[0].post { tiles[0].requestFocus() }
    }

    private fun showList(title: String, labels: List<String>, onPick: (Int) -> Unit) {
        val root = page(title, if (labels.isEmpty()) "" else "${labels.size} en la lista · Atrás para volver")
        val card = Ui.card(this).apply { setPadding(dp(10), dp(10), dp(10), dp(10)) }
        if (labels.isEmpty()) {
            card.setPadding(dp(24), dp(24), dp(24), dp(24))
            card.addView(Ui.label(this, "No hay nada en esta lista.", 16f, Ui.muted))
            root.addView(card, Ui.matchWrap())
            return
        }
        val rowPadH = dp(18)
        val rowPadV = dp(11)
        val list = ListView(this).apply {
            adapter = object : ArrayAdapter<String>(this@ShareActivity, android.R.layout.simple_list_item_1, labels) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val row = (convertView as? TextView) ?: TextView(context).apply {
                        textSize = 16f
                        setTextColor(Ui.text)
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(rowPadH, rowPadV, rowPadH, rowPadV)
                    }
                    row.text = getItem(position)
                    return row
                }
            }
            setOnItemClickListener { _, _, position, _ -> onPick(position) }
            selector = Ui.round(Ui.surfaceHigh, dp(12).toFloat(), dp(2), Ui.cyan)
            divider = null
            dividerHeight = dp(2)
            isVerticalScrollBarEnabled = false
        }
        card.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        list.requestFocus()
    }

    private fun showItems(title: String, items: List<Item>, onPick: (Item) -> Unit) =
        showList(title, items.map { it.label }) { onPick(items[it]) }

    // --- catálogo Xtream ---

    private fun browseCategories(kind: String) {
        val acc = account() ?: return
        val action = when (kind) { "live" -> "get_live_categories"; "vod" -> "get_vod_categories"; else -> "get_series_categories" }
        fetch(acc.apiUrl(action)) { body ->
            val items = array(body).map { Item(it.optString("category_name", "Sin nombre"), it) }
            go { showItems("Categorías", items) { browseStreams(kind, it.data.optString("category_id"), it.label) } }
        }
    }

    private fun browseStreams(kind: String, categoryId: String, categoryName: String) {
        val acc = account() ?: return
        val action = when (kind) { "live" -> "get_live_streams"; "vod" -> "get_vod_streams"; else -> "get_series" }
        fetch(acc.apiUrl(action, "category_id" to categoryId)) { body ->
            val items = array(body).map { Item(it.optString("name", "Sin nombre"), it) }
            go {
                showItems(categoryName, items) { item ->
                    when (kind) {
                        "live" -> share("live", acc.liveUrl(item.data.optInt("stream_id")), item.label)
                        "vod" -> share(
                            "vod",
                            acc.movieUrl(item.data.optInt("stream_id"), item.data.optString("container_extension", "mp4")),
                            item.label
                        )
                        else -> browseEpisodes(item.data.optString("series_id"), item.label)
                    }
                }
            }
        }
    }

    private fun browseEpisodes(seriesId: String, seriesName: String) {
        val acc = account() ?: return
        fetch(acc.apiUrl("get_series_info", "series_id" to seriesId)) { body ->
            val items = ArrayList<Item>()
            fun addSeason(season: String, episodes: JSONArray) {
                for (i in 0 until episodes.length()) {
                    val ep = episodes.optJSONObject(i) ?: continue
                    val number = ep.optString("episode_num", (i + 1).toString())
                    val name = ep.optString("title", "")
                    items.add(Item("T$season E$number" + if (name.isBlank()) "" else " · $name", ep))
                }
            }
            // "episodes" suele ser un objeto { "1": [...], "2": [...] }; algunos paneles envían una lista.
            val root = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
            val byObject = root.optJSONObject("episodes")
            val byArray = root.optJSONArray("episodes")
            if (byObject != null) {
                val seasons = byObject.keys().asSequence().toList().sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
                for (season in seasons) byObject.optJSONArray(season)?.let { addSeason(season, it) }
            } else if (byArray != null) {
                for (i in 0 until byArray.length()) byArray.optJSONArray(i)?.let { addSeason((i + 1).toString(), it) }
            }
            go {
                showItems(seriesName, items) { item ->
                    val id = item.data.optString("id")
                    val ext = item.data.optString("container_extension", "mkv")
                    share("vod", acc.episodeUrl(id, ext), "$seriesName ${item.label}")
                }
            }
        }
    }

    private fun array(body: String): List<JSONObject> {
        val arr = try { JSONArray(body) } catch (_: Exception) { return emptyList() }
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    private fun fetch(url: String, onOk: (String) -> Unit) {
        toastShort("Cargando…")
        thread {
            try {
                val body = http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    response.body?.string().orEmpty()
                }
                runOnUiThread { if (!isFinishing && !isDestroyed) onOk(body) }
            } catch (e: Exception) {
                runOnUiThread { toast("No se pudo cargar la lista: ${e.message ?: "sin detalles"}") }
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
            } catch (e: Exception) {
                runOnUiThread { toast("No se pudo compartir: ${e.message ?: "sin detalles"}") }
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
