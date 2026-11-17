package io.github.proify.lyricon.amprovider.xposed

import java.util.Collections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The AMPP-Meta limiter: the sentinel is the whole point — a device log that hits the cap must say
 * so once, and the event channel must keep working afterwards, otherwise a missing line looks like
 * a branch that never ran.
 */
class MetadataDiagnosticsTest {

    private val written: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Before
    fun installSink() {
        MetadataDiagnostics.resetForTest()
        MetadataDiagnostics.sink = { message -> written += message }
    }

    @After
    fun removeSink() {
        MetadataDiagnostics.sink = null
        MetadataDiagnostics.resetForTest()
    }

    @Test
    fun `a duplicate line is written once`() {
        MetadataDiagnostics.line("same line")
        MetadataDiagnostics.line("same line")

        assertEquals(listOf("same line"), written)
    }

    @Test
    fun `the cap reports itself once and never again`() {
        repeat(MetadataDiagnostics.LIMIT) { index ->
            MetadataDiagnostics.line("line " + index)
        }
        assertEquals(MetadataDiagnostics.LIMIT, written.size)

        MetadataDiagnostics.line("after the cap")
        MetadataDiagnostics.line("after the cap again")

        assertEquals(MetadataDiagnostics.LIMIT + 1, written.size)
        assertEquals(
            MetadataDiagnostics.LIMIT_EVENT + MetadataDiagnostics.LIMIT,
            written.last(),
        )
    }

    @Test
    fun `events keep flowing after the cap`() {
        repeat(MetadataDiagnostics.LIMIT) { index ->
            MetadataDiagnostics.line("line " + index)
        }
        MetadataDiagnostics.line("overflow")

        MetadataDiagnostics.event("meta effective id=1 title=はいよろこんで")
        MetadataDiagnostics.event("meta artist-origin id=1 originalLanguage=ja-JP")

        assertTrue(written.contains("meta effective id=1 title=はいよろこんで"))
        assertTrue(written.contains("meta artist-origin id=1 originalLanguage=ja-JP"))
    }

    @Test
    fun `the cap is the reviewed value`() {
        assertEquals(4096, MetadataDiagnostics.LIMIT)
    }

    @Test
    fun `script marks the shapes the artist report depends on`() {
        assertEquals("kana", MetadataDiagnostics.script("こっちのけんと"))
        assertEquals("kanji", MetadataDiagnostics.script("米津玄師"))
        assertEquals("latin", MetadataDiagnostics.script("Kocchi No Kento"))
        assertEquals("mixed", MetadataDiagnostics.script("Kocchi の 賢人"))
        assertEquals("empty", MetadataDiagnostics.script(null))
    }
}
