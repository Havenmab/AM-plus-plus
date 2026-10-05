package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtmlTimingPolicyTest {
    @Test
    fun `accepts Word root timing with single or double quotes and whitespace`() {
        assertEquals(
            TtmlTimingMode.WORD,
            TtmlTimingPolicy.modeOf(
                "<tt xmlns:itunes=\"urn\" itunes:timing = ' Word '><body/></tt>",
            ),
        )
    }

    @Test
    fun `missing and Line timing are non-word`() {
        assertEquals(TtmlTimingMode.NON_WORD, TtmlTimingPolicy.modeOf("<tt><body/></tt>"))
        assertEquals(
            TtmlTimingMode.NON_WORD,
            TtmlTimingPolicy.modeOf("<tt itunes:timing=\"Line\"><body/></tt>"),
        )
    }

    @Test
    fun `foreign Word metadata without translation needs fallback`() {
        val metadata = TtmlTimingPolicy.metadataOf(
            """
            <tt xml:lang="en-US" itunes:timing="Word">
              <body><div><p begin="0s" end="1s">hello</p></div></body>
            </tt>
            """.trimIndent(),
        )

        assertEquals(TtmlTimingMode.WORD, metadata.timingMode)
        assertEquals("en-US", metadata.language)
        assertFalse(metadata.hasTranslation)
        assertTrue(metadata.isForeign)
        assertTrue(metadata.needsTranslationFallback)
    }

    @Test
    fun `translation track suppresses foreign Word fallback`() {
        val metadata = TtmlTimingPolicy.metadataOf(
            """
            <tt xml:lang="ja" itunes:timing="Word">
              <body><div><p begin="0s" end="1s">歌</p></div></body>
              <translations><translation xml:lang="zh">歌</translation></translations>
            </tt>
            """.trimIndent(),
        )

        assertTrue(metadata.hasTranslation)
        assertTrue(metadata.isForeign)
        assertFalse(metadata.needsTranslationFallback)
    }

    @Test
    fun `empty translation and transliteration do not count as translation`() {
        val metadata = TtmlTimingPolicy.metadataOf(
            """
            <tt xml:lang="fr" itunes:timing="Word">
              <transliterations><transliteration>bonjour</transliteration></transliterations>
              <translations><translation>  <span> </span> </translation></translations>
            </tt>
            """.trimIndent(),
        )

        assertFalse(metadata.hasTranslation)
        assertTrue(metadata.needsTranslationFallback)
    }

    @Test
    fun `Chinese or missing language is not classified as foreign`() {
        val chinese = TtmlTimingPolicy.metadataOf(
            "<tt xml:lang=\"zh-Hans\" itunes:timing=\"Word\"><body/></tt>",
        )
        val missing = TtmlTimingPolicy.metadataOf(
            "<tt itunes:timing=\"Word\"><body/></tt>",
        )

        assertFalse(chinese.isForeign)
        assertFalse(chinese.needsTranslationFallback)
        assertNull(missing.language)
        assertFalse(missing.isForeign)
        assertFalse(missing.needsTranslationFallback)
    }

    @Test
    fun `hasTiming is false when there are no lines at all`() {
        assertFalse(TtmlTimingPolicy.hasTiming(""))
        assertFalse(TtmlTimingPolicy.hasTiming("<tt itunes:timing=\"Line\"><body/></tt>"))
    }

    @Test
    fun `hasTiming is false for plain text lines with only end times`() {
        val plain = """
            <tt itunes:timing="Line"><body>
              <p end="5s">first</p>
              <p end="10s">second</p>
            </body></tt>
        """.trimIndent()

        assertFalse(TtmlTimingPolicy.hasTiming(plain))
    }

    @Test
    fun `hasTiming is true for a line with a positive begin`() {
        val lineTimed = """
            <tt itunes:timing="Line"><body>
              <p begin="0s" end="5s">first</p>
              <p begin="5s" end="10s">second</p>
            </body></tt>
        """.trimIndent()

        assertTrue(TtmlTimingPolicy.hasTiming(lineTimed))
        assertEquals("LINE", TtmlTimingPolicy.timingKindOf(lineTimed))
    }

    @Test
    fun `hasTiming is true for word timing that starts at zero`() {
        val wordTimed = """
            <tt itunes:timing="Word"><body>
              <p begin="0s" end="5s">
                <span begin="0s" end="1s">a</span><span begin="1s" end="2s">b</span>
              </p>
            </body></tt>
        """.trimIndent()

        assertTrue(TtmlTimingPolicy.hasTiming(wordTimed))
        assertEquals("WORD", TtmlTimingPolicy.timingKindOf(wordTimed))
    }

    @Test
    fun `identity registry does not confuse equal pointers`() {
        val first = String(charArrayOf('p'))
        val equal = String(charArrayOf('p'))
        val registry = TtmlTimingObservationRegistry(maxEntries = 2)

        registry.record(first, TtmlTimingMode.WORD)

        assertEquals(TtmlTimingMode.WORD, registry.modeOf(first))
        assertNull(registry.modeOf(equal))
    }

    @Test
    fun `identity registry associates observed metadata with the Apple Music ID`() {
        val pointer = Any()
        val metadata = TtmlDocumentMetadata(
            timingMode = TtmlTimingMode.NON_WORD,
            language = "en",
            hasTranslation = false,
        )
        val registry = TtmlTimingObservationRegistry()

        registry.record(pointer, metadata, appleMusicId = 42L)

        assertEquals(metadata, registry.metadataOfAppleMusicId(42L))
    }

    @Test
    fun `identity registry keeps the raw document for the translation pass`() {
        val pointer = Any()
        val metadata = TtmlDocumentMetadata(
            timingMode = TtmlTimingMode.WORD,
            language = "ja",
            hasTranslation = false,
        )
        val registry = TtmlTimingObservationRegistry()

        registry.record(pointer, metadata, appleMusicId = 42L, rawTtml = "<tt>hello</tt>")

        assertEquals("<tt>hello</tt>", registry.rawTtmlOfAppleMusicId(42L))
        assertNull(registry.rawTtmlOfAppleMusicId(43L))
        assertNull(registry.rawTtmlOfAppleMusicId(-1L))
    }

    @Test
    fun `a record without raw ttml leaves no stale document behind`() {
        val pointer = Any()
        val registry = TtmlTimingObservationRegistry()

        registry.record(
            pointer,
            TtmlDocumentMetadata(TtmlTimingMode.WORD, language = "ja", hasTranslation = false),
            appleMusicId = 42L,
        )

        assertNull(registry.rawTtmlOfAppleMusicId(42L))
    }

    @Test
    fun `a capture without an adam id is kept and associated when the track is known`() {
        val pointer = Any()
        val metadata = TtmlDocumentMetadata(
            timingMode = TtmlTimingMode.WORD,
            language = "ja",
            hasTranslation = false,
        )
        val registry = TtmlTimingObservationRegistry()

        // Apple parses the displayed document before it binds the Adam ID.
        registry.record(pointer, metadata, appleMusicId = null, rawTtml = "<tt>apple</tt>")

        assertEquals("<tt>apple</tt>", registry.rawTtmlOf(pointer))
        assertNull(registry.rawTtmlOfAppleMusicId(42L))
        assertNull(registry.metadataOfAppleMusicId(42L))

        assertTrue(registry.associate(pointer, 42L))

        assertEquals("<tt>apple</tt>", registry.rawTtmlOfAppleMusicId(42L))
        assertEquals(metadata, registry.metadataOfAppleMusicId(42L))
    }

    @Test
    fun `association is by pointer identity and never leaks another document`() {
        val first = Any()
        val second = Any()
        val registry = TtmlTimingObservationRegistry()
        registry.record(first, TtmlTimingMode.WORD, rawTtml = "<tt>one</tt>")
        registry.record(second, TtmlTimingMode.NON_WORD, rawTtml = "<tt>two</tt>")

        assertFalse(registry.associate(Any(), 42L))
        assertFalse(registry.associate(null, 42L))
        assertFalse(registry.associate(first, 0L))

        assertTrue(registry.associate(second, 42L))
        assertTrue(registry.associate(first, 43L))

        assertEquals("<tt>two</tt>", registry.rawTtmlOfAppleMusicId(42L))
        assertEquals("<tt>one</tt>", registry.rawTtmlOfAppleMusicId(43L))
        assertNull(registry.rawTtmlOfAppleMusicId(44L))
        assertNull(registry.rawTtmlOf(null))
        assertNull(registry.rawTtmlOf(Any()))
    }

    @Test
    fun `association reports an observation even when no raw body was captured`() {
        val pointer = Any()
        val registry = TtmlTimingObservationRegistry()
        registry.record(pointer, TtmlTimingMode.NON_WORD)

        assertTrue(registry.associate(pointer, 42L))
        assertNull(registry.rawTtmlOfAppleMusicId(42L))
        assertEquals(TtmlTimingMode.NON_WORD, registry.metadataOfAppleMusicId(42L)?.timingMode)
    }

    @Test
    fun `registry evicts oldest observation`() {
        val registry = TtmlTimingObservationRegistry(maxEntries = 1)
        val first = Any()
        val second = Any()

        registry.record(first, TtmlTimingMode.WORD)
        registry.record(second, TtmlTimingMode.NON_WORD)

        assertNull(registry.modeOf(first))
        assertEquals(TtmlTimingMode.NON_WORD, registry.modeOf(second))
    }
}
