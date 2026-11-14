package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural guard for the AM-Lyrics index wiring.
 *
 * [createAutoLyricsRuntime] needs a real Android `Application`, so the behaviour that cannot
 * be unit-tested is asserted against its source: the custom source root reaches both fixed
 * providers, the cached index is installed as the client's provider, the 6h TTL check is a
 * daemon scheduler, and the settings page edits and normalizes the address.
 */
class AutoLyricsIndexCacheWiringTest {

    @Test
    fun `the runtime installs the disk cache, the custom root and the periodic check`() {
        val source = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/AutoLyricsReplacementSession.kt",
        )

        // The self-hosted mirror root reaches both GitHub-hosted providers.
        assertTrue(source.contains("AmllTtmlClient(lyricTransport, customSourceBaseUrl)"))
        assertTrue(
            source.contains("AmLyricsClient(lyricTransport, customSourceBaseUrl, amLyricsIndexCache)"),
        )
        assertTrue(source.contains("customLyricsSourceBaseUrl"))
        // The index cache lives under filesDir/ampp-amlyrics and is core's provider.
        assertTrue(source.contains("AM_LYRICS_CACHE_DIRECTORY = \"ampp-amlyrics\""))
        assertTrue(source.contains("FileAmLyricsIndexCache("))
        // The 6h check is a daemon scheduler shut down together with the runtime.
        assertTrue(source.contains("ScheduledThreadPoolExecutor"))
        assertTrue(source.contains("scheduleWithFixedDelay("))
        assertTrue(source.contains("FileAmLyricsIndexCache.PERIODIC_CHECK_MS"))
        assertTrue(source.contains("amLyricsIndexScheduler.shutdownNow()"))
    }

    @Test
    fun `the online lyrics page edits and normalizes the custom source address`() {
        val source = projectFile(
            "app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsPages.kt",
        )

        assertTrue(source.contains("ModuleText.CUSTOM_LYRICS_SOURCE_BASE_URL"))
        assertTrue(source.contains("ModuleText.CUSTOM_LYRICS_SOURCE_BASE_URL_HINT"))
        assertTrue(source.contains("settings.customLyricsSourceBaseUrl"))
        assertTrue(source.contains("ModuleSettings.normalizeCustomLyricsSourceBaseUrl(value)"))
        assertTrue(source.contains("settings.copy(customLyricsSourceBaseUrl = normalized)"))
        assertTrue(source.contains("MAX_CUSTOM_LYRICS_SOURCE_BASE_URL_LENGTH"))
    }

    @Test
    fun `the settings-process import and update actions use the same mirror`() {
        val controller = projectFile(
            "app/src/main/java/dev/amenhancer/module/settings/EmbeddedRuntimeSettingsController.kt",
        )
        assertTrue(controller.contains("customSourceRoot()"))
        assertTrue(controller.contains("AmllTtmlClient(HttpLyricTransport(), customRoot)"))
        assertTrue(controller.contains("AmllTtmlClient(transport, customRoot)"))
        assertTrue(controller.contains("AmLyricsClient(transport, customRoot)"))

        val importer = projectFile(
            "app/src/main/java/dev/amenhancer/module/lyrics/EmbeddedOnlineLyricsSources.kt",
        )
        assertTrue(importer.contains("customSourceBaseUrl: String = \"\""))
        assertTrue(importer.contains("AmllTtmlClient(HttpLyricTransport(), customRoot)"))
        assertTrue(importer.contains("AmLyricsClient(HttpLyricTransport(), customRoot)"))
    }

    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")
}
