package com.juntosplay.app

import android.content.Context
import android.net.Uri
import com.juntosplay.app.relay.StreamRelay
import java.io.FileInputStream
import java.io.IOException

/** Prepara el relay para compartir un contenido y avisa al otro equipo. */
object Sharing {
    data class Prepared(val localUrl: String, val remoteUrl: String, val kind: String, val title: String) {
        /** El relay conserva su ruta, pero ambos equipos tienen que abrir el nuevo contenido. */
        fun withPlaybackId(): Prepared {
            val id = java.util.UUID.randomUUID().toString()
            fun identified(url: String) = Uri.parse(url).buildUpon().appendQueryParameter("playback", id).build().toString()
            return copy(localUrl = identified(localUrl), remoteUrl = identified(remoteUrl))
        }
    }

    /** Dirección por la que el otro equipo llega a este, según la red elegida. */
    fun relayHost(context: Context): String {
        val prefs = RoomSession.prefs(context)
        // Tailscale: la app de Tailscale si está conectada; si no, el integrado de TogetherPlay.
        val tailscale = StreamRelay.tailscaleAddress() ?: TailnetBridge.ip()
        return when (prefs.getString(NetworkMode.PREF, NetworkMode.AUTO)) {
            NetworkMode.TAILSCALE -> tailscale
                ?: throw IOException("Tailscale no está conectado. Vincula este equipo en «Este equipo → Tailscale» o abre la app de Tailscale")
            NetworkMode.WIFI -> StreamRelay.localAddress()
                ?: throw IOException("no encuentro la red Wi-Fi o de cable de este equipo")
            else -> tailscale ?: StreamRelay.localAddress()
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
        headers: Map<String, String> = emptyMap(),
        active: () -> Boolean = { true }
    ): Prepared {
        val host = relayHost(context)
        val relay = RoomSession.relay(context)
        relay.start()
        TailnetBridge.serveRelay()
        val agent = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
            ?: UserAgents.current(RoomSession.prefs(context))
        val format = relay.share(upstreamUrl, live, agent, headers.filterKeys { !it.equals("User-Agent", true) }, active)
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
        TailnetBridge.serveRelay()
        relay.shareLocal(channel, size, type) {
            try { stream.close() } catch (_: IOException) {}
            try { pfd.close() } catch (_: IOException) {}
        }
        return Prepared(relay.urlFor("127.0.0.1"), relay.urlFor(host), "vod", title)
    }

    /** Avisa al otro equipo (hilo de UI). Devuelve false si no hay conexión con la sala. */
    /**
     * [watchKey]: la clave con que se guarda por dónde vas en este título. Viaja con el aviso para que el otro equipo
     * también lo anote: así, lo que dejaron a medias viendo juntos se puede reanudar desde cualquiera de los dos.
     */
    fun announce(p: Prepared, startMs: Long = 0, manualOnly: Boolean = false, watchKey: String? = null): Boolean {
        val remote = Uri.parse(p.remoteUrl).buildUpon()
        if (p.kind == "vod" && startMs > 0) remote.appendQueryParameter("resume_ms", startMs.toString())
        if (p.kind == "vod" && !watchKey.isNullOrBlank()) remote.appendQueryParameter("watch_key", watchKey)
        if (manualOnly || PlaybackAccess.marked(p.title)) {
            remote.appendQueryParameter(PlaybackAccess.MANUAL_SHARE_PARAM, "1")
        }
        val remoteUrl = remote.build().toString()
        RoomSession.currentShare = RoomSession.Share(remoteUrl, p.kind, p.title)
        val client = RoomSession.client ?: return false
        client.sendRelayShare(remoteUrl, p.kind, p.title)
        return true
    }
}
