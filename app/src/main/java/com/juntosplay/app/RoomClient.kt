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
                try { onMessage(JSONObject(text)) }
                catch (_: Exception) { onStatus("Respuesta del servidor no válida") }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onStatus("Error de conexión: ${t.message ?: "sin detalles"}")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onStatus("Conexión cerrada")
            }
        })
    }

    fun createRoom() = send(JSONObject().put("type", "create_room"))
    fun joinRoom(code: String) = send(JSONObject().put("type", "join_room").put("code", code.trim().uppercase()))
    fun sendPlayback(action: String, positionMs: Long = 0L) {
        send(JSONObject().put("type", "playback").put("action", action).put("positionMs", positionMs))
    }
    fun sendSignal(type: String, payload: JSONObject) {
        require(type in setOf("webrtc_offer", "webrtc_answer", "webrtc_ice"))
        send(JSONObject().put("type", type).put("payload", payload))
    }
    private fun send(json: JSONObject) {
        if (socket?.send(json.toString()) != true) onStatus("No hay conexión activa con el servidor")
    }
    fun close() {
        socket?.close(1000, "Cliente cerrando")
        socket = null
        http.dispatcher.executorService.shutdown()
    }
}
