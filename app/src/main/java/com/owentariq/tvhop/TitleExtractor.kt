package com.owentariq.tvhop

/** What we managed to read off a launcher card. */
data class CardInfo(
    val title: String,
    /** Release year, when the card mentioned one. Used to disambiguate remakes. */
    val year: Int? = null,
    /** "movie" or "series" when the card said so, else null. */
    val typeHint: String? = null
)

/**
 * Turns whatever the launcher exposes for a clicked card into a searchable
 * title, plus any year and type it mentioned.
 *
 * Launchers are wildly inconsistent. A single card can announce itself as any
 * of these, depending on the row and the device:
 *
 *   "Dune: Part Two"
 *   "Dune: Part Two, movie, 2024"
 *   "Dune: Part Two • 2024 • Sci-fi"
 *   "Play Dune: Part Two (2024)"
 *   "Dune: Part Two. Paul Atreides unites with Chani and the Fremen while
 *    seeking revenge against the conspirators who destroyed his family. 2024"
 *
 * That last shape is why candidates are ranked by *source priority*, not by
 * length. The longest string a card carries is usually its plot summary, and
 * searching a plot summary returns nonsense.
 */
object TitleExtractor {

    /** Separators used to append metadata after the title. */
    private val SEPARATORS = listOf(" • ", " · ", " | ", " — ", " – ")

    /** A trailing comma-chunk is metadata, not part of the title, if it looks like this. */
    private val METADATA_TAIL = Regex(
        """^\s*((19|20)\d{2}|movie|film|pel[íi]cula|series?|serie|tv show|show|season\s*\d+|temporada\s*\d+|episode\s*\d+|\d+\s*(min|h|hr)\w*|[a-z\-]{1,3}\s*\d{1,2}\+?|hd|uhd|4k)\s*$""",
        RegexOption.IGNORE_CASE
    )

    private val LEADING_VERB = Regex(
        """^(play|watch|resume|continue watching|reproducir|ver)\s+""",
        RegexOption.IGNORE_CASE
    )

    private val NOT_A_TITLE = setOf(
        "play", "watch", "resume", "more info", "info", "details", "search",
        "home", "back", "menu", "settings", "apps", "library", "reproducir", "ver"
    )

    /**
     * Durations, age ratings and quality badges. These carry letters, so the
     * "must contain a letter" check doesn't catch them.
     */
    private val NOISE = Regex(
        """^(\d+\s*h(\s*\d+\s*m(in)?)?|\d+\s*m(in|ins|inutes)?|(tv-)?(g|pg|pg-?13|nc-?17|ma|y7|14a?|18a?)|\d{1,2}\+|hd|uhd|4k|sd|cc|ad)$""",
        RegexOption.IGNORE_CASE
    )

    private val TRAILING_YEAR = Regex("""\s*[\(\[](19|20)\d{2}[\)\]]\s*$""")
    private val ANY_YEAR = Regex("""\b(19|20)\d{2}\b""")
    private val WHITESPACE = Regex("""\s+""")

    private val SERIES_WORD = Regex("""\b(series|serie|tv show|temporada|season|episode)\b""", RegexOption.IGNORE_CASE)
    private val MOVIE_WORD = Regex("""\b(movie|film|pel[íi]cula)\b""", RegexOption.IGNORE_CASE)

    /**
     * Longer than this and it's a synopsis, not a title. The longest real film
     * titles in circulation sit well under 60 characters.
     */
    private const val MAX_TITLE_LENGTH = 60

    /**
     * Button labels seen on launcher and detail screens, longest first so
     * "watch trailer" is tested before "watch".
     */
    private val BUTTON_PHRASES = listOf(
        "continue watching", "remove from watchlist", "add to watchlist",
        "buy or rent", "watch trailer", "play trailer", "more like this",
        "free with ads", "más información", "mas informacion", "ver tráiler",
        "ver trailer", "watch now", "play now", "more info", "watchlist",
        "episodes", "subscribe", "reproducir", "alquilar", "comprar",
        "details", "resume", "trailer", "share", "info", "play", "watch",
        "buy", "rent", "ver"
    ).sortedByDescending { it.length }

