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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun `the master toggle on builds one composite entry over the enabled sources in order`() {
        val selection = OnlineLyricSelection(
            sources = listOf(CustomLyricsSources.KUWO, CustomLyricsSources.NETEASE),
            mode = LyricSelectionMode.GLOBAL_BEST,
        )
        val constructed = mutableListOf<String>()

        val leading = buildOnlineLyricsChain(
            supplementEnabled = true,
            translationEnabled = false,
            selection = selection,
        ) { sourceId ->
            constructed += sourceId
            provider(sourceId)
        }.leading

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

        val leading = buildOnlineLyricsChain(
            supplementEnabled = true,
            translationEnabled = false,
            selection = selection,
        ) { sourceId ->
            if (sourceId == "unknown") null else provider(sourceId)
        }.leading

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
        assertTrue(
            assembly.contains(
                "onlineLyricsTranslationEnabled = settings.onlineLyricsTranslationEnabled",
            ),
        )
        assertTrue(
            assembly.contains(
                "hideMandarinPronunciation = settings.onlineLyricsHideMandarinPinyinEnabled",
            ),
        )
        assertTrue(
            assembly.contains(
                "MediaMetadataCache.getMetadataById(appleMusicId.toString())?.genre",
            ),
        )
        assertTrue(assembly.contains("onlineLyricsSelection = OnlineLyricSourcePolicy.resolve(settings)"))
        assertTrue(assembly.contains("currentTrack = { currentSong.current()?.details }"))
        assertTrue(runtime.contains("buildOnlineLyricsChain("))
        assertTrue(runtime.contains("onlineLyricProviderFor("))
        assertTrue(runtime.contains("SharedPreferencesNeSessionStore(application)"))
        assertTrue(runtime.contains("trailing = onlineSources"))
        assertTrue(runtime.contains("val enricher = chain.composite"))
        assertTrue(runtime.contains(".takeIf { onlineLyricsTranslationEnabled }"))
        assertTrue(runtime.contains("pronunciationRequested = true"))
        assertTrue(runtime.contains("hideMandarinPronunciation = hideMandarinPronunciation"))
        assertTrue(runtime.contains("translationEnricher = enricher"))
        assertTrue(runtime.contains("online-translation runtime supplement="))
    }

    @Test
    fun `both online toggles off construct no provider and no enricher`() {
        var constructed = false

        val chain = buildOnlineLyricsChain(
            supplementEnabled = false,
            translationEnabled = false,
            selection = OnlineLyricSelection(
                sources = OnlineLyricSources.DEFAULT_ORDER,
                mode = LyricSelectionMode.FIRST_PASSING,
            ),
        ) { sourceId ->
            constructed = true
            provider(sourceId)
        }

        assertTrue(chain.leading.isEmpty())
        assertNull(chain.composite)
        assertFalse(constructed)
    }

    @Test
    fun `the translation toggle borrows the composite without prepending a supplement`() {
        val selection = OnlineLyricSelection(
            sources = listOf(CustomLyricsSources.KUWO),
            mode = LyricSelectionMode.FIRST_PASSING,
        )

        val chain = buildOnlineLyricsChain(
            supplementEnabled = false,
            translationEnabled = true,
            selection = selection,
        ) { provider(it) }

        assertTrue(chain.leading.isEmpty())
        assertNotNull(chain.composite)
    }

    @Test
    fun `the supplement toggle prepends one composite entry`() {
        val selection = OnlineLyricSelection(
            sources = listOf(CustomLyricsSources.KUWO),
            mode = LyricSelectionMode.FIRST_PASSING,
        )

        val chain = buildOnlineLyricsChain(
            supplementEnabled = true,
            translationEnabled = false,
            selection = selection,
        ) { provider(it) }

        assertEquals(listOf(ONLINE_SEARCH_LYRIC_SOURCE), chain.leading.map { it.name })
        assertNotNull(chain.composite)
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
