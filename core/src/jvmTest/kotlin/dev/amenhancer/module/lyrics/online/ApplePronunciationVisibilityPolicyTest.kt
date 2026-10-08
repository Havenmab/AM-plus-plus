package dev.amenhancer.module.lyrics.online

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from HLE's `common.lyric.ApplePronunciationVisibilityPolicyTest`, over
 * the document-lane decision surface. HLE's `filterSong` / `filterOnlineLines`
 * are deliberately not ported: they strip Apple's own `roma` too, which the
 * document lane must never do (Apple's transliterations lane is left untouched
 * and the online pronunciation is simply not published), so the policy is
 * asserted directly instead of through those helpers.
 */
class ApplePronunciationVisibilityPolicyTest {
    @Test
    fun `recognizes only explicit Mandarin genres`() {
        listOf("Mandopop", "Mandarin Pop", "国语流行", "國語流行", "华语流行").forEach {
            assertTrue(it, ApplePronunciationVisibilityPolicy.isMandarinGenre(it))
        }
        listOf("Cantopop", "Cantonese Pop", "粤语流行", "粵語流行", "C-Pop", null).forEach {
            assertFalse(it, ApplePronunciationVisibilityPolicy.isMandarinGenre(it))
        }
    }

    @Test
    fun `uses Apple pronunciation language before genre metadata`() {
        assertTrue(
            ApplePronunciationVisibilityPolicy.shouldHide(
                genre = null,
                pronunciationLanguages = listOf("zh-Latn-pinyin"),
                hideMandarinPinyin = true,
            )
        )
        assertFalse(
            ApplePronunciationVisibilityPolicy.shouldHide(
                genre = "国语流行",
                pronunciationLanguages = listOf("zh-Latn-jyutping"),
                hideMandarinPinyin = true,
            )
        )
    }

    @Test
    fun `the switch off hides nothing and an unknown genre hides nothing`() {
        assertFalse(
            ApplePronunciationVisibilityPolicy.shouldHide(
                genre = "Mandopop",
                hideMandarinPinyin = false,
            )
        )
        assertFalse(
            ApplePronunciationVisibilityPolicy.shouldHide(
                genre = null,
                hideMandarinPinyin = true,
            )
        )
        assertFalse(
            ApplePronunciationVisibilityPolicy.shouldHide(
                genre = "J-Pop",
                hideMandarinPinyin = true,
            )
        )
    }

    @Test
    fun `hides a Mandarin song from its stored genre and languages`() {
        assertTrue(
            ApplePronunciationVisibilityPolicy.shouldHide(
                document = song("Mandopop"),
                hideMandarinPinyin = true,
            )
        )
        assertTrue(
            ApplePronunciationVisibilityPolicy.shouldHide(
                document = song(
                    genre = null,
                    pronunciationLanguages = listOf("zh-Latn-pinyin"),
                ),
                hideMandarinPinyin = true,
            )
        )
        assertFalse(
            ApplePronunciationVisibilityPolicy.shouldHide(
                document = song("Cantopop", listOf("yue-Latn-jyutping")),
                hideMandarinPinyin = true,
            )
        )
        assertFalse(
            ApplePronunciationVisibilityPolicy.shouldHide(
                document = song("J-Pop"),
                hideMandarinPinyin = true,
            )
        )
    }

    @Test
    fun `classifies pronunciation language tags`() {
        assertTrue(ApplePronunciationVisibilityPolicy.isMandarinPronunciationLanguage("zh-Latn-pinyin"))
        assertTrue(ApplePronunciationVisibilityPolicy.isMandarinPronunciationLanguage("cmn-Latn"))
        assertTrue(ApplePronunciationVisibilityPolicy.isMandarinPronunciationLanguage("cmn-Latn-pinyin"))
        assertFalse(ApplePronunciationVisibilityPolicy.isMandarinPronunciationLanguage("zh-Hans"))
        assertTrue(ApplePronunciationVisibilityPolicy.isCantonesePronunciationLanguage("zh-Latn-jyutping"))
        assertTrue(ApplePronunciationVisibilityPolicy.isCantonesePronunciationLanguage("yue-Latn"))
        assertFalse(ApplePronunciationVisibilityPolicy.isCantonesePronunciationLanguage("cmn-Latn-pinyin"))
    }

    private fun song(
        genre: String?,
        pronunciationLanguages: List<String> = emptyList(),
    ): NativeLyricDocument = NativeLyricDocument(
        metadata = buildList {
            genre?.let { add(LyricMetadataKeys.APPLE_CATALOG_GENRE to it) }
            pronunciationLanguages.takeIf(List<String>::isNotEmpty)?.let {
                add(LyricMetadataKeys.APPLE_PRONUNCIATION_LANGUAGES to it.joinToString(","))
            }
        }.takeIf(List<Pair<String, String>>::isNotEmpty)
            ?.let { lyricMetadataOf(*it.toTypedArray()) },
    )
}
