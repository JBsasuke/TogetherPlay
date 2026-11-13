package com.juntosplay.app

import android.content.Context
import com.juntosplay.app.relay.StreamRelay
import org.json.JSONObject
import java.io.File

/** Estado compartido entre pantallas: la conexión con la sala y el relay de video. */
object RoomSession {
    data class Share(val url: String, val kind: String, val title: String)

    /** Conexión activa con el servidor de salas (la crea MainActivity). */
    var client: RoomClient? = null

    /** El reproductor abierto recibe aquí los mensajes "playback" y "relay_stop" (hilo de UI). */
    var playbackListener: ((JSONObject) -> Unit)? = null

    /** Lo que este televisor está compartiendo ahora, para reenviarlo si el otro entra tarde. */
    var currentShare: Share? = null

    private var relayInstance: StreamRelay? = null

    @Synchronized
    fun relay(context: Context): StreamRelay =
        relayInstance ?: StreamRelay(File(context.applicationContext.cacheDir, "relay")).also { relayInstance = it }

    @Synchronized
    fun stopRelay() {
        relayInstance?.stop()
        relayInstance = null
        currentShare = null
    }
}
