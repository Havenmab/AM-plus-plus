package dev.amenhancer.module.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsSourceIndexWriterStructuralTest {
    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")

    @Test
    fun `automatic enrichment is persisted with its provider and song title`() {
        val session = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/AutoLyricsReplacementSession.kt",
        )
        val writer = projectFile(
            "app/src/main/java/dev/amenhancer/module/settings/LyricsSourceIndexWriter.kt",
        )

        assertTrue(session.contains("LyricsSourceCustomIndexBridge.saveAutomaticLyrics("))
        assertTrue(session.contains("ttml = outcome.ttml"))
        assertTrue(session.contains("source = providerSource"))
        assertTrue(session.contains("displayName = songName"))
        assertTrue(writer.contains("source = source"))
        assertTrue(writer.contains("displayName = displayName"))
        assertTrue(writer.contains("existing?.appleMusicId,"))
        assertTrue(writer.contains("userEdited = false"))
    }

    @Test
    fun `automatic save leaves manual edited disabled and locked entries alone`() {
        val session = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/AutoLyricsReplacementSession.kt",
        )
        val writer = projectFile(
            "app/src/main/java/dev/amenhancer/module/settings/LyricsSourceIndexWriter.kt",
        )

        assertTrue(session.contains("!AutoLyricsReplacementBridge.isLocked(appleMusicId)"))
        val automatic = writer.substringAfter("private fun saveAutomatic(")
            .substringBefore("private fun saveAppleNative(")
        assertTrue(automatic.contains("existing.userEdited"))
        assertTrue(automatic.contains("!existing.enabled"))
        assertTrue(automatic.contains("existing.source == CustomLyricsSources.MANUAL"))
        assertTrue(automatic.contains("enabled = existing?.enabled ?: false"))
        assertTrue(automatic.contains("userEdited = false"))
        assertFalse(automatic.contains("userEdited = true"))
        assertTrue(automatic.contains("歌曲 "))
    }

    @Test
    fun `explicit Apple copy is disabled and preserves user-owned active entries`() {
        val writer = projectFile(
            "app/src/main/java/dev/amenhancer/module/settings/LyricsSourceIndexWriter.kt",
        )
        val bridge = projectFile(
            "host-api/src/main/java/dev/amenhancer/module/hook/LyricsSourceTarget.kt",
        )
        val apple = writer.substringAfter("private fun saveAppleNative(")
            .substringBefore("private fun automaticLyricsSource(")

        assertTrue(bridge.contains("fun saveAppleNativeCopy(songId: Long, rawAppleTtml: String, songName: String)"))
        assertTrue(writer.contains("fun registerAutomatic(content: EmbeddedContentManager)"))
        assertTrue(writer.contains("source = CustomLyricsSources.APPLE"))
        assertTrue(apple.contains("enabled = false"))
        assertTrue(apple.contains("userEdited = false"))
        assertTrue(apple.contains("existing.userEdited"))
        assertTrue(apple.contains("existing.source == CustomLyricsSources.MANUAL"))
        assertTrue(apple.contains("existing?.appleMusicId"))
        assertFalse(apple.contains("AutoLyricsReplacementBridge.isLocked"))
    }

    @Test
    fun `automatic bridge is registered for runtime lifetime and cleaned on close`() {
        val session = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/AutoLyricsReplacementSession.kt",
        )
        val writer = projectFile(
            "app/src/main/java/dev/amenhancer/module/settings/LyricsSourceIndexWriter.kt",
        )

        assertTrue(session.contains("LyricsSourceIndexWriter.registerAutomatic(configuredContent)"))
        assertTrue(session.contains("unregisterAutomaticIndexWriter()"))
        assertTrue(writer.contains("LyricsSourceCustomIndexBridge.saveAutomatic = automaticWriter"))
        assertTrue(writer.contains("LyricsSourceCustomIndexBridge.saveAppleNative = appleWriter"))
        assertTrue(writer.contains("if (LyricsSourceCustomIndexBridge.saveAutomatic === automaticWriter)"))
        assertTrue(writer.contains("if (LyricsSourceCustomIndexBridge.saveAppleNative === appleWriter)"))
    }

    @Test
    fun `automatic writer accepts only indexed provider source tags`() {
        val writer = projectFile(
            "app/src/main/java/dev/amenhancer/module/settings/LyricsSourceIndexWriter.kt",
        )

        for (source in listOf(
            "CustomLyricsSources.APPLE",
            "CustomLyricsSources.QQ",
            "CustomLyricsSources.NETEASE",
            "CustomLyricsSources.KUWO",
            "CustomLyricsSources.KUGOU",
        )) {
            assertTrue("missing $source", writer.contains(source))
        }
        assertTrue(writer.contains("else -> null"))
    }
}
