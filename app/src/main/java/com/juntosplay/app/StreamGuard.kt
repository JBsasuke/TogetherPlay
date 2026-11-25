package com.juntosplay.app

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Semaphore

/**
 * Lleva la cuenta de los videos abiertos contra el servidor del video dentro de la app.
 *
 * Muchas cuentas permiten una sola conexión: antes de abrir un canal, película o serie se cierra
 * cualquier otro que siguiera abierto (la vista previa de la guía, una película en segundo plano…),
 * y todo queda anotado en el registro "TPConexion" para poder revisar qué estaba conectado.
 */
internal object StreamGuard {
    const val TAG = "TPConexion"

    private class Entry(val label: String, val close: () -> Unit, val since: Long)

    private val open = LinkedHashMap<Any, Entry>()

    /** Se abrió un video. [close] lo cierra del todo (se llama en el hilo principal). */
    fun opened(owner: Any, label: String, close: () -> Unit) {
        val count = synchronized(this) {
            open[owner] = Entry(label, close, SystemClock.elapsedRealtime())
            open.size
        }
        Log.i(TAG, "Abierto: $label · videos abiertos en la app: $count")
    }

    /** Se cerró el video de [owner] (ya no hay conexión con el proveedor). */
    fun closed(owner: Any) {
        val (entry, count) = synchronized(this) { open.remove(owner) to open.size }
        if (entry != null) {
            val seconds = (SystemClock.elapsedRealtime() - entry.since) / 1000
            Log.i(TAG, "Cerrado: ${entry.label} (estuvo ${seconds}s) · quedan abiertos: $count")
        }
    }

    /** Cierra todo lo que no sea de [owner] antes de abrir algo nuevo. Llamar en el hilo principal. */
    fun closeOthers(owner: Any) {
        val others = synchronized(this) { open.filterKeys { it !== owner }.toList() }
        for ((key, entry) in others) {
            Log.w(TAG, "Seguía abierto y se cierra antes de abrir otro: ${entry.label}")
            runCatching { entry.close() }
            synchronized(this) { open.remove(key) }
        }
    }

    /** Resumen para el registro cuando el proveedor rechaza una conexión. */
    fun describe(): String = synchronized(this) {
        if (open.isEmpty()) "ningún otro video abierto en esta app" else open.values.joinToString { it.label }
    }
}

/**
 * Ordena las consultas a la API del proveedor (player_api.php): como mucho dos a la vez y con una
 * pequeña pausa entre ellas. Muchos paneles bloquean un rato la cuenta (responden 403/404) si
 * reciben muchas consultas seguidas, y entonces tampoco abren los videos.
 * Si el proveedor responde con un rechazo, las consultas que no son urgentes (carátulas,
 * programación de canales cercanos) se pausan un minuto.
 */
internal object ProviderGate {
    private val permits = Semaphore(2, true)
    private val lock = Any()
    private var lastStart = 0L
    @Volatile private var pausedUntil = 0L
    private var strikes = 0
    private var lastStrike = 0L
    /** Consultas que no son urgentes hechas en el último minuto. */
    private val background = ArrayDeque<Long>()
    private const val GAP_MS = 400L
    private const val BACKGROUND_PER_MINUTE = 12
    private val blockCodes = setOf(401, 403, 404, 405, 429, 456, 458, 503, 509, 512, 513)

    /** Corre una consulta al proveedor respetando el orden. No usar en el hilo principal. */
    fun <T> call(what: String = "consulta", block: () -> T): T {
        permits.acquire()
        try {
            synchronized(lock) {
                val wait = lastStart + GAP_MS - SystemClock.elapsedRealtime()
                if (wait > 0) Thread.sleep(wait)
                lastStart = SystemClock.elapsedRealtime()
            }
            Log.i(StreamGuard.TAG, "Consulta al proveedor: $what")
            return block()
        } finally {
            permits.release()
        }
    }

    /**
     * ¿Se puede hacer ahora una consulta que no es urgente (programación de canales cercanos,
     * detalles al pasar por un título)? Como mucho [BACKGROUND_PER_MINUTE] por minuto y nunca
     * mientras el proveedor esté molesto.
     */
    fun tryBackground(): Boolean = synchronized(lock) {
        if (resting) return@synchronized false
        val now = SystemClock.elapsedRealtime()
        while (background.isNotEmpty() && now - background.first() > 60_000L) background.removeFirst()
        if (background.size >= BACKGROUND_PER_MINUTE) return@synchronized false
        background.addLast(now)
        true
    }

    /**
     * Anota la respuesta del proveedor. Un rechazo pausa lo no urgente: 2 min la primera vez,
     * 5 min si se repite enseguida y 15 min si sigue (cada intento durante un bloqueo lo alarga).
     */
    fun report(code: Int, what: String) {
        val now = SystemClock.elapsedRealtime()
        if (code in 200..299) {
            synchronized(lock) { if (now >= pausedUntil) strikes = 0 }
            return
        }
        Log.w(StreamGuard.TAG, "El proveedor respondió $code a $what")
        if (code !in blockCodes) return
        synchronized(lock) {
            strikes = if (now - lastStrike < 20 * 60_000L) strikes + 1 else 1
            lastStrike = now
            val pause = when (strikes) { 1 -> 2 * 60_000L; 2 -> 5 * 60_000L; else -> 15 * 60_000L }
            pausedUntil = maxOf(pausedUntil, now + pause)
            Log.w(StreamGuard.TAG, "La app hace pausa de consultas por ${pause / 60_000} min (rechazo nº $strikes)")
        }
    }

    /** true mientras conviene no hacer consultas que no son urgentes. */
    val resting: Boolean get() = SystemClock.elapsedRealtime() < pausedUntil

    /** Milisegundos que faltan para que termine la pausa (0 si no hay). */
    fun restMs(): Long = (pausedUntil - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

    /** Nombre de la consulta sin datos de la cuenta (para el registro). */
    fun label(url: String): String {
        val action = Regex("""[?&]action=([^&]+)""").find(url)?.groupValues?.get(1)
        return when {
            action != null -> action
            url.contains("xmltv.php") -> "xmltv"
            else -> "consulta"
        }
    }
}
