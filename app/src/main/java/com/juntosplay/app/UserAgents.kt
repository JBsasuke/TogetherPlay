package com.juntosplay.app

import android.content.SharedPreferences
import com.juntosplay.app.relay.StreamRelay

/**
 * Cómo se identifica la app ante el proveedor IPTV. Algunos paneles solo
 * aceptan ciertos reproductores; si uno falla, se puede probar otro.
 */
object UserAgents {
    const val PREF = "xtream_user_agent"
    const val VLC = "vlc"
    const val TIVIMATE = "tivimate"
    const val BROWSER = "browser"

    val options = listOf(VLC to "VLC", TIVIMATE to "TiviMate", BROWSER to "Navegador")

    fun value(key: String?): String = when (key) {
        TIVIMATE -> "TiviMate/5.1.6 (Android 12)"
        BROWSER -> "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
        else -> StreamRelay.USER_AGENT
    }

    fun current(prefs: SharedPreferences): String = value(prefs.getString(PREF, VLC))
}
