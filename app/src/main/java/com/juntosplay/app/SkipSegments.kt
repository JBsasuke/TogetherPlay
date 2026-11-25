package com.juntosplay.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Saltar los "rellenos" de las series: la intro, el resumen ("anteriormente en…"), los créditos y el
 * avance del próximo episodio. Los tiempos salen de dos bases de datos gratis hechas por la comunidad:
 * TheIntroDB (theintrodb.org, por TMDB) e IntroDB (introdb.app, por IMDb). Si una serie no tiene datos,
 * se marcan a mano desde el reproductor y valen para todos sus episodios.
 */
internal object SkipSegments {
    enum class Type(val key: String, val button: String, val done: String) {
        RECAP("recap", "Saltar resumen", "Resumen saltado"),
        INTRO("intro", "Saltar intro", "Intro saltada"),
        CREDITS("credits", "Saltar créditos", "Créditos saltados"),
        PREVIEW("preview", "Saltar avance", "Avance saltado"),
    }

    /** [endMs] null: hasta el final del episodio. */
    data class Segment(val type: Type, val startMs: Long, val endMs: Long?, val confidence: Double = 0.0) {
        /** Créditos y avance: van al final (el botón puede pasar al siguiente episodio). */
        val atEnd: Boolean get() = type == Type.CREDITS || type == Type.PREVIEW
    }

    /** Qué episodio es. [series] identifica la serie (para las marcas a mano). */
    data class Episode(val series: String, val title: String, val tmdb: Long?, val imdb: String?,
                       val season: Int, val episode: Int) {
        fun toBundle() = Bundle().apply {
            putString("series", series)
            putString("title", title)
            tmdb?.let { putLong("tmdb", it) }
            imdb?.let { putString("imdb", it) }
            putInt("season", season)
            putInt("episode", episode)
        }

        companion object {
            fun from(bundle: Bundle?): Episode? {
                bundle ?: return null
                val series = bundle.getString("series").orEmpty()
                val season = bundle.getInt("season", -1)
                val episode = bundle.getInt("episode", -1)
                if (series.isBlank() || season < 0 || episode <= 0) return null
                return Episode(series, bundle.getString("title").orEmpty(),
                    bundle.getLong("tmdb", 0L).takeIf { it > 0 },
                    bundle.getString("imdb")?.takeIf { it.startsWith("tt") }, season, episode)
            }
        }
    }

    enum class Mode(val key: String, val label: String) {
        BUTTON("button", "Botón"),
        AUTO("auto", "Automático"),
        OFF("off", "Apagado"),
    }

    private const val PREF_MODE = "skip_segments_mode"
    private const val PREF_MARKS = "skip_marks_"
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()
    private val cache = LruCache<String, List<Segment>>(60)

    fun mode(context: Context): Mode {
        val key = RoomSession.prefs(context).getString(PREF_MODE, Mode.BUTTON.key)
        return Mode.entries.firstOrNull { it.key == key } ?: Mode.BUTTON
    }

    fun label(context: Context): String = "Saltar intro y créditos · " + mode(context).label

    fun choose(activity: Activity, done: () -> Unit = {}) {
        val modes = Mode.entries
        val names = arrayOf(
            "Botón: aparece «Saltar intro» y lo aprietas con OK",
            "Automático: se salta solo (y pasa al siguiente episodio)",
            "Apagado",
        )
        AlertDialog.Builder(activity).setTitle("Saltar intro, resumen y créditos")
            .setSingleChoiceItems(names, modes.indexOf(mode(activity))) { dialog, which ->
                RoomSession.prefs(activity).edit().putString(PREF_MODE, modes[which].key).apply()
                dialog.dismiss()
                done()
            }.setNegativeButton("Cancelar", null).create().also(Ui::showDialog)
    }

    // --- Marcas a mano (por serie) ---

    data class Marks(val introStart: Long? = null, val introEnd: Long? = null, val creditsFromEnd: Long? = null) {
        val empty: Boolean get() = introEnd == null && creditsFromEnd == null
    }

    fun marks(context: Context, series: String): Marks {
        val raw = RoomSession.prefs(context).getString(PREF_MARKS + series, null) ?: return Marks()
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return Marks()
        fun value(key: String) = o.optLong(key, -1L).takeIf { it >= 0 }
        return Marks(value("intro_start"), value("intro_end"), value("credits_from_end"))
    }

