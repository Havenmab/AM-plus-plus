package dev.amenhancer.module.lyrics.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The device report these tests cover: `dev.amenhancer.module.lyrics.online.LyricLaneClassifier`
 * is present in the built dex but cannot be loaded, so every call site threw
 * `NoClassDefFoundError`. The translation lane then stayed empty — which is why the
 * manual "choose a translation from the source sheet" published nothing and the
 * automatic enrichment neither wrote the overlay nor saved a file — and the exception
 * the enricher swallowed took the whole completion with it.
 *
 * A JVM test cannot make a Kotlin object fail to load, so [OnlineTranslationExtraction.extract]
 * takes a classifier seam: these tests pass a function that throws, which is exactly
 * what the device sees at the call site. What must survive is the single rule the
 * classifier enforces — Chinese in the translation lane, kana/romaji in the
 * romanization lane, and nothing else in either — instead of a raw passthrough of the
 * provider's `trans` / `roma` columns (QQ Music's `trans` is the reading for a
 * Japanese song, and publishing that as the translation is the bug that started all of
 * this).
 */
class OnlineTranslationExtractionDegradedTest {

    /** What the device throws when the classifier class cannot be loaded. */
    private val unavailableClassifier:
        (String?, String?, String?) -> OnlineTranslationExtraction.LanePair =
        { _, _, _ ->
            throw NoClassDefFoundError("dev.amenhancer.module.lyrics.online.LyricLaneClassifier")
        }

    @Test
    fun `a provider translation still reaches the translation lane`() {
        val result = LyricsResult(
            tags = emptyMap(),
            original = listOf(line(1_000L, "First line")),
            translated = listOf(line(1_000L, "第一行：云层之后")),
            romanization = null,
        )

        val extracted = OnlineTranslationExtraction
            .extract(result, laneClassifier = unavailableClassifier)
            .single()

        assertEquals("第一行：云层之后", extracted.translation)
        assertNull(extracted.romanization)
    }

    @Test
    fun `a reading a provider sent as its translation never becomes the translation`() {
        // QQ Music answers a Japanese song's `trans` with the reading; rendered as
        // the page's 翻译 this was the original bug, and a passthrough fallback would
        // bring it straight back.
        val result = LyricsResult(
            tags = emptyMap(),
            original = listOf(line(1_000L, "君の名前を呼んだ")),
            translated = listOf(line(1_000L, "kimi no na wa yonda")),
            romanization = null,
        )

        val extracted = OnlineTranslationExtraction
            .extract(result, laneClassifier = unavailableClassifier)
            .single()

        assertNull(extracted.translation)
        assertEquals("kimi no na wa yonda", extracted.romanization)
    }

    @Test
    fun `kana in the translation column is published as a reading`() {
        val result = LyricsResult(
            tags = emptyMap(),
            original = listOf(line(1_000L, "夢ならばどれほどよかったでしょう")),
            translated = listOf(line(1_000L, "ゆめならば")),
            romanization = null,
        )

        val extracted = OnlineTranslationExtraction
            .extract(result, laneClassifier = unavailableClassifier)
            .single()

        assertNull(extracted.translation)
        assertEquals("ゆめならば", extracted.romanization)
    }

    @Test
    fun `an english line fills neither lane`() {
        val result = LyricsResult(
            tags = emptyMap(),
            original = listOf(line(1_000L, "First line")),
            translated = listOf(line(1_000L, "Hello world")),
            romanization = listOf(line(1_000L, "Hello world")),
        )

        val extracted = OnlineTranslationExtraction
            .extract(result, laneClassifier = unavailableClassifier)
            .single()

        assertNull(extracted.translation)
        assertNull(extracted.romanization)
    }

    private fun line(start: Long, text: String): LyricsLine = LyricsLine(
        start = start,
        end = start + 1_000L,
        words = listOf(LyricsWord(start, start + 1_000L, text)),
    )
}
