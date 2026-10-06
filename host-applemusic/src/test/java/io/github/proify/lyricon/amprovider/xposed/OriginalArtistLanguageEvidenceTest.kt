package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginalArtistLanguageEvidenceTest {
    @Test
    fun japaneseArtistNameProvidesJapaneseScriptEvidence() {
        assertTrue(
            AppleInternalCatalogResolver.hasCjkArtistScript("當山 みれい", "ja-JP"),
        )
        assertFalse(
            AppleInternalCatalogResolver.hasCjkArtistScript("MIREI", "ja-JP"),
        )
    }

    @Test
    fun koreanAndChineseScriptEvidenceStaysLanguageSpecific() {
        assertTrue(
            AppleInternalCatalogResolver.hasCjkArtistScript("방탄소년단", "ko-KR"),
        )
        assertTrue(
            AppleInternalCatalogResolver.hasCjkArtistScript("周杰伦", "zh-Hans-CN"),
        )
        assertFalse(
            AppleInternalCatalogResolver.hasCjkArtistScript("宇多田ヒカル", "zh-Hans-CN"),
        )
    }

    @Test
    fun artistRegionIsUsedOnlyAfterGenreAndIsrcSignalsAreAbsent() {
        assertEquals(
            listOf("ja-JP"),
            AppleInternalCatalogResolver.languageTagsForOriginalMetadata(
                genre = null,
                catalogGenres = emptyList(),
                isrc = null,
                artistLanguages = listOf("ja-JP"),
            ),
        )
        assertEquals(
            listOf("ko-KR"),
            AppleInternalCatalogResolver.languageTagsForOriginalMetadata(
                genre = null,
                catalogGenres = emptyList(),
                isrc = "KRABC0000000",
                artistLanguages = listOf("ja-JP"),
            ),
        )
    }

    @Test
    fun sameTitleJapaneseScriptArtistOnlyAliasIsApplied() {
        // The probe's target storefront is derived from the song's own origin (genre / ISRC
        // country), so a Japanese-script artist alias can only come from the song's own region.
        // MIREI -> 當山 みれい is exactly the correction this feature exists for.
        val alias = AppleInternalCatalogResolver.Alias(
            title = "Let Me Know",
            artist = "當山 みれい",
            language = "ja-JP",
        )

        assertTrue(
            AppleInternalCatalogResolver.isConfidentOriginalSongAlias(
                alias = alias,
                localizedTitle = "Let Me Know",
                localizedArtist = "MIREI",
            ),
        )
    }

    @Test
    fun sameTitleHanScriptArtistOnlyAliasIsApplied() {
        // Han-only original names have no reading we can compute, so no correspondence heuristic
        // could ever admit them; the region derivation is the evidence, not the script shape.
        val alias = AppleInternalCatalogResolver.Alias(
            title = "Lemon",
            artist = "米津玄師",
            language = "ja-JP",
        )

        assertTrue(
            AppleInternalCatalogResolver.isConfidentOriginalSongAlias(
                alias = alias,
                localizedTitle = "Lemon",
                localizedArtist = "Kenshi Yonezu",
            ),
        )
    }

    @Test
    fun sameEnglishTitleDoesNotAcceptRomanizedArtistOnlyAlias() {
        val alias = AppleInternalCatalogResolver.Alias(
            title = "Let Me Know",
            artist = "Mirei",
            language = "ja-JP",
        )

        assertFalse(
            AppleInternalCatalogResolver.isConfidentOriginalSongAlias(
                alias = alias,
                localizedTitle = "Let Me Know",
                localizedArtist = "MIREI",
            ),
        )
    }

    @Test
    fun aJapaneseStorefrontArtistAliasForAWesternSongIsAcceptedByTheSameTitleRule() {
        // The rule cannot tell this apart from a genuine correction, but the input is unreachable
        // through the probe: the target storefront comes from the song's own genre/ISRC, so a
        // Western release is never looked up in the Japanese storefront.  Accepting it is the
        // deliberate trade-off -- the correspondence heuristic that used to reject it also rejected
        // legitimate Han-only and kana originals, which is the costlier error of the two.
        val alias = AppleInternalCatalogResolver.Alias(
            title = "I Ain't Worried",
            artist = "ワンリパブリック",
            language = "ja-JP",
        )

        assertTrue(
            AppleInternalCatalogResolver.isConfidentOriginalSongAlias(
                alias = alias,
                localizedTitle = "I Ain't Worried",
                localizedArtist = "OneRepublic",
            ),
        )
    }

    @Test
    fun japaneseIsrcProvidesTheActualArtistOnlyTrustLanguage() {
        assertEquals(
            listOf("ja-JP"),
            AppleInternalCatalogResolver.languageTagsForIsrc("JPU901901790"),
        )
    }

}
