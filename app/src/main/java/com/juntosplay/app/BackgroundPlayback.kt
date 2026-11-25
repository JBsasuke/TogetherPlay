package com.juntosplay.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.media3.common.Player

/** Retiene la prioridad de reproducción del teléfono mientras el reproductor está activo. */
internal class BackgroundPlayback(private val activity: Activity, private val title: () -> String) : Player.Listener {
    private var player: Player? = null

    fun attach(next: Player?) {
        if (player === next) return
        player?.let { old ->
            old.removeListener(this)
            PlaybackService.clear(old)
        }
        player = next
        next?.addListener(this)
        update()
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) ||
            events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) ||
            events.contains(Player.EVENT_PLAYER_ERROR)) update()
    }

    fun update() {
        val target = player ?: return
        if (target.playWhenReady && target.playerError == null &&
            (target.playbackState == Player.STATE_READY || target.playbackState == Player.STATE_BUFFERING)) {
            PlaybackService.show(activity, target, title())
        } else {
            PlaybackService.clear(target)
        }
    }
}

class PlaybackService : Service() {
    private var mediaSession: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            playback?.player?.pause()
            return START_NOT_STICKY
        }
        publish()
        if (playback == null) stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun publish() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Reproducción de TogetherPlay", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val current = playback
        val session = mediaSession ?: MediaSession(this, "TogetherPlay").also {
            it.setCallback(object : MediaSession.Callback() {
                override fun onPause() { playback?.player?.pause() }
                override fun onStop() { onPause() }
            })
            it.isActive = true
            mediaSession = it
        }
        session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, current?.title ?: "TogetherPlay")
            .build())
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP)
            .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
            .build())
        val pause = PendingIntent.getService(this, 0, Intent(this, PlaybackService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val style = Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        val notification = builder
            .setContentTitle(current?.title?.ifBlank { "TogetherPlay" } ?: "TogetherPlay")
            .setContentText("Reproduciendo en TogetherPlay")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setStyle(style)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_pause), "Pausar", pause
            ).build())
            .apply { current?.let { setContentIntent(it.open) } }
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else startForeground(NOTIFICATION_ID, notification)
        if (current != null) acquireWakeLock()
        running = this
    }

    @android.annotation.SuppressLint("WakelockTimeout") // Se libera al pausar, cerrar o terminar el contenido.
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TogetherPlay:playback")
                .apply { acquire() }
        }
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        if (running === this) running = null
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        private const val ACTION_PAUSE = "com.juntosplay.app.PAUSE_PLAYBACK"
        private const val CHANNEL = "togetherplay_playback"
        private const val NOTIFICATION_ID = 7404
        private data class Playback(val player: Player, val title: String, val open: PendingIntent)
        private var playback: Playback? = null
        private var running: PlaybackService? = null
        private val handler = Handler(Looper.getMainLooper())
        private val stopIfIdle = Runnable { if (playback == null) running?.stopSelf() }

        internal fun show(activity: Activity, player: Player, title: String) {
            if (playback?.let { it.player === player && it.title == title } == true) return
            val open = PendingIntent.getActivity(activity, 0,
                Intent(Intent.ACTION_MAIN).setClass(activity, MainActivity::class.java)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val next = Playback(player, title, open)
            val service = running
            if (service != null) {
                handler.removeCallbacks(stopIfIdle)
                playback = next
                service.publish()
                return
            }
            val intent = Intent(activity, PlaybackService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) activity.startForegroundService(intent) else activity.startService(intent)
                playback = next
            } catch (_: IllegalStateException) {
                // Se vuelve a intentar al regresar a la actividad.
            }
        }

        internal fun clear(player: Player) {
            if (playback?.player !== player) return
            playback = null
            // Conserva el servicio durante un cambio inmediato de episodio o decodificador.
            handler.removeCallbacks(stopIfIdle)
            handler.postDelayed(stopIfIdle, 1_000L)
        }
    }
}
