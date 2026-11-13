package com.juntosplay.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

class MainActivity : Activity() {
    private val bg = Color.rgb(12, 15, 29)
    private val card = Color.rgb(29, 36, 58)
    private val accent = Color.rgb(139, 124, 255)
    private val focusColor = Color.rgb(0, 229, 255)
    private val white = Color.WHITE
    private val muted = Color.rgb(190, 198, 218)
    private val prefs by lazy { getSharedPreferences("togetherplay_settings", Context.MODE_PRIVATE) }

    private lateinit var page: LinearLayout
    private lateinit var serverInput: EditText
    private lateinit var statusText: TextView
    private lateinit var roomText: TextView
    private lateinit var codeInput: EditText
    private var roomClient: RoomClient? = null
    private var restoringRoom = false
    private val captureRequestCode = 7401

    private val defaultServer = "wss://togetherplay.onrender.com"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showHome()
        val savedServer = prefs.getString("server_url", defaultServer) ?: defaultServer
        serverInput.setText(savedServer)
        val savedRoom = prefs.getString("room_code", "") ?: ""
        if (savedRoom.isNotBlank()) {
            roomText.text = "Sala guardada: $savedRoom"
            restoringRoom = true
            connectServer()
        }
    }

    private fun showHome() {
        val scroll = ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true }
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 26, 30, 38)
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        scroll.addView(page)
        setContentView(scroll)

        text("TogetherPlay", 32f, true, white)
        text("Conecta tus televisores por Internet", 17f, false, muted)
        space(22)
        text("Servidor", 20f, true, accent)
        serverInput = EditText(this).apply {
            hint = "wss://tu-servidor.com"
            setHintTextColor(muted); setTextColor(white); textSize = 17f
            setSingleLine(true); setPadding(16, 14, 16, 14)
            background = rounded(card, 12); isFocusable = true
        }
        page.addView(serverInput, lp())
        button("Guardar servidor y conectar") { connectServer() }
        statusText = text("Estado: desconectado", 15f, false, muted)
        space(18)

        text("Sala compartida", 20f, true, accent)
        roomText = text("Todavía no hay una sala vinculada", 17f, true, white)
        button("Crear sala") {
            if (roomClient == null) toast("Conecta primero con el servidor.")
            else roomClient?.createRoom()
        }
        codeInput = EditText(this).apply {
            hint = "Código de sala"
            setHintTextColor(muted); setTextColor(white); textSize = 17f
            setSingleLine(true); setPadding(16, 14, 16, 14)
            background = rounded(card, 12)
        }
        page.addView(codeInput, lp())
        button("Guardar sala y unirme") {
            val code = codeInput.text.toString().trim().uppercase()
            if (code.isBlank()) toast("Escribe el código de sala.")
            else {
                prefs.edit().putString("room_code", code).apply()
                roomText.text = "Sala guardada: $code"
                roomClient?.joinRoom(code) ?: toast("Conecta primero con el servidor.")
            }
        }
        button("Compartir pantalla") { requestScreenCapture() }
        button("Detener captura") { stopService(Intent(this, ScreenCaptureService::class.java)) }
        button("Olvidar sala guardada") {
            prefs.edit().remove("room_code").apply()
            roomText.text = "No hay una sala guardada"
            toast("Se eliminó el código guardado de este televisor.")
        }

        space(18)
        text("Controles de prueba", 20f, true, accent)
        text("Envía eventos a la otra pantalla. El control real de aplicaciones externas requiere compatibilidad de cada reproductor.", 14f, false, muted)
        button("PAUSA") { roomClient?.sendPlayback("pause") ?: toast("Conecta y entra en una sala.") }
        button("REPRODUCIR") { roomClient?.sendPlayback("play") ?: toast("Conecta y entra en una sala.") }
        button("AVANZAR 30 SEGUNDOS") { roomClient?.sendPlayback("seek", 30_000L) ?: toast("Conecta y entra en una sala.") }
        space(16)
        text("La aplicación recuerda el servidor y la sala en este televisor. Si el servidor pierde la sala tras reiniciarse, habrá que crear o vincular otra.", 14f, false, muted)
    }


    private fun requestScreenCapture() {
        val code = prefs.getString("room_code", "") ?: ""
        if (code.isBlank()) {
            toast("Primero crea una sala o únete a una.")
            return
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), captureRequestCode)
    }

    @Deprecated("Android screen capture permission result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != captureRequestCode) return
        if (resultCode != RESULT_OK || data == null) {
            toast("No se autorizó compartir la pantalla.")
            return
        }
        val service = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
        }
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
        toast("Captura local iniciada. La transmisión al otro dispositivo aún está en integración.")
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
            onStatus = { status -> runOnUiThread { statusText.text = "Estado: $status" } },
            onConnected = {
                runOnUiThread {
                    statusText.text = "Estado: conectado al servidor"
                    val savedRoom = prefs.getString("room_code", "") ?: ""
                    if (savedRoom.isNotBlank()) {
                        roomClient?.joinRoom(savedRoom)
                        statusText.text = "Estado: recuperando sala $savedRoom…"
                    }
                }
            }
        )
        roomClient?.connect(url)
    }

    private fun handleServerMessage(msg: JSONObject) {
        when (msg.optString("type")) {
            "room_created" -> {
                val code = msg.optString("code")
                prefs.edit().putString("room_code", code).apply()
                codeInput.setText(code)
                roomText.text = "Sala guardada: $code"
                toast("Sala creada y guardada en este televisor.")
            }
            "room_joined" -> {
                val code = msg.optString("code")
                prefs.edit().putString("room_code", code).apply()
                roomText.text = "Conectado a sala: $code"
                statusText.text = "Estado: sala vinculada"
                toast("Te uniste a la sala.")
            }
            "peer_joined" -> toast("El otro televisor se conectó.")
            "peer_left" -> toast("El otro televisor se desconectó.")
            "playback" -> toast("Control recibido: ${msg.optString("action")}")
            "error" -> {
                val message = msg.optString("message", "Error del servidor")
                if (restoringRoom) {
                    restoringRoom = false
                    statusText.text = "Estado: no se pudo recuperar la sala"
                }
                toast(message)
            }
        }
    }

    private fun text(value: String, size: Float, bold: Boolean, color: Int): TextView {
        val t = TextView(this).apply {
            text = value; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 6, 0, 9)
        }
        page.addView(t, lp())
        return t
    }

    private fun button(value: String, action: () -> Unit) {
        val b = Button(this).apply {
            text = value; isAllCaps = false; textSize = 17f
            setTextColor(white); background = rounded(accent, 13)
            isFocusable = true; isFocusableInTouchMode = true
            setPadding(18, 12, 18, 12)
            setOnFocusChangeListener { view, hasFocus ->
                view.background = if (hasFocus) bordered(focusColor, 13, 4) else rounded(accent, 13)
                setTextColor(if (hasFocus) Color.rgb(8, 20, 34) else white)
                scaleX = if (hasFocus) 1.025f else 1f
                scaleY = if (hasFocus) 1.025f else 1f
            }
            setOnClickListener { action() }
        }
        val params = lp(); params.bottomMargin = 10
        page.addView(b, params)
    }

    private fun space(dp: Int) { page.addView(View(this), LinearLayout.LayoutParams(1, dp)) }
    private fun lp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius.toFloat()
    }
    private fun bordered(color: Int, radius: Int, stroke: Int) = GradientDrawable().apply {
        setColor(Color.WHITE); cornerRadius = radius.toFloat(); setStroke(stroke, color)
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        roomClient?.close()
        super.onDestroy()
    }
}
