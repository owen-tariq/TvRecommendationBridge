package com.owentariq.tvhop

import android.net.Uri
import android.util.Log
import android.util.LruCache
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A resolved title: an IMDb-style id plus the content type it belongs to. */
data class Meta(val id: String, val type: String, val name: String)

/**
 * Resolves a card title to an IMDb id using Cinemeta, Stremio's public
 * metadata addon.
 *
 * Cinemeta needs no API key and no account, which is the whole reason it's
 * used here instead of TMDB: the app works the moment it's installed, with
 * nothing for the user to sign up for. It also returns IMDb ids natively,
 * which is exactly the id space both Stremio and Nuvio address content by.
 */
object MetaResolver {

    private const val TAG = "MetaResolver"
    private const val BASE = "https://v3-cinemeta.strem.io/catalog"
    private const val TIMEOUT_MS = 8000

    private val cache = LruCache<String, Meta>(128)

    /** Blocking. Call from a background thread. */
    fun resolve(rawTitle: String): Meta? {
        val title = rawTitle.trim()
        if (title.isEmpty()) return null

        val key = normalize(title)
        cache.get(key)?.let { return it }

        val candidates = ArrayList<Meta>()
        for (type in arrayOf("movie", "series")) {
            candidates += search(type, title)
        }

        val best = pickBest(title, candidates)
        if (best != null) cache.put(key, best)
        return best
    }

    private fun search(type: String, query: String): List<Meta> {
        val url = "$BASE/$type/top/search=${Uri.encode(query)}.json"
        val body = get(url) ?: return emptyList()

        return try {
            val metas = JSONObject(body).optJSONArray("metas") ?: return emptyList()
            (0 until metas.length()).mapNotNull { index ->
                val item = metas.optJSONObject(index) ?: return@mapNotNull null
                // Prefer the explicit imdb_id; fall back to id, which for
                // Cinemeta is the same tt-style value.
                val id = item.optString("imdb_id").ifBlank { item.optString("id") }
                val name = item.optString("name")
                if (id.isBlank() || name.isBlank()) return@mapNotNull null
                Meta(id = id, type = item.optString("type").ifBlank { type }, name = name)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse $type results", e)
            emptyList()
        }
    }

    /**
     * Cinemeta is a fuzzy search, so the first hit isn't always the right one.
     * Prefer an exact title match before falling back to search order.
     */
    private fun pickBest(query: String, candidates: List<Meta>): Meta? {
        if (candidates.isEmpty()) return null
        val target = normalize(query)

        candidates.firstOrNull { normalize(it.name) == target }?.let { return it }
        candidates.firstOrNull { normalize(it.name).startsWith(target) }?.let { return it }
        return candidates.first()
    }

    private fun normalize(value: String): String =
        value.lowercase()
            .replace(Regex("""[^\p{L}\p{N} ]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun get(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode !in 200..299) {
                Log.w(TAG, "HTTP ${connection.responseCode} for $url")
                null
            } else {
                connection.inputStream.bufferedReader().use { it.readText() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Request failed for $url", e)
            null
        } finally {
            connection?.disconnect()
        }
    }
}