    /** What may follow a button word and still leave it a button: a price, "now", a badge. */
    private val TRIVIAL_TAIL = Regex(
        """^(\$?\d[\d.,]*|now|free|free with ads|with ads|hd|uhd|4k|sd)$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * True if this is a button rather than something to watch.
     *
     * The distinction that matters: "Watch now" is a button, but "Watch
     * Severance" is a card whose label happens to start with the same word.
     * So a phrase only counts as a button when nothing substantial follows it
     * — a price or a badge doesn't count ("Buy or rent $19.99" is still a
     * button).
     *
     * A trailing clause is checked too, because the launcher labels app tiles
     * as "Plex, Watch now" — which was being read as the series "NOW".
     */
    fun isAffordance(value: String): Boolean {
        val normalised = value.trim().lowercase().trim('.', '!', ' ')
        if (normalised.isEmpty()) return false

        val afterComma = normalised.substringAfterLast(',', "").trim()
        if (afterComma.isNotEmpty() && matchesButton(afterComma)) return true

        return matchesButton(normalised)
    }

    private fun matchesButton(value: String): Boolean {
        for (phrase in BUTTON_PHRASES) {
            if (value == phrase) return true
            if (value.startsWith("$phrase ")) {
                val rest = value.removePrefix(phrase).trim().trim(',', ':', '-', '–', '—').trim()
                if (rest.isEmpty() || TRIVIAL_TAIL.matches(rest)) return true
            }
        }
        return false
    }

    /**
     * Layout scaffolding the Google TV launcher exposes as accessibility
     * labels: grid cells announce themselves as "Column 3", the detail screen
     * as "Detail Page", the home screen as "Main user home screen".
     *
     * These are not titles, and searching for them is actively harmful — they
     * fuzzy-match some unrelated film, get cached, and then every card in that
     * column opens the same wrong thing.
     */
    private val STRUCTURAL = Regex(
        """^((column|row|section|tab|page|item|card|grid|list|carousel|shelf)\s*\d*|""" +
            """detail page|main user home screen|home screen|main screen|navigation( bar| drawer)?|""" +
            """app bar|toolbar|menu bar|search bar|content area|scroll(able)? (view|area))$""",
        RegexOption.IGNORE_CASE
    )

    /** True if this is a layout container's label rather than any real content. */
    fun isStructural(value: String): Boolean = STRUCTURAL.matches(value.trim())

    /**
     * @param candidates card strings in descending order of trustworthiness —
     *   the node's own text first, the content description (which is where
     *   synopses live) last.
     */
    fun extract(candidates: List<CharSequence?>): CardInfo? {
        val raw = candidates.mapNotNull { it?.toString() }.filter { it.isNotBlank() }
        if (raw.isEmpty()) return null

        // Buttons and layout labels are discarded BEFORE cleaning. Doing it
        // after was a real bug: cleaning turned "WATCH NOW" into "NOW", which
        // is an actual series, so the affordance check never saw it.
        val usable = raw.filterNot { isAffordance(it) || isStructural(it) }

        // Take the first candidate that survives cleaning, in priority order.
        // Never the longest — that's the synopsis.
        val title = usable.firstNotNullOfOrNull { clean(it) } ?: return null

        // The year can legitimately come from any of the strings, including one
        // we rejected as a title.
        val year = raw.firstNotNullOfOrNull { candidate ->
            ANY_YEAR.find(candidate)?.value?.toIntOrNull()
        }?.takeIf { it in 1900..2099 }

        val joined = raw.joinToString(" ")
        val typeHint = when {
            SERIES_WORD.containsMatchIn(joined) -> "series"
            MOVIE_WORD.containsMatchIn(joined) -> "movie"
            else -> null
        }

        return CardInfo(title = title, year = year, typeHint = typeHint)
    }

    fun clean(raw: String?): String? {
        if (raw == null) return null

        var value = raw.trim()
        if (value.isEmpty()) return null

        // "Dune: Part Two • 2024 • Sci-fi" -> "Dune: Part Two"
        for (separator in SEPARATORS) {
            val index = value.indexOf(separator)
            if (index > 0) {
                value = value.substring(0, index)
                break
            }
        }

        // "Title. Long synopsis sentence..." -> "Title". Only split on a period
        // that's followed by a space and more words, so "Dr. No" survives.
        if (value.length > MAX_TITLE_LENGTH) {
            val sentenceEnd = Regex("""\.\s+\p{Lu}""").find(value)
            if (sentenceEnd != null && sentenceEnd.range.first > 0) {
                value = value.substring(0, sentenceEnd.range.first).trim()
            }
        }

        // "Dune: Part Two, movie, 2024" -> "Dune: Part Two". Peel one chunk at a
        // time from the right, stopping at the first that isn't metadata, so
        // "Crouching Tiger, Hidden Dragon" survives.
        while (true) {
            val index = value.lastIndexOf(',')
            if (index <= 0) break
            val tail = value.substring(index + 1)
            if (!METADATA_TAIL.matches(tail)) break
            value = value.substring(0, index).trim()
        }

        val withoutVerb = value.replace(LEADING_VERB, "")
        // "Play Dune: Part Two" -> "Dune: Part Two" is right, but "WATCH NOW"
        // -> "NOW" matched a real series. Only strip when what's left still
        // looks like a title in its own right.
        if (withoutVerb.length >= 5 || withoutVerb.contains(' ')) {
            value = withoutVerb
        }
        value = value.replace(TRAILING_YEAR, "")
        value = value.replace(WHITESPACE, " ").trim()
        value = value.trimEnd('.', ',', '·', '•', '-', '–', '—').trim()

        if (value.length < 2) return null
        // Still a paragraph after cleaning? Then it was never a title.
        if (value.length > MAX_TITLE_LENGTH) return null
        if (value.lowercase() in NOT_A_TITLE) return null
        if (NOISE.matches(value)) return null
        if (isStructural(value)) return null
        if (value.none { it.isLetter() }) return null

        return value
    }
}
