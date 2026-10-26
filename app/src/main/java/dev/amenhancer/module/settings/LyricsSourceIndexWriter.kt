package dev.amenhancer.module.settings

import dev.amenhancer.module.hook.LyricsSourceCustomIndexBridge
import dev.amenhancer.module.hook.ModernXposedRuntime
import dev.amenhancer.module.lyrics.CustomLyricsDraft
import dev.amenhancer.module.lyrics.source.LyricSourceProviders
import dev.amenhancer.module.lyrics.source.LyricSourceText
import dev.amenhancer.module.model.CustomLyricsSources
import dev.amenhancer.module.ui.EmbeddedActionResult
import dev.amenhancer.module.ui.EmbeddedSettingsController
import java.util.Locale

/**
 * Publishes the lyrics-source sheet's two write operations on the host-api bridge.
 *
 * The sheet runs in this module while the hooks that call it live in :host-applemusic, so
 * the only way they meet is [LyricsSourceCustomIndexBridge]: a provider source id comes in,
 * and this writer turns it into a custom-lyrics index record (or removes one).
 *
 * Registration is fail-open. A bridge that cannot be installed must never take the settings
 * host down, so every assignment is guarded and only logged; a failing write returns false
 * so the sheet can show its own error instead of pretending the switch worked.
 *
 * It lives here rather than in the settings UI package because it owns core vocabulary
 * (provider ids and their labels); the UI only calls [register]/[unregister].
 */
internal object LyricsSourceIndexWriter {

    fun register(controller: EmbeddedSettingsController) {
        runCatching {
            LyricsSourceCustomIndexBridge.replace = { songId, ttml, providerSource ->
                replace(controller, songId, ttml, providerSource)
            }
        }.onFailure { error -> fail("register replace", error) }
        runCatching {
            LyricsSourceCustomIndexBridge.remove = { songId -> remove(controller, songId) }
        }.onFailure { error -> fail("register remove", error) }
    }

    /** Mirrors uninstall(): a torn-down host must not leave callable writers behind. */
    fun unregister() {
        LyricsSourceCustomIndexBridge.replace = null
        LyricsSourceCustomIndexBridge.remove = null
    }

    private fun replace(
        controller: EmbeddedSettingsController,
        songId: Long,
        ttml: String,
        providerSource: String,
    ): String? = runCatching {
        // The index rejects a second entry for a song that already has one unless the caller
        // says which entry it is replacing. A song whose lyrics came from a previous online
        // pick always has one, so every later switch used to fail with "target exists" and the
        // sheet only showed a generic storage error.
        val alreadyIndexed = runCatching {
            controller.lyricsEntries().any { it.appleMusicId == songId }
        }.getOrDefault(false)
        val result = controller.saveLyrics(
            CustomLyricsDraft(
                appleMusicId = songId,
                displayName = LyricSourceText.providerLabel(providerSource),
                ttml = ttml,
                source = customLyricsSource(providerSource),
                // The sheet stores what the user picked, so the automatic completion
                // and replacement pipelines must never silently overwrite it later.
                userEdited = true,
            ),
            replacingAppleMusicId = songId.takeIf { alreadyIndexed },
        )
        when (result) {
            is EmbeddedActionResult.Done -> null
            is EmbeddedActionResult.Failed -> {
                fail("replace", IllegalStateException(result.message))
                result.message
            }
        }
    }.getOrElse { error ->
        fail("replace", error)
        error.message ?: "写入自定义歌词索引失败"
    }

    private fun remove(controller: EmbeddedSettingsController, songId: Long): Boolean =
        runCatching {
            controller.deleteLyrics(songId) is EmbeddedActionResult.Done
        }.getOrElse { error ->
            fail("remove", error)
            false
        }

    /**
     * Inverse of [LyricSourceProviders.fromCustomLyricsSource]: the index stores the module's
     * own source tags, while the sheet speaks in provider ids. Anything the index cannot
     * represent (Apple's own document, an unknown id) degrades to a manual record, exactly
     * like the forward mapping degrades to `file`.
     */
    private fun customLyricsSource(providerSource: String): String =
        when (providerSource.trim().lowercase(Locale.ROOT)) {
            LyricSourceProviders.QQ -> CustomLyricsSources.QQ
            LyricSourceProviders.NETEASE -> CustomLyricsSources.NETEASE
            LyricSourceProviders.KUWO -> CustomLyricsSources.KUWO
            LyricSourceProviders.KUGOU -> CustomLyricsSources.KUGOU
            LyricSourceProviders.LUNABEAT -> CustomLyricsSources.LUNABEAT
            LyricSourceProviders.AMLL -> CustomLyricsSources.AMLL
            LyricSourceProviders.AM_LYRICS -> CustomLyricsSources.AM_LYRICS
            LyricSourceProviders.CACHE -> CustomLyricsSources.AUTO_CACHE
            else -> CustomLyricsSources.MANUAL
        }

    private fun fail(operation: String, error: Throwable) {
        ModernXposedRuntime.log(
            "lyrics source index writer " + operation + " failed: " +
                error.javaClass.simpleName + ": " + error.message,
            error,
        )
    }
}
