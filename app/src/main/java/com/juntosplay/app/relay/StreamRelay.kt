package com.juntosplay.app.relay

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.util.TreeMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Relay de video para TogetherPlay.
 *
 * Abre el stream del proveedor IPTV UNA sola vez y lo reparte a varios
 * reproductores: el de este televisor (por 127.0.0.1) y el del otro televisor
 * (por Tailscale). Así el proveedor solo ve una conexión.
 *
 * - En vivo (MPEG-TS): los bytes se reparten tal cual a todos los conectados.
 * - Series y películas: el archivo se va guardando en caché a medida que se
 *   descarga y cada reproductor lee de la caché, con soporte de Range (seek).
 *
 * Solo hay un contenido compartido a la vez. Las credenciales del proveedor
 * nunca salen de este dispositivo: el otro lado solo recibe [urlFor].
 *
 * No depende de Android, así que se puede probar en una PC.
 */
class StreamRelay(
    private val cacheDir: File,
    private val requestedPort: Int = DEFAULT_PORT,
    private val userAgent: String = USER_AGENT,
    private val log: (String) -> Unit = {}
) {
    /** Parte secreta de la URL; sin ella el relay responde 404. */
    val token: String = ByteArray(9).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "relay-client").apply { isDaemon = true }
    }
    @Volatile private var server: ServerSocket? = null
    @Volatile private var source: Source? = null

    val port: Int get() = server?.localPort ?: requestedPort
    val path: String get() = "/$token/stream"

    /** URL que debe abrir un reproductor. [host] = 127.0.0.1 o la IP de Tailscale. */
    fun urlFor(host: String): String = "http://$host:$port$path"

    @Synchronized
    fun start() {
        if (server != null) return
        // Restos de una sesión anterior que no se cerró bien.
        cacheDir.listFiles { f -> f.name.startsWith("relay-") && f.name.endsWith(".cache") }?.forEach { it.delete() }
        val s = ServerSocket(requestedPort)
        server = s
        thread(name = "relay-accept", isDaemon = true) {
            while (!s.isClosed) {
                try {
                    val client = s.accept()
                    pool.execute { handle(client) }
                } catch (_: IOException) {
                    // socket cerrado en stop()
                }
            }
        }
        log("Relay escuchando en el puerto ${s.localPort}")
    }

    @Synchronized
    fun stop() {
        replaceSource(null)
        try { server?.close() } catch (_: IOException) {}
        server = null
    }

    /** Comparte un canal en vivo (URL .ts del proveedor). */
    fun shareLive(upstreamUrl: String) = replaceSource(LiveSource(upstreamUrl, userAgent, log))

    /** Comparte un episodio o película (archivo .mkv/.mp4 del proveedor). */
    fun shareVod(upstreamUrl: String) {
        cacheDir.mkdirs()
        replaceSource(VodSource(upstreamUrl, userAgent, cacheDir, log))
    }

    /** Deja de compartir y libera la conexión con el proveedor. */
    fun stopSharing() = replaceSource(null)

    @Synchronized
    private fun replaceSource(next: Source?) {
        val old = source
        source = next
        old?.close()
    }

    private fun handle(socket: Socket) {
        try {
            socket.use { s ->
                s.soTimeout = 15_000
                val req = readRequest(s.getInputStream()) ?: return
                s.soTimeout = 0
                val out = s.getOutputStream()
                if (req.method != "GET" && req.method != "HEAD") {
                    out.write(simpleResponse(405, "Method Not Allowed")); return
                }
                val src = source
                if (req.path.substringBefore('?') != path || src == null) {
                    out.write(simpleResponse(404, "Not Found")); return
                }
                src.serve(req, out)
                out.flush()
            }
        } catch (_: IOException) {
            // el reproductor cerró la conexión: normal al cambiar de canal o hacer seek
        }
    }

    companion object {
        const val DEFAULT_PORT = 8765

        /** Muchos proveedores IPTV rechazan agentes desconocidos; el de VLC casi siempre se acepta. */
        const val USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

        /** IP de Tailscale de este dispositivo (rango 100.64.0.0/10), o null si no hay. */
        fun tailscaleAddress(): String? {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (ni in interfaces) {
                for (addr in ni.inetAddresses) {
                    if (addr !is Inet4Address) continue
                    val b = addr.address
                    if ((b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 64) return addr.hostAddress
                }
            }
            return null
        }

        /** IP de la red de casa (Wi-Fi o cable), sin contar Tailscale ni otras VPN. */
        fun localAddress(): String? {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            var fallback: String? = null
            for (ni in interfaces) {
                if (!ni.isUp || ni.isLoopback) continue
                val name = ni.name.lowercase()
                if (name.startsWith("tun") || name.startsWith("tailscale") || name.startsWith("rmnet")) continue
                for (addr in ni.inetAddresses) {
                    if (addr !is Inet4Address || !addr.isSiteLocalAddress) continue
                    if (name.startsWith("wlan") || name.startsWith("eth")) return addr.hostAddress
                    if (fallback == null) fallback = addr.hostAddress
                }
            }
            return fallback
        }
    }
}

