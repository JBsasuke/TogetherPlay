package com.juntosplay.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("togetherplay_settings", Context.MODE_PRIVATE) }

    private lateinit var serverInput: EditText
    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var roomText: TextView
    private lateinit var codeInput: EditText
    private var roomClient: RoomClient? = null
    private var restoringRoom = false

    private val defaultServer = "wss://togetherplay.onrender.com"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showHome()
        val savedServer = prefs.getString("server_url", defaultServer) ?: defaultServer
        serverInput.setText(savedServer)
        val savedRoom = prefs.getString("room_code", "") ?: ""
        if (savedRoom.isNotBlank()) {
            roomText.text = savedRoom
            codeInput.setText(savedRoom)
            restoringRoom = true
            connectServer()
        }
    }

    private fun dp(value: Int) = Ui.dp(this, value)

    private fun showHome() {
        val wide = resources.configuration.screenWidthDp >= 700
        val scroll = ScrollView(this).apply { background = Ui.pageBackground(); isFillViewport = true }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val side = dp(if (wide) 40 else 18)
            setPadding(side, dp(22), side, dp(30))
        }
        scroll.addView(page)
        setContentView(scroll)

        // Cabecera: logo, nombre y estado de la conexión.
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply { setImageResource(R.drawable.logo) }, LinearLayout.LayoutParams(dp(52), dp(52)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), 0) }
        titles.addView(Ui.label(this, "TogetherPlay", 22f, bold = true))
        titles.addView(Ui.label(this, "Mira · Juega · Disfruta juntos", 13f, Ui.muted))
        header.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        statusDot = View(this).apply { background = Ui.oval(Ui.bad) }
        statusText = Ui.label(this, "Desconectado", 14f)
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.round(Ui.surface, dp(100).toFloat(), dp(1), Ui.outline)
            setPadding(dp(14), dp(8), dp(16), dp(8))
        }
        pill.addView(statusDot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(8) })
        pill.addView(statusText)
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
        if (wide) {
            header.addView(pill, LinearLayout.LayoutParams(wrap, wrap))
            page.addView(header, Ui.matchWrap(bottomMargin = dp(24)))
        } else {
            page.addView(header, Ui.matchWrap(bottomMargin = dp(14)))
            page.addView(pill, LinearLayout.LayoutParams(wrap, wrap).apply { bottomMargin = dp(18) })
        }

        // Servidor y sala, lado a lado en televisores.
        val serverCard = Ui.card(this)
        serverCard.addView(Ui.sectionTitle(this, "Servidor"))
        serverCard.addView(Ui.label(this, "Dirección del servidor de salas", 14f, Ui.muted), Ui.matchWrap(bottomMargin = dp(10)))
        serverInput = Ui.input(this, "wss://tu-servidor.com")
        serverCard.addView(serverInput, Ui.matchWrap(bottomMargin = dp(14)))
        val connectButton = Ui.button(this, "Guardar y conectar") { connectServer() }
        serverCard.addView(connectButton, Ui.matchWrap())

        val roomCard = Ui.card(this)
        roomCard.addView(Ui.sectionTitle(this, "Sala"))
        roomText = Ui.label(this, "Sin sala", 21f, bold = true).apply { letterSpacing = 0.08f }
        roomCard.addView(roomText, Ui.matchWrap(bottomMargin = dp(14)))
        roomCard.addView(Ui.button(this, "Crear sala nueva") {
            if (roomClient == null) toast("Conecta primero con el servidor.")
            else roomClient?.createRoom()
        }, Ui.matchWrap(bottomMargin = dp(12)))
        val joinRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        codeInput = Ui.input(this, "Código de sala")
        joinRow.addView(codeInput, LinearLayout.LayoutParams(0, wrap, 1f).apply { rightMargin = dp(12) })
        joinRow.addView(Ui.button(this, "Unirme", Ui.Style.SECONDARY) {
            val code = codeInput.text.toString().trim().uppercase()
            if (code.isBlank()) toast("Escribe el código de sala.")
            else {
                prefs.edit().putString("room_code", code).apply()
                roomText.text = code
                roomClient?.joinRoom(code) ?: toast("Conecta primero con el servidor.")
            }
        }, LinearLayout.LayoutParams(wrap, wrap))
        roomCard.addView(joinRow, Ui.matchWrap(bottomMargin = dp(6)))
        roomCard.addView(Ui.button(this, "Olvidar sala guardada", Ui.Style.GHOST) {
            prefs.edit().remove("room_code").apply()
            roomText.text = "Sin sala"
            codeInput.setText("")
            toast("Se eliminó el código guardado de este televisor.")
        }, Ui.matchWrap())

        val row = LinearLayout(this).apply { orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        if (wide) {
            row.addView(serverCard, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { rightMargin = dp(20) })
            row.addView(roomCard, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        } else {
            row.addView(serverCard, Ui.matchWrap(bottomMargin = dp(16)))
            row.addView(roomCard, Ui.matchWrap())
        }
        page.addView(row, Ui.matchWrap(bottomMargin = dp(20)))

        // Ver juntos.
        val watchCard = Ui.card(this)
        watchCard.addView(Ui.sectionTitle(this, "Ver juntos"))
        watchCard.addView(Ui.label(this, "Comparte tu IPTV", 18f, bold = true), Ui.matchWrap(bottomMargin = dp(6)))
        watchCard.addView(
            Ui.label(this, "Elige un canal, película o episodio. Se abre aquí y en el otro televisor con una sola conexión al proveedor.", 14f, Ui.muted),
            Ui.matchWrap(bottomMargin = dp(18))
        )
        watchCard.addView(Ui.label(this, "Red para el video", 14f, Ui.muted), Ui.matchWrap(bottomMargin = dp(8)))
        val modeHelp = Ui.label(this, "", 13f, Ui.hint)
        fun describeMode(mode: String) = when (mode) {
            NetworkMode.WIFI -> "Los dos equipos en el mismo Wi-Fi o cable. Ideal para probar en casa."
            NetworkMode.TAILSCALE -> "Entre casas distintas. Los dos equipos con Tailscale conectado."
            else -> "Usa Tailscale si está conectado; si no, el Wi-Fi de la casa."
        }
        val savedMode = prefs.getString(NetworkMode.PREF, NetworkMode.AUTO) ?: NetworkMode.AUTO
        modeHelp.text = describeMode(savedMode)
        watchCard.addView(
            Ui.chipGroup(
                this,
                listOf(NetworkMode.AUTO to "Automática", NetworkMode.WIFI to "Wi-Fi local", NetworkMode.TAILSCALE to "Tailscale"),
                savedMode
            ) { mode ->
                prefs.edit().putString(NetworkMode.PREF, mode).apply()
                modeHelp.text = describeMode(mode)
            },
            if (wide) LinearLayout.LayoutParams(Ui.dp(this, 560), wrap).apply { bottomMargin = dp(8) } else Ui.matchWrap(bottomMargin = dp(8))
        )
        watchCard.addView(modeHelp, Ui.matchWrap(bottomMargin = dp(18)))
        val shareButton = Ui.button(this, "Compartir desde mi IPTV") {
            if (roomClient == null) toast("Conecta primero con el servidor y entra en una sala.")
            else startActivity(Intent(this, ShareActivity::class.java))
        }
        watchCard.addView(shareButton, if (wide) LinearLayout.LayoutParams(wrap, wrap) else Ui.matchWrap())
        page.addView(watchCard, Ui.matchWrap(bottomMargin = dp(18)))

        page.addView(Ui.label(this, "La aplicación recuerda el servidor y la sala en este televisor. Si el servidor pierde la sala tras reiniciarse, habrá que crear o vincular otra.", 13f, Ui.hint))

        // En televisores el foco empieza en un botón, no en un campo de texto.
        connectButton.post { connectButton.requestFocus() }
    }

    private fun setStatus(status: String) {
        statusText.text = status.replaceFirstChar { it.uppercase() }
        val t = status.lowercase()
        val color = when {
            t.contains("reconectando") || t.contains("recuperando") -> Ui.warn
            t.contains("error") || t.contains("no se pudo") || t.contains("cerrada") ||
                t.contains("desconectado") || t.contains("no hay") || t.contains("debe") -> Ui.bad
            t.contains("conectado") || t.contains("vinculada") -> Ui.ok
            else -> Ui.warn
        }
        statusDot.background = Ui.oval(color)
    }

    private fun connectServer() {
        val url = serverInput.text.toString().trim()
        if (!url.startsWith("wss://") && !url.startsWith("ws://")) {
            toast("La dirección debe comenzar con wss:// o ws://")
            return
        }
        prefs.edit().putString("server_url", url).apply()
        roomClient?.close()
        roomClient = RoomClient(
            onMessage = { msg -> runOnUiThread { handleServerMessage(msg) } },
            onStatus = { status -> runOnUiThread { setStatus("$status") } },
            onConnected = {
                runOnUiThread {
                    setStatus("conectado al servidor")
                    val savedRoom = prefs.getString("room_code", "") ?: ""
                    if (savedRoom.isNotBlank()) {
                        roomClient?.joinRoom(savedRoom, createIfMissing = true)
                        setStatus("recuperando sala $savedRoom…")
                    }
                }
            }
        )
        RoomSession.client = roomClient
        roomClient?.connect(url)
    }

    private fun handleServerMessage(msg: JSONObject) {
        when (msg.optString("type")) {
            "room_created" -> {
                val code = msg.optString("code")
                prefs.edit().putString("room_code", code).apply()
                codeInput.setText(code)
                roomText.text = code
                toast("Sala creada y guardada en este televisor.")
            }
            "room_joined" -> {
                val code = msg.optString("code")
                prefs.edit().putString("room_code", code).apply()
                roomText.text = code
                setStatus("sala vinculada")
                toast("Te uniste a la sala.")
            }
            "peer_joined" -> {
                toast("El otro televisor se conectó.")
                // Si ya se está compartiendo algo, el que entra tarde lo recibe ahora.
                RoomSession.currentShare?.let { roomClient?.sendRelayShare(it.url, it.kind, it.title) }
            }
            "peer_left" -> toast("El otro televisor se desconectó.")
            "relay_share" -> {
                val url = msg.optString("url")
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    startActivity(
                        PlayerActivity.intent(this, url, msg.optString("kind", "vod"), msg.optString("title"), host = false)
                    )
                }
            }
            "playback", "relay_stop" -> RoomSession.playbackListener?.invoke(msg)
            "error" -> {
                val message = msg.optString("message", "Error del servidor")
                if (restoringRoom) {
                    restoringRoom = false
                    setStatus("no se pudo recuperar la sala")
                }
                toast(message)
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        if (RoomSession.client === roomClient) RoomSession.client = null
        Thread { RoomSession.stopRelay() }.start()
        roomClient?.close()
        super.onDestroy()
    }
}
