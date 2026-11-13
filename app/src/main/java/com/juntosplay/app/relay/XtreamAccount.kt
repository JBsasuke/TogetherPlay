package com.juntosplay.app.relay

import java.net.URLEncoder

/**
 * Cuenta Xtream Codes: arma las URL de la API y de los streams.
 * [server] es la dirección del panel, por ejemplo "http://proveedor.com:8080".
 */
data class XtreamAccount(val server: String, val username: String, val password: String) {
    private val base = server.trim().trimEnd('/')
    private val user = enc(username)
    private val pass = enc(password)

    /** Canal en vivo en MPEG-TS, para [StreamRelay.shareLive]. */
    fun liveUrl(streamId: Int): String = "$base/live/$user/$pass/$streamId.ts"

    /** Película, para [StreamRelay.shareVod]. [extension] viene en get_vod_streams (container_extension). */
    fun movieUrl(streamId: Int, extension: String): String = "$base/movie/$user/$pass/$streamId.$extension"

    /** Episodio de serie, para [StreamRelay.shareVod]. El id y la extensión vienen en get_series_info. */
    fun episodeUrl(episodeId: String, extension: String): String = "$base/series/$user/$pass/$episodeId.$extension"

    /**
     * URL de player_api.php. Acciones útiles: get_live_categories, get_live_streams,
     * get_vod_categories, get_vod_streams, get_series_categories, get_series,
     * get_series_info (con "series_id"). Sin acción devuelve los datos de la cuenta.
     */
    fun apiUrl(action: String? = null, vararg params: Pair<String, String>): String {
        val sb = StringBuilder("$base/player_api.php?username=$user&password=$pass")
        if (action != null) sb.append("&action=").append(enc(action))
        for ((k, v) in params) sb.append('&').append(enc(k)).append('=').append(enc(v))
        return sb.toString()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
