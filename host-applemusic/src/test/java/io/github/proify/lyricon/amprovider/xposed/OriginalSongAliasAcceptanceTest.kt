package io.github.proify.lyricon.amprovider.xposed

import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Alias
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Acceptance rule for a per-song original-name correction when the catalog keeps the account's
 * Latin title and only translates the artist credit.
 *
 * Device evidence (region = 简体中文（美国）, correction on): the account rows kept the US
 * title/artist because a candidate whose title equals the localized title was always rejected,
 * even when the artist was the original-region credit ("Wotoha" -> "をとは",
 * "PinocchioP" -> "ピノキオピー").
 */
class OriginalSongAliasAcceptanceTest {

    private fun alias(title: String, artist: String, language: String = "ja-JP") =
        Alias(title = title, artist = artist, language = language)

    private fun confident(alias: Alias, title: String, artist: String) =
        AppleInternalCatalogResolver.isConfidentOriginalSongAlias(
            alias = alias,
            localizedTitle = title,
            localizedArtist = artist,
        )

    @Test
    fun `same Latin title with the original-region artist is accepted`() {
        // Catchphrase / Yabasu & Hatsune Miku (the rejected device candidate).
        assertTrue(
            confident(
                alias("Catchphrase", "ヤバス, Hatsune Miku"),
                "Catchphrase",
                "Yabasu & Hatsune Miku",
            ),
        )
        // Planet Train (feat. botan) / Hige Driver.
        assertTrue(
            confident(
                alias("Planet Train (feat. botan)", "ヒゲドライバー"),
                "Planet Train (feat. botan)",
                "Hige Driver",
            ),
        )
        // J'sRPG / Wotoha.
        assertTrue(
            confident(alias("J'sRPG", "をとは"), "J'sRPG", "Wotoha"),
        )
        // PinocchioP keeps a Latin title on some tracks.
        assertTrue(
            confident(alias("GETCHA!", "ピノキオピー"), "GETCHA!", "PinocchioP"),
        )
        assertTrue(
            confident(alias("Sensitive Dance", "ばばなつみ"), "Sensitive Dance", "Natsumi Baba"),
        )
    }

    @Test
    fun `a translated title is still accepted through the original-title rule`() {
        // These never needed the new clause; they pin the fail-open behaviour around it.
        assertTrue(
            confident(alias("春めく", "はるまき"), "Harumeku", "Harumaki"),
        )
        assertTrue(
            confident(alias("煙とブルー", "スモーク"), "Smoke and Blue", "Smoke"),
        )
        assertTrue(
            confident(
                alias("魔法みたいなミュージック! (feat. 初音ミク)", "ピノキオピー"),
                "Music Like Magic! (feat. Hatsune Miku)",
                "PinocchioP",
            ),
        )
    }

    @Test
    fun `a candidate that differs in the title without non-Latin script stays rejected`() {
        assertFalse(
            confident(
                alias("Catchy Phrase", "Yabasu & Hatsune Miku"),
                "Catchphrase",
                "Yabasu & Hatsune Miku",
            ),
        )
    }

    @Test
    fun `an unrelated artist is not accepted merely because it is non-Latin`() {
        // Title differs: non-Latin artist script alone must never be enough.
        assertFalse(
            confident(
                alias("A Different Song", "ヤバス, Hatsune Miku"),
                "Catchphrase",
                "Yabasu & Hatsune Miku",
            ),
        )
        // Same title, but the candidate is a storefront localization of a Western artist.
        assertFalse(
            confident(
                alias("I Ain't Worried", "ワンリパブリック"),
                "I Ain't Worried",
                "OneRepublic",
            ),
        )
        // Same title, but a Han credit is a different name, not a reading of the Latin one.
        assertFalse(
            confident(alias("Let Me Know", "當山 みれい"), "Let Me Know", "MIREI"),
        )
        // A Latin-only artist is never evidence of an original region.
        assertFalse(
            confident(alias("Let Me Know", "Mirei"), "Let Me Know", "MIREI"),
        )
    }

    @Test
    fun `the correspondence guard reads kana and rejects unrelated Latin names`() {
        assertEquals("yabasu", AppleInternalCatalogResolver.kanaReadingOf("ヤバス"))
        assertEquals("otoha", AppleInternalCatalogResolver.kanaReadingOf("をとは"))
        assertEquals("higedoraiba", AppleInternalCatalogResolver.kanaReadingOf("ヒゲドライバー"))
        assertEquals("kya", AppleInternalCatalogResolver.kanaReadingOf("キャ"))
        assertNull(AppleInternalCatalogResolver.kanaReadingOf("當山"))
        assertNull(AppleInternalCatalogResolver.kanaReadingOf("OneRepublic"))
    }
}
