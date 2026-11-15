package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural guard for the presence-index wiring.
 *
 * [createAutoLyricsRuntime] needs a real Android `Application`, so the assembly that cannot be
 * unit-tested is asserted against its source: the cache lives in its own private directory, it
 * is given the same custom root as the other mirrors and the same scheduler as the AM-Lyrics
 * index, the resolver receives it as `presence`, and the runtime log line keeps the enabled
 * state observable.
 */
class LyricPresenceWiringTest {

    @Test
    fun `the runtime builds the presence cache from the custom root and hands it to the resolver`() {
        val source = projectFile(SESSION)

        assertTrue(source.contains("LYRIC_PRESENCE_CACHE_DIRECTORY = \"ampp-lyrics-presence\""))
        assertTrue(source.contains("FileLyricPresenceCache("))
        // Only a configured mirror publishes the index; a blank root keeps the cache disabled.
        assertTrue(source.contains("customRoot = customSourceBaseUrl"))
        assertTrue(source.contains("presence = lyricPresenceCache"))
        assertTrue(source.contains("presence=\${lyricPresenceCache.enabled}"))
    }

    @Test
    fun `the presence refresh shares the AM-Lyrics TTL scheduler and stops with it`() {
        val source = projectFile(SESSION)

        assertTrue(source.contains("lyricPresenceCache.refreshIfStale()"))
        assertTrue(source.contains("FileLyricPresenceCache.PERIODIC_CHECK_MS"))
        assertTrue(source.contains("amLyricsIndexScheduler.shutdownNow()"))
    }

    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")

    private companion object {
        const val SESSION =
            "app/src/main/java/dev/amenhancer/module/hook/AutoLyricsReplacementSession.kt"
    }
}
