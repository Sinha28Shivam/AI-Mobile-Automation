package com.jarvis.assistant.tools

import java.text.Normalizer

/** An installed launchable app. */
data class AppEntry(val label: String, val packageName: String)

sealed interface AppResolution {
    data class Match(val app: AppEntry) : AppResolution
    data class Ambiguous(val candidates: List<AppEntry>) : AppResolution
    data object NotFound : AppResolution
}

/**
 * Pure matching of a spoken app name to installed app labels.
 *
 * Normalization: lowercase, diacritics removed ("Café" → "cafe"), punctuation removed, and a
 * leading "the" or trailing "app"/"application" dropped from both query and label.
 *
 * Tiers, best first; only the best non-empty tier is considered:
 * 1. exact normalized equality (also ignoring spaces: "whats app" = "WhatsApp"),
 * 2. label starts with the query on a word boundary ("youtube" → "YouTube Music"),
 * 3. every query word is a prefix of a label word, in order ("google cal" → "Google Calendar"),
 * 4. word-boundary contains ("maps" → "Google Maps"),
 * 5. plain substring of at least [MIN_CONTAINS_LENGTH] chars.
 * More than one distinct package in the winning tier is ambiguous (at most [MAX_CANDIDATES]
 * candidates, shortest labels first).
 */
object AppNameResolver {

    const val MAX_CANDIDATES = 3
    private const val MIN_CONTAINS_LENGTH = 3
    private const val MAX_QUERY_LENGTH = 60
    private val DIACRITICS = Regex("\\p{Mn}+")
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")

    /** Lowercase, diacritics and punctuation removed; keeps every word. */
    fun basicNormalize(s: String): String {
        val base = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(DIACRITICS, "")
        return base.replace("'", "").replace("\u2019", "").replace(NON_ALNUM, " ").trim()
    }

    /** [basicNormalize] plus a leading "the" and trailing "app"/"application" removed. */
    fun normalize(s: String): String {
        var n = basicNormalize(s).removePrefix("the ")
        for (suffix in listOf(" application", " app")) {
            if (n.endsWith(suffix) && n.length > suffix.length) n = n.removeSuffix(suffix)
        }
        return n.trim()
    }

    private fun compact(s: String) = s.replace(" ", "")

    fun resolve(query: String, apps: List<AppEntry>): AppResolution {
        if (query.length > MAX_QUERY_LENGTH) return AppResolution.NotFound
        val q = normalize(query)
        if (q.isEmpty()) return AppResolution.NotFound
        // "whats app" must still equal "WhatsApp" even though "app" is normally dropped.
        val qFullCompact = compact(basicNormalize(query).removePrefix("the "))
        val qc = compact(q)
        val qWords = q.split(" ")
        val unique = apps.distinctBy { it.packageName }.map { it to normalize(it.label) }.filter { it.second.isNotEmpty() }

        val tiers: List<(String) -> Boolean> = listOf(
            { label -> label == q || compact(label) == qc || compact(label) == qFullCompact },
            { label -> label.startsWith("$q ") || compact(label).startsWith(qc) },
            { label -> wordsPrefixInOrder(qWords, label.split(" ")) },
            { label -> " $label ".contains(" $q ") },
            { label -> qc.length >= MIN_CONTAINS_LENGTH && compact(label).contains(qc) },
        )
        for (tier in tiers) {
            val hits = unique.filter { tier(it.second) }.map { it.first }
            when {
                hits.size == 1 -> return AppResolution.Match(hits.single())
                hits.size > 1 -> return AppResolution.Ambiguous(
                    hits.sortedWith(compareBy({ it.label.length }, { it.label.lowercase() })).take(MAX_CANDIDATES),
                )
            }
        }
        return AppResolution.NotFound
    }

    private fun wordsPrefixInOrder(query: List<String>, label: List<String>): Boolean {
        if (query.size < 2 || query.size > label.size) return false
        var li = 0
        for (w in query) {
            while (li < label.size && !label[li].startsWith(w)) li++
            if (li == label.size) return false
            li++
        }
        return label.first().startsWith(query.first())
    }
}
