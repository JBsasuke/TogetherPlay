package com.juntosplay.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * Recibe videos de otras apps (Kodi, Stremio, TiviMate, Nuvio, WUPlay, M3UFY...)
 * cuando eligen TogetherPlay como reproductor externo o en «Abrir con».
 * Prepara el relay, avisa al otro equipo y abre el reproductor.
 */
class ExternalPlayActivity : Activity() {
    private lateinit var message: TextView
    private lateinit var buttons: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { background = Ui.pageBackground() }
        val card = Ui.card(this)
        card.addView(Ui.label(this, "Ver juntos", 20f, bold = true), Ui.matchWrap(bottomMargin = Ui.dp(this, 8)))
        message = Ui.label(this, "Preparando el video…", 15f, Ui.muted)
        card.addView(message, Ui.matchWrap(bottomMargin = Ui.dp(this, 16)))
        buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = android.view.View.GONE }
        buttons.addView(Ui.button(this, "Reintentar") { start() }, Ui.matchWrap(bottomMargin = Ui.dp(this, 10)))
        buttons.addView(Ui.button(this, "Salir", Ui.Style.SECONDARY) { finish() }, Ui.matchWrap())
        card.addView(buttons, Ui.matchWrap())
        val width = minOf(Ui.dp(this, 480), resources.displayMetrics.widthPixels - Ui.dp(this, 32))
        root.addView(card, FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        Ui.applySystemBarsPadding(root)
        setContentView(root)
        start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        start()
    }

    private fun start() {
        val uri = intent?.data
        if (uri == null) {
            showError("La otra app no envió ningún video.")
            return
        }
        buttons.visibility = android.view.View.GONE
        message.text = "Preparando el video…"
        val title = titleFrom(intent, uri)
        val headers = headersFrom(intent)
        val startMs = positionFrom(intent)
        val inRoom = RoomSession.ensureConnected(this)
        val appContext = applicationContext
        val type = intent.type
        thread {
            try {
                val p = when (uri.scheme?.lowercase()) {
                    "http", "https" -> Sharing.prepareUrl(appContext, uri.toString(), live = null, title = title, headers = headers)
                    "content", "file" -> Sharing.prepareLocal(appContext, uri, type, title)
                    else -> throw IllegalArgumentException("este tipo de enlace (${uri.scheme}) no se puede compartir")
                }
                runOnUiThread {
                    val sent = Sharing.announce(p)
                    if (!inRoom || !sent) {
                        Toast.makeText(this, "Se reproduce solo aquí: entra en una sala en TogetherPlay para verlo juntos.", Toast.LENGTH_LONG).show()
                    }
                    startActivity(PlayerActivity.intent(this, p.localUrl, p.kind, title, host = true, startMs = startMs))
                    finish()
                }
            } catch (e: Throwable) {
                runOnUiThread { showError("No se pudo preparar el video: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    private fun showError(text: String) {
        message.text = text
        buttons.visibility = android.view.View.VISIBLE
        buttons.getChildAt(0)?.requestFocus()
    }

    /** Cada app manda el título con un nombre distinto. */
    private fun titleFrom(intent: Intent, uri: Uri): String {
        for (key in listOf("title", Intent.EXTRA_TITLE, "android.intent.extra.title", "name", "filename")) {
            intent.getStringExtra(key)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return uri.lastPathSegment?.substringBeforeLast('.')?.take(80) ?: "Video"
    }

    /** Cabeceras HTTP al estilo MX Player/VLC: "headers" = [clave, valor, clave, valor...]. */
    private fun headersFrom(intent: Intent): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        intent.getStringArrayExtra("headers")?.let { arr ->
            var i = 0
            while (i + 1 < arr.size) { out[arr[i]] = arr[i + 1]; i += 2 }
        }
        val bundle = if (Build.VERSION.SDK_INT >= 33) intent.getBundleExtra("android.media.intent.extra.HTTP_HEADERS")
        else @Suppress("DEPRECATION") intent.getBundleExtra("android.media.intent.extra.HTTP_HEADERS")
        bundle?.keySet()?.forEach { k -> bundle.getString(k)?.let { out[k] = it } }
        intent.getStringExtra("User-Agent")?.let { out["User-Agent"] = it }
        intent.getStringExtra("Referer")?.let { out["Referer"] = it }
        return out
    }

    private fun positionFrom(intent: Intent): Long {
        val extras = intent.extras ?: return 0L
        @Suppress("DEPRECATION")
        val v = extras.get("position") ?: extras.get("from_start_position") ?: return 0L
        return when (v) {
            is Int -> v.toLong()
            is Long -> v
            else -> 0L
        }.coerceAtLeast(0L)
    }
}
