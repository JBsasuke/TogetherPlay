package com.juntosplay.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
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
    private val prefs by lazy { RoomSession.prefs(this) }

    private lateinit var serverInput: EditText
    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var roomText: TextView
    private lateinit var codeInput: EditText
    private lateinit var screenButton: TextView
    private var restoringRoom = false
    private val roomClient get() = RoomSession.client

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showHome()
        val savedServer = prefs.getString("server_url", RoomSession.DEFAULT_SERVER) ?: RoomSession.DEFAULT_SERVER
        serverInput.setText(savedServer)
        val savedRoom = prefs.getString("room_code", "") ?: ""
        if (savedRoom.isNotBlank()) {
            roomText.text = savedRoom
            codeInput.setText(savedRoom)
        }
        RoomSession.uiListener = { msg -> handleServerMessage(msg) }
        RoomSession.statusListener = { status -> setStatus(status) }
        if (RoomSession.client == null) {
            if (savedRoom.isNotBlank()) {
                restoringRoom = true
                connectServer()
            }
        } else {
            setStatus(RoomSession.lastStatus)
        }
    }

    override fun onResume() {
        super.onResume()
        RoomSession.uiListener = { msg -> handleServerMessage(msg) }
        RoomSession.statusListener = { status -> setStatus(status) }
        updateScreenButton()
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

        // Otras apps y pantalla.
        val appsCard = Ui.card(this)
        appsCard.addView(Ui.sectionTitle(this, "Desde otras apps"))
        appsCard.addView(Ui.label(this, "Kodi, Stremio, TiviMate, Nuvio, WUPlay, M3UFY…", 18f, bold = true), Ui.matchWrap(bottomMargin = dp(6)))
        appsCard.addView(
            Ui.label(this, "En esas apps elige TogetherPlay como reproductor externo (o en «Abrir con»). El video se abre aquí y en el otro equipo, igual que con tu IPTV.", 14f, Ui.muted),
            Ui.matchWrap(bottomMargin = dp(18))
        )
        appsCard.addView(Ui.label(this, "Compartir pantalla", 16f, bold = true), Ui.matchWrap(bottomMargin = dp(6)))
        appsCard.addView(
            Ui.label(this, "Para lo que no se pueda mandar como video: el otro equipo ve tu pantalla en directo, con sonido si la app lo permite.", 14f, Ui.muted),
            Ui.matchWrap(bottomMargin = dp(14))
        )
        screenButton = Ui.button(this, "Compartir mi pantalla", Ui.Style.SECONDARY) { toggleScreenShare() }
        appsCard.addView(screenButton, if (wide) LinearLayout.LayoutParams(wrap, wrap) else Ui.matchWrap())
        page.addView(appsCard, Ui.matchWrap(bottomMargin = dp(18)))

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
        setStatus("Conectando…")
        RoomSession.connect(this, url)
    }

    private fun handleServerMessage(msg: JSONObject) {
        when (msg.optString("type")) {
            "room_created" -> {
                val code = msg.optString("code")
                codeInput.setText(code)
                roomText.text = code
                toast("Sala creada y guardada en este televisor.")
            }
            "room_joined" -> {
                val code = msg.optString("code")
                roomText.text = code
                if (restoringRoom) restoringRoom = false else toast("Te uniste a la sala.")
            }
            "peer_joined" -> toast("El otro equipo se conectó.")
            "peer_left" -> toast("El otro equipo se desconectó.")
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

    // --- compartir pantalla ---

    private fun updateScreenButton() {
        if (!::screenButton.isInitialized) return
        screenButton.text = if (RoomSession.screenHosting) "Dejar de compartir pantalla" else "Compartir mi pantalla"
    }

    private fun toggleScreenShare() {
        if (RoomSession.screenHosting) {
            startService(Intent(this, ScreenShareService::class.java).setAction(ScreenShareService.ACTION_STOP))
            RoomSession.screenHosting = false
            updateScreenButton()
            return
        }
        if (roomClient == null || prefs.getString("room_code", "").isNullOrBlank()) {
            toast("Conecta con el servidor y entra en una sala primero.")
            return
        }
        // El permiso de micrófono es el que Android exige para capturar el sonido de otras apps.
        if (Build.VERSION.SDK_INT >= 29 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
            return
        }
        askCapture()
    }

    private fun askCapture() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
        } catch (_: Exception) {
            toast("Este equipo no permite capturar la pantalla.")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_AUDIO) return
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            toast("Sin ese permiso se comparte solo la imagen, sin sonido.")
        }
        askCapture()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            toast("No se autorizó compartir la pantalla.")
            return
        }
        val service = Intent(this, ScreenShareService::class.java)
            .putExtra(ScreenShareService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(ScreenShareService.EXTRA_RESULT_DATA, data)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
        RoomSession.screenHosting = true
        updateScreenButton()
        toast("Compartiendo tu pantalla. Ya puedes abrir la app que quieras mostrar.")
    }

    override fun onDestroy() {
        // La conexión con la sala sigue viva: otras apps pueden mandar videos a TogetherPlay.
        if (RoomSession.uiListener != null) RoomSession.uiListener = null
        RoomSession.statusListener = null
        super.onDestroy()
    }

    private companion object {
        const val REQUEST_CAPTURE = 7401
        const val REQUEST_AUDIO = 7402
    }
}
