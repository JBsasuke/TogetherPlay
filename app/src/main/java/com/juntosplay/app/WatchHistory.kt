package com.juntosplay.app

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

internal class WatchHistory(context: Context) {
    private val app = context.applicationContext
    private val prefs = RoomSession.prefs(app)

    data class Entry(val positionMs: Long = 0, val watched: Boolean = false, val updatedAt: Long = 0, val durationMs: Long = 0) {
        val label: String get() = when {
            watched -> "✓ Visto"
            positionMs > 0 -> "En progreso"
            else -> "Pendiente"
        }

        /** Cuánto llevas (0 a 1), si se sabe cuánto dura; si no, -1. */
        val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else -1f
    }

    data class TitleProgress(private val entries: List<Entry>) {
        val watched = entries.isNotEmpty() && entries.all { it.watched }
        val updatedAt = entries.maxOfOrNull { it.updatedAt } ?: 0L
        val resumeIndex: Int? = entries.indices.filter { entries[it].watched || entries[it].positionMs > 0 }
            .maxWithOrNull(compareBy<Int> { entries[it].updatedAt }.thenBy { it })?.let { latest ->
                if (!entries[latest].watched) latest
                else (latest + 1 until entries.size).firstOrNull { !entries[it].watched }
                    ?: entries.indices.firstOrNull { !entries[it].watched }
            }
        val label get() = when {
            watched -> "Vista"
            resumeIndex != null -> "En progreso"
            else -> "Pendiente"
        }
    }

    fun progress(keys: List<String>): TitleProgress = TitleProgress(keys.map(::get))

    private val providerKeys = HashMap<Pair<String, String>, String>()

    fun get(key: String): Entry {
        val value = prefs.getString(key, null) ?: return Entry()
        return runCatching {
            val json = JSONObject(value)
            Entry(json.optLong("position").coerceAtLeast(0L), json.optBoolean("watched"), json.optLong("updated_at"),
                json.optLong("duration").coerceAtLeast(0L))
        }.getOrDefault(Entry())
    }

    fun track(key: String, position: Long, duration: Long, ended: Boolean) {
        val entry = get(key)
        if (entry.watched) return
        val completed = ended || duration > 0 && position >= duration * 0.9
        // También se guarda cuánto dura (para la rayita de avance en Seguir viendo).
        if (completed || position >= 5_000) save(key, Entry(if (completed) 0 else position, completed,
            durationMs = duration.coerceAtLeast(0L)))
    }

    fun toggleWatched(key: String): Entry {
        val next = Entry(watched = !get(key).watched, updatedAt = System.currentTimeMillis())
        save(key, next)
        return next
    }

    fun setWatched(keys: List<String>, watched: Boolean) {
        val changed = keys.distinct().filter { get(it).watched != watched }
        if (changed.isEmpty()) return
        val editor = prefs.edit()
        val now = System.currentTimeMillis()
        changed.forEach { write(editor, it, Entry(watched = watched, updatedAt = now), now) }
        editor.apply()
    }

    fun importWatched(keys: List<String>) {
        val updated = keys.distinct().filter { !get(it).watched }
        if (updated.isEmpty()) return
        val editor = prefs.edit()
        val now = System.currentTimeMillis()
        updated.forEach { write(editor, it, Entry(watched = true), now) }
        editor.apply()
        onImported?.invoke()
    }

    /**
     * Avance traído de otro equipo (visto a medias). Solo se usa si es más
     * reciente que lo de aquí y el episodio no está marcado como visto.
     */
    fun importProgress(progress: Map<String, Triple<Long, Long, Long>>) {
        val editor = prefs.edit()
        var changed = false
        progress.forEach { (key, value) ->
            val (position, pausedAt, duration) = value
            val local = get(key)
            if (local.watched || position <= 0 || pausedAt <= local.updatedAt) return@forEach
            if (kotlin.math.abs(local.positionMs - position) < 30_000L) return@forEach
            write(editor, key, Entry(position, durationMs = duration.takeIf { it > 0 } ?: local.durationMs), pausedAt)
            changed = true
        }
        if (changed) {
            editor.apply()
            onImported?.invoke()
        }
    }

    private fun save(key: String, entry: Entry) {
        val previous = get(key)
        if (previous.positionMs == entry.positionMs && previous.watched == entry.watched &&
            (entry.durationMs <= 0 || previous.durationMs == entry.durationMs)) return
        val editor = prefs.edit()
        write(editor, key, entry, System.currentTimeMillis())
        editor.apply()
    }

    private fun write(editor: android.content.SharedPreferences.Editor, key: String, entry: Entry, now: Long) {
        if (entry == Entry()) editor.remove(key)
        else editor.putString(key, JSONObject().put("position", entry.positionMs).put("watched", entry.watched)
            .put("updated_at", now).apply { if (entry.durationMs > 0) put("duration", entry.durationMs) }.toString())
    }

    companion object {
        const val PREFIX = "watch_history_"
        var onImported: (() -> Unit)? = null
    }
}

