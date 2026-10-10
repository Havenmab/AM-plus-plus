package dev.amenhancer.module.lyrics.online

import dev.amenhancer.module.hook.TtmlDocumentMetadata
import dev.amenhancer.module.hook.TtmlTimingMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineEnrichmentPolicyTest {

    private fun document(hasTranslation: Boolean = false) = TtmlDocumentMetadata(
        timingMode = TtmlTimingMode.WORD,
        language = "ja",
        hasTranslation = hasTranslation,
    )

    @Test
    fun `a document already carrying a translation lane needs no translation pass`() {
        assertFalse(
            OnlineEnrichmentPolicy.needsOnlineEnrichment(
                document = document(hasTranslation = true),
                lines = listOf(NativeLyricLine(text = "hello world")),
            ),
        )
    }

    @Test
    fun `a line without a meaningful translation triggers enrichment`() {
        assertTrue(
            OnlineEnrichmentPolicy.needsOnlineEnrichment(
                document = document(),
                lines = listOf(
                    NativeLyricLine(text = "hello world"),
                    NativeLyricLine(text = "second line"),
                ),
            ),
        )
    }

    @Test
    fun `every line translated means no enrichment`() {
        assertFalse(
            OnlineEnrichmentPolicy.needsOnlineEnrichment(
                document = document(),
                lines = listOf(
                    NativeLyricLine(text = "hello world", translation = "你好，世界"),
                    NativeLyricLine(text = "second line", translation = "第二行"),
                ),
            ),
        )
    }

    @Test
    fun `a slash only translation placeholder is not meaningful`() {
        assertTrue(
            OnlineEnrichmentPolicy.needsOnlineEnrichment(
                document = document(),
                lines = listOf(NativeLyricLine(text = "Listen", translation = "// //")),
            ),
        )
    }

    @Test
    fun `pronunciation is never a reason to enrich`() {
        // Every line is translated but none carries a romanization, and Apple's
        // own translation lane is present: the Apple-only policy adds no
        // pronunciation lane, so there is nothing to do.
        assertFalse(
            OnlineEnrichmentPolicy.needsOnlineEnrichment(
                document = document(hasTranslation = true),
                lines = listOf(
                    NativeLyricLine(text = "hello world", translation = "你好，世界"),
                    NativeLyricLine(text = "second line", translation = "第二行"),
                ),
            ),
        )
    }

    @Test
    fun `a fully chinese song never gets a translation lane`() {
        assertFalse(
            OnlineEnrichmentPolicy.needsOnlineEnrichment(
                document = document(),
                lines = listOf(
                    NativeLyricLine(text = "感谢你曾来过"),
                    NativeLyricLine(text = "我早已明白了"),
                ),
            ),
        )
    }

    @Test
    fun `an english interjection does not turn a chinese song into a translation target`() {
        assertFalse(
            OnlineEnrichmentPolicy.needsOnlineEnrichment(
                document = document(),
                lines = listOf(
                    NativeLyricLine(text = "whoa"),
                    NativeLyricLine(text = "感谢你曾来过"),
                ),
            ),
        )
    }

    @Test
    fun `no lines means no enrichment`() {
        assertFalse(
            OnlineEnrichmentPolicy.needsOnlineEnrichment(
                document = document(),
                lines = emptyList(),
            ),
        )
    }

    @Test
    fun `blank and slash only lines do not trigger enrichment`() {
        assertFalse(
            OnlineEnrichmentPolicy.needsOnlineEnrichment(
                document = document(hasTranslation = true),
                lines = listOf(
                    NativeLyricLine(text = "  "),
                    NativeLyricLine(text = null),
                ),
            ),
        )
    }

    @Test
    fun `raw ttml overload reads the existing translation lane`() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" itunes:timing="Word" xml:lang="ja">
              <head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
                <translations><translation type="subtitle" xml:lang="zh-Hans">
                  <text for="L1">你好</text>
                </translation></translations>
              </iTunesMetadata></metadata></head>
              <body><div><p itunes:key="L1">hello</p></div></body>
            </tt>
        """.trimIndent()

        assertFalse(
            OnlineEnrichmentPolicy.needsOnlineEnrichment(
                ttml = ttml,
                lines = listOf(NativeLyricLine(text = "hello")),
            ),
        )
    }

    @Test
    fun `raw ttml overload without a translation lane needs enrichment`() {
        val ttml =
            "<tt xmlns=\"http://www.w3.org/ns/ttml\" xml:lang=\"ja\">" +
                "<body><div><p>hello</p></div></body></tt>"

        assertTrue(
            OnlineEnrichmentPolicy.needsOnlineEnrichment(
                ttml = ttml,
                lines = listOf(NativeLyricLine(text = "hello")),
            ),
        )
    }
}
