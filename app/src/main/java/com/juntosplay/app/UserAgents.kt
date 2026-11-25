package com.juntosplay.app

import android.content.SharedPreferences
import com.juntosplay.app.relay.StreamRelay

/** Cómo se identifica la app al pedir un video (User-Agent). */
object UserAgents {
    @Suppress("UNUSED_PARAMETER")
    fun current(prefs: SharedPreferences): String = StreamRelay.USER_AGENT
}
