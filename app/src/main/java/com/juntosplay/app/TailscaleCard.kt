package com.juntosplay.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
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
        if (!TailnetBridge.available) return
        val key = keyInput.text.toString()
        keyInput.visibility = View.GONE
        status.text = "Iniciando Tailscale…"
        thread {
            try {
                TailnetBridge.start(activity, key)
            } catch (e: Throwable) {
                activity.runOnUiThread { status.text = "No se pudo iniciar Tailscale: ${e.message}" }
            }
        }
    }

    private fun unlink() {
        thread {
            TailnetBridge.logout(activity)
            activity.runOnUiThread {
                qrFor = ""
                refresh()
            }
        }
    }

    private fun refresh() {
        if (!TailnetBridge.available) {
            status.text = "Esta versión de la app no trae Tailscale integrado. Puedes usar la app de Tailscale."
            linkButton.visibility = View.GONE
            unlinkButton.visibility = View.GONE
            return
        }
        thread {
            val s = TailnetBridge.status()
            activity.runOnUiThread { show(s) }
        }
    }

    private fun show(s: TailnetBridge.Status) {
        when {
            s.state == "Stopped" -> {
                status.text = "Vincula este equipo una sola vez y queda guardado. No hace falta la app de Tailscale. " +
                    "Al pulsar «Vincular» aparece un código QR: escanéalo con tu teléfono (con tu cuenta de Tailscale) y aprueba."
                hideQr()
                keyInput.visibility = View.VISIBLE
                linkButton.visibility = View.VISIBLE
                unlinkButton.visibility = View.GONE
            }
            s.state == "Running" && s.ip.isNotBlank() -> {
                val who = listOf(s.name, s.ip).filter { it.isNotBlank() }.joinToString(" · ")
                status.text = "✓ Vinculado: $who" + if (s.tailnet.isNotBlank()) "\nRed: ${s.tailnet}" else ""
                hideQr()
                keyInput.visibility = View.GONE
                linkButton.visibility = View.GONE
                unlinkButton.visibility = View.VISIBLE
            }
            s.authUrl.isNotBlank() -> {
                status.text = "Escanea el código con tu teléfono y aprueba este equipo en tu cuenta de Tailscale. " +
                    "Si estás lejos, manda una foto del código o el enlace a quien tenga la cuenta."
                showQr(s.authUrl)
                linkButton.visibility = View.GONE
                unlinkButton.visibility = View.VISIBLE
            }
            else -> {
                status.text = "Conectando con Tailscale…"
                linkButton.visibility = View.GONE
                unlinkButton.visibility = View.VISIBLE
            }
        }
    }

    private fun hideQr() {
        qr.visibility = View.GONE
        link.visibility = View.GONE
    }

    private fun showQr(url: String) {
        link.text = url
        link.visibility = View.VISIBLE
        qr.visibility = View.VISIBLE
        if (url == qrFor) return
        qrFor = url
        qr.setImageBitmap(qrBitmap(url, 512))
    }

    private fun qrBitmap(text: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2))
        val pixels = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            pixels[y * size + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
    }
}
