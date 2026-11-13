package com.juntosplay.app

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack as SystemAudioTrack
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.Image
import android.media.ImageReader
import android.os.Build
import org.json.JSONObject
import org.webrtc.*
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebRTC screen-share session.
 *
 * Video is captured from the one user-approved MediaProjection.
 * On Android 10+, app playback audio is captured only from apps that allow
 * playback capture. PCM audio is carried in a WebRTC data channel.
 */
class ScreenShareClient(
    private val context: Context,
    private val sendSignal: (String, JSONObject) -> Unit,
    private val onRemoteVideo: (VideoTrack) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val egl = EglBase.create()
    private val factory: PeerConnectionFactory
    private var peer: PeerConnection? = null
    private var localCapturer: ProjectionVideoCapturer? = null
    private var localVideoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private val audioRunning = AtomicBoolean(false)
    private var outgoingAudio: DataChannel? = null
    private var incomingAudio: DataChannel? = null
    private var speaker: SystemAudioTrack? = null
    private var isHost = false
    private var hasPeer = false
    private var projection: MediaProjection? = null

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .createInitializationOptions()
        )
        val encoder = DefaultVideoEncoderFactory(egl.eglBaseContext, true, true)
        val decoder = DefaultVideoDecoderFactory(egl.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoder)
            .setVideoDecoderFactory(decoder)
            .createPeerConnectionFactory()
    }

    fun startHost(mediaProjection: MediaProjection) {
        isHost = true
        projection = mediaProjection
        try {
            ensurePeer()
            val capturer = ProjectionVideoCapturer(mediaProjection)
            val source = factory.createVideoSource(true)
            capturer.initialize(null, context, source.capturerObserver)
            capturer.startCapture(capturer.captureWidth, capturer.captureHeight, 15)
            localCapturer = capturer
            localVideoSource = source
            localVideoTrack = factory.createVideoTrack("togetherplay-screen", source)
            localVideoTrack?.let { peer?.addTrack(it, listOf("togetherplay-stream")) }

            val init = DataChannel.Init().apply {
                ordered = false
                maxRetransmits = 0
            }
            outgoingAudio = peer?.createDataChannel("togetherplay-playback-audio", init)
            observeAudioChannel(outgoingAudio, playbackOutput = false)
            startPlaybackAudioCapture(mediaProjection)
            onStatus("Capturando pantalla; preparando conexión con la otra persona")
            if (hasPeer) createOffer()
        } catch (e: Exception) {
            onStatus("No se pudo iniciar la captura: ${e.message ?: "error desconocido"}")
        }
    }

    fun onPeerJoined() {
        hasPeer = true
        if (isHost && localVideoTrack != null) createOffer()
    }

    fun handleSignal(type: String, payload: JSONObject) {
        when (type) {
            "webrtc_offer" -> {
                ensurePeer()
                val sdp = SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(payload.optString("sdpType", "offer")),
                    payload.optString("sdp")
                )
                peer?.setRemoteDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        peer?.createAnswer(object : SimpleSdpObserver() {
                            override fun onCreateSuccess(desc: SessionDescription) {
                                peer?.setLocalDescription(SimpleSdpObserver(), desc)
                                sendSignal("webrtc_answer", JSONObject()
                                    .put("sdpType", desc.type.canonicalForm())
                                    .put("sdp", desc.description))
                            }
                            override fun onCreateFailure(error: String) { onStatus("Error creando respuesta: $error") }
                        }, MediaConstraints())
                    }
                    override fun onSetFailure(error: String) { onStatus("Oferta inválida: $error") }
                }, sdp)
            }
            "webrtc_answer" -> {
                val sdp = SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(payload.optString("sdpType", "answer")),
                    payload.optString("sdp")
                )
                peer?.setRemoteDescription(SimpleSdpObserver(), sdp)
            }
            "webrtc_ice" -> {
                val candidate = IceCandidate(
                    payload.optString("sdpMid"),
                    payload.optInt("sdpMLineIndex"),
                    payload.optString("candidate")
                )
                peer?.addIceCandidate(candidate)
            }
        }
    }

    private fun ensurePeer() {
        if (peer != null) return
        val ice = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val config = PeerConnection.RTCConfiguration(ice).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        peer = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                onStatus("Conexión multimedia: ${state.name}")
            }
            override fun onStandardizedIceConnectionChange(state: PeerConnection.IceConnectionState) {}
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                onStatus("Transmisión: ${state.name}")
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(candidate: IceCandidate) {
                sendSignal("webrtc_ice", JSONObject()
                    .put("sdpMid", candidate.sdpMid)
                    .put("sdpMLineIndex", candidate.sdpMLineIndex)
                    .put("candidate", candidate.sdp))
            }
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {
                stream.videoTracks.firstOrNull()?.let(onRemoteVideo)
            }
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {
                incomingAudio = channel
                observeAudioChannel(channel, playbackOutput = true)
            }
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<MediaStream>) {
                (receiver.track() as? VideoTrack)?.let(onRemoteVideo)
            }
            override fun onTrack(transceiver: RtpTransceiver) {
                (transceiver.receiver.track() as? VideoTrack)?.let(onRemoteVideo)
            }
        })
        if (peer == null) throw IllegalStateException("No se pudo crear PeerConnection")
    }

    private fun createOffer() {
        val pc = peer ?: return
        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc.setLocalDescription(SimpleSdpObserver(), desc)
                sendSignal("webrtc_offer", JSONObject()
                    .put("sdpType", desc.type.canonicalForm())
                    .put("sdp", desc.description))
                onStatus("Oferta de transmisión enviada")
            }
            override fun onCreateFailure(error: String) { onStatus("Error creando oferta: $error") }
        }, MediaConstraints())
    }

    private fun startPlaybackAudioCapture(mediaProjection: MediaProjection) {
        if (Build.VERSION.SDK_INT < 29) {
            onStatus("Este Android no permite capturar el audio interno; la imagen sí puede transmitirse.")
            return
        }
        try {
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(48000)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build()
            val minBuffer = AudioRecord.getMinBufferSize(
                48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)
            val record = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuffer * 2)
                .setAudioPlaybackCaptureConfig(playbackConfig)
                .build()
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                onStatus("No se pudo inicializar la captura de audio interno.")
                return
            }
            audioRecord = record
            record.startRecording()
            audioRunning.set(true)
            audioThread = Thread {
                val packet = ByteArray(960) // 10 ms, 48 kHz, mono, PCM16
                while (audioRunning.get()) {
                    val count = try { record.read(packet, 0, packet.size) } catch (_: Exception) { -1 }
                    if (count > 0) {
                        val channel = outgoingAudio
                        if (channel != null && channel.state() == DataChannel.State.OPEN &&
                            channel.bufferedAmount() < 512_000) {
                            val bytes = packet.copyOf(count)
                            channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
                        }
                    } else if (count < 0) {
                        onStatus("La captura de audio interno se interrumpió ($count).")
                        break
                    }
                }
            }.apply { name = "TogetherPlay-Playback-Audio"; start() }
            onStatus("Captura de audio interno iniciada")
        } catch (e: SecurityException) {
            onStatus("Android no autorizó el audio interno: concede el permiso de audio y vuelve a compartir.")
        } catch (e: Exception) {
            onStatus("No se pudo capturar el audio interno: ${e.message ?: "error"}")
        }
    }

    private fun observeAudioChannel(channel: DataChannel?, playbackOutput: Boolean) {
        if (channel == null) return
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {}
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (!playbackOutput || !buffer.binary) return
                val data = ByteArray(buffer.data.remaining())
                buffer.data.get(data)
                playPcm(data)
            }
        })
    }

    private fun playPcm(data: ByteArray) {
        try {
            var track = speaker
            if (track == null) {
                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(48000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
                track = SystemAudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                    .setAudioFormat(format)
                    .setTransferMode(SystemAudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(19200)
                    .build()
                track.play()
                speaker = track
            }
            if (Build.VERSION.SDK_INT >= 23) track.write(data, 0, data.size, SystemAudioTrack.WRITE_NON_BLOCKING)
            else @Suppress("DEPRECATION") track.write(data, 0, data.size)
        } catch (_: Exception) {
            onStatus("No se pudo reproducir el audio recibido.")
        }
    }

    fun close() {
        audioRunning.set(false)
        try { audioRecord?.stop() } catch (_: Exception) {}
        audioRecord?.release()
        audioRecord = null
        audioThread?.join(300)
        audioThread = null
        outgoingAudio?.close()
        incomingAudio?.close()
        speaker?.pause()
        speaker?.flush()
        speaker?.release()
        speaker = null
        localCapturer?.stopCapture()
        localCapturer?.dispose()
        localCapturer = null
        localVideoTrack?.dispose()
        localVideoSource?.dispose()
        peer?.close()
        peer = null
        factory.dispose()
        egl.release()
    }

    private class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String) {}
        override fun onSetFailure(error: String) {}
    }

    private inner class ProjectionVideoCapturer(
        private val mediaProjection: MediaProjection
    ) : VideoCapturer {
        val captureWidth: Int
        val captureHeight: Int
        private var observer: CapturerObserver? = null
        private var reader: ImageReader? = null
        private var display: VirtualDisplay? = null
        private var running = false

        init {
            val metrics = context.resources.displayMetrics
            val scale = minOf(1f, 1280f / metrics.widthPixels, 720f / metrics.heightPixels)
            captureWidth = ((metrics.widthPixels * scale).toInt() / 2) * 2
            captureHeight = ((metrics.heightPixels * scale).toInt() / 2) * 2
        }

        override fun initialize(helper: SurfaceTextureHelper?, context: Context, observer: CapturerObserver) {
            this.observer = observer
        }

        override fun startCapture(width: Int, height: Int, framerate: Int) {
            if (running) return
            running = true
            val w = captureWidth
            val h = captureHeight
            val imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
            reader = imageReader
            imageReader.setOnImageAvailableListener({ source ->
                val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    if (running) deliverImage(image)
                } finally {
                    image.close()
                }
            }, android.os.Handler(android.os.Looper.getMainLooper()))
            display = mediaProjection.createVirtualDisplay(
                "TogetherPlay-WebRTC", w, h, metricsDensity(), DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface, null, null
            )
            if (display == null) {
                running = false
                throw IllegalStateException("No se pudo crear la pantalla virtual")
            }
        }

        private fun metricsDensity() = context.resources.displayMetrics.densityDpi

        private fun deliverImage(image: Image) {
            val plane = image.planes[0]
            val src = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val w = captureWidth
            val h = captureHeight
            val out = JavaI420Buffer.allocate(w, h)
            val y = out.dataY
            val u = out.dataU
            val v = out.dataV
            for (row in 0 until h) {
                for (col in 0 until w) {
                    val index = row * rowStride + col * pixelStride
                    val r = src.get(index).toInt() and 255
                    val g = src.get(index + 1).toInt() and 255
                    val b = src.get(index + 2).toInt() and 255
                    val yy = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                    y.put(row * out.strideY + col, yy.coerceIn(0, 255).toByte())
                    if (row % 2 == 0 && col % 2 == 0) {
                        val uu = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                        val vv = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                        u.put((row / 2) * out.strideU + col / 2, uu.coerceIn(0, 255).toByte())
                        v.put((row / 2) * out.strideV + col / 2, vv.coerceIn(0, 255).toByte())
                    }
                }
            }
            val frame = VideoFrame(out, 0, System.nanoTime())
            observer?.onFrameCaptured(frame)
            frame.release()
        }

        override fun stopCapture() {
            running = false
            display?.release()
            display = null
            reader?.setOnImageAvailableListener(null, null)
            reader?.close()
            reader = null
        }
        override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {}
        override fun dispose() { stopCapture(); observer = null }
        override fun isScreencast(): Boolean = true
    }
}
