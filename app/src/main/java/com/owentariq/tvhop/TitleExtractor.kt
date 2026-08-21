package com.owentariq.tvhop

/**
 * Turns whatever the launcher exposes for a focused/clicked card into a bare
 * title we can search for.
 *
 * Launchers are inconsistent here. Depending on the row and the device, a card
 * announces itself as any of:
 *
 *   "Dune: Part Two"
 *   "Dune: Part Two, movie, 2024"
 *   "Dune: Part Two • 2024 • Sci-fi"
 *   "Play Dune: Part Two (2024)"
 *
 * so we take every string the event carries, strip the decoration off each one
 * and keep the most plausible candidate.
 */
object TitleExtractor {

    /** Separators used to append metadata after the title. */
    private val SEPARATORS = listOf(" • ", " · ", " | ", " — ", " – ")

    /** A trailing comma-chunk is metadata, not part of the title, if it looks like this. */
    private val METADATA_TAIL = Regex(
        """^\s*((19|20)\d{2}|movie|film|pel[íi]cula|series?|serie|tv show|show|season\s*\d+|temporada\s*\d+|episode\s*\d+|\d+\s*(min|h|hr)\w*|[a-z\-]{1,3}\s*\d{1,2}\+?|hd|uhd|4k)\s*$""",
        RegexOption.IGNORE_CASE
    )

    /** Chrome that sometimes prefixes a card's spoken label. */
    private val LEADING_VERB = Regex(
        """^(play|watch|resume|continue watching|reproducir|ver)\s+""",
        RegexOption.IGNORE_CASE
    )

    /** Pure UI affordances that are never titles. */
    private val NOT_A_TITLE = setOf(
        "play", "watch", "resume", "more info", "info", "details", "search",
        "home", "back", "menu", "settings", "apps", "library", "reproducir", "ver"
    )

    private val TRAILING_YEAR = Regex("""\s*[\(\[](19|20)\d{2}[\)\]]\s*$""")
    private val WHITESPACE = Regex("""\s+""")

    /**
     * @param candidates every text the accessibility event carried, in the
     *   order the framework supplied them.
     */
    fun extract(candidates: List<CharSequence?>): String? =
        candidates
            .mapNotNull { clean(it?.toString()) }
            .maxByOrNull { it.length }

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

        // "Dune: Part Two, movie, 2024" -> "Dune: Part Two". Peel one chunk at a
        // time from the right, and stop at the first that isn't metadata, so a
        // title with a comma in it ("Crouching Tiger, Hidden Dragon") survives.
        while (true) {
            val index = value.lastIndexOf(',')
            if (index <= 0) break
            val tail = value.substring(index + 1)
            if (!METADATA_TAIL.matches(tail)) break
            value = value.substring(0, index).trim()
        }

        value = value.replace(LEADING_VERB, "")
        value = value.replace(TRAILING_YEAR, "")
        value = value.replace(WHITESPACE, " ").trim()

        if (value.length < 2) return null
        if (value.lowercase() in NOT_A_TITLE) return null
        // A label with no letters at all (a bare duration, a rating) isn't a title.
        if (value.none { it.isLetter() }) return null

        return value
    }
}
