package io.github.proify.lyricon.amprovider.xposed

import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Alias
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HLE's confidence rule for a per-song original-name candidate, ported verbatim:
 *
 * ```
 * isOriginalTitle(alias, localizedTitle) || nonLatinLetterCount(localizedTitle) > 0
 * ```
 *
 * A candidate is confident only when its title differs from the localized title and carries
 * non-Latin script, or when the localized title itself already carries non-Latin script.  A
 * catalog row that keeps the account's Latin title and only swaps the artist credit is therefore
 * rejected here; the origin-region probe's own provenance is the evidence for those, and HLE keeps
 * that evidence out of this title-confidence predicate.
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
    fun `a translated title with non-Latin script is accepted through the original-title rule`() {
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
    fun `a non-Latin localized title trusts the candidate regardless of its title`() {
        // nonLatinLetterCount(localizedTitle) > 0 is the second condition.
        assertTrue(
            confident(
                alias("ハルメク", "はるまき"),
                "春めく",
                "はるまき",
            ),
        )
    }

    @Test
    fun `a same Latin title with the original-region artist is not confident by title alone`() {
        // Catchphrase / Yabasu & Hatsune Miku: the title is unchanged, so the title rule rejects
        // it.  HLE's origin-region lookup carries the provenance for this correction instead.
        assertFalse(
            confident(
                alias("Catchphrase", "ヤバス, Hatsune Miku"),
                "Catchphrase",
                "Yabasu & Hatsune Miku",
            ),
        )
        assertFalse(
            confident(
                alias("Planet Train (feat. botan)", "ヒゲドライバー"),
                "Planet Train (feat. botan)",
                "Hige Driver",
            ),
        )
        assertFalse(
            confident(alias("J'sRPG", "をとは"), "J'sRPG", "Wotoha"),
        )
        assertFalse(
            confident(alias("GETCHA!", "ピノキオピー"), "GETCHA!", "PinocchioP"),
        )
        assertFalse(
            confident(alias("Sensitive Dance", "ばばなつみ"), "Sensitive Dance", "Natsumi Baba"),
        )
    }

    @Test
    fun `a same-title artist-only alias is rejected for kana and kanji originals alike`() {
        assertFalse(
            confident(alias("Lemon", "米津玄師"), "Lemon", "Kenshi Yonezu"),
        )
        assertFalse(
            confident(alias("Let Me Know", "當山 みれい"), "Let Me Know", "MIREI"),
        )
        assertFalse(
            confident(alias("I Ain't Worried", "ワンリパブリック"), "I Ain't Worried", "OneRepublic"),
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
        assertFalse(
            confident(alias("Let Me Know", "Mirei"), "Let Me Know", "MIREI"),
        )
    }
}
