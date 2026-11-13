package com.juntosplay.app

import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RoomClient(
    private val onMessage: (JSONObject) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onConnected: () -> Unit = {}
) {
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var serverUrl: String? = null
    @Volatile private var closedByUser = false
    /** Cada intento de conexión tiene su número; los avisos de intentos viejos se ignoran. */
    @Volatile private var generation = 0
    @Volatile private var failedAttempts = 0

    /** Último aviso de relay enviado, por si el servidor no lo conoce y hay que reenviarlo. */
    @Volatile private var pendingRelay: JSONObject? = null
    /** El servidor es una versión anterior sin relay_share: los avisos van dentro de la señalización WebRTC. */
    @Volatile private var legacyRelay = false

    fun connect(serverUrl: String) {
        val url = serverUrl.trim()
        if (!url.startsWith("wss://") && !url.startsWith("ws://")) {
            onStatus("La dirección debe comenzar con wss:// o ws://")
            return
        }
        this.serverUrl = url
        closedByUser = false
        open()
    }

    private fun open() {
        val url = serverUrl ?: return
        val gen = ++generation
        val request = Request.Builder().url(url).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen != generation) { webSocket.cancel(); return }
                failedAttempts = 0
                onStatus("Conectado al servidor")
                onConnected()
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen != generation) return
                val msg = try { JSONObject(text) } catch (_: Exception) {
                    onStatus("Respuesta del servidor no válida")
                    return
                }
                handle(msg)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (gen == generation) scheduleReconnect("Sin conexión con el servidor")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (gen == generation) scheduleReconnect("Conexión cerrada")
            }
        })
    }

    /** Vuelve a conectar sola, esperando un poco más en cada intento (2, 4, 8, 16 y luego 30 s). */
    private fun scheduleReconnect(reason: String) {
        if (closedByUser) return
        val delay = minOf(30L, 2L shl minOf(failedAttempts, 3))
        failedAttempts++
        onStatus("$reason · reconectando en $delay s…")
        try {
            scheduler.schedule({ if (!closedByUser) open() }, delay, TimeUnit.SECONDS)
        } catch (_: Exception) {
            // ya se cerró el cliente
        }
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
    /** [createIfMissing]: al reconectar, si el servidor se reinició y perdió la sala, la crea con el mismo código. */
    fun joinRoom(code: String, createIfMissing: Boolean = false) = send(
        JSONObject().put("type", "join_room").put("code", code.trim().uppercase()).put("create_if_missing", createIfMissing)
    )
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

    /** Señal WebRTC para compartir pantalla (el servidor la reenvía al otro equipo). */
    fun sendSignal(type: String, payload: JSONObject) {
        send(JSONObject().put("type", type).put("payload", payload))
    }

    /** Mensaje propio de la app; viaja por la vía que entienden todas las versiones del servidor. */
    fun sendCustom(msg: JSONObject) = sendTunneled(msg)

    private fun sendTunneled(msg: JSONObject) {
        send(JSONObject().put("type", TUNNEL_TYPE).put("payload", JSONObject().put(TUNNEL_KEY, msg)))
    }

    private fun send(json: JSONObject) {
        if (socket?.send(json.toString()) != true) onStatus("No hay conexión activa con el servidor")
    }
    fun close() {
        closedByUser = true
        generation++
        socket?.close(1000, "Cliente cerrando")
        socket = null
        scheduler.shutdownNow()
        http.dispatcher.executorService.shutdown()
    }

    private companion object {
        // Tipo que el servidor anterior ya reenvía tal cual al otro miembro de la sala.
        const val TUNNEL_TYPE = "webrtc_offer"
        const val TUNNEL_KEY = "togetherplay_relay"
    }
}