// ---------------------------------------------------------------------------
// HTTP mínimo
// ---------------------------------------------------------------------------

internal class Request(val method: String, val path: String, val headers: Map<String, String>)

internal interface Source : Closeable {
    fun serve(req: Request, out: OutputStream)
}

private fun readLine(input: InputStream, limit: Int): String? {
    val sb = StringBuilder()
    while (sb.length < limit) {
        val c = input.read()
        if (c < 0) return if (sb.isEmpty()) null else sb.toString()
        if (c == '\n'.code) return sb.toString().trimEnd('\r')
        sb.append(c.toChar())
    }
    throw IOException("línea demasiado larga")
}

private fun readRequest(input: InputStream): Request? {
    val first = readLine(input, 4096) ?: return null
    val parts = first.split(' ')
    if (parts.size < 2) return null
    val headers = HashMap<String, String>()
    for (i in 0 until 100) {
        val line = readLine(input, 4096) ?: break
        if (line.isEmpty()) break
        val idx = line.indexOf(':')
        if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
    }
    return Request(parts[0].uppercase(), parts[1], headers)
}

private fun head(status: String, headers: List<Pair<String, String>>): ByteArray {
    val sb = StringBuilder("HTTP/1.1 ").append(status).append("\r\n")
    for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
    sb.append("Connection: close\r\n\r\n")
    return sb.toString().toByteArray(Charsets.ISO_8859_1)
}

private fun simpleResponse(code: Int, text: String): ByteArray =
    head("$code $text", listOf("Content-Length" to "0"))

/** 503 (ExoPlayer no lo reintenta) con el motivo en X-Relay-Error, para que el reproductor lo muestre. */
private fun relayError(reason: String): ByteArray {
    val clean = reason.replace(Regex("[\\r\\n]+"), " ").take(300)
    return head("503 Service Unavailable", listOf("Content-Length" to "0", "X-Relay-Error" to clean))
}

// ---------------------------------------------------------------------------
// Conexión con el proveedor
// ---------------------------------------------------------------------------

internal class Upstream(val conn: HttpURLConnection, val input: InputStream) : Closeable {
    override fun close() {
        // disconnect() primero: cerrar el stream antes intentaría leer el resto de la respuesta.
        conn.disconnect()
        try { input.close() } catch (_: IOException) {}
    }
}

