package dev.amenhancer.module.lyrics
import dev.amenhancer.module.lyrics.source.AmLyricsClient
import dev.amenhancer.module.lyrics.source.AmllTtmlClient
import dev.amenhancer.module.lyrics.source.FileLunabeatCatalogCache
import dev.amenhancer.module.lyrics.source.HttpLyricTransport
import dev.amenhancer.module.lyrics.source.LunabeatClient
import dev.amenhancer.module.model.ModuleSettings
import java.io.File

/**
 * The lyrics-editor importer used by the embedded settings host.
 *
 * [customSourceBaseUrl] is the user's self-hosted mirror from the settings page: both
 * GitHub-hosted sources try it before the public hosts, so an import costs a local round
 * trip instead of a cross-border one. An unusable value is dropped by the normalizer.
 */
internal fun createEmbeddedOnlineLyricsImporter(
    application: android.app.Application,
    customSourceBaseUrl: String = "",
): CustomLyricsOnlineImporter {
    val customRoot = ModuleSettings.normalizeCustomLyricsSourceBaseUrl(customSourceBaseUrl)
        .takeIf(String::isNotEmpty)
    return CustomLyricsOnlineImporter(
        fetchAmll = AmllTtmlClient(HttpLyricTransport(), customRoot)::fetch,
        fetchAmLyrics = AmLyricsClient(HttpLyricTransport(), customRoot)::fetch,
        fetchLunabeat = LunabeatClient(
            indexTransport = HttpLyricTransport(maxResponseBytes = LunabeatClient.INDEX_MAX_BYTES),
            lyricsTransport = HttpLyricTransport(),
            cache = FileLunabeatCatalogCache(File(application.filesDir, "ampp-lunabeat-cache")),
        )::fetch,
    )
}
