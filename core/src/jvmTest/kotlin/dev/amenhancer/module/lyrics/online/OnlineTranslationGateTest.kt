package dev.amenhancer.module.lyrics.online

import dev.amenhancer.module.hook.TtmlDocumentMetadata
import dev.amenhancer.module.hook.TtmlTimingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the diagnostic decision helper: each rejection case must report the
 * exact reason the device log will be grepped for, and the helper must agree
 * with the production decision it explains.
 */
class OnlineTranslationGateTest {

    @Test
    fun `each rejection case reports its own reason`() {
        assertEquals(
            OnlineTranslationReason.NO_CANDIDATES,
            OnlineTranslationGate.firstBlocker(
                candidateCount = 0,
                baseLineCount = 2,
                document = foreignUntranslated(),
                lines = untranslatedLines(),
            ),
        )
        assertEquals(
            OnlineTranslationReason.EMPTY_APPLE_DOCUMENT,
            OnlineTranslationGate.firstBlocker(
                candidateCount = 1,
                baseLineCount = 0,
                document = foreignUntranslated(),
                lines = emptyList(),
            ),
        )
        assertEquals(
            OnlineTranslationReason.NO_LINE_NEEDS_TRANSLATION,
            OnlineTranslationGate.firstBlocker(
                candidateCount = 1,
                baseLineCount = 2,
                document = foreignUntranslated(),
                lines = untranslatedLines(),
                translationRequested = false,
            ),
        )
        assertEquals(
            OnlineTranslationReason.APPLE_ALREADY_TRANSLATED,
            OnlineTranslationGate.firstBlocker(
                candidateCount = 1,
                baseLineCount = 2,
                document = foreignUntranslated().copy(hasTranslation = true),
                lines = untranslatedLines(),
            ),
        )
        assertEquals(
            OnlineTranslationReason.FULLY_CHINESE,
            OnlineTranslationGate.firstBlocker(
                candidateCount = 1,
                baseLineCount = 2,
                document = foreignUntranslated().copy(language = "zh-Hans"),
                lines = listOf(
                    NativeLyricLine(begin = 0L, text = "感谢你曾来过"),
                    NativeLyricLine(begin = 1_000L, text = "我早已明白了"),
                ),
            ),
        )
        assertEquals(
            OnlineTranslationReason.NO_LINE_NEEDS_TRANSLATION,
            OnlineTranslationGate.firstBlocker(
                candidateCount = 1,
                baseLineCount = 1,
                document = foreignUntranslated(),
                lines = listOf(
                    NativeLyricLine(begin = 0L, text = "Hello", translation = "你好"),
                ),
            ),
        )
        assertEquals(
            OnlineTranslationReason.PROCEED,
            OnlineTranslationGate.firstBlocker(
                candidateCount = 1,
                baseLineCount = 2,
                document = foreignUntranslated(),
                lines = untranslatedLines(),
            ),
        )
    }

    @Test
    fun `the gate agrees with the production enrichment decision`() {
        val documents = listOf(
            foreignUntranslated(),
            foreignUntranslated().copy(hasTranslation = true),
            foreignUntranslated().copy(language = "zh-Hans"),
            foreignUntranslated().copy(timingMode = TtmlTimingMode.NON_WORD),
        )
        val lineCases = listOf(
            untranslatedLines(),
            listOf(NativeLyricLine(begin = 0L, text = "Hello", translation = "你好")),
            listOf(
                NativeLyricLine(begin = 0L, text = "感谢你曾来过"),
                NativeLyricLine(begin = 1_000L, text = "我早已明白了"),
            ),
            emptyList<NativeLyricLine>(),
        )

        documents.forEach { document ->
            lineCases.forEach { lines ->
                listOf(true, false).forEach { requested ->
                    val gate = OnlineTranslationGate.firstBlocker(
                        candidateCount = 2,
                        baseLineCount = lines.size,
                        document = document,
                        lines = lines,
                        translationRequested = requested,
                    )
                    val production = OnlineEnrichmentPolicy.needsOnlineEnrichment(
                        document = document,
                        lines = lines,
                        translationRequested = requested,
                        pronunciationRequested = false,
                    )
                    assertEquals(
                        "gate=$gate production=$production document=$document",
                        production,
                        gate == OnlineTranslationReason.PROCEED,
                    )
                }
            }
        }
        assertTrue(
            OnlineTranslationGate.firstBlocker(
                candidateCount = 0,
                baseLineCount = 2,
                document = foreignUntranslated(),
                lines = untranslatedLines(),
            ) != OnlineTranslationReason.PROCEED,
        )
    }

    private fun foreignUntranslated() = TtmlDocumentMetadata(
        timingMode = TtmlTimingMode.WORD,
        language = "ja",
        hasTranslation = false,
    )

    private fun untranslatedLines() = listOf(
        NativeLyricLine(begin = 0L, text = "Hello world"),
        NativeLyricLine(begin = 1_000L, text = "Second line"),
    )
}
