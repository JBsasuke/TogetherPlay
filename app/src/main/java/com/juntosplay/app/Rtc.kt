package com.juntosplay.app

import android.content.Context
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/** Piezas comunes de WebRTC para compartir pantalla. */
object Rtc {
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions()
        )
        initialized = true
    }

    /**
     * STUN para cruzar el router de casa. Con Tailscale los dos equipos además se ven
     * directamente por sus direcciones 100.x, que WebRTC también prueba.
     */
    fun config() = PeerConnection.RTCConfiguration(
        listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer()
        )
    ).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
    }

    fun sdpJson(desc: SessionDescription): JSONObject =
        JSONObject().put("sdpType", desc.type.canonicalForm()).put("sdp", desc.description)

    fun sdpFrom(payload: JSONObject, fallback: String) = SessionDescription(
        SessionDescription.Type.fromCanonicalForm(payload.optString("sdpType", fallback)),
        payload.optString("sdp")
    )

    fun iceJson(c: IceCandidate): JSONObject =
        JSONObject().put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex).put("candidate", c.sdp)

    fun iceFrom(payload: JSONObject) =
        IceCandidate(payload.optString("sdpMid"), payload.optInt("sdpMLineIndex"), payload.optString("candidate"))

    open class Sdp(
        private val onCreated: (SessionDescription) -> Unit = {},
        private val onSet: () -> Unit = {},
        private val onError: (String) -> Unit = {}
    ) : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) = onCreated(desc)
        override fun onSetSuccess() = onSet()
        override fun onCreateFailure(error: String) = onError(error)
        override fun onSetFailure(error: String) = onError(error)
    }

    /** Observador con todo vacío, para sobrescribir solo lo necesario. */
    open class Observer : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
        override fun onIceCandidate(candidate: IceCandidate) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(channel: DataChannel) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
    }

    /** Audio de la pantalla: PCM 16 bits, 48 kHz, mono, en paquetes de 10 ms por un canal de datos. */
    const val AUDIO_RATE = 48_000
    const val AUDIO_PACKET_BYTES = 960
}
