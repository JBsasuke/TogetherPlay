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
    @Volatile private var hls = false

    val port: Int get() = server?.localPort ?: requestedPort
    /** Con HLS la ruta termina en .m3u8 para que el reproductor lo reconozca. */
    val path: String get() = "/$token/stream" + if (hls) ".m3u8" else ""

    /** URL que debe abrir un reproductor. [host] = 127.0.0.1 o la IP de Tailscale. */
    fun urlFor(host: String): String = "http://$host:$port$path"

    @Synchronized
    fun start() {
        if (server != null) return
        // Restos de una sesión anterior que no se cerró bien.
        cacheDir.listFiles { f -> f.name.startsWith("relay-") || f.name.startsWith("hls-") }?.forEach { it.deleteRecursively() }
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
        replaceSource(null, isHls = false)
        try { server?.close() } catch (_: IOException) {}
        server = null
    }

    /**
     * Abre el contenido una vez para ver en qué formato lo entrega el proveedor y lo
     * comparte del modo que corresponde: HLS (m3u8), canal en vivo (TS) o archivo.
     * Devuelve [FORMAT_HLS] o [FORMAT_PROGRESSIVE]. Lanza IOException con el motivo si no se pudo.
     */
    fun share(
        upstreamUrl: String,
        live: Boolean?,
        agent: String = userAgent,
        headers: Map<String, String> = emptyMap()
    ): String {
        var last: IOException? = null
        for (attempt in 0 until 2) {
            try {
                val up = openUpstream(upstreamUrl, agent, null, headers)
                var isLive = live ?: false
                try {
                    // Si no se sabe si es en vivo: sin tamaño conocido se trata como transmisión en vivo.
                    if (live == null) isLive = up.conn.contentLengthLong < 0
                    val buf = ByteArray(16 * 1024)
                    val n = readSome(up.input, buf)
                    val probe = UpstreamProbe.of(up, buf, n)
                    if (HlsSource.isPlaylist(up.conn.contentType, buf, n)) {
                        val body = java.io.ByteArrayOutputStream()
                        if (n > 0) body.write(buf, 0, n)
                        while (true) {
                            val r = up.input.read(buf)
                            if (r < 0) break
                            body.write(buf, 0, r)
                            if (body.size() > HlsSource.MAX_PLAYLIST_BYTES) throw IOException("lista HLS demasiado grande")
                        }
                        cacheDir.mkdirs()
                        replaceSource(
                            HlsSource(upstreamUrl, up.finalUrl, body.toByteArray(), agent, "/$token/hls/", cacheDir, probe, log, headers),
                            isHls = true
                        )
                        log("Formato detectado: HLS")
                        return FORMAT_HLS
                    }
                    probe.notVideoReason()?.let { throw IOException(it) }
                } finally {
                    up.close()
                }
                // Se deja al proveedor liberar la conexión de prueba antes de abrir la definitiva.
                Thread.sleep(REOPEN_PAUSE_MS)
                if (isLive) shareLive(upstreamUrl, agent, headers) else shareVod(upstreamUrl, agent, headers)
                log("Formato detectado: " + if (isLive) "TS en vivo" else "archivo")
                return if (isLive) FORMAT_LIVE else FORMAT_PROGRESSIVE
            } catch (e: IOException) {
                last = e
                if (attempt == 0) Thread.sleep(1200)
            }
        }
        throw last ?: IOException("no se pudo abrir el contenido")
    }

    /** Comparte un canal en vivo (URL .ts del proveedor). */
    fun shareLive(upstreamUrl: String, agent: String = userAgent, headers: Map<String, String> = emptyMap()) =
        replaceSource(LiveSource(upstreamUrl, agent, log, headers), isHls = false)

    /** Comparte un archivo de este equipo (p. ej. uno que abrió Kodi desde su almacenamiento). */
    fun shareLocal(channel: FileChannel, size: Long, contentType: String, onClose: () -> Unit) =
        replaceSource(LocalSource(channel, size, contentType, onClose), isHls = false)

    /** Comparte un episodio o película (archivo .mkv/.mp4 del proveedor). */
    fun shareVod(upstreamUrl: String, agent: String = userAgent, headers: Map<String, String> = emptyMap()) {
        cacheDir.mkdirs()
        replaceSource(VodSource(upstreamUrl, agent, cacheDir, log, headers), isHls = false)
    }

    private fun readSome(input: java.io.InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
            if (off >= 1024) break
        }
        return off
    }

    /** Deja de compartir y libera la conexión con el proveedor. */
    fun stopSharing() = replaceSource(null, isHls = false)

    @Synchronized
    private fun replaceSource(next: Source?, isHls: Boolean = false) {
        val old = source
        source = next
        hls = isHls
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
                // Diagnóstico: qué respondió el proveedor (sin credenciales).
                if (req.path.substringBefore('?') == "/$token/info") {
                    val body = (src?.info() ?: "{}").toByteArray(Charsets.UTF_8)
                    out.write(head("200 OK", listOf(
                        "Content-Type" to "application/json; charset=utf-8",
                        "Content-Length" to body.size.toString(),
                        "Cache-Control" to "no-store"
                    )))
                    out.write(body)
                    return
                }
                val p = req.path.substringBefore('?')
                val ours = p == "/$token/stream" || p == "/$token/stream.m3u8" || p.startsWith("/$token/hls/")
                if (!ours || src == null) {
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
        const val FORMAT_HLS = "hls"
        const val FORMAT_PROGRESSIVE = "progressive"
        const val FORMAT_LIVE = "live"
        private const val REOPEN_PAUSE_MS = 600L

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
    /** JSON con lo que respondió el proveedor la última vez, o null si aún no hay nada. */
    fun info(): String?
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

internal fun head(status: String, headers: List<Pair<String, String>>): ByteArray {
    val sb = StringBuilder("HTTP/1.1 ").append(status).append("\r\n")
    for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
    sb.append("Connection: close\r\n\r\n")
    return sb.toString().toByteArray(Charsets.ISO_8859_1)
}

private fun simpleResponse(code: Int, text: String): ByteArray =
    head("$code $text", listOf("Content-Length" to "0"))

/** 503 (ExoPlayer no lo reintenta) con el motivo en X-Relay-Error, para que el reproductor lo muestre. */
internal fun relayError(reason: String): ByteArray {
    val clean = reason.replace(Regex("[\\r\\n]+"), " ").take(300)
    // Codificada para que los acentos lleguen bien; el reproductor la decodifica.
    val encoded = java.net.URLEncoder.encode(clean, "UTF-8")
    return head("503 Service Unavailable", listOf("Content-Length" to "0", "X-Relay-Error" to encoded))
}

// ---------------------------------------------------------------------------
// Conexión con el proveedor
// ---------------------------------------------------------------------------

internal class Upstream(val conn: HttpURLConnection, val input: InputStream, val finalUrl: URL) : Closeable {
    override fun close() {
        // disconnect() primero: cerrar el stream antes intentaría leer el resto de la respuesta.
        conn.disconnect()
        try { input.close() } catch (_: IOException) {}
    }
}

/** Abre [url] siguiendo redirecciones (los paneles Xtream suelen redirigir a otro host). */
internal fun openUpstream(
    url: String,
    userAgent: String,
    rangeStart: Long?,
    extra: Map<String, String> = emptyMap()
): Upstream {
    var current = URL(url)
    for (hop in 0 until 6) {
        val c = current.openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", userAgent)
        c.setRequestProperty("Accept-Encoding", "identity")
        // Cabeceras que pasó la app de origen (Referer, Cookie, User-Agent propio...).
        for ((k, v) in extra) c.setRequestProperty(k, v)
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
        return Upstream(c, c.inputStream, current)
    }
    throw IOException("Demasiadas redirecciones")
}

/** Lo que respondió el proveedor al abrir un contenido, para detectar si no es video. */
internal class UpstreamProbe(
    private val status: Int,
    private val contentType: String?,
    private val length: Long,
    private val host: String,
    private val head: ByteArray
) {
    /** Motivo legible si lo recibido claramente no es un video; null si parece video. */
    fun notVideoReason(): String? {
        if (head.isEmpty()) return "el proveedor no envió datos (respuesta vacía)"
        val text = String(head, 0, minOf(head.size, 512), Charsets.ISO_8859_1).trimStart()
        if (text.startsWith("#EXTM3U")) {
            return "el proveedor envía este contenido como lista HLS (m3u8), que todavía no está soportada"
        }
        val sample = head.take(minOf(head.size, 256))
        val printable = sample.all { b -> val c = b.toInt() and 0xff; c == 9 || c == 10 || c == 13 || c in 32..126 }
        val ct = contentType?.lowercase().orEmpty()
        if (ct.startsWith("text/") || ct.contains("json") || ct.contains("html") || (printable && sample.size >= 8)) {
            val snippet = text.replace(Regex("\\s+"), " ").take(90)
            return "el proveedor envió texto en lugar de video: «$snippet»"
        }
        return null
    }

    fun json(): String {
        fun q(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val ascii = head.take(64).joinToString("") { b ->
            val c = b.toInt() and 0xff
            if (c in 32..126) c.toChar().toString() else "."
        }
        val hex = head.take(16).joinToString(" ") { "%02x".format(it) }
        return "{" +
            "\"status\":$status," +
            "\"contentType\":${q(contentType ?: "")}," +
            "\"length\":$length," +
            "\"host\":${q(host)}," +
            "\"startAscii\":${q(ascii)}," +
            "\"startHex\":${q(hex)}" +
            "}"
    }

    companion object {
        fun of(up: Upstream, buf: ByteArray, n: Int) = UpstreamProbe(
            up.conn.responseCode,
            up.conn.contentType,
            up.conn.contentLengthLong,
            up.finalUrl.host ?: "",
            buf.copyOf(maxOf(n, 0).coerceAtMost(buf.size))
        )
    }
}

// ---------------------------------------------------------------------------
// En vivo: una conexión al proveedor, repartida a todos los conectados
// ---------------------------------------------------------------------------

internal class LiveSource(
    private val url: String,
    private val userAgent: String,
    private val log: (String) -> Unit,
    private val headers: Map<String, String> = emptyMap()
) : Source {
    private val lock = Object()
    private val subscribers = CopyOnWriteArrayList<ArrayBlockingQueue<ByteArray>>()
    private var pump: Thread? = null
    @Volatile private var upstream: Upstream? = null
    @Volatile private var closed = false
    /** Motivo por el que el proveedor no entregó el canal. */
    @Volatile private var failure: String? = null
    @Volatile private var probe: UpstreamProbe? = null

    override fun info(): String? = probe?.json()

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
            var fatal = false
            try {
                val up = openUpstream(url, userAgent, null, headers)
                everConnected = true
                upstream = up
                var first = true
                while (!closed && subscribers.isNotEmpty()) {
                    val n = readChunk(up.input, buf)
                    if (first) {
                        first = false
                        val p = UpstreamProbe.of(up, buf, n)
                        probe = p
                        val reason = p.notVideoReason()
                        if (reason != null) {
                            // Reintentar no cambia nada: se avisa ya.
                            failure = reason
                            fatal = true
                            log("Canal no válido: $reason")
                            break
                        }
                    }
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
            if (fatal || failures >= (if (everConnected) MAX_FAILURES else FIRST_OPEN_TRIES)) {
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
    private val log: (String) -> Unit,
    private val headers: Map<String, String> = emptyMap()
) : Source {
    private val file: File = File.createTempFile("relay-", ".cache", cacheDir)
    private val channel: FileChannel = java.io.RandomAccessFile(file, "rw").channel

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
    @Volatile private var probe: UpstreamProbe? = null

    override fun info(): String? = probe?.json()

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
                // Desde el principio se pide sin Range, igual que un reproductor normal.
                val up = openUpstream(url, userAgent, if (start == 0L) null else start, headers)
                upstream = up
                // Se lee el primer trozo antes de anunciar el archivo, para comprobar que es video.
                val firstN = up.input.read(buf)
                if (start == 0L) {
                    val p = UpstreamProbe.of(up, buf, firstN)
                    probe = p
                    val reason = p.notVideoReason()
                    if (reason != null) {
                        log("Contenido no válido: $reason")
                        synchronized(lock) {
                            error = reason
                            idle = true
                            lock.notifyAll()
                        }
                        continue
                    }
                }
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
                var pendingFirst: Int? = firstN
                while (!restart) {
                    val n = pendingFirst ?: up.input.read(buf)
                    pendingFirst = null
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

// ---------------------------------------------------------------------------
// Archivo de este equipo
// ---------------------------------------------------------------------------

internal class LocalSource(
    private val channel: FileChannel,
    private val size: Long,
    private val contentType: String,
    private val onClose: () -> Unit
) : Source {
    override fun info(): String? = null

    override fun serve(req: Request, out: OutputStream) {
        val range = req.headers["range"]?.let { Regex("""bytes=(\d*)-(\d*)""").find(it) }
        var start = 0L
        var end = size - 1
        if (range != null) {
            val a = range.groupValues[1].toLongOrNull()
            val b = range.groupValues[2].toLongOrNull()
            if (a == null && b != null) start = maxOf(0, size - b) else {
                start = a ?: 0L
                end = minOf(b ?: (size - 1), size - 1)
            }
            if (start >= size || end < start) {
                out.write(head("416 Range Not Satisfiable", listOf("Content-Range" to "bytes */$size", "Content-Length" to "0")))
                return
            }
            out.write(head("206 Partial Content", listOf(
                "Content-Type" to contentType, "Accept-Ranges" to "bytes",
                "Content-Length" to (end - start + 1).toString(), "Content-Range" to "bytes $start-$end/$size"
            )))
        } else {
            out.write(head("200 OK", listOf(
                "Content-Type" to contentType, "Accept-Ranges" to "bytes", "Content-Length" to size.toString()
            )))
        }
        if (req.method == "HEAD") return
        val buf = ByteBuffer.allocate(64 * 1024)
        var pos = start
        while (pos <= end) {
            buf.clear()
            buf.limit(minOf(buf.capacity().toLong(), end - pos + 1).toInt())
            val n = channel.read(buf, pos)
            if (n <= 0) break
            out.write(buf.array(), 0, n)
            pos += n
        }
    }

    override fun close() {
        try { channel.close() } catch (_: IOException) {}
        onClose()
    }
}
