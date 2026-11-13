package com.juntosplay.app

import android.app.Activity
import android.os.Bundle
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
    private val bg = Color.rgb(16, 17, 26)
    private val card = Color.rgb(31, 33, 47)
    private val accent = Color.rgb(139, 124, 255)
    private val white = Color.WHITE
    private val muted = Color.rgb(183, 185, 202)

    private lateinit var page: LinearLayout
    private lateinit var serverInput: EditText
    private lateinit var statusText: TextView
    private lateinit var roomText: TextView
    private var roomClient: RoomClient? = null

    // Replace with your deployed secure WebSocket address when available.
    // Example: wss://api.your-domain.com
    private val defaultServer = "wss://YOUR-SERVER-DOMAIN"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showHome()
    }

    private fun showHome() {
        val scroll = ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true }
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 36)
        }
        scroll.addView(page)
        setContentView(scroll)

        text("JuntosPlay", 32f, true, white)
        text("Conexión remota entre televisores por Internet", 16f, false, muted)
        space(22)

        text("Servidor de Internet", 20f, true, accent)
        serverInput = EditText(this).apply {
            setText(defaultServer)
            hint = "wss://tu-servidor.com"
            setHintTextColor(muted)
            setTextColor(white)
            textSize = 16f
            setSingleLine(true)
            setPadding(14, 12, 14, 12)
            background = rounded(card, 12)
            isFocusable = true
        }
        page.addView(serverInput, lp())
        button("Conectar por Internet") { connectServer() }
        statusText = text("Estado: desconectado", 14f, false, muted)
        space(18)

        text("Sesión compartida", 20f, true, accent)
        roomText = text("Código de sala: todavía no creado", 16f, true, white)
        button("Crear sala y generar código") {
            if (roomClient == null) toast("Primero conecta con el servidor.")
            else roomClient?.createRoom()
        }
        val codeInput = EditText(this).apply {
            hint = "Introduce el código que te enviaron"
            setHintTextColor(muted)
            setTextColor(white)
            textSize = 16f
            setSingleLine(true)
            setPadding(14, 12, 14, 12)
            background = rounded(card, 12)
        }
        page.addView(codeInput, lp())
        button("Unirme a la sala") {
            val code = codeInput.text.toString().trim()
            if (code.isBlank()) toast("Escribe el código de sala.")
            else roomClient?.joinRoom(code) ?: toast("Primero conecta con el servidor.")
        }

        space(18)
        text("Prueba de sincronización", 20f, true, accent)
        text("Estos botones envían eventos de control a los dispositivos de la misma sala. Todavía no controlan reproductores externos.", 14f, false, muted)
        button("Enviar PAUSA") { roomClient?.sendPlayback("pause") ?: toast("Conecta y entra en una sala.") }
        button("Enviar REPRODUCIR") { roomClient?.sendPlayback("play") ?: toast("Conecta y entra en una sala.") }
        button("Enviar AVANCE de 30 segundos") { roomClient?.sendPlayback("seek", 30_000L) ?: toast("Conecta y entra en una sala.") }

        space(20)
        text("Importante: para que Estados Unidos y República Dominicana se conecten, este servidor debe estar desplegado en Internet con WSS. La dirección de ejemplo todavía debe sustituirse por la dirección real.", 14f, false, muted)
    }

    private fun connectServer() {
        val url = serverInput.text.toString().trim()
        if (url.contains("YOUR-SERVER-DOMAIN")) {
            toast("Primero hay que desplegar el servidor y colocar su dirección WSS.")
            return
        }
        roomClient?.close()
        roomClient = RoomClient(
            onMessage = { msg -> runOnUiThread { handleServerMessage(msg) } },
            onStatus = { status -> runOnUiThread { statusText.text = "Estado: $status" } }
        )
        roomClient?.connect(url)
    }

    private fun handleServerMessage(msg: JSONObject) {
        when (msg.optString("type")) {
            "room_created" -> {
                val code = msg.optString("code")
                roomText.text = "Código de sala: $code"
                toast("Sala creada. Comparte este código con tu pareja.")
            }
            "room_joined" -> {
                roomText.text = "Conectado a sala: ${msg.optString("code")}"
                toast("Te uniste a la sala.")
            }
            "peer_joined" -> toast("Tu pareja se conectó a la sala.")
            "playback" -> toast("Evento recibido: ${msg.optString("action")}")
            "error" -> toast(msg.optString("message", "Error del servidor"))
        }
    }

    private fun text(value: String, size: Float, bold: Boolean, color: Int): TextView {
        val t = TextView(this).apply {
            text = value; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 5, 0, 8)
        }
        page.addView(t, lp())
        return t
    }

    private fun button(value: String, action: () -> Unit) {
        val b = Button(this).apply {
            text = value; isAllCaps = false; textSize = 16f
            setTextColor(white); background = rounded(accent, 14)
            isFocusable = true; isFocusableInTouchMode = true
            setOnClickListener { action() }
        }
        val params = lp(); params.bottomMargin = 9
        page.addView(b, params)
    }

    private fun space(dp: Int) { page.addView(View(this), LinearLayout.LayoutParams(1, dp)) }
    private fun lp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius.toFloat()
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        roomClient?.close()
        super.onDestroy()
    }
}
