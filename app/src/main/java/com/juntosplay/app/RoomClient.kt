package com.juntosplay.app

import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocket client for the JuntosPlay room relay.
 * Use a public wss:// endpoint for communication between countries.
 */
class RoomClient(
    private val onMessage: (JSONObject) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

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

    fun joinRoom(code: String) =
        send(JSONObject().put("type", "join_room").put("code", code.trim().uppercase()))

    fun sendPlayback(action: String, positionMs: Long = 0L) {
        send(JSONObject()
            .put("type", "playback")
            .put("action", action)
            .put("positionMs", positionMs))
    }

    private fun send(json: JSONObject) {
        val sent = socket?.send(json.toString()) ?: false
        if (!sent) onStatus("No hay conexión activa con el servidor")
    }

    fun close() {
        socket?.close(1000, "Cliente cerrando")
        socket = null
        http.dispatcher.executorService.shutdown()
    }
}
