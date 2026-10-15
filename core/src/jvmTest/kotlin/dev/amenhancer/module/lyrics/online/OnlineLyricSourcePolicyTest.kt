package dev.amenhancer.module.lyrics.online

import dev.amenhancer.module.model.CustomLyricsSources
import dev.amenhancer.module.model.ModuleSettings
import dev.amenhancer.module.model.OnlineLyricSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for the online chain selection policy: built-in default
 * order, stored user order, the automatic flag, per-source filtering, the
 * never-empty fallback, and the global-best strategy switch.
 */
class OnlineLyricSourcePolicyTest {

    @Test
    fun `automatic order uses the built-in default order`() {
        val selection = OnlineLyricSourcePolicy.resolve(ModuleSettings())

        assertEquals(OnlineLyricSources.DEFAULT_ORDER, selection.sources)
        assertEquals(LyricSelectionMode.FIRST_PASSING, selection.mode)
    }

    @Test
    fun `the stored user order is used when the automatic flag is off`() {
        val selection = OnlineLyricSourcePolicy.resolve(
            ModuleSettings(
                onlineLyricsAutomaticOrderEnabled = false,
                onlineLyricsSourceOrder = "kugou,kuwo",
            ),
        )

        assertEquals(
            listOf(
                CustomLyricsSources.KUGOU,
                CustomLyricsSources.KUWO,
                CustomLyricsSources.NETEASE,
                CustomLyricsSources.QQ,
            ),
            selection.sources,
        )
    }

    @Test
    fun `automatic order ignores the stored user order`() {
        val selection = OnlineLyricSourcePolicy.resolve(
            ModuleSettings(
                onlineLyricsAutomaticOrderEnabled = true,
                onlineLyricsSourceOrder = "kugou,kuwo",
            ),
        )

        assertEquals(OnlineLyricSources.DEFAULT_ORDER, selection.sources)
    }

    @Test
    fun `disabled sources are filtered out of the resolved order`() {
        val selection = OnlineLyricSourcePolicy.resolve(
            ModuleSettings(
                onlineLyricsSourceNeteaseEnabled = false,
                onlineLyricsSourceKuwoEnabled = false,
            ),
        )

        assertEquals(
            listOf(CustomLyricsSources.QQ, CustomLyricsSources.KUGOU),
            selection.sources,
        )
    }

    @Test
    fun `disabling every source falls back to the default-enabled set, never empty`() {
        val selection = OnlineLyricSourcePolicy.resolve(
            ModuleSettings(
                onlineLyricsSourceNeteaseEnabled = false,
                onlineLyricsSourceQqEnabled = false,
                onlineLyricsSourceKuwoEnabled = false,
                onlineLyricsSourceKugouEnabled = false,
            ),
        )

        assertEquals(OnlineLyricSources.DEFAULT_ORDER, selection.sources)
        assertTrue(selection.sources.isNotEmpty())
    }

    @Test
    fun `the never-empty fallback stays inside the available sources`() {
        val selection = OnlineLyricSourcePolicy.resolve(
            settings = ModuleSettings(
                onlineLyricsSourceKuwoEnabled = false,
            ),
            available = listOf(CustomLyricsSources.KUWO),
        )

        assertEquals(listOf(CustomLyricsSources.KUWO), selection.sources)
    }

    @Test
    fun `only the available sources are chained`() {
        val selection = OnlineLyricSourcePolicy.resolve(
            settings = ModuleSettings(onlineLyricsSourceKugouEnabled = false),
            available = listOf(CustomLyricsSources.KUWO, CustomLyricsSources.QQ),
        )

        assertEquals(listOf(CustomLyricsSources.QQ, CustomLyricsSources.KUWO), selection.sources)
    }

    @Test
    fun `the global best flag selects the global best match mode`() {
        assertEquals(
            LyricSelectionMode.FIRST_PASSING,
            OnlineLyricSourcePolicy.selectionMode(globalBest = false),
        )
        assertEquals(
            LyricSelectionMode.GLOBAL_BEST,
            OnlineLyricSourcePolicy.selectionMode(globalBest = true),
        )
        assertEquals(
            LyricSelectionMode.GLOBAL_BEST,
            OnlineLyricSourcePolicy.resolve(
                ModuleSettings(onlineLyricsGlobalBestEnabled = true),
            ).mode,
        )
    }

    @Test
    fun `global best takes the highest passing candidate while first passing keeps order`() {
        val kuwo = song(Source.KUWO)
        val qm = song(Source.QM)
        val candidates = listOf(ScoredSong(kuwo, 86), ScoredSong(qm, 99))

        assertEquals(
            kuwo,
            LyricMatchPolicy.select(candidates, OnlineLyricSourcePolicy.selectionMode(false)),
        )
        assertEquals(
            qm,
            LyricMatchPolicy.select(candidates, OnlineLyricSourcePolicy.selectionMode(true)),
        )
    }

    private fun song(source: Source): SongSearchResult = SongSearchResult(
        id = source.name,
        title = "Song",
        artist = "Artist",
        album = "",
        duration = 200_000L,
        source = source,
    )
}
