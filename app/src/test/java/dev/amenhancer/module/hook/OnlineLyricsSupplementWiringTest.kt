package dev.amenhancer.module.hook

import dev.amenhancer.module.lyrics.online.LyricSelectionMode
import dev.amenhancer.module.lyrics.online.OnlineLyricSelection
import dev.amenhancer.module.lyrics.source.AutoLyricsSource
import dev.amenhancer.module.model.CustomLyricsSources
import dev.amenhancer.module.model.OnlineLyricSources
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the opt-in wiring: the search sources exist in the resolver chain only
 * while the master setting is on, they are built in the resolved order, and the
 * source list comes from the pure policy rather than the settings page.
 */
class OnlineLyricsSupplementWiringTest {

    @Test
    fun `the master toggle off keeps every search source absent instead of failing`() {
        var constructed = false

        val leading = onlineLyricsLeadingSources(
            enabled = false,
            selection = OnlineLyricSelection(
                sources = OnlineLyricSources.DEFAULT_ORDER,
                mode = LyricSelectionMode.FIRST_PASSING,
            ),
        ) {
            constructed = true
            AutoLyricsSource(it, acceptsLineTiming = true) { null }
        }

        assertTrue(leading.isEmpty())
        assertFalse(constructed)
    }

    @Test
    fun `the master toggle on builds exactly the enabled sources in the resolved order`() {
        val selection = OnlineLyricSelection(
            sources = listOf(CustomLyricsSources.KUWO, CustomLyricsSources.NETEASE),
            mode = LyricSelectionMode.GLOBAL_BEST,
        )
        val constructed = mutableListOf<String>()

        val leading = onlineLyricsLeadingSources(enabled = true, selection = selection) { sourceId ->
            constructed += sourceId
            AutoLyricsSource(sourceId, acceptsLineTiming = true) { null }
        }

        assertEquals(
            listOf(CustomLyricsSources.KUWO, CustomLyricsSources.NETEASE),
            leading.map { it.name },
        )
        assertEquals(listOf(CustomLyricsSources.KUWO, CustomLyricsSources.NETEASE), constructed)
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
        assertTrue(runtime.contains("onlineLyricSourceFor("))
        assertTrue(runtime.contains("leading = leading"))
    }

    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")
}
