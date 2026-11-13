package com.juntosplay.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.nio.ByteBuffer

/**
 * Comparte la pantalla de este equipo con el otro por WebRTC. Es un servicio en
 * primer plano para seguir transmitiendo mientras se usa otra app.
 */
class ScreenShareService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var egl: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var helper: SurfaceTextureHelper? = null
    private var source: VideoSource? = null
    private var track: VideoTrack? = null
    private var peer: PeerConnection? = null
    private var audioChannel: DataChannel? = null
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    @Volatile private var audioRunning = false
    private val pendingIce = ArrayList<IceCandidate>()
    private var remoteSet = false
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground()
        if (started) return START_NOT_STICKY
        val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startSession(data)
            started = true
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo compartir la pantalla: ${e.message ?: "error"}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Compartir pantalla", NotificationManager.IMPORTANCE_LOW))
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        val notification = builder
            .setContentTitle("TogetherPlay")
            .setContentText("Compartiendo tu pantalla")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Detener", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startSession(data: Intent) {
        Rtc.init(this)
        val egl = EglBase.create().also { this.egl = it }
        val factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
            .also { this.factory = it }
        val capturer = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopSelf() }
            }
        })
        this.capturer = capturer
        val helper = SurfaceTextureHelper.create("tp-screen", egl.eglBaseContext).also { this.helper = it }
        val source = factory.createVideoSource(true).also { this.source = it }
        capturer.initialize(helper, applicationContext, source.capturerObserver)
        val (w, h) = captureSize()
        capturer.startCapture(w, h, 30)
        track = factory.createVideoTrack("tp-screen", source)
        startAudio(projectionOf(capturer))

        RoomSession.screenSignalListener = { type, payload -> onSignal(type, payload) }
        RoomSession.screenPeerJoined = { offer() }
        RoomSession.screenHosting = true
        offer()
    }

    /** Pantalla a 1280 px en el lado largo como máximo (720p en una TV). */
    private fun captureSize(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        val long = maxOf(dm.widthPixels, dm.heightPixels).coerceAtLeast(1)
        val scale = minOf(1f, 1280f / long)
        return ((dm.widthPixels * scale).toInt() / 2 * 2) to ((dm.heightPixels * scale).toInt() / 2 * 2)
    }

    /** ScreenCapturerAndroid guarda la proyección internamente; hace falta para capturar el sonido. */
    private fun projectionOf(c: ScreenCapturerAndroid): MediaProjection? = try {
        val field = ScreenCapturerAndroid::class.java.getDeclaredField("mediaProjection")
        field.isAccessible = true
        field.get(c) as? MediaProjection
    } catch (_: Exception) {
        null
    }

    // --- conexión con el otro equipo ---

    private fun offer() {
        val factory = factory ?: return
        val track = track ?: return
        closePeer()
        remoteSet = false
        pendingIce.clear()
        val pc = factory.createPeerConnection(Rtc.config(), object : Rtc.Observer() {
            override fun onIceCandidate(candidate: IceCandidate) {
                RoomSession.client?.sendSignal("webrtc_ice", Rtc.iceJson(candidate))
            }
        }) ?: return
        peer = pc
        pc.addTrack(track, listOf("tp"))
        audioChannel = pc.createDataChannel("tp-audio", DataChannel.Init().apply {
            ordered = false
            maxRetransmits = 0
        })
        pc.createOffer(Rtc.Sdp(onCreated = { desc ->
            pc.setLocalDescription(Rtc.Sdp(onSet = {
                RoomSession.client?.sendSignal("webrtc_offer", Rtc.sdpJson(desc))
            }), desc)
        }), org.webrtc.MediaConstraints())
    }

    private fun onSignal(type: String, payload: JSONObject) {
        val pc = peer ?: return
        when (type) {
            "webrtc_answer" -> pc.setRemoteDescription(Rtc.Sdp(onSet = {
                main.post {
                    remoteSet = true
                    pendingIce.forEach { peer?.addIceCandidate(it) }
                    pendingIce.clear()
                }
            }), Rtc.sdpFrom(payload, "answer"))
            "webrtc_ice" -> {
                val c = Rtc.iceFrom(payload)
                if (remoteSet) pc.addIceCandidate(c) else pendingIce.add(c)
            }
        }
    }

    private fun closePeer() {
        audioChannel?.let { try { it.close(); it.dispose() } catch (_: Exception) {} }
        audioChannel = null
        peer?.let { try { it.close(); it.dispose() } catch (_: Exception) {} }
        peer = null
    }

    // --- sonido de las otras apps (Android 10 o más) ---

    private fun startAudio(projection: MediaProjection?) {
        if (projection == null || Build.VERSION.SDK_INT < 29) return
        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(Rtc.AUDIO_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val min = AudioRecord.getMinBufferSize(Rtc.AUDIO_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val record = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(min, 4096) * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()
            if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); return }
            audioRecord = record
            record.startRecording()
            audioRunning = true
            audioThread = Thread({
                val packet = ByteArray(Rtc.AUDIO_PACKET_BYTES)
                while (audioRunning) {
                    val n = try { record.read(packet, 0, packet.size) } catch (_: Exception) { -1 }
                    if (n < 0) break
                    val ch = audioChannel
                    if (n > 0 && ch != null && ch.state() == DataChannel.State.OPEN && ch.bufferedAmount() < 256_000) {
                        ch.send(DataChannel.Buffer(ByteBuffer.wrap(packet.copyOf(n)), true))
                    }
                }
            }, "tp-screen-audio").apply { start() }
        } catch (_: SecurityException) {
            // Sin permiso de micrófono no se puede capturar el sonido; la imagen sigue.
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        RoomSession.client?.sendCustom(JSONObject().put("type", "screen_stop"))
        RoomSession.screenHosting = false
        RoomSession.screenSignalListener = null
        RoomSession.screenPeerJoined = null
        audioRunning = false
        try { audioRecord?.stop() } catch (_: Exception) {}
        audioRecord?.release()
        audioRecord = null
        closePeer()
        try { capturer?.stopCapture() } catch (_: Exception) {}
        capturer?.dispose()
        capturer = null
        track?.dispose()
        source?.dispose()
        helper?.dispose()
        factory?.dispose()
        egl?.release()
        track = null; source = null; helper = null; factory = null; egl = null
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.juntosplay.app.STOP_SCREEN"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL = "togetherplay_screen"
        private const val NOTIFICATION_ID = 7402
    }
}
