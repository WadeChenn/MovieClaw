package io.movieclaw.android.feature.discover

import io.movieclaw.android.core.model.DiscoveredTitle
import io.movieclaw.android.core.model.DiscoverySection
import org.junit.Assert.*
import org.junit.Test

class DiscoveryHeroTest {
    private fun title(ref: String, poster: String? = "https://img.example/poster.jpg") =
        DiscoveredTitle(titleRef = ref, title = ref, provider = ref.substringBefore(':'), posterUrl = poster)

    private fun row(presentation: String, vararg titles: DiscoveredTitle) =
        DiscoverRow(DiscoverySection(presentation = presentation), titles.toList())

    @Test fun `explicit server hero wins over first ranked row`() {
        val rows = listOf(row("ranked-row", title("tmdb:1")), row("hero", title("tmdb:2")))
        assertEquals(listOf("tmdb:2"), discoveryHeroTitles(rows).map { it.titleRef })
    }

    @Test fun `douban poster rows produce a hero without borrowing tmdb or removing rows`() {
        val rows = listOf(row("ranked-row", title("douban:1"), title("douban:2")), row("poster-row", title("douban:3")))
        assertEquals(listOf("douban:1", "douban:2"), discoveryHeroTitles(rows).map { it.titleRef })
        assertEquals(2, rows.size)
        assertTrue(discoveryHeroTitles(rows).all { it.provider == "douban" })
    }

    @Test fun `empty hero and imageless first row fall back to later artwork`() {
        val rows = listOf(row("hero", title("", "x")), row("ranked-row", title("douban:1", "")), row("poster-row", title("douban:2")))
        assertEquals(listOf("douban:2"), discoveryHeroTitles(rows).map { it.titleRef })
    }

    @Test fun `hero ignores duplicate refs and missing artwork and limits to six`() {
        val titles = (1..9).map { title("douban:$it") }
        val rows = listOf(DiscoverRow(DiscoverySection(), listOf(title("", "x"), title("douban:0", null), titles[0]) + titles))
        assertEquals((1..6).map { "douban:$it" }, discoveryHeroTitles(rows).map { it.titleRef })
    }

    @Test fun `backdrop alone is enough and empty feeds do not invent titles`() {
        val t = title("douban:1", null).copy(backdropUrl = "https://img.example/backdrop.jpg")
        assertEquals(listOf(t), discoveryHeroTitles(listOf(row("poster-row", t))))
        assertTrue(discoveryHeroTitles(emptyList()).isEmpty())
        assertTrue(discoveryHeroTitles(listOf(row("hero", title("tmdb:1", null)))).isEmpty())
    }
}
