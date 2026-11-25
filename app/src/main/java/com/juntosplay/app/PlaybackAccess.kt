package com.juntosplay.app

import org.json.JSONObject
import java.util.Locale

internal object PlaybackAccess {
    const val MANUAL_EXTRA = "adult_playback"
    const val MANUAL_SHARE_PARAM = "manual_playback"
    private val adultLabel = Regex(
        """(?<![A-Z0-9])(?:XXX|18\s*\+|ADULT(?:S|OS?|AS?)?|PORN[A-Z]*|EROTIC[A-Z]*|BRAZZERS|PLAYBOY|HUSTLER|SEX HOT|REALITY KINGS|PENTHOUSE)(?![A-Z0-9])"""
    )
    private val adultFlags = listOf("is_adult", "adult", "is_xxx")
    private val affirmative = setOf("1", "true", "yes")
    private val labelFields = listOf("genre", "category_name", "group-title", "parental_rating", "mpaa_rating", "rating_mpaa")

    fun marked(title: String): Boolean = adultLabel.containsMatchIn(java.text.Normalizer.normalize(title.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), ""))

    fun marked(title: String, metadata: JSONObject): Boolean =
        marked(title) || adultFlags.any {
            metadata.optString(it).trim().lowercase(Locale.ROOT) in affirmative
        } || labelFields.any { marked(metadata.optString(it)) }

    fun markedCategory(metadata: JSONObject, adultIds: Set<String>): Boolean =
        metadata.optString("category_id") in adultIds || metadata.optJSONArray("category_ids")?.let { ids ->
            (0 until ids.length()).any { ids.optString(it) in adultIds }
        } == true
}
