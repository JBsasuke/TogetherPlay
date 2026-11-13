package com.juntosplay.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import com.juntosplay.app.relay.StreamRelay
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Tailscale integrado (nodo propio dentro de la app, sin la app de Tailscale).
 * La parte nativa es un AAR hecho con gomobile (carpeta tailscale/); se usa por
 * reflexión para que la app compile aunque no esté incluido.
 */
object TailnetBridge {
    data class Status(val state: String, val authUrl: String, val ip: String, val name: String, val tailnet: String)

    const val PREF_ENABLED = "tailscale_embedded"

    private val native: Class<*>? by lazy {
        try { Class.forName("tsbridge.Tsbridge") } catch (_: Throwable) { null }
    }

    /** Si esta compilación de la app trae Tailscale integrado. */
    val available: Boolean get() = native != null

    @Volatile private var started = false
    @Volatile private var lastStatus = Status("Stopped", "", "", "", "")
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private fun call(name: String, vararg args: Any): Any? {
        val c = native ?: throw IOException("esta versión de la app no trae Tailscale integrado")
        val m = c.methods.firstOrNull { it.name == name && it.parameterTypes.size == args.size }
            ?: throw IOException("función $name no encontrada")
        try {
            return m.invoke(null, *args)
        } catch (e: InvocationTargetException) {
            throw IOException(e.targetException?.message ?: "error de Tailscale", e.targetException)
        }
    }

    /** Arranca el nodo (bloquea un momento: llamar fuera del hilo de UI). */
    @Synchronized
    fun start(context: Context, authKey: String = "") {
        if (started) return
        val app = context.applicationContext
        updateInterfaces()
        val dir = File(app.filesDir, "tailscale").absolutePath
        call("start", dir, hostname(), authKey.trim())
        started = true
        RoomSession.prefs(app).edit().putBoolean(PREF_ENABLED, true).apply()
        watchNetwork(app)
    }

    /** Si quedó vinculado antes, se vuelve a encender al abrir la app. */
    fun autoStart(context: Context) {
        if (!available || started) return
        if (!RoomSession.prefs(context).getBoolean(PREF_ENABLED, false)) return
        val app = context.applicationContext
        Thread { try { start(app) } catch (_: Exception) {} }.start()
    }

    fun status(): Status {
        if (!started) return Status("Stopped", "", "", "", "").also { lastStatus = it }
        return try {
            val j = JSONObject(call("status") as String)
            Status(
                j.optString("state", "Starting"), j.optString("authURL"), j.optString("ip"),
                j.optString("name"), j.optString("tailnet")
            )
        } catch (_: Exception) {
            Status("Starting", "", "", "", "")
        }.also { lastStatus = it }
    }

    /** IP de Tailscale integrado si está funcionando. */
    fun ip(): String? {
        if (!started) return null
        val s = if (lastStatus.state == "Running" && lastStatus.ip.isNotBlank()) lastStatus else status()
        return s.ip.takeIf { s.state == "Running" && it.isNotBlank() }
    }

    /** Publica el relay en Tailscale para que el otro equipo llegue a él. */
    fun serveRelay() {
        if (ip() == null) return
        try { call("serve", StreamRelay.DEFAULT_PORT.toLong()) } catch (_: Exception) {}
    }

    /**
     * Si la dirección es de Tailscale y este equipo no tiene la app de Tailscale pero sí el
     * integrado, devuelve una dirección local que llega al otro equipo por el integrado.
     */
    fun rewriteForPlayback(url: String): String {
        val uri = Uri.parse(url)
        val host = uri.host ?: return url
        if (!isTailscaleIp(host) || StreamRelay.tailscaleAddress() != null || ip() == null) return url
        return try {
            val port = call("proxy", "$host:${if (uri.port > 0) uri.port else 80}") as Long
            uri.buildUpon().encodedAuthority("127.0.0.1:$port").build().toString()
        } catch (_: Exception) {
            url
        }
    }

    fun logout(context: Context) {
        try { call("logout") } catch (_: Exception) {}
        started = false
        RoomSession.prefs(context).edit().putBoolean(PREF_ENABLED, false).apply()
        File(context.applicationContext.filesDir, "tailscale").deleteRecursively()
        networkCallback?.let {
            try { (context.applicationContext.getSystemService(ConnectivityManager::class.java)).unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        networkCallback = null
    }

    private fun isTailscaleIp(host: String): Boolean = try {
        val a = InetAddress.getByName(host)
        a is Inet4Address && (a.address[0].toInt() and 0xff) == 100 && (a.address[1].toInt() and 0xc0) == 64
    } catch (_: Exception) {
        false
    }

    private fun hostname(): String {
        val model = (Build.MODEL ?: "android").lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(30)
        return "togetherplay-$model"
    }

    /** Go no puede leer las interfaces de red en Android 11 o más: se las pasa Android. */
    private fun updateInterfaces() {
        val sb = StringBuilder()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces() ?: return) {
                var flags = 0
                if (ni.isUp) flags = flags or 1           // net.FlagUp
                if (ni.isLoopback) flags = flags or 4     // net.FlagLoopback
                if (ni.isPointToPoint) flags = flags or 8 // net.FlagPointToPoint
                if (ni.supportsMulticast()) flags = flags or 16
                val addrs = ni.interfaceAddresses.mapNotNull { ia ->
                    val a = ia.address ?: return@mapNotNull null
                    val ip = a.hostAddress?.substringBefore('%') ?: return@mapNotNull null
                    "$ip/${ia.networkPrefixLength}"
                }.joinToString(",")
                sb.append(ni.name).append('|').append(ni.index).append('|')
                    .append(try { ni.mtu } catch (_: Exception) { 1500 }).append('|')
                    .append(flags).append('|').append(addrs).append('\n')
            }
            call("setInterfaces", sb.toString())
        } catch (_: Exception) {
        }
    }

    private fun watchNetwork(app: Context) {
        if (networkCallback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = updateInterfaces()
            override fun onLost(network: Network) = updateInterfaces()
        }
        try {
            app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (_: Exception) {
        }
    }
}
