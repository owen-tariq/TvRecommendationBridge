package com.owentariq.tvhop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * These cases are the label shapes Google TV / Android TV launchers have been
 * observed to put on a recommendation card. The synopsis case is the one that
 * caused v1.0.0 to open unrelated titles.
 */
class TitleExtractorTest {

    @Test
    fun `plain title`() {
        val card = TitleExtractor.extract(listOf("Dune: Part Two"))!!
        assertEquals("Dune: Part Two", card.title)
    }

    @Test
    fun `comma separated metadata is stripped but the year is kept`() {
        val card = TitleExtractor.extract(listOf("Dune: Part Two, movie, 2024"))!!
        assertEquals("Dune: Part Two", card.title)
        assertEquals(2024, card.year)
        assertEquals("movie", card.typeHint)
    }

    @Test
    fun `bullet separated metadata is stripped`() {
        val card = TitleExtractor.extract(listOf("Dune: Part Two • 2024 • Sci-fi"))!!
        assertEquals("Dune: Part Two", card.title)
        assertEquals(2024, card.year)
    }

    @Test
    fun `leading verb and parenthesised year are stripped`() {
        val card = TitleExtractor.extract(listOf("Play Dune: Part Two (2024)"))!!
        assertEquals("Dune: Part Two", card.title)
        assertEquals(2024, card.year)
    }

    /** The v1.0.0 bug: the longest string won, so the synopsis was searched. */
    @Test
    fun `synopsis in content description does not become the title`() {
        val card = TitleExtractor.extract(
            listOf(
                "Dune: Part Two",
                "Dune: Part Two. Paul Atreides unites with Chani and the Fremen " +
                    "while seeking revenge against the conspirators who destroyed " +
                    "his family. 2024"
            )
        )!!
        assertEquals("Dune: Part Two", card.title)
        assertEquals(2024, card.year)
    }

    /** Even when the synopsis is the only thing available, trim to the title. */
    @Test
    fun `synopsis alone is trimmed to its first sentence`() {
        val card = TitleExtractor.extract(
            listOf(
                "Severance. Employees undergo a procedure separating their work " +
                    "and personal memories. 2022"
            )
        )!!
        assertEquals("Severance", card.title)
        assertEquals(2022, card.year)
    }

    @Test
    fun `a title containing a comma survives`() {
        val card = TitleExtractor.extract(listOf("Crouching Tiger, Hidden Dragon"))!!
        assertEquals("Crouching Tiger, Hidden Dragon", card.title)
    }

    @Test
    fun `series are detected from the label`() {
        val card = TitleExtractor.extract(listOf("Breaking Bad, TV show, 2008"))!!
        assertEquals("Breaking Bad", card.title)
        assertEquals("series", card.typeHint)
        assertEquals(2008, card.year)
    }

    @Test
    fun `spanish labels work too`() {
        val card = TitleExtractor.extract(listOf("Ver El Hoyo, película, 2019"))!!
        assertEquals("El Hoyo", card.title)
        assertEquals("movie", card.typeHint)
        assertEquals(2019, card.year)
    }

    @Test
    fun `ui affordances are rejected`() {
        assertNull(TitleExtractor.extract(listOf("Play")))
        assertNull(TitleExtractor.extract(listOf("More info")))
        assertNull(TitleExtractor.extract(listOf("2h 46m")))
    }

    @Test
    fun `node text is preferred over content description`() {
        val card = TitleExtractor.extract(
            listOf("Severance", "Watch Severance now on Apple TV+, drama series, 2022")
        )!!
        assertEquals("Severance", card.title)
    }

    @Test
    fun `abbreviated titles are not split at the period`() {
        val card = TitleExtractor.extract(listOf("Dr. No"))!!
        assertEquals("Dr. No", card.title)
    }
}
