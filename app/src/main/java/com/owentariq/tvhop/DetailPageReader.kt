package com.owentariq.tvhop

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Reads the title off Google TV's detail page.
 *
 * This is the reliable path, and it's reliable for a simple reason: by the
 * time that page is on screen, Google has already decided exactly which film
 * you picked and rendered its name, year and type. The home-screen card, by
 * contrast, exposes only layout scaffolding ("Column 1") to accessibility.
 *
 * Rather than guess at positions, this prefers nodes whose view id looks like
 * a title (`com.google.android.videos:id/title`, `…:id/show_title`, …), which
 * is the app telling us directly which view holds the name.
 */
object DetailPageReader {

    private const val MAX_DEPTH = 12
    private const val MAX_NODES = 300

    /** View ids that name the content. Checked in order. */
    private val TITLE_ID_HINTS = listOf(
        "movie_title", "show_title", "content_title", "detail_title",
        "title_text", "header_title", "title"
    )

    /** Ids that look title-ish but aren't the content's name. */
    private val TITLE_ID_EXCLUSIONS = listOf(
        "row_title", "section_title", "app_title", "channel_title",
        "subtitle", "episode_title", "toolbar_title"
    )

    private val YEAR = Regex("""\b(19|20)\d{2}\b""")
    private val SERIES_WORD = Regex("""\b(tv show|series|serie|season|temporada)\b""", RegexOption.IGNORE_CASE)
    private val MOVIE_WORD = Regex("""\b(movie|film|pel[íi]cula)\b""", RegexOption.IGNORE_CASE)

    data class Scan(val card: CardInfo?, val diagnostics: String)

    /**
     * @param root the detail page's root node, from `rootInActiveWindow`.
     */
    fun read(root: AccessibilityNodeInfo?): Scan {
        if (root == null) return Scan(null, "no window root")

        val nodes = ArrayList<Pair<String?, String>>() // view id -> text
        collect(root, 0, nodes)
        if (nodes.isEmpty()) return Scan(null, "window had no text")

        val allText = nodes.joinToString(" ") { it.second }

        val title = byTitleViewId(nodes) ?: firstPlausibleText(nodes)
        if (title == null) {
            return Scan(
                null,
                "no title found in ${nodes.size} nodes: " +
                    nodes.take(6).joinToString(" | ") { "${shortId(it.first)}=${it.second.take(28)}" }
            )
        }

        val year = YEAR.find(allText)?.value?.toIntOrNull()?.takeIf { it in 1900..2099 }
        val typeHint = when {
            SERIES_WORD.containsMatchIn(allText) -> "series"
            MOVIE_WORD.containsMatchIn(allText) -> "movie"
            else -> null
        }

        return Scan(
            CardInfo(title = title, year = year, typeHint = typeHint),
            "detail page: \"$title\"" + (year?.let { " ($it)" } ?: "") +
                (typeHint?.let { " [$it]" } ?: "")
        )
    }

    /** The app naming its own title view is the strongest signal available. */
    private fun byTitleViewId(nodes: List<Pair<String?, String>>): String? {
        for (hint in TITLE_ID_HINTS) {
            val hit = nodes.firstOrNull { (viewId, text) ->
                val id = viewId?.substringAfterLast('/')?.lowercase() ?: return@firstOrNull false
                id.contains(hint) &&
                    TITLE_ID_EXCLUSIONS.none { id.contains(it) } &&
                    TitleExtractor.clean(text) != null
            }
            if (hit != null) return TitleExtractor.clean(hit.second)
        }
        return null
    }

    /**
     * Fallback for pages that don't name their views: the first text near the
     * top that survives cleaning and isn't a button or layout label.
     */
    private fun firstPlausibleText(nodes: List<Pair<String?, String>>): String? =
        nodes.asSequence()
            .map { it.second }
            .filterNot { TitleExtractor.isAffordance(it) || TitleExtractor.isStructural(it) }
            .mapNotNull { TitleExtractor.clean(it) }
            .firstOrNull()

    private fun collect(
        node: AccessibilityNodeInfo,
        depth: Int,
        out: MutableList<Pair<String?, String>>
    ) {
        if (depth > MAX_DEPTH || out.size >= MAX_NODES) return

        val text = node.text?.toString()?.trim().orEmpty()
        val description = node.contentDescription?.toString()?.trim().orEmpty()
        val value = text.ifEmpty { description }
        if (value.isNotEmpty()) out.add(node.viewIdResourceName to value)

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            collect(child, depth + 1, out)
            if (out.size >= MAX_NODES) return
        }
    }

    private fun shortId(viewId: String?): String =
        viewId?.substringAfterLast('/') ?: "-"
}
