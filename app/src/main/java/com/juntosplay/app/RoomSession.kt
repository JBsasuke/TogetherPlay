package com.juntosplay.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.juntosplay.app.relay.StreamRelay
import org.json.JSONObject
import java.io.File

/**
 * Conexión con la sala y estado compartido de toda la app. Vive mientras viva el
 * proceso, así funciona aunque la app se abra desde otra (Kodi, Stremio...) o
 * mientras se comparte la pantalla con la app en segundo plano.
 */
@SuppressLint("StaticFieldLeak") // solo se guarda el contexto de la aplicación
object RoomSession {
    data class Share(val url: String, val kind: String, val title: String)

    const val PREFS = "togetherplay_settings"
    const val DEFAULT_SERVER = "wss://togetherplay.onrender.com"

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null

    /** Conexión activa con el servidor de salas. */
    @Volatile var client: RoomClient? = null
        private set

    /** Último estado de la conexión, para mostrarlo al abrir la pantalla principal. */
    var lastStatus = "Desconectado"
        private set
    /** Si el otro equipo está en la sala (según lo último que dijo el servidor). */
    var peerPresent = false
        private set

    /** La pantalla principal escucha aquí para mostrar avisos (hilo de UI). */
    var uiListener: ((JSONObject) -> Unit)? = null
    var statusListener: ((String) -> Unit)? = null

    /** El reproductor abierto recibe aquí "playback" y "relay_stop" (hilo de UI). */
    var playbackListener: ((JSONObject) -> Unit)? = null

    /** Señales WebRTC para compartir pantalla: las recibe quien comparte o quien mira (hilo de UI). */
    var screenSignalListener: ((String, JSONObject) -> Unit)? = null
    /** Quien comparte pantalla vuelve a ofrecer la conexión cuando el otro entra. */
    var screenPeerJoined: (() -> Unit)? = null
    /** Quien mira cierra cuando el otro deja de compartir. */
    var screenStopListener: (() -> Unit)? = null
    /** Si este equipo está compartiendo su pantalla ahora. */
    @Volatile var screenHosting = false

    private val pendingScreenSignals = ArrayList<Pair<String, JSONObject>>()

    /** Lo que este equipo está compartiendo ahora, para reenviarlo si el otro entra tarde. */
    var currentShare: Share? = null

    private var relayInstance: StreamRelay? = null

    fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Conecta (o reconecta) con [url] y lo guarda como servidor de este equipo. */
    fun connect(context: Context, url: String) {
        app = context.applicationContext
        prefs(context).edit().putString("server_url", url).apply()
        client?.close()
        val c = RoomClient(
            onMessage = { msg -> main.post { dispatch(msg) } },
            onStatus = { status -> main.post { setStatus(status) } },
            onConnected = {
                main.post {
                    setStatus("Conectado al servidor")
                    val ctx = app ?: return@post
                    val saved = prefs(ctx).getString("room_code", "").orEmpty()
                    if (saved.isNotBlank()) {
                        client?.joinRoom(saved, createIfMissing = true)
                        setStatus("Recuperando sala $saved…")
                    }
                }
            }
        )
        client = c
        c.connect(url)
        SessionService.start(context)
    }

    /** Sale de la sala en este equipo: cierra la conexión, deja de compartir y quita el aviso. */
    fun disconnect() {
        client?.close()
        client = null
        peerPresent = false
        setStatus("Desconectado")
        Thread { stopRelay() }.start()
        app?.let { SessionService.stop(it) }
    }

    /** Conecta con el servidor guardado si todavía no hay conexión. Devuelve false si no hay sala guardada. */
    fun ensureConnected(context: Context): Boolean {
        app = context.applicationContext
        val prefs = prefs(context)
        if (client == null) {
            val url = prefs.getString("server_url", DEFAULT_SERVER) ?: DEFAULT_SERVER
            if (url.startsWith("ws://") || url.startsWith("wss://")) connect(context, url)
        }
        return prefs.getString("room_code", "").orEmpty().isNotBlank()
    }

