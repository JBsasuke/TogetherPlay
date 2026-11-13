package com.juntosplay.app

import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class RoomClient(
    private val onMessage: (JSONObject) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onConnected: () -> Unit = {}
) {
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null

    /** Último aviso de relay enviado, por si el servidor no lo conoce y hay que reenviarlo. */
    @Volatile private var pendingRelay: JSONObject? = null
    /** El servidor es una versión anterior sin relay_share: los avisos van dentro de la señalización WebRTC. */
    @Volatile private var legacyRelay = false

    fun connect(serverUrl: String) {
        if (!serverUrl.startsWith("wss://") && !serverUrl.startsWith("ws://")) {
            onStatus("La dirección debe comenzar con wss:// o ws://")
            return
        }
        val request = Request.Builder().url(serverUrl.trim()).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onStatus("Conectado al servidor")
                onConnected()
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = try { JSONObject(text) } catch (_: Exception) {
                    onStatus("Respuesta del servidor no válida")
                    return
                }
                handle(msg)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onStatus("Error de conexión: ${t.message ?: "sin detalles"}")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onStatus("Conexión cerrada")
            }
        })
    }

    private fun handle(msg: JSONObject) {
        val type = msg.optString("type")
        // Servidor sin la versión nueva: responde "Tipo de mensaje desconocido" a relay_share.
        if (type == "error" && msg.optString("message").startsWith("Tipo de mensaje desconocido")) {
            val relay = pendingRelay
            if (relay != null) {
                pendingRelay = null
                legacyRelay = true
                sendTunneled(relay)
                return
            }
        }
        if (type == TUNNEL_TYPE) {
            val inner = msg.optJSONObject("payload")?.optJSONObject(TUNNEL_KEY)
            if (inner != null) { onMessage(inner); return }
        }
        onMessage(msg)
    }

    fun createRoom() = send(JSONObject().put("type", "create_room"))
    fun joinRoom(code: String) = send(JSONObject().put("type", "join_room").put("code", code.trim().uppercase()))
    fun sendPlayback(action: String, positionMs: Long = 0L) {
        send(JSONObject().put("type", "playback").put("action", action).put("positionMs", positionMs))
    }
    /** Avisa al otro televisor que abra [relayUrl] (kind: "live" o "vod"). */
    fun sendRelayShare(relayUrl: String, kind: String, title: String = "") {
        sendRelay(JSONObject().put("type", "relay_share").put("url", relayUrl).put("kind", kind).put("title", title))
    }
    fun sendRelayStop() = sendRelay(JSONObject().put("type", "relay_stop"))

    private fun sendRelay(msg: JSONObject) {
        if (legacyRelay) sendTunneled(msg)
        else {
            pendingRelay = msg
            send(msg)
        }
    }

    private fun sendTunneled(msg: JSONObject) {
        send(JSONObject().put("type", TUNNEL_TYPE).put("payload", JSONObject().put(TUNNEL_KEY, msg)))
    }

    private fun send(json: JSONObject) {
        if (socket?.send(json.toString()) != true) onStatus("No hay conexión activa con el servidor")
    }
    fun close() {
        socket?.close(1000, "Cliente cerrando")
        socket = null
        http.dispatcher.executorService.shutdown()
    }

    private companion object {
        // Tipo que el servidor anterior ya reenvía tal cual al otro miembro de la sala.
        const val TUNNEL_TYPE = "webrtc_offer"
        const val TUNNEL_KEY = "togetherplay_relay"
    }
}
