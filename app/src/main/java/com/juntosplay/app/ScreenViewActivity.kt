package com.juntosplay.app

import android.app.Activity
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Muestra en directo la pantalla que comparte el otro equipo. */
class ScreenViewActivity : Activity() {
    private lateinit var egl: EglBase
    private lateinit var factory: PeerConnectionFactory
    private lateinit var renderer: SurfaceViewRenderer
    private lateinit var status: android.widget.TextView
    private var peer: PeerConnection? = null
    private var shownTrack: VideoTrack? = null
    private val pendingIce = ArrayList<IceCandidate>()
    private var remoteSet = false

    private val audioQueue = ArrayBlockingQueue<ByteArray>(40)
    @Volatile private var audioRunning = true
    private var audioThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Rtc.init(this)
        egl = EglBase.create()
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); keepScreenOn = true }
        renderer = SurfaceViewRenderer(this)
        renderer.init(egl.eglBaseContext, null)
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        renderer.setEnableHardwareScaler(true)
        root.addView(renderer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        status = Ui.label(this, "Conectando con la pantalla compartida…", 16f, Ui.muted).apply {
            background = Ui.round(Ui.surface, Ui.dp(context, 14).toFloat(), Ui.dp(context, 1), Ui.outline)
            setPadding(Ui.dp(context, 18), Ui.dp(context, 12), Ui.dp(context, 18), Ui.dp(context, 12))
        }
        root.addView(status, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setContentView(root)

        startAudioPlayer()
        RoomSession.screenSignalListener = { type, payload -> onSignal(type, payload) }
        RoomSession.screenStopListener = {
            Toast.makeText(this, "Se dejó de compartir la pantalla.", Toast.LENGTH_LONG).show()
            finish()
        }
        RoomSession.takePendingScreenSignals().forEach { (type, payload) -> onSignal(type, payload) }
    }

    private fun onSignal(type: String, payload: JSONObject) {
        when (type) {
            "webrtc_offer" -> answer(payload)
            "webrtc_ice" -> {
                val c = Rtc.iceFrom(payload)
                val pc = peer
                if (pc != null && remoteSet) pc.addIceCandidate(c) else pendingIce.add(c)
            }
        }
    }

    private fun answer(offer: JSONObject) {
        // Una oferta nueva (p. ej. tras reconectar) reemplaza la conexión anterior.
        shownTrack?.removeSink(renderer)
        shownTrack = null
        peer?.let { try { it.close(); it.dispose() } catch (_: Exception) {} }
        remoteSet = false
        val pc = factory.createPeerConnection(Rtc.config(), object : Rtc.Observer() {
            override fun onIceCandidate(candidate: IceCandidate) {
                RoomSession.client?.sendSignal("webrtc_ice", Rtc.iceJson(candidate))
            }
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                (receiver.track() as? VideoTrack)?.let { show(it) }
            }
            override fun onTrack(transceiver: RtpTransceiver) {
                (transceiver.receiver.track() as? VideoTrack)?.let { show(it) }
            }
            override fun onDataChannel(channel: DataChannel) {
                channel.registerObserver(object : DataChannel.Observer {
                    override fun onBufferedAmountChange(previousAmount: Long) {}
                    override fun onStateChange() {}
                    override fun onMessage(buffer: DataChannel.Buffer) {
                        if (!buffer.binary) return
                        val bytes = ByteArray(buffer.data.remaining())
                        buffer.data.get(bytes)
                        // Si el sonido se atrasa, se descarta lo viejo para no acumular retraso.
                        if (!audioQueue.offer(bytes)) { audioQueue.clear(); audioQueue.offer(bytes) }
                    }
                })
            }
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                runOnUiThread {
                    when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> status.visibility = View.GONE
                        PeerConnection.PeerConnectionState.DISCONNECTED -> {
                            status.text = "Conexión inestable, esperando…"; status.visibility = View.VISIBLE
                        }
                        PeerConnection.PeerConnectionState.FAILED -> {
                            status.text = "No se pudo conectar con la pantalla compartida. Revisen que los dos tengan Tailscale conectado."
                            status.visibility = View.VISIBLE
                        }
                        else -> {}
                    }
                }
            }
        }) ?: return
        peer = pc
        pc.setRemoteDescription(Rtc.Sdp(onSet = {
            pc.createAnswer(Rtc.Sdp(onCreated = { desc ->
                pc.setLocalDescription(Rtc.Sdp(onSet = {
                    RoomSession.client?.sendSignal("webrtc_answer", Rtc.sdpJson(desc))
                    runOnUiThread {
                        remoteSet = true
                        pendingIce.forEach { pc.addIceCandidate(it) }
                        pendingIce.clear()
                    }
                }), desc)
            }), org.webrtc.MediaConstraints())
        }), Rtc.sdpFrom(offer, "offer"))
    }

    private fun show(track: VideoTrack) = runOnUiThread {
        if (shownTrack === track) return@runOnUiThread
        shownTrack?.removeSink(renderer)
        shownTrack = track
        track.addSink(renderer)
    }

    private fun startAudioPlayer() {
        audioThread = Thread({
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(Rtc.AUDIO_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val track = try {
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build())
                    .setAudioFormat(format)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(Rtc.AUDIO_PACKET_BYTES * 20)
                    .build()
            } catch (_: Exception) { return@Thread }
            track.play()
            try {
                while (audioRunning) {
                    val data = audioQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    track.write(data, 0, data.size)
                }
            } finally {
                try { track.stop() } catch (_: Exception) {}
                track.release()
            }
        }, "tp-screen-speaker").apply { start() }
    }

    override fun onDestroy() {
        if (RoomSession.screenStopListener != null) RoomSession.screenStopListener = null
        RoomSession.screenSignalListener = null
        audioRunning = false
        audioThread?.join(400)
        shownTrack?.removeSink(renderer)
        peer?.let { try { it.close(); it.dispose() } catch (_: Exception) {} }
        peer = null
        renderer.release()
        factory.dispose()
        egl.release()
        super.onDestroy()
    }
}
