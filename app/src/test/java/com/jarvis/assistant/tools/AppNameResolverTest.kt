package com.jarvis.assistant.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNameResolverTest {

    private val apps = listOf(
        AppEntry("YouTube", "com.google.android.youtube"),
        AppEntry("YouTube Music", "com.google.android.apps.youtube.music"),
        AppEntry("WhatsApp", "com.whatsapp"),
        AppEntry("WhatsApp Business", "com.whatsapp.w4b"),
        AppEntry("Google Maps", "com.google.android.apps.maps"),
        AppEntry("Mi Calculator", "com.miui.calculator"),
        AppEntry("Notes", "com.miui.notes"),
        AppEntry("Google Keep Notes", "com.google.android.keep"),
    )

    private fun match(q: String) = (AppNameResolver.resolve(q, apps) as AppResolution.Match).app.packageName

    @Test fun exactMatchBeatsPrefix() {
        assertEquals("com.google.android.youtube", match("youtube"))
        assertEquals("com.whatsapp", match("WhatsApp"))
        assertEquals("com.whatsapp", match("whats app"))
        assertEquals("com.miui.notes", match("notes"))
    }

    @Test fun prefixAndContains() {
        assertEquals("com.google.android.apps.youtube.music", match("youtube music"))
        assertEquals("com.google.android.apps.maps", match("google maps"))
        assertEquals("com.google.android.apps.maps", match("maps"))
        assertEquals("com.miui.calculator", match("calculator"))
    }

    @Test fun ambiguous() {
        val r = AppNameResolver.resolve("google", apps)
        assertTrue(r is AppResolution.Ambiguous)
        assertEquals(setOf("Google Keep Notes", "Google Maps"), (r as AppResolution.Ambiguous).candidates.map { it.label }.toSet())
    }

    @Test fun duplicatePackageNotAmbiguous() {
        val dup = listOf(AppEntry("Camera", "com.cam"), AppEntry("Camera", "com.cam"))
        assertTrue(AppNameResolver.resolve("camera", dup) is AppResolution.Match)
    }

    @Test fun diacriticsAppSuffixAndMultiWord() {
        val more = apps + listOf(
            AppEntry("Café Finder", "com.cafe"),
            AppEntry("Google Calendar", "com.google.android.calendar"),
            AppEntry("Spotify: Music and Podcasts", "com.spotify.music"),
        )
        fun m(q: String) = (AppNameResolver.resolve(q, more) as AppResolution.Match).app.packageName
        assertEquals("com.cafe", m("cafe finder"))
        assertEquals("com.cafe", m("Café"))
        assertEquals("com.google.android.youtube", m("youtube app"))
        assertEquals("com.google.android.youtube", m("the YouTube application"))
        assertEquals("com.google.android.calendar", m("google cal"))
        assertEquals("com.spotify.music", m("spotify"))
        assertEquals("com.google.android.apps.youtube.music", m("youtube mus"))
    }

    @Test fun ambiguousIsCappedAtThreeShortestFirst() {
        val many = listOf(
            AppEntry("Google Photos", "p"), AppEntry("Google Maps", "m"), AppEntry("Google Keep Notes", "k"),
            AppEntry("Google Fit", "f"), AppEntry("Google Wallet", "w"),
        )
        val r = AppNameResolver.resolve("google", many) as AppResolution.Ambiguous
        assertEquals(listOf("Google Fit", "Google Maps", "Google Photos"), r.candidates.map { it.label })
    }

    @Test fun exactTierBeatsContainsTier() {
        val list = listOf(AppEntry("Notes", "n"), AppEntry("Keep Notes", "k"), AppEntry("Notes Pro", "p"))
        assertEquals("n", (AppNameResolver.resolve("notes", list) as AppResolution.Match).app.packageName)
    }

    @Test fun missing() {
        assertEquals(AppResolution.NotFound, AppNameResolver.resolve("x".repeat(100), apps))
        assertEquals(AppResolution.NotFound, AppNameResolver.resolve("telegram", apps))
        assertEquals(AppResolution.NotFound, AppNameResolver.resolve("", apps))
        assertEquals(AppResolution.NotFound, AppNameResolver.resolve("yo", emptyList()))
    }
}
