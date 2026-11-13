package com.juntosplay.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Reproductor sincronizado. Abre la URL del relay (127.0.0.1 en el anfitrión,
 * la IP de Tailscale o de la red de casa en el otro televisor) y mantiene
 * play/pausa/seek a la par mediante los mensajes "playback" de la sala.
 */
class PlayerActivity : Activity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var errorBox: LinearLayout
    private lateinit var errorText: TextView
    private var isHost = false
    private var kind = "vod"
    private var url = ""
    /** Mientras se aplica una orden remota, los cambios locales no se reenvían. */
    private var ignoreLocalUntil = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            setBackgroundColor(Color.BLACK)
            keepScreenOn = true
        }
        root.addView(playerView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        errorText = Ui.label(this, "", 15f, Ui.muted)
        errorBox = Ui.card(this).apply {
            visibility = View.GONE
            addView(Ui.label(context, "No se pudo reproducir", 20f, bold = true),
                Ui.matchWrap(bottomMargin = Ui.dp(context, 10)))
            // El texto puede ser largo: se desplaza sin empujar los botones fuera de la pantalla.
            val textScroll = ScrollView(context).apply { addView(errorText) }
            addView(textScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                bottomMargin = Ui.dp(context, 18)
            })
            addView(Ui.button(context, "Reintentar") { retry() }, Ui.matchWrap(bottomMargin = Ui.dp(context, 10)))
            addView(Ui.button(context, "Salir", Ui.Style.SECONDARY) { finish() }, Ui.matchWrap())
        }
        val metrics = resources.displayMetrics
        val margin = Ui.dp(this, 16)
        val boxWidth = minOf(Ui.dp(this, 520), metrics.widthPixels - 2 * margin)
        val boxHeight = minOf(Ui.dp(this, 460), metrics.heightPixels - 2 * margin)
        root.addView(errorBox, FrameLayout.LayoutParams(boxWidth, boxHeight, Gravity.CENTER))
        setContentView(root)
        RoomSession.playbackListener = { msg -> onRemote(msg) }
        load(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        load(intent)
    }

    private fun load(intent: Intent) {
        val newUrl = intent.getStringExtra(EXTRA_URL)
        if (newUrl.isNullOrBlank()) { finish(); return }
        url = newUrl
        isHost = intent.getBooleanExtra(EXTRA_HOST, false)
        kind = intent.getStringExtra(EXTRA_KIND) ?: "vod"
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (title.isNotBlank()) toast(if (isHost) "Compartiendo: $title" else "Viendo juntos: $title")
        hideError()

        player?.release()
        val p = ExoPlayer.Builder(this).build()
        player = p
        playerView.player = p
        p.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (isLocalChange()) {
                    RoomSession.client?.sendPlayback(if (playWhenReady) "play" else "pause", p.currentPosition)
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                // Solo el anfitrión mueve la posición del otro lado.
                if (reason == Player.DISCONTINUITY_REASON_SEEK && kind == "vod" && isHost && isLocalChange()) {
                    RoomSession.client?.sendPlayback("seek", newPosition.positionMs)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) hideError()
            }

            override fun onPlayerError(error: PlaybackException) {
                val message = describe(error)
                showError(message)
                appendProviderInfo(message)
            }
        })
        p.setMediaItem(MediaItem.fromUri(url))
        p.playWhenReady = true
        p.prepare()
    }

    private fun retry() {
        val p = player ?: return
        hideError()
        p.prepare()
        p.playWhenReady = true
    }

    private fun showError(message: String) {
        errorText.text = message
        playerView.hideController()
        errorBox.visibility = View.VISIBLE
        errorBox.getChildAt(2)?.requestFocus()
    }

    /** Pide al relay qué respondió el proveedor y lo añade al mensaje (sirve para diagnosticar). */
    private fun appendProviderInfo(message: String) {
        val infoUrl = url.replace(Regex("/stream$"), "/info")
        if (infoUrl == url) return
        thread {
            val line = try {
                val c = URL(infoUrl).openConnection() as HttpURLConnection
                c.connectTimeout = 4000
                c.readTimeout = 4000
                val body = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                c.disconnect()
                val j = JSONObject(body)
                if (!j.has("status")) null else buildString {
                    append("Proveedor: HTTP ").append(j.optInt("status"))
                    val type = j.optString("contentType")
                    if (type.isNotBlank()) append(" · ").append(type)
                    val length = j.optLong("length", -1)
                    if (length >= 0) append(" · ").append(length / 1024).append(" KB")
                    val host = j.optString("host")
                    if (host.isNotBlank()) append(" · ").append(host)
                    append("\nComienza con: ").append(j.optString("startAscii"))
                    append("\n(").append(j.optString("startHex")).append(")")
                }
            } catch (_: Exception) {
                null
            }
            if (line != null) runOnUiThread {
                if (errorBox.visibility == View.VISIBLE && errorText.text.toString() == message) {
                    errorText.text = "$message\n\n$line"
                }
            }
        }
    }

    private fun hideError() {
        if (::errorBox.isInitialized) errorBox.visibility = View.GONE
    }

    /** Explica el error en palabras, con el detalle técnico al final. */
    private fun describe(error: PlaybackException): String {
        val causes = generateSequence(error.cause) { it.cause }.toList()
        val http = causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        val where = Uri.parse(url).let { "${it.host}:${it.port}" }
        val message = if (http != null) {
            val relayReason = http.headerFields.entries
                .firstOrNull { it.key?.equals("X-Relay-Error", ignoreCase = true) == true }
                ?.value?.firstOrNull()
            when {
                relayReason != null -> "El proveedor IPTV no entregó el video: $relayReason."
                http.responseCode == 404 -> "Ese contenido ya no se está compartiendo."
                else -> "El televisor que comparte respondió con un error (HTTP ${http.responseCode})."
            }
        } else {
            when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                    if (isHost) "No se pudo abrir el relay en este equipo."
                    else "No se pudo conectar con el equipo que comparte ($where). Revisen que los dos estén en el mismo Wi-Fi, " +
                        "o los dos con Tailscale conectado, según la red elegida en ese equipo."
                PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED ->
                    "Este equipo bloquea las conexiones sin cifrar."
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
                    "Lo que llegó del proveedor no se reconoce como video."
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
                    "Este equipo no puede decodificar ese video."
                PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ->
                    "El canal en vivo se atrasó demasiado. Pulsa Reintentar."
                else -> "Ocurrió un error al reproducir."
            }
        }
        val detail = causes.lastOrNull()?.message?.take(160)
        return buildString {
            append(message)
            append("\n\n").append(error.errorCodeName)
            val parsing = error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
            if (!detail.isNullOrBlank() && http == null && !parsing) append(" · ").append(detail)
        }
    }

    private fun isLocalChange() = SystemClock.elapsedRealtime() > ignoreLocalUntil

    private fun onRemote(msg: JSONObject) {
        val p = player ?: return
        when (msg.optString("type")) {
            "relay_stop" -> if (!isHost) {
                toast("Se dejó de compartir.")
                finish()
            }
            "playback" -> {
                ignoreLocalUntil = SystemClock.elapsedRealtime() + 1000
                val position = msg.optLong("positionMs", 0L)
                when (msg.optString("action")) {
                    "pause" -> { alignTo(p, position); p.playWhenReady = false }
                    "play" -> { alignTo(p, position); p.playWhenReady = true }
                    "seek" -> if (kind == "vod") p.seekTo(position)
                }
            }
        }
    }

    /** En series y películas, iguala la posición si la diferencia se nota. */
    private fun alignTo(p: ExoPlayer, position: Long) {
        if (kind == "vod" && abs(p.currentPosition - position) > 1500) p.seekTo(position)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Con el cuadro de error visible, el control remoto maneja sus botones y no el reproductor.
        if (errorBox.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
        return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        // Al salir de la pantalla no tiene sentido seguir descargando.
        if (!isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        RoomSession.playbackListener = null
        player?.release()
        player = null
        if (isHost) {
            RoomSession.currentShare = null
            RoomSession.client?.sendRelayStop()
            val appContext = applicationContext
            thread {
                // Si ya se eligió otro contenido, ese relay sigue en uso.
                if (RoomSession.currentShare == null) RoomSession.relay(appContext).stopSharing()
            }
        }
        super.onDestroy()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_HOST = "host"

        fun intent(context: Context, url: String, kind: String, title: String, host: Boolean): Intent =
            Intent(context, PlayerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_KIND, kind)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_HOST, host)
    }
}
