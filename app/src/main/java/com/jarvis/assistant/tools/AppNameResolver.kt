package com.jarvis.assistant.tools

/** An installed launchable app. */
data class AppEntry(val label: String, val packageName: String)

sealed interface AppResolution {
    data class Match(val app: AppEntry) : AppResolution
    data class Ambiguous(val candidates: List<AppEntry>) : AppResolution
    data object NotFound : AppResolution
}

/**
 * Pure matching of a spoken app name to installed app labels. Tiers, best first:
 * exact normalized equality, label prefix, word-boundary contains, plain contains.
 * Only the best non-empty tier is considered; more than one distinct package there is ambiguous.
 */
object AppNameResolver {

    fun normalize(s: String): String =
        s.lowercase()
            .replace("'", "").replace("\u2019", "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    private fun compact(s: String) = normalize(s).replace(" ", "")

    fun resolve(query: String, apps: List<AppEntry>): AppResolution {
        val q = normalize(query)
        if (q.isEmpty()) return AppResolution.NotFound
        val qc = q.replace(" ", "")
        val unique = apps.distinctBy { it.packageName }

        val tiers: List<(AppEntry) -> Boolean> = listOf(
            { normalize(it.label) == q || compact(it.label) == qc },
            { normalize(it.label).startsWith("$q ") || compact(it.label).startsWith(qc) },
            { " ${normalize(it.label)} ".contains(" $q ") },
            { qc.length >= MIN_CONTAINS_LENGTH && compact(it.label).contains(qc) },
        )
        for (tier in tiers) {
            val hits = unique.filter(tier)
            when {
                hits.size == 1 -> return AppResolution.Match(hits.single())
                hits.size > 1 -> return AppResolution.Ambiguous(hits.sortedBy { it.label.lowercase() })
            }
        }
        return AppResolution.NotFound
    }

    private const val MIN_CONTAINS_LENGTH = 3
}