    fun saveMarks(context: Context, series: String, marks: Marks) {
        val editor = RoomSession.prefs(context).edit()
        if (marks.empty && marks.introStart == null) editor.remove(PREF_MARKS + series)
        else editor.putString(PREF_MARKS + series, JSONObject().apply {
            marks.introStart?.let { put("intro_start", it) }
            marks.introEnd?.let { put("intro_end", it) }
            marks.creditsFromEnd?.let { put("credits_from_end", it) }
        }.toString())
        editor.apply()
    }

    /** Los tiempos de internet con las marcas a mano encima (las marcas mandan). */
    fun withMarks(found: List<Segment>, marks: Marks, durationMs: Long): List<Segment> {
        if (marks.empty) return found
        val result = found.toMutableList()
        val introEnd = marks.introEnd
        if (introEnd != null && introEnd > (marks.introStart ?: 0L)) {
            result.removeAll { it.type == Type.INTRO }
            result.add(Segment(Type.INTRO, marks.introStart ?: 0L, introEnd, 1.0))
        }
        val fromEnd = marks.creditsFromEnd
        if (fromEnd != null && durationMs > fromEnd) {
            result.removeAll { it.type == Type.CREDITS }
            result.add(Segment(Type.CREDITS, durationMs - fromEnd, null, 1.0))
        }
        return result.sortedBy { it.startMs }
    }

    // --- Tiempos de internet ---

    /**
     * Busca los tiempos de [episode] (en segundo plano). [durationMs] ayuda a TheIntroDB a elegir la
     * versión correcta (normal, extendida…). [done] llega en el hilo principal con lo encontrado y de dónde.
     */
    fun fetch(context: Context, episode: Episode, durationMs: Long, done: (List<Segment>, String) -> Unit) {
        val cacheKey = listOf(episode.tmdb, episode.imdb, episode.title, episode.season, episode.episode).joinToString("|")
        cache.get(cacheKey)?.let { list -> done(list, "guardado"); return }
        val appContext = context.applicationContext
        thread(name = "rellenos") {
            var source = ""
            var imdb = episode.imdb
            var list = episode.tmdb?.let { theIntroDb("tmdb_id=$it", episode, durationMs) }.orEmpty()
            if (list.isNotEmpty()) source = "TheIntroDB"
            if (list.isEmpty() && imdb != null && episode.tmdb == null) {
                list = theIntroDb("imdb_id=$imdb", episode, durationMs)
                if (list.isNotEmpty()) source = "TheIntroDB"
            }
            if (imdb != null) {
                // IntroDB completa lo que falte (muchas veces solo tiene la intro).
                val extra = introDb(imdb, episode)
                if (extra.isNotEmpty()) {
                    val have = list.map { it.type }.toSet()
                    val added = extra.filter { it.type !in have }
                    if (added.isNotEmpty()) {
                        list = (list + added).sortedBy { it.startMs }
                        source = listOf(source, "IntroDB").filter { it.isNotBlank() }.joinToString(" + ")
                    }
                }
            }
            if (durationMs > 0) list = list.filter { it.startMs < durationMs }
            cache.put(cacheKey, list)
            if (list.isEmpty()) PlaybackLog.add(appContext, "Sin tiempos de intro/créditos para T${episode.season} E${episode.episode}")
            main.post { done(list, source) }
        }
    }

    private fun theIntroDb(id: String, episode: Episode, durationMs: Long): List<Segment> {
        val query = "$id&season=${episode.season}&episode=${episode.episode}" +
            (if (durationMs > 0) "&duration_ms=$durationMs" else "")
        // v2 es la que usan casi todas las apps; si no responde, la más nueva.
        for (version in listOf("v2", "v3")) {
            val body = get("https://api.theintrodb.org/$version/media?$query") ?: continue
            return parse(body)
        }
        return emptyList()
    }

    private fun introDb(imdb: String, episode: Episode): List<Segment> {
        val body = get("https://api.introdb.app/segments?imdb_id=$imdb&season=${episode.season}&episode=${episode.episode}")
            ?: return emptyList()
        return parse(body)
    }

