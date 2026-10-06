package io.github.proify.lyricon.amprovider.xposed

import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Alias
import org.junit.Assert.assertFalse
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
 *
 * The probe's target region is derived from the song's own origin
 * ([AppleInternalCatalogResolver.languageTagsForOriginalMetadata] maps genre and the ISRC
 * country prefix to a language, then
 * [AppleInternalCatalogResolver.storefrontForOriginalLanguage] maps it to a storefront).  A
 * lookup that reached the Japanese storefront is therefore a Japanese-origin release by
 * construction, and what that storefront reports *is* the original-region form -- no extra
 * artist-name correspondence is needed, and requiring one rejected kanji-bearing originals.
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
    fun `a kanji original artist is accepted`() {
        // Kenshi Yonezu -> 米津玄師.  The removed kana-only rule rejected this because the
        // original credit is Han, which is a common Japanese artist-name shape.  The Japanese
        // storefront only reports this candidate for a Japanese-origin release, so the kanji
        // credit is the original-region form, not a translation of a Western artist.
        assertTrue(
            confident(alias("Lemon", "米津玄師"), "Lemon", "Kenshi Yonezu"),
        )
        // MIREI -> 當山 みれい (kanji + kana), the other kanji shape the old Han rule blocked.
        assertTrue(
            confident(alias("Let Me Know", "當山 みれい"), "Let Me Know", "MIREI"),
        )
    }

    @Test
    fun `candidates the removed correspondence heuristic pinned are now accepted`() {
        // These three shared too few characters with the localized Latin artist under the old
        // heuristic (3, Han-blocked, and 2 respectively) and were rejected.  They are accepted
        // now because the guard was removed: each is a same-title candidate carrying non-Latin
        // script, and it can only have come back from the storefront matching the song's own
        // origin, so it is the original-region credit by construction.
        //
        // OneRepublic -> ワンリパブリック (3 shared chars).  "I Ain't Worried" is only probed
        // against the Japanese storefront if its genre/ISRC says Japanese origin, so a Western
        // artist's storefront localization is not reachable here.
        assertTrue(
            confident(
                alias("I Ain't Worried", "ワンリパブリック"),
                "I Ain't Worried",
                "OneRepublic",
            ),
        )
        // Some Artist -> アーティスト (2 shared chars).
        assertTrue(
            confident(alias("Some Song", "アーティスト"), "Some Song", "Some Artist"),
        )
    }

    @Test
    fun `a translated title is still accepted through the original-title rule`() {
        // These never needed the same-title clause; they pin the fail-open behaviour around it.
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
    fun `an unrelated artist with a differing title stays rejected`() {
        // Title differs and carries no non-Latin script: the non-Latin artist alone must not
        // make an unrelated catalog row look like an original.
        assertFalse(
            confident(
                alias("A Different Song", "ヤバス, Hatsune Miku"),
                "Catchphrase",
                "Yabasu & Hatsune Miku",
            ),
        )
    }

    @Test
    fun `a same-title candidate with no non-Latin script stays rejected`() {
        // A Latin-only artist is never evidence of an original region.
        assertFalse(
            confident(alias("Let Me Know", "Mirei"), "Let Me Know", "MIREI"),
        )
    }
}
