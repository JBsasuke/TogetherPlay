package com.juntosplay.app.relay

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread

/**
 * Relay de HLS (m3u8). Las listas se reescriben para que cada dirección pase por
 * el relay, y cada lista o segmento se pide UNA vez al proveedor aunque lo pidan
 * los dos reproductores: el segundo lee lo que ya bajó (o está bajando) el primero.
 */
internal class HlsSource(
    private val mainUrl: String,
    mainFinalUrl: URL,
    mainBody: ByteArray,
    private val userAgent: String,
    /** Prefijo de las rutas del relay para los recursos, p. ej. "/<token>/hls/". */
    private val prefix: String,
    cacheDir: File,
    private val probe: UpstreamProbe?,
    private val log: (String) -> Unit,
    private val headers: Map<String, String> = emptyMap()
) : Source {
    private val dir = File(cacheDir, "hls-${System.nanoTime()}").apply { mkdirs() }
    private val lock = Object()
    private val urls = ArrayList<String>()
    private val ids = HashMap<String, Int>()
    private val resources = HashMap<String, Resource>()
    private val upstreamSlots = Semaphore(MAX_PARALLEL)
    @Volatile private var closed = false

    init {
        val main = Resource(mainUrl)
        main.setPlaylist(mainFinalUrl, mainBody)
        resources[mainUrl] = main
    }

    override fun info(): String? = probe?.json()

    override fun serve(req: Request, out: OutputStream) {
        val path = req.path.substringBefore('?')
        val url = if (path.startsWith(prefix)) {
            val id = path.removePrefix(prefix).substringBefore('/').toIntOrNull()
            synchronized(lock) { id?.let { urls.getOrNull(it) } }
        } else {
            mainUrl
        }
        if (url == null) {
            out.write(head("404 Not Found", listOf("Content-Length" to "0")))
            return
        }
        obtain(url).serveTo(req, out)
    }

    override fun close() {
        val all = synchronized(lock) {
            closed = true
            resources.values.toList().also { resources.clear() }
        }
        all.forEach { it.release() }
        dir.deleteRecursively()
    }

    // --- direcciones ---

    private fun proxied(absolute: String): String {
        val id = synchronized(lock) {
            ids.getOrPut(absolute) { urls.add(absolute); urls.size - 1 }
        }
        // Se conserva el nombre del archivo: el reproductor usa la extensión (.ts, .aac, .m4s...) como pista.
        val name = absolute.substringBefore('?').substringAfterLast('/')
            .filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
            .take(60)
            .ifEmpty { "r" }
        return "$prefix$id/$name"
    }

    private fun resolve(base: URL, ref: String): String = try {
        base.toURI().resolve(URI(ref.trim())).toString()
    } catch (_: Exception) {
        URL(base, ref.trim()).toString()
    }

    /** Cambia cada dirección de la lista (líneas y atributos URI="...") por una del relay. */
    private fun rewrite(text: String, base: URL): String {
        val uriAttr = Regex("""URI="([^"]*)"""")
        return text.lineSequence().joinToString("\n") { line ->
            val t = line.trim()
            when {
                t.isEmpty() -> line
                t.startsWith("#") -> uriAttr.replace(line) { m -> "URI=\"${proxied(resolve(base, m.groupValues[1]))}\"" }
                else -> proxied(resolve(base, t))
            }
        }
    }

    // --- recursos compartidos ---

    private fun obtain(url: String): Resource {
        val evicted = ArrayList<Resource>()
        val r = synchronized(lock) {
            if (closed) throw IOException("cerrado")
            val existing = resources[url]
            if (existing != null && existing.isFresh()) {
                existing
            } else {
                Resource(url).also { fresh ->
                    resources[url] = fresh
                    thread(name = "relay-hls", isDaemon = true) { fresh.download() }
                    evicted.addAll(evictLocked())
                    existing?.let { if (it.readers == 0) evicted.add(it) }
                }
            }
        }
        evicted.forEach { it.release() }
        return r
    }

    /** Quita de la caché lo más viejo que nadie está leyendo, si se pasa del límite. */
    private fun evictLocked(): List<Resource> {
        val removed = ArrayList<Resource>()
        var bytes = resources.values.sumOf { it.size() }
        val candidates = resources.values.filter { it.isMedia() && it.readers == 0 && it.isDone() }
            .sortedBy { it.lastUsed }
        for (c in candidates) {
            if (bytes <= MAX_CACHE_BYTES) break
            bytes -= c.size()
            resources.remove(c.url)
            removed.add(c)
        }
        return removed
    }

    private inner class Resource(val url: String) {
        private val rlock = Object()
        private val createdAt = System.currentTimeMillis()
        @Volatile var lastUsed = createdAt
        @Volatile var readers = 0
        private var ready = false
        private var playlist: ByteArray? = null
        private var staticPlaylist = false
        private var file: File? = null
        private var channel: FileChannel? = null
        private var written = 0L
        private var total = -1L
        private var contentType = "application/octet-stream"
        private var done = false
        private var failure: String? = null

        fun isFresh(): Boolean = synchronized(rlock) {
            when {
                failure != null -> false
                playlist != null && !staticPlaylist -> System.currentTimeMillis() - createdAt < LIVE_PLAYLIST_TTL_MS
                else -> true
            }
        }
        fun isMedia() = synchronized(rlock) { ready && playlist == null }
        fun isDone() = synchronized(rlock) { done || failure != null }
        fun size() = synchronized(rlock) { if (playlist == null) written else 0L }

        fun setPlaylist(finalUrl: URL, body: ByteArray) {
            val text = String(body, Charsets.UTF_8)
            val rewritten = rewrite(text, finalUrl).toByteArray(Charsets.UTF_8)
            synchronized(rlock) {
                // Las listas maestras y las de películas no cambian; las de canales en vivo sí.
                staticPlaylist = text.contains("#EXT-X-ENDLIST") || text.contains("#EXT-X-STREAM-INF") ||
                    text.contains("#EXT-X-PLAYLIST-TYPE:VOD")
                playlist = rewritten
                contentType = "application/vnd.apple.mpegurl"
                ready = true
                done = true
                rlock.notifyAll()
            }
        }

        fun download() {
            try {
                upstreamSlots.acquire()
            } catch (_: InterruptedException) {
                fail("interrumpido"); return
            }
            try {
                var attempt = 0
                while (true) {
                    try {
                        fetchOnce()
                        return
                    } catch (e: IOException) {
                        attempt++
                        val started = synchronized(rlock) { ready }
                        if (closed || started || attempt >= 3) {
                            if (!closed) log("HLS: no se pudo bajar ${url.substringAfterLast('/').substringBefore('?')}: ${e.message}")
                            fail(e.message ?: "error al descargar")
                            return
                        }
                        Thread.sleep(400L * attempt)
                    }
                }
            } finally {
                upstreamSlots.release()
            }
        }

        private fun fetchOnce() {
            val up = openUpstream(url, userAgent, null, headers)
            try {
                val buf = ByteArray(64 * 1024)
                val n0 = up.input.read(buf)
                val type = up.conn.contentType
                if (isPlaylist(type, buf, n0)) {
                    val bos = ByteArrayOutputStream()
                    if (n0 > 0) bos.write(buf, 0, n0)
                    while (true) {
                        val n = up.input.read(buf)
                        if (n < 0) break
                        bos.write(buf, 0, n)
                        if (bos.size() > MAX_PLAYLIST_BYTES) throw IOException("lista HLS demasiado grande")
                    }
                    setPlaylist(up.finalUrl, bos.toByteArray())
                    return
                }
                val f = File.createTempFile("seg-", ".bin", dir)
                val ch = RandomAccessFile(f, "rw").channel
                synchronized(rlock) {
                    if (closed) { ch.close(); f.delete(); throw IOException("cerrado") }
                    file = f
                    channel = ch
                    total = up.conn.contentLengthLong
                    contentType = type ?: "video/mp2t"
                    ready = true
                    rlock.notifyAll()
                }
                var n = n0
                var pos = 0L
                while (n >= 0 && !closed) {
                    if (n > 0) {
                        val bb = ByteBuffer.wrap(buf, 0, n)
                        var off = 0
                        while (bb.hasRemaining()) off += ch.write(bb, pos + off)
                        pos += n
                        synchronized(rlock) { written = pos; rlock.notifyAll() }
                    }
                    n = up.input.read(buf)
                }
                synchronized(rlock) {
                    if (total >= 0 && pos < total) throw IOException("el segmento llegó incompleto")
                    total = pos
                    done = true
                    rlock.notifyAll()
                }
            } finally {
                up.close()
            }
        }

        private fun fail(reason: String) = synchronized(rlock) {
            failure = reason
            rlock.notifyAll()
        }

        fun release() {
            synchronized(rlock) {
                failure = failure ?: "cerrado"
                rlock.notifyAll()
            }
            try { channel?.close() } catch (_: IOException) {}
            file?.delete()
        }

        fun serveTo(req: Request, out: OutputStream) {
            readers++
            lastUsed = System.currentTimeMillis()
            try {
                val deadline = System.currentTimeMillis() + 30_000
                synchronized(rlock) {
                    while (!ready && failure == null && System.currentTimeMillis() < deadline) rlock.wait(500)
                }
                val (isReady, why, pl, type) = synchronized(rlock) { Quad(ready, failure, playlist, contentType) }
                if (!isReady) {
                    out.write(relayError(why ?: "el proveedor no respondió a tiempo"))
                    return
                }
                if (pl != null) {
                    out.write(head("200 OK", listOf(
                        "Content-Type" to type,
                        "Content-Length" to pl.size.toString(),
                        "Cache-Control" to "no-cache"
                    )))
                    if (req.method != "HEAD") out.write(pl)
                    return
                }
                serveMedia(req, out, type)
            } finally {
                readers--
                lastUsed = System.currentTimeMillis()
            }
        }

        private fun serveMedia(req: Request, out: OutputStream, type: String) {
            val knownTotal = synchronized(rlock) { total }
            val range = req.headers["range"]?.let { Regex("""bytes=(\d*)-(\d*)""").find(it) }
            var start = 0L
            var end = Long.MAX_VALUE
            if (range != null && knownTotal >= 0) {
                val a = range.groupValues[1].toLongOrNull()
                val b = range.groupValues[2].toLongOrNull()
                if (a == null && b != null) { start = maxOf(0, knownTotal - b); end = knownTotal - 1 }
                else { start = a ?: 0L; end = minOf(b ?: (knownTotal - 1), knownTotal - 1) }
                if (start >= knownTotal || end < start) {
                    out.write(head("416 Range Not Satisfiable",
                        listOf("Content-Range" to "bytes */$knownTotal", "Content-Length" to "0")))
                    return
                }
                out.write(head("206 Partial Content", listOf(
                    "Content-Type" to type,
                    "Content-Length" to (end - start + 1).toString(),
                    "Content-Range" to "bytes $start-$end/$knownTotal"
                )))
            } else {
                val headers = mutableListOf("Content-Type" to type)
                if (knownTotal >= 0) {
                    end = knownTotal - 1
                    headers.add("Content-Length" to knownTotal.toString())
                }
                out.write(head("200 OK", headers))
            }
            if (req.method == "HEAD") return
            out.flush()

            val buf = ByteArray(64 * 1024)
            var pos = start
            while (pos <= end) {
                // 0 = hay datos, 1 = terminó, 2 = falló
                var state: Int
                var avail = 0L
                synchronized(rlock) {
                    while (written <= pos && !done && failure == null) rlock.wait(500)
                    state = when {
                        written > pos -> 0
                        failure != null -> 2
                        else -> 1
                    }
                    avail = written - pos
                }
                if (state == 2) throw IOException("descarga fallida")
                if (state == 1) break
                val ch = synchronized(rlock) { channel } ?: throw IOException("sin archivo")
                val len = minOf(avail, buf.size.toLong(), end - pos + 1).toInt()
                val bb = ByteBuffer.wrap(buf, 0, len)
                var read = 0
                while (bb.hasRemaining()) {
                    val r = ch.read(bb, pos + read)
                    if (r < 0) throw IOException("caché truncada")
                    read += r
                }
                out.write(buf, 0, len)
                pos += len
            }
        }
    }

    private data class Quad(val ready: Boolean, val failure: String?, val playlist: ByteArray?, val type: String)

    companion object {
        const val MAX_PARALLEL = 3
        const val LIVE_PLAYLIST_TTL_MS = 1500L
        const val MAX_PLAYLIST_BYTES = 8 * 1024 * 1024
        const val MAX_CACHE_BYTES = 300L * 1024 * 1024

        fun isPlaylist(contentType: String?, buf: ByteArray, n: Int): Boolean {
            if (contentType?.lowercase()?.contains("mpegurl") == true) return true
            if (n < 7) return false
            var i = 0
            // BOM de UTF-8 o espacios al inicio
            if (n >= 3 && buf[0] == 0xEF.toByte() && buf[1] == 0xBB.toByte() && buf[2] == 0xBF.toByte()) i = 3
            while (i < n && (buf[i] == ' '.code.toByte() || buf[i] == '\n'.code.toByte() || buf[i] == '\r'.code.toByte())) i++
            return n - i >= 7 && String(buf, i, 7, Charsets.US_ASCII) == "#EXTM3U"
        }
    }
}
