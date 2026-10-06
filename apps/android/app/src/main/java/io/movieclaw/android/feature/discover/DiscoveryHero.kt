package io.movieclaw.android.feature.discover

import io.movieclaw.android.core.model.DiscoveredTitle

/** Prefer the server's featured section; otherwise feature artwork from its first usable row. */
internal fun discoveryHeroTitles(rows: List<DiscoverRow>): List<DiscoveredTitle> {
    fun DiscoveredTitle.isUsable() = titleRef.isNotBlank() &&
        (!backdropUrl.isNullOrBlank() || !posterUrl.isNullOrBlank())
    val featured = rows.firstOrNull { row ->
        row.section.presentation == "hero" && row.titles.any { it.isUsable() }
    } ?: rows.firstOrNull { row -> row.titles.any { it.isUsable() } }
    return featured?.titles.orEmpty()
        .filter { it.isUsable() }
        .distinctBy { it.titleRef }
        .take(6)
}
