package com.juntosplay.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
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
    private val bg = Color.rgb(12, 15, 29)
    private val accent = Color.rgb(139, 124, 255)
    private val muted = Color.rgb(190, 198, 218)
    private val prefs by lazy { getSharedPreferences("togetherplay_settings", Context.MODE_PRIVATE) }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
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

    private fun page(title: String): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(30, 26, 30, 26)
        }
        root.addView(TextView(this).apply {
            text = title; textSize = 26f; setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, 16)
        })
        setContentView(root)
        return root
    }

    private fun showAccountForm() {
        val root = page("Cuenta IPTV (Xtream Codes)")
        fun field(hint: String, value: String, password: Boolean = false) = EditText(this).apply {
            this.hint = hint; setText(value)
            setHintTextColor(muted); setTextColor(Color.WHITE); textSize = 17f; setSingleLine(true)
            if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            root.addView(this, lp())
        }
        val server = field("Servidor, p. ej. http://proveedor.com:8080", prefs.getString("xtream_server", "").orEmpty())
        val user = field("Usuario", prefs.getString("xtream_user", "").orEmpty())
        val pass = field("Contraseña", prefs.getString("xtream_pass", "").orEmpty(), password = true)
        root.addView(Button(this).apply {
            text = "Guardar"; isAllCaps = false; textSize = 17f
            setOnClickListener {
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
                        .apply()
                    backStack.clear()
                    current = null
                    go { showMenu() }
                }
            }
        }, lp())
        root.addView(TextView(this).apply {
            text = "Los datos se guardan solo en este televisor. El otro lado nunca los recibe."
            textSize = 14f; setTextColor(muted); setPadding(0, 14, 0, 0)
        })
    }

    private fun showMenu() {
        current = current ?: { showMenu() }
        val options = listOf("Canales en vivo", "Películas", "Series", "Cambiar cuenta IPTV")
        showList("Compartir desde mi IPTV", options) { index ->
            when (index) {
                0 -> browseCategories("live")
                1 -> browseCategories("vod")
                2 -> browseCategories("series")
                else -> go { showAccountForm() }
            }
        }
    }

    private fun showList(title: String, labels: List<String>, onPick: (Int) -> Unit) {
        val root = page(title)
        if (labels.isEmpty()) {
            root.addView(TextView(this).apply { text = "No hay nada en esta lista."; textSize = 17f; setTextColor(muted) })
            return
        }
        val list = ListView(this).apply {
            adapter = ArrayAdapter(this@ShareActivity, android.R.layout.simple_list_item_1, labels)
            setOnItemClickListener { _, _, position, _ -> onPick(position) }
            setSelector(android.R.drawable.list_selector_background)
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
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
        val client = RoomSession.client
        if (client == null) { toast("Conecta primero con el servidor y entra en una sala."); return }
        val appContext = applicationContext
        thread {
            try {
                val host = StreamRelay.tailscaleAddress()
                    ?: throw IOException("no encuentro Tailscale; ábrelo y conéctalo en este televisor")
                val relay = RoomSession.relay(appContext)
                relay.start()
                if (kind == "live") relay.shareLive(upstreamUrl) else relay.shareVod(upstreamUrl)
                val shared = RoomSession.Share(relay.urlFor(host), kind, title)
                val localUrl = relay.urlFor("127.0.0.1")
                runOnUiThread {
                    RoomSession.currentShare = shared
                    client.sendRelayShare(shared.url, kind, title)
                    startActivity(PlayerActivity.intent(this, localUrl, kind, title, host = true))
                }
            } catch (e: Exception) {
                runOnUiThread { toast("No se pudo compartir: ${e.message ?: "sin detalles"}") }
            }
        }
    }

    private fun lp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun toastShort(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        http.dispatcher.executorService.shutdown()
        super.onDestroy()
    }
}
