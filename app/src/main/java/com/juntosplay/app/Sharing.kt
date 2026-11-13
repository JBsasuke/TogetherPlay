package com.juntosplay.app

import android.content.Context
import android.net.Uri
import com.juntosplay.app.relay.StreamRelay
import java.io.FileInputStream
import java.io.IOException

/** Prepara el relay para compartir un contenido y avisa al otro equipo. */
object Sharing {
    data class Prepared(val localUrl: String, val remoteUrl: String, val kind: String, val title: String)

    /** Dirección por la que el otro equipo llega a este, según la red elegida. */
    fun relayHost(context: Context): String {
        val prefs = RoomSession.prefs(context)
        return when (prefs.getString(NetworkMode.PREF, NetworkMode.AUTO)) {
            NetworkMode.TAILSCALE -> StreamRelay.tailscaleAddress()
                ?: throw IOException("no encuentro Tailscale; ábrelo y conéctalo en este equipo")
            NetworkMode.WIFI -> StreamRelay.localAddress()
                ?: throw IOException("no encuentro la red Wi-Fi o de cable de este equipo")
            else -> StreamRelay.tailscaleAddress() ?: StreamRelay.localAddress()
                ?: throw IOException("no encuentro ni Tailscale ni la red Wi-Fi de este equipo")
        }
    }

    /**
     * Comparte una dirección http(s). [live]: true/false si se sabe, null para detectarlo.
     * Bloquea (abre el contenido para ver su formato): llamar fuera del hilo de UI.
     */
    fun prepareUrl(
        context: Context,
        upstreamUrl: String,
        live: Boolean?,
        title: String,
        headers: Map<String, String> = emptyMap()
    ): Prepared {
        val host = relayHost(context)
        val relay = RoomSession.relay(context)
        relay.start()
        val agent = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
            ?: UserAgents.current(RoomSession.prefs(context))
        val format = relay.share(upstreamUrl, live, agent, headers.filterKeys { !it.equals("User-Agent", true) })
        val kind = if (format == StreamRelay.FORMAT_LIVE || live == true) "live" else "vod"
        return Prepared(relay.urlFor("127.0.0.1"), relay.urlFor(host), kind, title)
    }

    /** Comparte un archivo de este equipo (content:// o file://). Llamar fuera del hilo de UI. */
    fun prepareLocal(context: Context, uri: Uri, mimeType: String?, title: String): Prepared {
        val host = relayHost(context)
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("no se pudo abrir el archivo")
        val stream = FileInputStream(pfd.fileDescriptor)
        val channel = stream.channel
        val size = if (pfd.statSize > 0) pfd.statSize else channel.size()
        val type = mimeType ?: context.contentResolver.getType(uri) ?: "video/*"
        val relay = RoomSession.relay(context)
        relay.start()
        relay.shareLocal(channel, size, type) {
            try { stream.close() } catch (_: IOException) {}
            try { pfd.close() } catch (_: IOException) {}
        }
        return Prepared(relay.urlFor("127.0.0.1"), relay.urlFor(host), "vod", title)
    }

    /** Avisa al otro equipo (hilo de UI). Devuelve false si no hay conexión con la sala. */
    fun announce(p: Prepared): Boolean {
        RoomSession.currentShare = RoomSession.Share(p.remoteUrl, p.kind, p.title)
        val client = RoomSession.client ?: return false
        client.sendRelayShare(p.remoteUrl, p.kind, p.title)
        return true
    }
}
