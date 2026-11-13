package com.juntosplay.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
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
    /** Dirección tal como llegó del otro equipo (url puede ser la versión por Tailscale integrado). */
    private var sourceUrl = ""
    /** Mientras se aplica una orden remota, los cambios locales no se reenvían. */
    private var ignoreLocalUntil = 0L
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var syncButton: TextView
    /** Este equipo pidió sincronizar y espera a que el otro esté listo en el mismo punto. */
    private var leadingSync = false
    private var leaderPosition = 0L
    /** El otro pidió sincronizar: al quedar listo en ese punto, se le avisa. */
    private var awaitingSyncReady = false
    private val leaderTimeout = Runnable { finishLeadSync() }
    private val speedReset = Runnable { player?.setPlaybackSpeed(1f) }
    private val ticker = object : Runnable {
        override fun run() {
            // El que comparte manda su posición cada 10 s; el otro corrige si se va desfasando.
            val p = player
            if (p != null && isHost && kind == "vod" && p.isPlaying) send("tick", p.currentPosition)
            handler.postDelayed(this, TICK_MS)
        }
    }

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

        // Botón de sincronizar: aparece junto con los controles del reproductor.
        syncButton = Ui.button(this, "⟳  Sincronizar", Ui.Style.SECONDARY) { requestSync() }.apply { visibility = View.GONE }
        root.addView(syncButton, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END
        ).apply { val m = Ui.dp(this@PlayerActivity, 20); setMargins(m, m, m, m) })
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            syncButton.visibility = visibility
        })
        setContentView(root)
        handler.postDelayed(ticker, TICK_MS)
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
        // Al reconectarse la sala llega el mismo aviso otra vez: si ya se está viendo, no se reinicia.
        if (newUrl == sourceUrl && player != null && errorBox.visibility != View.VISIBLE) return
        sourceUrl = newUrl
        url = TailnetBridge.rewriteForPlayback(newUrl)
        isHost = intent.getBooleanExtra(EXTRA_HOST, false)
        kind = intent.getStringExtra(EXTRA_KIND) ?: "vod"
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (title.isNotBlank()) toast(if (isHost) "Compartiendo: $title" else "Viendo juntos: $title")
        hideError()

        player?.release()
        // Tiempos amplios: el relay espera al proveedor antes de responder.
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        // Búfer más grande que el normal: aguanta mejor los altibajos de la conexión entre casas.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 120_000, 2_500, 5_000)
            .build()
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            .setLoadControl(loadControl)
            .build()
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
                if (playbackState == Player.STATE_READY) {
                    hideError()
                    reportSyncReady()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // En vivo, si se quedó atrás, se vuelve al directo sin molestar.
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    p.seekToDefaultPosition()
                    p.prepare()
                    return
                }
                val message = describe(error)
                showError(message)
                appendProviderInfo(message)
            }
        })
        val item = MediaItem.Builder().setUri(url)
        if (Uri.parse(url).path?.endsWith(".m3u8") == true) item.setMimeType(MimeTypes.APPLICATION_M3U8)
        val startMs = intent.getLongExtra(EXTRA_START, 0L)
        // Si la otra app ya iba por un punto de la película, se sigue desde ahí.
        if (startMs > 0 && kind == "vod") p.setMediaItem(item.build(), startMs) else p.setMediaItem(item.build())
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
        val infoUrl = url.replace(Regex("/stream(\\.m3u8)?$"), "/info")
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
                ?.let { raw -> try { URLDecoder.decode(raw, "UTF-8") } catch (_: Exception) { raw } }
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
    private fun ignoreLocal(ms: Long) { ignoreLocalUntil = SystemClock.elapsedRealtime() + ms }
    private fun send(action: String, position: Long) { RoomSession.client?.sendPlayback(action, position) }

    // --- sincronizar ---

    private fun isHlsLive(p: ExoPlayer) = p.isCurrentMediaItemLive && p.currentLiveOffset != C.TIME_UNSET

    /**
     * Este equipo pasa a ser la referencia.
     * Películas y series: se pausa aquí, el otro salta al mismo punto, avisa que está listo y arrancan juntos.
     * En vivo (HLS): el otro se pone a la misma distancia del directo. En vivo (TS): los dos vuelven a abrir el canal a la vez.
     */
    private fun requestSync() {
        val p = player ?: return
        if (RoomSession.client == null) { toast("No hay conexión con la sala."); return }
        when {
            kind == "vod" -> {
                ignoreLocal(1500)
                p.playWhenReady = false
                leaderPosition = p.currentPosition
                leadingSync = true
                send("sync_hold", leaderPosition)
                handler.removeCallbacks(leaderTimeout)
                handler.postDelayed(leaderTimeout, 8_000)
                toast("Sincronizando…")
            }
            isHlsLive(p) -> {
                send("sync_live", p.currentLiveOffset)
                toast("El otro equipo se ajusta a este.")
            }
            else -> {
                send("resync_live", 0)
                reloadLive()
                toast("Sincronizando el canal en los dos equipos…")
            }
        }
    }

    private fun finishLeadSync() {
        if (!leadingSync) return
        leadingSync = false
        handler.removeCallbacks(leaderTimeout)
        val p = player ?: return
        ignoreLocal(1500)
        p.seekTo(leaderPosition)
        p.playWhenReady = true
        send("play", leaderPosition)
    }

    private fun reportSyncReady() {
        val p = player ?: return
        if (!awaitingSyncReady || p.playbackState != Player.STATE_READY) return
        awaitingSyncReady = false
        send("sync_ready", p.currentPosition)
    }

    private fun reloadLive() {
        val p = player ?: return
        ignoreLocal(2000)
        p.stop()
        p.prepare()
        p.playWhenReady = true
    }

    /** Corrección automática: desfases pequeños se arreglan acelerando o frenando un poco; grandes, saltando. */
    private fun correctDrift(p: ExoPlayer, hostPosition: Long) {
        if (isHost || kind != "vod" || !p.isPlaying || leadingSync || awaitingSyncReady) return
        val target = hostPosition + TRANSIT_MS
        val drift = p.currentPosition - target
        when {
            abs(drift) > 3_000 -> { ignoreLocal(1500); p.seekTo(target) }
            abs(drift) > 400 -> {
                p.setPlaybackSpeed(if (drift > 0) 0.95f else 1.05f)
                handler.removeCallbacks(speedReset)
                handler.postDelayed(speedReset, (abs(drift) / 0.05).toLong().coerceAtMost(30_000))
            }
        }
    }

    private fun onRemote(msg: JSONObject) {
        val p = player ?: return
        when (msg.optString("type")) {
            "relay_stop" -> if (!isHost) {
                toast("Se dejó de compartir.")
                finish()
            }
            "playback" -> {
                val position = msg.optLong("positionMs", 0L)
                when (msg.optString("action")) {
                    "tick" -> { correctDrift(p, position); return }
                    "sync_ready" -> { finishLeadSync(); return }
                }
                ignoreLocal(1000)
                when (msg.optString("action")) {
                    "pause" -> { alignTo(p, position); p.playWhenReady = false }
                    "play" -> { alignTo(p, position); p.playWhenReady = true }
                    "seek" -> if (kind == "vod") p.seekTo(position)
                    "sync_hold" -> {
                        ignoreLocal(2000)
                        p.playWhenReady = false
                        p.setPlaybackSpeed(1f)
                        awaitingSyncReady = true
                        p.seekTo(position)
                        // Si el salto cae en lo ya cargado puede no haber cambio de estado: se comprueba igual.
                        handler.postDelayed({ reportSyncReady() }, 400)
                    }
                    "sync_live" -> if (isHlsLive(p)) {
                        val delta = p.currentLiveOffset - position
                        if (abs(delta) > 300) p.seekTo(p.currentPosition + delta)
                    }
                    "resync_live" -> reloadLive()
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
        // Atajo en el control remoto: botón de menú (o rojo) = sincronizar.
        if (event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_PROG_RED) {
            if (event.action == KeyEvent.ACTION_UP) requestSync()
            return true
        }
        if (syncButton.hasFocus()) return super.dispatchKeyEvent(event)
        return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations || isFinishing) return
        // Con la pantalla apagada se sigue reproduciendo (y compartiendo) como si nada.
        val screenOn = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        if (!screenOn) return
        // Si se fue a otra app, se pausa solo aquí; el otro equipo sigue viendo.
        ignoreLocal(1500)
        player?.playWhenReady = false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Ui.hideSystemBars(this)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
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
        private const val EXTRA_START = "start_ms"
        private const val TICK_MS = 10_000L
        /** Tiempo aproximado que tarda un mensaje en llegar al otro equipo. */
        private const val TRANSIT_MS = 150L

        fun intent(context: Context, url: String, kind: String, title: String, host: Boolean, startMs: Long = 0L): Intent =
            Intent(context, PlayerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_KIND, kind)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_START, startMs)
    }
}
