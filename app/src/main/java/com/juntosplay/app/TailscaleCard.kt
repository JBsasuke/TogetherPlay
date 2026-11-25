package com.juntosplay.app

import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.juntosplay.app.relay.StreamRelay
import kotlin.concurrent.thread

/**
 * Sección "Tailscale integrado": vincular este equipo con un código QR (se escanea con
 * el teléfono que tiene la cuenta de Tailscale), ver el estado y desvincular.
 */
class TailscaleCard(private val activity: Activity, wide: Boolean) {
    val view: LinearLayout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val status: TextView = Ui.label(activity, "", 14f, Ui.muted)
    private val qr = ImageView(activity).apply { visibility = View.GONE; setBackgroundColor(Color.WHITE) }
    private val link: TextView = Ui.label(activity, "", 13f, Ui.hint).apply { visibility = View.GONE }
    private val keyInput = Ui.input(activity, "Clave de autenticación (opcional)").apply { visibility = View.GONE }
    private val linkButton: TextView
    private val unlinkButton: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var qrFor = ""
    private var polling = false
    private var pairingRequested = false
    private var operationInProgress = false
    private var refreshInFlight = false
    private var operationGeneration = 0
    private var startupError: String? = null
    private val poll = object : Runnable {
        override fun run() {
            refresh()
            if (polling) handler.postDelayed(this, 2000)
        }
    }

    init {
        val c = activity
        view.addView(Ui.label(c, "Tailscale integrado", 16f, bold = true), Ui.matchWrap(bottomMargin = Ui.dp(c, 6)))
        view.addView(status, Ui.matchWrap(bottomMargin = Ui.dp(c, 10)))
        val qrSize = Ui.dp(c, if (wide) 200 else 220)
        view.addView(qr, LinearLayout.LayoutParams(qrSize, qrSize).apply { bottomMargin = Ui.dp(c, 8) })
        view.addView(link, Ui.matchWrap(bottomMargin = Ui.dp(c, 10)))
        view.addView(keyInput, Ui.matchWrap(bottomMargin = Ui.dp(c, 10)))
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        linkButton = Ui.button(c, "Vincular este equipo", Ui.Style.SECONDARY) { link() }
        unlinkButton = Ui.button(c, "Desvincular", Ui.Style.GHOST) { unlink() }
        linkButton.visibility = View.GONE
        unlinkButton.visibility = View.GONE
        row.addView(linkButton, LinearLayout.LayoutParams(wrap, wrap).apply { rightMargin = Ui.dp(c, 12) })
        row.addView(unlinkButton, LinearLayout.LayoutParams(wrap, wrap))
        view.addView(row, Ui.matchWrap())
        refresh()
    }

    fun onResume() {
        polling = true
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    fun onPause() {
        polling = false
        handler.removeCallbacks(poll)
    }

    private fun link() {
        if (!TailnetBridge.available || operationInProgress) return
        val key = keyInput.text.toString()
        pairingRequested = true
        status.text = "Iniciando Tailscale…"
        runOperation { TailnetBridge.start(activity, key) }
    }

    private fun unlink() {
        if (operationInProgress) return
        pairingRequested = false
        status.text = "Desvinculando Tailscale…"
        runOperation { TailnetBridge.logout(activity) }
    }

    private fun runOperation(action: () -> Unit) {
        operationGeneration++
        operationInProgress = true
        startupError = null
        hideQr()
        keyInput.visibility = View.GONE
        linkButton.visibility = View.GONE
        unlinkButton.visibility = View.GONE
        thread {
            val failure = runCatching(action).exceptionOrNull()
            activity.runOnUiThread {
                operationInProgress = false
                if (failure != null) {
                    pairingRequested = false
                    startupError = "No se pudo completar la operación de Tailscale."
                }
                if (!activity.isDestroyed && !activity.isFinishing) refresh()
            }
        }
    }

    private fun refresh() {
        if (operationInProgress || refreshInFlight) return
        refreshInFlight = true
        val generation = operationGeneration
        thread {
            val externalIp = runCatching { StreamRelay.tailscaleAddress() }.getOrNull()
            val s = TailnetBridge.status()
            activity.runOnUiThread {
                refreshInFlight = false
                if (activity.isDestroyed || activity.isFinishing) return@runOnUiThread
                if (generation == operationGeneration) show(s, externalIp)
                else refresh()
            }
        }
    }

    private fun show(s: TailnetBridge.Status, externalIp: String?) {
        hideQr()
        // Lo que debe verse se decide primero y se aplica al final, solo si cambió. Antes, cada 2 segundos, los
        // botones se escondían y volvían a salir: si el cursor estaba en «Vincular este equipo», lo perdía y saltaba
        // solo a otra parte de la pantalla.
        var keyWanted = View.GONE
        var linkWanted = View.GONE
        var unlinkWanted = View.GONE
        val enabled = RoomSession.prefs(activity).getBoolean(TailnetBridge.PREF_ENABLED, false)
        when {
            s.state == "Running" -> {
                pairingRequested = false
                startupError = null
                val who = listOf(s.name, s.ip).filter { it.isNotBlank() }.joinToString(" · ")
                status.text = "✓ Vinculado" + if (who.isBlank()) "" else ": $who"
                if (s.tailnet.isNotBlank()) status.append("\nRed: " + s.tailnet)
                unlinkWanted = View.VISIBLE
            }
            externalIp != null -> {
                pairingRequested = false
                status.text = "✓ Conectado mediante la app de Tailscale · $externalIp"
            }
            !TailnetBridge.available -> {
                status.text = "Esta versión no incluye Tailscale integrado. Puedes usar la app de Tailscale."
            }
            s.state == "NeedsLogin" && pairingRequested && s.authUrl.isNotBlank() -> {
                status.text = "Escanea el código con tu teléfono y aprueba este equipo en tu cuenta de Tailscale."
                showQr(s.authUrl)
                unlinkWanted = View.VISIBLE
            }
            s.state == "NeedsLogin" -> {
                status.text = if (pairingRequested) "Solicitando enlace de Tailscale…"
                    else "Tailscale solicita iniciar sesión. Pulsa «Vincular este equipo» para continuar."
                linkWanted = if (pairingRequested) View.GONE else View.VISIBLE
                unlinkWanted = if (enabled) View.VISIBLE else View.GONE
            }
            s.state == "NeedsMachineAuth" -> {
                pairingRequested = false
                status.text = "Tailscale está esperando la aprobación de este equipo en tu cuenta."
                unlinkWanted = View.VISIBLE
            }
            s.state == "Stopped" && !enabled -> {
                status.text = startupError ?: "Pulsa «Vincular este equipo» para iniciar sesión en Tailscale."
                keyWanted = View.VISIBLE
                linkWanted = View.VISIBLE
            }
            else -> {
                status.text = startupError ?: "Conectando con Tailscale…"
                unlinkWanted = if (enabled) View.VISIBLE else View.GONE
            }
        }
        if (keyInput.visibility != keyWanted) keyInput.visibility = keyWanted
        if (linkButton.visibility != linkWanted) linkButton.visibility = linkWanted
        if (unlinkButton.visibility != unlinkWanted) unlinkButton.visibility = unlinkWanted
    }

    private fun hideQr() {
        qr.visibility = View.GONE
        link.visibility = View.GONE
        link.text = ""
    }

    private fun showQr(url: String) {
        link.text = url
        link.visibility = View.VISIBLE
        qr.visibility = View.VISIBLE
        if (url == qrFor) return
        qrFor = url
        qr.setImageBitmap(Ui.qrBitmap(url, 512))
    }
}
