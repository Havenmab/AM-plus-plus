package dev.amenhancer.module.hook

import dev.amenhancer.module.lyrics.source.AutoLyricsSource
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the opt-in wiring: the Kuwo source exists in the resolver chain only
 * while the setting is on, and the current track comes from the same cache the
 * custom-lyrics target already resolves.
 */
class OnlineLyricsSupplementWiringTest {

    @Test
    fun `the toggle off keeps the search source absent instead of failing`() {
        var constructed = false

        val leading = onlineLyricsLeadingSources(enabled = false) {
            constructed = true
            AutoLyricsSource("kuwo", acceptsLineTiming = true) { null }
        }

        assertTrue(leading.isEmpty())
        assertFalse(constructed)
    }

    @Test
    fun `the toggle on prepends exactly the search source`() {
        val kuwo = AutoLyricsSource("kuwo", acceptsLineTiming = true) { null }

        assertEquals(listOf(kuwo), onlineLyricsLeadingSources(enabled = true) { kuwo })
    }

    @Test
    fun `the assembly reads the setting and the runtime prepends the source`() {
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
        assertTrue(assembly.contains("currentTrack = { currentSong.current()?.details }"))
        assertTrue(runtime.contains("onlineLyricsLeadingSources(onlineLyricsSupplementEnabled)"))
        assertTrue(runtime.contains("leading = leading"))
        assertTrue(runtime.contains("KuwoAutoLyricsSource.create(lyricTransport, currentTrack)"))
    }

    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")
}
