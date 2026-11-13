package com.juntosplay.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Reproductor sincronizado. Abre la URL del relay (127.0.0.1 en el anfitrión,
 * la IP de Tailscale en el otro televisor) y mantiene play/pausa/seek a la par
 * mediante los mensajes "playback" de la sala.
 */
class PlayerActivity : Activity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private var isHost = false
    private var kind = "vod"
    /** Mientras se aplica una orden remota, los cambios locales no se reenvían. */
    private var ignoreLocalUntil = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playerView = PlayerView(this).apply {
            setBackgroundColor(Color.BLACK)
            keepScreenOn = true
        }
        setContentView(playerView)
        RoomSession.playbackListener = { msg -> onRemote(msg) }
        load(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        load(intent)
    }

    private fun load(intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) { finish(); return }
        isHost = intent.getBooleanExtra(EXTRA_HOST, false)
        kind = intent.getStringExtra(EXTRA_KIND) ?: "vod"
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (title.isNotBlank()) toast(if (isHost) "Compartiendo: $title" else "Viendo juntos: $title")

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

            override fun onPlayerError(error: PlaybackException) {
                toast("No se pudo reproducir: ${error.errorCodeName}")
            }
        })
        p.setMediaItem(MediaItem.fromUri(url))
        p.playWhenReady = true
        p.prepare()
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