/** Abre [url] siguiendo redirecciones (los paneles Xtream suelen redirigir a otro host). */
internal fun openUpstream(url: String, userAgent: String, rangeStart: Long?): Upstream {
    var current = URL(url)
    for (hop in 0 until 6) {
        val c = current.openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", userAgent)
        c.setRequestProperty("Accept-Encoding", "identity")
        if (rangeStart != null) c.setRequestProperty("Range", "bytes=$rangeStart-")
        val code = c.responseCode
        if (code in 300..399) {
            val location = c.getHeaderField("Location")
            c.disconnect()
            if (location == null) throw IOException("Redirección sin destino")
            current = URL(current, location)
            continue
        }
        if (code != 200 && code != 206) {
            c.disconnect()
            throw IOException(
                when (code) {
                    401, 403 -> "el proveedor rechazó la conexión (HTTP $code). ¿Hay otra app usando la cuenta ahora mismo?"
                    404 -> "el proveedor no encontró ese contenido (HTTP 404)"
                    else -> "el proveedor respondió HTTP $code"
                }
            )
        }
        return Upstream(c, c.inputStream)
    }
    throw IOException("Demasiadas redirecciones")
}

// ---------------------------------------------------------------------------
// En vivo: una conexión al proveedor, repartida a todos los conectados
// ---------------------------------------------------------------------------

internal class LiveSource(
    private val url: String,
    private val userAgent: String,
    private val log: (String) -> Unit
) : Source {
    private val lock = Object()
    private val subscribers = CopyOnWriteArrayList<ArrayBlockingQueue<ByteArray>>()
    private var pump: Thread? = null
    @Volatile private var upstream: Upstream? = null
    @Volatile private var closed = false
    /** Motivo por el que el proveedor no entregó el canal. */
    @Volatile private var failure: String? = null

    override fun serve(req: Request, out: OutputStream) {
        val headers = head("200 OK", listOf("Content-Type" to "video/mp2t", "Cache-Control" to "no-store"))
        if (req.method == "HEAD") { out.write(headers); return }
        val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CHUNKS)
        synchronized(lock) {
            if (closed) throw IOException("cerrado")
            subscribers.add(queue)
            if (pump == null) {
                failure = null
                pump = thread(name = "relay-live", isDaemon = true) { pumpLoop() }
            }
        }
        try {
            // Se responde 200 solo cuando llega video; si el proveedor falla, el reproductor recibe el motivo.
            val first = queue.poll(FIRST_DATA_TIMEOUT_S, TimeUnit.SECONDS)
            if (first == null || first.isEmpty()) {
                out.write(relayError(failure ?: "el canal no envió video en $FIRST_DATA_TIMEOUT_S segundos"))
                return
            }
            out.write(headers)
            out.write(first)
            out.flush()
            var idleSeconds = 0
            while (!closed && idleSeconds < 30) {
                val chunk = queue.poll(1, TimeUnit.SECONDS)
                if (chunk == null) { idleSeconds++; continue }
                if (chunk.isEmpty()) break // fin de la transmisión
                idleSeconds = 0
                out.write(chunk)
            }
        } finally {
            synchronized(lock) {
                subscribers.remove(queue)
                // Sin espectadores: se suelta la conexión con el proveedor.
                if (subscribers.isEmpty()) upstream?.close()
            }
        }
    }

    private fun pumpLoop() {
        var failures = 0
        var everConnected = false
        val buf = ByteArray(CHUNK)
        while (true) {
            synchronized(lock) {
                if (closed || subscribers.isEmpty()) { pump = null; return }
            }
            var gotData = false
            try {
                val up = openUpstream(url, userAgent, null)
                everConnected = true
                upstream = up
                while (!closed && subscribers.isNotEmpty()) {
                    val n = readChunk(up.input, buf)
                    if (n <= 0) break
                    gotData = true
                    failures = 0
                    val chunk = buf.copyOf(n)
                    for (q in subscribers) {
                        if (!q.offer(chunk)) {
                            // Espectador lento: se descarta su atraso para que vuelva al directo.
                            q.clear()
                            q.offer(chunk)
                        }
                    }
                    if (n < buf.size) break
                }
            } catch (e: IOException) {
                failure = e.message ?: e.javaClass.simpleName
                if (!closed && subscribers.isNotEmpty()) log("Stream en vivo interrumpido: ${e.message}")
            } finally {
                upstream?.close()
                upstream = null
            }
            if (!gotData) failures++
            // Si nunca se pudo abrir, se avisa pronto en vez de dejar la pantalla en negro.
            if (failures >= (if (everConnected) MAX_FAILURES else FIRST_OPEN_TRIES)) {
                log("No se pudo abrir el canal tras $failures intentos")
                synchronized(lock) {
                    for (q in subscribers) { q.clear(); q.offer(ByteArray(0)) }
                    pump = null
                }
                return
            }
            Thread.sleep(minOf(1000L * maxOf(failures, 1), 5000L))
        }
    }

    /** Llena el buffer completo para mantener los trozos alineados a paquetes TS de 188 bytes. */
    private fun readChunk(input: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            for (q in subscribers) { q.clear(); q.offer(ByteArray(0)) }
        }
        upstream?.close()
    }

    private companion object {
        const val CHUNK = 188 * 64          // ~12 KB
        const val QUEUE_CHUNKS = 1024       // ~12 MB de margen por espectador
        const val MAX_FAILURES = 8
        const val FIRST_OPEN_TRIES = 3
        const val FIRST_DATA_TIMEOUT_S = 25L
    }
}

