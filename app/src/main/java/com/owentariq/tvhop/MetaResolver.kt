package com.owentariq.tvhop

import android.net.Uri
import android.util.Log
import android.util.LruCache
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A resolved title: an IMDb-style id plus the content type it belongs to. */
data class Meta(val id: String, val type: String, val name: String, val year: Int? = null)

/**
 * Resolves a card to an IMDb id using Cinemeta, Stremio's public metadata
 * addon — no API key, no account, and it returns IMDb ids natively, which is
 * the id space both Stremio and Nuvio address content by.
 *
 * Cinemeta's search is fuzzy and will happily return something for nearly any
 * string, so the scoring below does the real work. Opening the wrong film is
 * worse than opening nothing, so a candidate has to clear [MIN_SCORE] before
 * we act on it.
 */
object MetaResolver {

    private const val TAG = "MetaResolver"
    private const val BASE = "https://v3-cinemeta.strem.io/catalog"

    /**
     * 8s was far too patient for something sitting between a button press and
     * a screen opening. A lookup that hasn't answered in 4s isn't going to
     * feel responsive anyway.
     */
    private const val TIMEOUT_MS = 4000

    /** The movie and series catalogues are queried at the same time, not in turn. */
    private val searchPool = java.util.concurrent.Executors.newFixedThreadPool(2)

    /**
     * Below this, we'd rather tell the user we couldn't identify the card than
     * open something unrelated. An exact title match alone scores 100.
     */
    private const val MIN_SCORE = 55

    private val cache = LruCache<String, Meta>(128)

    /** Blocking. Call from a background thread. */
    fun resolve(card: CardInfo): Meta? {
        val title = card.title.trim()
        if (title.isEmpty()) return null

        val key = "${normalize(title)}|${card.year ?: ""}|${card.typeHint ?: ""}"
        cache.get(key)?.let {
            Log.d(TAG, "Cache hit for \"$title\" -> ${it.id}")
            return it
        }

        // Both catalogues at once — sequential lookups doubled the wait for
        // every card.
        val started = System.currentTimeMillis()
        val futures = arrayOf("movie", "series").map { type ->
            type to searchPool.submit<List<Meta>> { search(type, title) }
        }

        val candidates = ArrayList<Pair<Meta, Int>>()
        for ((_, future) in futures) {
            val results = try {
                future.get()
            } catch (e: Exception) {
                Log.w(TAG, "Search failed", e)
                emptyList()
            }
            results.forEachIndexed { index, meta ->
                candidates += meta to score(card, meta, index)
            }
        }
        val elapsed = System.currentTimeMillis() - started

        if (candidates.isEmpty()) {
            Log.i(TAG, "No results at all for \"$title\"")
            DebugLog.add("✗ \"$title\" — no results (${elapsed}ms)")
            return null
        }

        val ranked = candidates.sortedByDescending { it.second }
        val (best, bestScore) = ranked.first()
        val runnerUp = ranked.getOrNull(1)

        if (bestScore < MIN_SCORE) {
            Log.i(
                TAG,
                "Best match for \"$title\" was \"${best.name}\" (${best.id}) scoring " +
                    "$bestScore, below $MIN_SCORE — refusing to guess"
            )
            DebugLog.add(
                "✗ \"$title\" — best was \"${best.name}\" (${best.year ?: "?"}) " +
                    "score $bestScore < $MIN_SCORE, not opening"
            )
            return null
        }

        Log.d(TAG, "Matched \"$title\" -> ${best.name} (${best.id}) score $bestScore in ${elapsed}ms")
        DebugLog.add(
            "✓ \"$title\" → ${best.name} (${best.year ?: "?"}) ${best.id} " +
                "score $bestScore" +
                (runnerUp?.let { ", 2nd: ${it.first.name} ${it.second}" } ?: "") +
                " [${elapsed}ms]"
        )
        cache.put(key, best)
        return best
    }

    /**
     * Higher is better. An exact title match is worth 100 on its own; the year
     * is the main tie-breaker between remakes and same-named titles.
     */
    private fun score(card: CardInfo, meta: Meta, resultIndex: Int): Int {
        val target = normalize(card.title)
        val name = normalize(meta.name)

        var score = when {
            name == target -> 100
            name.startsWith(target) || target.startsWith(name) -> 55
            name.contains(target) || target.contains(name) -> 35
            else -> tokenOverlap(target, name)
        }

        // Year is the strongest disambiguator we have: it separates Dune (1984)
        // from Dune (2021), and it's usually right there on the card.
        if (card.year != null && meta.year != null) {
            score += when {
                card.year == meta.year -> 45
                kotlin.math.abs(card.year - meta.year) == 1 -> 10 // release-date skew
                else -> -40
            }
        }

        if (card.typeHint != null) {
            score += if (card.typeHint == meta.type) 20 else -20
        }

        // Mild preference for Cinemeta's own ranking, as a last tie-break.
        score += (5 - resultIndex).coerceAtLeast(0)

        return score
    }

    /** Fraction of the card's words present in the candidate, scaled to 0..40. */
    private fun tokenOverlap(target: String, name: String): Int {
        val targetWords = target.split(' ').filter { it.length > 2 }.toSet()
        if (targetWords.isEmpty()) return 0
        val nameWords = name.split(' ').toSet()
        val hits = targetWords.count { it in nameWords }
        return (40.0 * hits / targetWords.size).toInt()
    }

    private fun search(type: String, query: String): List<Meta> {
        val url = "$BASE/$type/top/search=${Uri.encode(query)}.json"
        val body = get(url) ?: return emptyList()

        return try {
            val metas = JSONObject(body).optJSONArray("metas") ?: return emptyList()
            (0 until metas.length()).mapNotNull { index ->
                val item = metas.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("imdb_id").ifBlank { item.optString("id") }
                val name = item.optString("name")
                if (id.isBlank() || name.isBlank()) return@mapNotNull null
                Meta(
                    id = id,
                    type = item.optString("type").ifBlank { type },
                    name = name,
                    year = parseYear(item)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse $type results", e)
            emptyList()
        }
    }

    /** `releaseInfo` is usually "2024", but for series it can be "2019-2023". */
    private fun parseYear(item: JSONObject): Int? {
        val raw = item.optString("year").ifBlank { item.optString("releaseInfo") }
        return Regex("""(19|20)\d{2}""").find(raw)?.value?.toIntOrNull()
    }

    private fun normalize(value: String): String =
        value.lowercase()
            .replace('&', ' ')
            .replace(Regex("""\b(the|a|an|el|la|los|las|un|una)\b"""), " ")
            .replace(Regex("""[^\p{L}\p{N} ]"""), " ")
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
