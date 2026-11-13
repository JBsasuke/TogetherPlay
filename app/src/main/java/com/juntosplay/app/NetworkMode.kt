package com.juntosplay.app

/** Por dónde viaja el video entre los dos televisores. */
object NetworkMode {
    const val PREF = "network_mode"
    /** Tailscale si está conectado; si no, la red de casa. */
    const val AUTO = "auto"
    /** Misma red Wi-Fi o de cable (pruebas en casa). */
    const val WIFI = "wifi"
    /** Entre casas distintas, por Tailscale (la app de Tailscale o el integrado de TogetherPlay). */
    const val TAILSCALE = "tailscale"
}
