package dev.amenhancer.module.hook

import dev.amenhancer.module.lyrics.online.LyricSelectionMode
import dev.amenhancer.module.lyrics.online.LyricsResult
import dev.amenhancer.module.lyrics.online.OnlineLyricSelection
import dev.amenhancer.module.lyrics.online.SearchLyricsSource
import dev.amenhancer.module.lyrics.online.SongSearchResult
import dev.amenhancer.module.lyrics.online.Source
import dev.amenhancer.module.model.CustomLyricsSources
import dev.amenhancer.module.model.OnlineLyricSources
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the opt-in wiring: the search providers exist in the resolver chain
 * only while the master setting is on, they collapse into one composite entry
 * built in the resolved order, the source list comes from the pure policy
 * rather than the settings page, and the Netease session is persisted.
 */
class OnlineLyricsSupplementWiringTest {

    @Test
    fun `the master toggle off keeps every search provider absent instead of failing`() {
        var constructed = false

        val leading = onlineLyricsLeadingSources(
            enabled = false,
            selection = OnlineLyricSelection(
                sources = OnlineLyricSources.DEFAULT_ORDER,
                mode = LyricSelectionMode.FIRST_PASSING,
            ),
        ) { sourceId ->
            constructed = true
            provider(sourceId)
        }

        assertTrue(leading.isEmpty())
        assertFalse(constructed)
    }

    @Test
    fun `the master toggle on builds one composite entry over the enabled sources in order`() {
        val selection = OnlineLyricSelection(
            sources = listOf(CustomLyricsSources.KUWO, CustomLyricsSources.NETEASE),
            mode = LyricSelectionMode.GLOBAL_BEST,
        )
        val constructed = mutableListOf<String>()

        val leading = onlineLyricsLeadingSources(enabled = true, selection = selection) { sourceId ->
            constructed += sourceId
            provider(sourceId)
        }

        assertEquals(1, leading.size)
        assertEquals(ONLINE_SEARCH_LYRIC_SOURCE, leading.single().name)
        assertTrue(leading.single().acceptsLineTiming)
        assertEquals(listOf(CustomLyricsSources.KUWO, CustomLyricsSources.NETEASE), constructed)
    }

    @Test
    fun `an unknown source id is skipped without dropping the rest of the chain`() {
        val selection = OnlineLyricSelection(
            sources = listOf(CustomLyricsSources.KUWO, "unknown"),
            mode = LyricSelectionMode.FIRST_PASSING,
        )

        val leading = onlineLyricsLeadingSources(enabled = true, selection = selection) { sourceId ->
            if (sourceId == "unknown") null else provider(sourceId)
        }

        assertEquals(1, leading.size)
        assertEquals(ONLINE_SEARCH_LYRIC_SOURCE, leading.single().name)
    }

    @Test
    fun `the assembly resolves the chain from settings and the runtime builds it lazily`() {
        val assembly = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/AppleMusicAssembly.kt",
        )
        val runtime = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/AutoLyricsReplacementSession.kt",
        )

        assertTrue(
            assembly.contains(
                "onlineLyricsSupplementEnabled = settings.onlineLyricsSupplementEnabled",
            ),
        )
        assertTrue(assembly.contains("onlineLyricsSelection = OnlineLyricSourcePolicy.resolve(settings)"))
        assertTrue(assembly.contains("currentTrack = { currentSong.current()?.details }"))
        assertTrue(runtime.contains("onlineLyricsLeadingSources("))
        assertTrue(runtime.contains("onlineLyricProviderFor("))
        assertTrue(runtime.contains("SharedPreferencesNeSessionStore(application)"))
        assertTrue(runtime.contains("leading = leading"))
    }

    private fun provider(sourceId: String): SearchLyricsSource = object : SearchLyricsSource {
        override val sourceType: Source = Source.KUWO

        override fun search(
            keyword: String,
            page: Int,
            separator: String,
            pageSize: Int,
            durationMs: Long,
        ): List<SongSearchResult> = emptyList()

        override fun getLyrics(song: SongSearchResult): LyricsResult? = null
    }

    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")
}