    private fun setStatus(status: String) {
        lastStatus = status
        statusListener?.invoke(status)
    }

    private fun dispatch(msg: JSONObject) {
        val ctx = app
        when (msg.optString("type")) {
            "room_created" -> {
                ctx?.let { prefs(it).edit().putString("room_code", msg.optString("code")).apply() }
                peerPresent = false
            }
            "room_joined" -> {
                ctx?.let { prefs(it).edit().putString("room_code", msg.optString("code")).apply() }
                setStatus("Sala vinculada")
                // El servidor nuevo dice cuántos hay; con el anterior se asume que el otro puede estar.
                peerPresent = !msg.has("members") || msg.optInt("members") >= 2
                currentShare?.let { client?.sendRelayShare(it.url, it.kind, it.title) }
                if (screenHosting && peerPresent) screenPeerJoined?.invoke()
            }
            "peer_joined" -> {
                peerPresent = true
                // Si ya se está compartiendo algo, el que entra tarde lo recibe ahora.
                currentShare?.let { client?.sendRelayShare(it.url, it.kind, it.title) }
                if (screenHosting) screenPeerJoined?.invoke()
            }
            "peer_left" -> peerPresent = false
            "relay_share" -> {
                val url = msg.optString("url")
                if (ctx != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    val intent = PlayerActivity.intent(ctx, url, msg.optString("kind", "vod"), msg.optString("title"), host = false)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { ctx.startActivity(intent) } catch (_: Exception) {}
                }
            }
            "playback", "relay_stop" -> playbackListener?.invoke(msg)
            "screen_stop" -> {
                synchronized(pendingScreenSignals) { pendingScreenSignals.clear() }
                screenStopListener?.invoke()
            }
            "webrtc_offer", "webrtc_answer", "webrtc_ice" -> {
                val type = msg.optString("type")
                val payload = msg.optJSONObject("payload") ?: JSONObject()
                val listener = screenSignalListener
                if (listener != null) {
                    listener(type, payload)
                } else if (!screenHosting && ctx != null) {
                    // Llega una pantalla compartida: se abre el visor y se le pasan las señales al estar listo.
                    synchronized(pendingScreenSignals) {
                        if (type == "webrtc_offer") pendingScreenSignals.clear()
                        pendingScreenSignals.add(type to payload)
                    }
                    if (type == "webrtc_offer") {
                        try {
                            ctx.startActivity(Intent(ctx, ScreenViewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: Exception) {}
                    }
                }
            }
            "error" -> {
                // Avisos esperables mientras el otro equipo no está en la sala: no hace falta mostrarlos.
                if (msg.optString("message").startsWith("Se necesitan dos dispositivos")) {
                    peerPresent = false
                    return
                }
            }
        }
        uiListener?.invoke(msg)
    }

    /** El visor de pantalla toma las señales que llegaron antes de abrirse. */
    fun takePendingScreenSignals(): List<Pair<String, JSONObject>> = synchronized(pendingScreenSignals) {
        pendingScreenSignals.toList().also { pendingScreenSignals.clear() }
    }

    @Synchronized
    fun relay(context: Context): StreamRelay =
        relayInstance ?: StreamRelay(File(context.applicationContext.cacheDir, "relay")).also { relayInstance = it }

    private fun cacheRoot(context: Context) = File(context.applicationContext.cacheDir, "relay")

    fun cacheBytes(context: Context): Long =
        cacheRoot(context).walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /** Borra la caché del video si no se está compartiendo nada. Devuelve false si está en uso. */
    @Synchronized
    fun clearCache(context: Context): Boolean {
        if (currentShare != null) return false
        cacheRoot(context).listFiles()?.forEach { it.deleteRecursively() }
        return true
    }

    @Synchronized
    fun stopRelay() {
        relayInstance?.stop()
        relayInstance = null
        currentShare = null
    }
}