// ---------------------------------------------------------------------------
// Series / películas: descarga única con caché en disco y soporte de Range
// ---------------------------------------------------------------------------

internal class VodSource(
    private val url: String,
    private val userAgent: String,
    cacheDir: File,
    private val log: (String) -> Unit
) : Source {
    private val file: File = File.createTempFile("relay-", ".cache", cacheDir)
    private val channel: FileChannel =
        FileChannel.open(file.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE)

    private val lock = Object()
    /** Tramos ya descargados: inicio -> fin (exclusivo). */
    private val ranges = TreeMap<Long, Long>()
    private var total = -1L
    private var headersKnown = false
    private var contentType = "application/octet-stream"
    private var noRange = false          // el proveedor ignora Range: solo descarga secuencial
    private var fetchPos = 0L            // próximo byte que escribirá la descarga
    private var idle = true              // la descarga espera a que alguien pida algo
    private var wanted: Long? = null     // posición pedida por un reproductor fuera de lo descargado
    private var readerPos = 0L
    private var bytesThisConn = 0L
    private var connStartedAt = 0L
    private var error: String? = null
    private var closed = false
    private var fetcher: Thread? = null
    @Volatile private var upstream: Upstream? = null

    // --- estado de la caché (siempre con lock tomado) ---

    private fun cachedEnd(p: Long): Long {
        val e = ranges.floorEntry(p)
        return if (e != null && e.value > p) e.value else -1L
    }

    private fun addRange(start: Long, end: Long) {
        var ns = start
        var ne = end
        val lo = ranges.floorEntry(start)
        if (lo != null && lo.value >= start) { ns = lo.key; ne = maxOf(ne, lo.value) }
        var hi = ranges.ceilingEntry(ns)
        while (hi != null && hi.key <= ne) {
            ne = maxOf(ne, hi.value)
            ranges.remove(hi.key)
            hi = ranges.ceilingEntry(ns)
        }
        ranges[ns] = ne
    }

    /** Primer byte que falta, o -1 si el archivo está completo. */
    private fun firstGap(): Long {
        var p = 0L
        while (true) {
            val ce = cachedEnd(p)
            if (ce < 0) break
            p = ce
        }
        return if (total >= 0 && p >= total) -1L else p
    }

    private fun demand(p: Long) {
        // Solo se avisa cuando aparece un pedido nuevo: avisar en cada intento haría que
        // los reproductores en espera se despertaran entre sí sin dejar avanzar la descarga.
        val wake = wanted == null
        wanted = p
        if (fetcher == null) fetcher = thread(name = "relay-vod", isDaemon = true) { fetchLoop() }
        else if (wake) lock.notifyAll()
    }

    // --- descarga única desde el proveedor ---

    private fun fetchLoop() {
        var failures = 0
        val buf = ByteArray(64 * 1024)
        while (true) {
            var start = -1L
            synchronized(lock) {
                while (!closed && start < 0) {
                    val w = wanted
                    if (w != null) {
                        wanted = null
                        if (idle) { failures = 0; error = null }
                        idle = false
                        if (!noRange) fetchPos = w
                    }
                    if (idle) {
                        lock.wait()
                    } else if (noRange) {
                        if (firstGap() < 0) idle = true else { fetchPos = 0; start = 0 }
                    } else {
                        val ce = cachedEnd(fetchPos)
                        if (ce > fetchPos) fetchPos = ce
                        if (total >= 0 && fetchPos >= total) {
                            val gap = firstGap()
                            if (gap < 0) idle = true else { fetchPos = gap; start = gap }
                        } else {
                            start = fetchPos
                        }
                    }
                }
                if (closed) return
                bytesThisConn = 0
                connStartedAt = System.currentTimeMillis()
            }

            try {
                val up = openUpstream(url, userAgent, start)
                upstream = up
                synchronized(lock) {
                    if (closed) return
                    val code = up.conn.responseCode
                    if (code == 206) {
                        val cr = up.conn.getHeaderField("Content-Range") ?: ""
                        val m = Regex("""bytes (\d+)-\d+/(\d+)""").find(cr)
                        if (m == null || m.groupValues[1].toLong() != start) {
                            throw IOException("Content-Range inesperado: $cr")
                        }
                        total = m.groupValues[2].toLong()
                    } else {
                        // 200: el proveedor envía el archivo desde el principio.
                        total = up.conn.contentLengthLong
                        if (start != 0L) { noRange = true; fetchPos = 0 }
                    }
                    up.conn.contentType?.let { contentType = it }
                    headersKnown = true
                    lock.notifyAll()
                }

                var restart = false
                while (!restart) {
                    val n = up.input.read(buf)
                    if (n < 0) break
                    val pos = synchronized(lock) { if (closed) return; fetchPos }
                    val bb = ByteBuffer.wrap(buf, 0, n)
                    var written = 0
                    while (bb.hasRemaining()) written += channel.write(bb, pos + written)
                    synchronized(lock) {
                        if (closed) return
                        addRange(pos, pos + n)
                        fetchPos = pos + n
                        bytesThisConn += n
                        failures = 0
                        lock.notifyAll()
                        if (!noRange) {
                            val w = wanted
                            if (w != null) {
                                val served = cachedEnd(w) > w || (w >= fetchPos && w - fetchPos <= NEAR)
                                val ranEnough = bytesThisConn >= MIN_RUN ||
                                    System.currentTimeMillis() - connStartedAt >= MIN_RUN_MS
                                if (served) wanted = null else if (ranEnough) restart = true
                            }
                            // Se alcanzó un tramo ya descargado: saltar al siguiente hueco.
                            if (cachedEnd(fetchPos) > fetchPos) restart = true
                        }
                        // No adelantarse sin límite a lo que se está viendo.
                        while (!closed && !restart && wanted == null && fetchPos - readerPos > MAX_AHEAD) {
                            lock.wait(1000)
                        }
                    }
                }
                if (!restart) {
                    synchronized(lock) {
                        if (total < 0) total = fetchPos // longitud desconocida: el final es donde terminó
                        lock.notifyAll()
                    }
                }
            } catch (e: IOException) {
                val give = synchronized(lock) {
                    if (closed) return
                    failures++
                    // Si el archivo nunca se pudo abrir, se avisa pronto.
                    val limit = if (headersKnown) MAX_FAILURES else FIRST_OPEN_TRIES
                    if (failures >= limit) {
                        error = e.message ?: "no se pudo descargar del proveedor"
                        idle = true
                        lock.notifyAll()
                    }
                    failures >= limit
                }
                log("Descarga interrumpida (${e.message})" + if (give) "" else ", reintentando")
                if (!give) Thread.sleep(minOf(300L * failures, 2000L))
            } finally {
                upstream?.close()
                upstream = null
            }
            // Dar tiempo al proveedor a liberar la conexión anterior antes de abrir otra.
            Thread.sleep(RECONNECT_PAUSE_MS)
        }
    }

    // --- lectura para los reproductores ---

    private fun readAt(p: Long, buf: ByteArray, len: Int): Int {
        var n = 0
        synchronized(lock) {
            while (n == 0) {
                if (closed) throw IOException("cerrado")
                readerPos = p
                val ce = cachedEnd(p)
                if (ce > p) {
                    n = minOf(len.toLong(), ce - p).toInt()
                } else {
                    if (total >= 0 && p >= total) return -1
                    val e = error
                    if (e != null) { error = null; throw IOException(e) }
                    val near = !idle && p >= fetchPos && p - fetchPos <= NEAR
                    if (!near) demand(p)
                    lock.wait(500)
                }
            }
        }
        val bb = ByteBuffer.wrap(buf, 0, n)
        var read = 0
        while (bb.hasRemaining()) {
            val r = channel.read(bb, p + read)
            if (r < 0) throw IOException("caché truncada")
            read += r
        }
        return n
    }

    override fun serve(req: Request, out: OutputStream) {
        val range = req.headers["range"]?.let { Regex("""bytes=(\d*)-(\d*)""").find(it) }
        val reqStart = range?.groupValues?.get(1)?.toLongOrNull()
        val reqEnd = range?.groupValues?.get(2)?.toLongOrNull()

        val t: Long
        val type: String
        synchronized(lock) {
            if (!headersKnown) {
                demand(reqStart ?: 0L)
                val deadline = System.currentTimeMillis() + 30_000
                while (!headersKnown && !closed && error == null && System.currentTimeMillis() < deadline) {
                    lock.wait(500)
                }
            }
            if (!headersKnown) {
                val reason = error ?: "el proveedor no respondió a tiempo"
                error = null
                out.write(relayError(reason))
                return
            }
            t = total
            type = contentType
        }

        var pos: Long
        var remaining: Long
        if (t < 0) {
            // Longitud desconocida: se sirve de corrido desde el principio.
            pos = 0
            remaining = Long.MAX_VALUE
            out.write(head("200 OK", listOf("Content-Type" to type)))
        } else {
            val start: Long
            val end: Long
            if (range != null && reqStart == null && reqEnd != null) {
                start = maxOf(0, t - reqEnd)      // bytes=-N: los últimos N
                end = t - 1
            } else {
                start = reqStart ?: 0L
                end = minOf(reqEnd ?: (t - 1), t - 1)
            }
            if (start >= t || end < start) {
                out.write(head("416 Range Not Satisfiable",
                    listOf("Content-Range" to "bytes */$t", "Content-Length" to "0")))
                return
            }
            pos = start
            remaining = end - start + 1
            val headers = mutableListOf(
                "Content-Type" to type,
                "Accept-Ranges" to "bytes",
                "Content-Length" to remaining.toString()
            )
            if (range != null) {
                headers.add("Content-Range" to "bytes $start-$end/$t")
                out.write(head("206 Partial Content", headers))
            } else {
                out.write(head("200 OK", headers))
            }
        }
        if (req.method == "HEAD") return
        out.flush()

        val buf = ByteArray(64 * 1024)
        while (remaining > 0) {
            val n = readAt(pos, buf, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            pos += n
            remaining -= n
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
        upstream?.close()
        try { channel.close() } catch (_: IOException) {}
        file.delete()
    }

    private companion object {
        const val NEAR = 8L * 1024 * 1024          // si la descarga está a <8 MB, se espera en vez de reiniciarla
        const val MIN_RUN = 1024L * 1024           // mínimo por conexión antes de saltar a otra posición
        const val MIN_RUN_MS = 3000L
        const val MAX_AHEAD = 256L * 1024 * 1024   // máximo adelantado respecto a lo que se ve
        const val RECONNECT_PAUSE_MS = 300L
        const val MAX_FAILURES = 10
        const val FIRST_OPEN_TRIES = 3
    }
}