    private fun get(url: String): String? = runCatching {
        http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    }.getOrNull()

    private val typeNames = mapOf(
        "intro" to Type.INTRO, "opening" to Type.INTRO, "op" to Type.INTRO,
        "recap" to Type.RECAP,
        "credits" to Type.CREDITS, "outro" to Type.CREDITS, "ending" to Type.CREDITS, "ed" to Type.CREDITS,
        "preview" to Type.PREVIEW,
    )

    /** Entiende las respuestas de las dos bases (y formas parecidas): un segmento por tipo. */
    internal fun parse(body: String): List<Segment> {
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: runCatching { JSONObject().put("segments", JSONArray(body)) }.getOrNull()
            ?: return emptyList()
        val found = ArrayList<Segment>()
        val containers = listOfNotNull(root, root.optJSONObject("segments"), root.optJSONObject("media"),
            root.optJSONObject("data"), root.optJSONObject("result"))
        for (container in containers) {
            for ((name, type) in typeNames) collect(container.opt(name), type, found)
        }
        // Lista con el tipo en cada segmento: [{"segment_type": "intro", "start_sec": …}]
        for (listKey in listOf("segments", "results", "data")) {
            val list = root.optJSONArray(listKey) ?: continue
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                val name = sequenceOf("segment_type", "type", "category", "skip_type", "skipType")
                    .map { o.optString(it).lowercase() }.firstOrNull { it.isNotBlank() } ?: continue
                typeNames[name]?.let { segment(o, it) }?.let(found::add)
            }
        }
        // Respuesta corta de IntroDB: {"start": 2.5, "end": 58.0} es la intro.
        if (found.isEmpty() && root.has("start") && root.has("end")) segment(root, Type.INTRO)?.let(found::add)
        return found.groupBy { it.type }.values.map { list -> list.maxByOrNull { it.confidence } ?: list.first() }
            .sortedBy { it.startMs }
    }

    private fun collect(value: Any?, type: Type, out: MutableList<Segment>) {
        when (value) {
            is JSONArray -> for (i in 0 until value.length()) collect(value.opt(i), type, out)
            is JSONObject -> segment(value, type)?.let(out::add)
        }
    }

    private fun segment(o: JSONObject, type: Type): Segment? {
        val start = time(o, listOf("start_ms", "startMs"), listOf("start_sec", "startSec", "start", "start_time", "startTime"))
        val end = time(o, listOf("end_ms", "endMs"), listOf("end_sec", "endSec", "end", "end_time", "endTime"))
        val atEnd = type == Type.CREDITS || type == Type.PREVIEW
        // start vacío = desde el principio (intro, resumen); end vacío = hasta el final (créditos, avance).
        if (start == null && end == null) return null
        if (!atEnd && end == null) return null
        if (atEnd && start == null) return null
        val startMs = start ?: 0L
        if (end != null && end <= startMs) return null
        val confidence = o.optDouble("confidence", 0.0).takeIf { !it.isNaN() } ?: 0.0
        return Segment(type, startMs, end, confidence)
    }

    /** Milisegundos de [o]: campos en ms, o en segundos (número, "58.5" o "00:00:58"). */
    private fun time(o: JSONObject, msKeys: List<String>, secKeys: List<String>): Long? {
        for (key in msKeys) {
            if (!o.has(key) || o.isNull(key)) continue
            o.optDouble(key).takeIf { !it.isNaN() && it >= 0 }?.let { return it.toLong() }
        }
        for (key in secKeys) {
            if (!o.has(key) || o.isNull(key)) continue
            val value = o.opt(key)
            val seconds = when (value) {
                is Number -> value.toDouble()
                is String -> if (':' in value) {
                    val parts = value.split(':').map { it.trim().toDoubleOrNull() }
                    if (parts.any { it == null }) null else parts.fold(0.0) { total, part -> total * 60 + (part ?: 0.0) }
                } else value.trim().toDoubleOrNull()
                else -> null
            }
            if (seconds != null && seconds >= 0) return (seconds * 1000).toLong()
        }
        return null
    }

    /** "1:05:09" o "4:30". */
    fun clock(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
            else String.format(java.util.Locale.US, "%d:%02d", m, s)
    }
}
