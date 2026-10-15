package dev.amenhancer.module.hook

import android.content.Context
import dev.amenhancer.module.config.RegionSelection
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver
import io.github.proify.lyricon.amprovider.xposed.AppleMetadataOverrideStore
import io.github.proify.lyricon.amprovider.xposed.MediaMetadataCache
import io.github.proify.lyricon.amprovider.xposed.ProviderLogger
import java.io.File

/**
 * Disk layout of the persistent 检索库 (the region/original metadata caches) and
 * the maintenance pass behind the settings page's 「清空检索库」 action.
 *
 * Every region namespace owns its own pair of SQLite databases plus an
 * artist-region `SharedPreferences` file, and every one of those has to go.
 * The file names are derived from [RegionSelection.cacheNamespace] rather than
 * hard-coded so a new region cannot silently keep a stale cache behind.  The
 * retired un-namespaced names are always included as well: an installation that
 * predates the per-region split may still carry them.
 */
internal object HleMetadataCacheIndex {
    private const val LOCALIZED_DATABASE_PREFIX = "hyperlyricsenhanced_apple_metadata"
    private const val ORIGINAL_DATABASE_PREFIX = "hyperlyricsenhanced_apple_original_metadata"
    private const val ARTIST_REGION_PREFERENCES_PREFIX =
        "hyperlyricsenhanced_apple_original_artist_regions"

    /** SQLite sidecars; the database is unusable if a stale one is left behind. */
    private val SIDECAR_SUFFIXES = listOf("-journal", "-wal", "-shm")

    /**
     * Database names retired before the per-region namespace split: HLE's own
     * un-namespaced names plus the fork's short-lived `_v5` namespace.
     */
    private val LEGACY_DATABASE_NAMES = listOf(
        "${LOCALIZED_DATABASE_PREFIX}.db",
        "${ORIGINAL_DATABASE_PREFIX}.db",
        "${ORIGINAL_DATABASE_PREFIX}_v5.db",
    )

    /** The matching retired artist-region preferences names. */
    private val LEGACY_PREFERENCES_NAMES = listOf(
        ARTIST_REGION_PREFERENCES_PREFIX,
        "${ARTIST_REGION_PREFERENCES_PREFIX}_v5",
    )

    /** Every physical cache namespace the runtime has ever used. */
    internal fun cacheNamespaces(): List<String> =
        RegionSelection.values().map(RegionSelection::cacheNamespace).distinct()

    internal fun localizedDatabaseNames(): List<String> =
        cacheNamespaces().map { "${LOCALIZED_DATABASE_PREFIX}_$it.db" }

    internal fun originalDatabaseNames(): List<String> =
        cacheNamespaces().map { "${ORIGINAL_DATABASE_PREFIX}_$it.db" }

    internal fun artistRegionPreferencesNames(): List<String> =
        cacheNamespaces().map { "${ARTIST_REGION_PREFERENCES_PREFIX}_$it" } +
            LEGACY_PREFERENCES_NAMES

    internal fun databaseNames(): List<String> =
        (localizedDatabaseNames() + originalDatabaseNames() + LEGACY_DATABASE_NAMES).distinct()

    /**
     * Deletes every persistent artifact, plus the live runtime's in-memory copies.
     *
     * The live SQLite handles are closed first so a deleted database cannot keep
     * answering from an open connection, and the artist-region preferences are
     * cleared in memory as well because the runtime keeps a `SharedPreferences`
     * reference alive for the process lifetime.
     */
    internal fun clear(
        context: Context,
        catalogResolver: AppleInternalCatalogResolver,
        metadataStore: AppleMetadataOverrideStore,
    ): HleMetadataCacheClearReport {
        val applicationContext = context.applicationContext
        val memoryEntries = catalogResolver.dropMetadataCachesForMaintenance()
        metadataStore.onConfigurationChanged()
        MediaMetadataCache.resetOriginalMetadataResolutions()

        var databases = 0
        var sidecars = 0
        databaseNames().forEach { name ->
            val database = applicationContext.getDatabasePath(name)
            if (database.isFile && database.delete()) databases++
            SIDECAR_SUFFIXES.forEach { suffix ->
                val sidecar = File(database.path + suffix)
                if (sidecar.isFile && sidecar.delete()) sidecars++
            }
            // The platform delete also drops any sidecar it tracks for this name
            // on this OS version; the explicit pass above is what makes the
            // -journal/-wal/-shm removal guaranteed and countable.
            applicationContext.deleteDatabase(name)
        }

        var preferences = 0
        artistRegionPreferencesNames().forEach { name ->
            val preferencesFile = File(
                applicationContext.dataDir,
                "shared_prefs/$name.xml",
            )
            if (preferencesFile.isFile && preferencesFile.delete()) preferences++
            applicationContext.deleteSharedPreferences(name)
        }

        return HleMetadataCacheClearReport(
            databases = databases,
            sidecars = sidecars,
            preferences = preferences,
            memoryEntries = memoryEntries,
        )
    }
}

internal data class HleMetadataCacheClearReport(
    val databases: Int,
    val sidecars: Int,
    val preferences: Int,
    val memoryEntries: Int,
)

/**
 * Remembers which 「清空检索库」 generation this installation has already handled.
 *
 * The generation itself lives in the settings store; without a handled marker a
 * clear requested just before the process died would be silently considered
 * handled by the next cold start's freshly built observer.  The marker is
 * module bookkeeping beside the caches, not part of them.
 */
internal class HleMetadataCacheClearHandledStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun read(): Long = preferences.getLong(KEY_HANDLED_GENERATION, 0L)

    fun write(generation: Long) {
        preferences.edit().putLong(KEY_HANDLED_GENERATION, generation).commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "hyperlyricsenhanced_apple_metadata_maintenance"
        const val KEY_HANDLED_GENERATION = "handled_clear_generation"
    }
}

/**
 * Handles the one-shot 「清空检索库」 signal exactly once per generation.
 *
 * The settings UI runs in the same process but may not touch the native runtime,
 * so it only bumps [dev.amenhancer.module.model.ModuleSettings.metadataCacheClearGeneration].
 * The runtime polls the cheap single-key value on its metadata request seams and
 * performs the physical clear here the first time it sees the new generation; a
 * failed clear keeps the generation unhandled so the next request retries it.
 */
internal class HleMetadataCacheClearObserver(
    private val signal: () -> Long,
    private val onClear: () -> HleMetadataCacheClearReport,
    private val initialHandledGeneration: Long = 0L,
    private val onHandled: (Long) -> Unit = {},
    private val log: (String) -> Unit = { ProviderLogger.info(it) },
    private val logError: (String) -> Unit = { ProviderLogger.error(it) },
) {
    @Volatile
    private var handledGeneration: Long = initialHandledGeneration
    private val lock = Any()

    fun observe() {
        val requested = runCatching(signal).getOrDefault(handledGeneration)
        if (requested == handledGeneration) return
        synchronized(lock) {
            val confirmed = runCatching(signal).getOrDefault(handledGeneration)
            if (confirmed == handledGeneration) return
            if (confirmed < handledGeneration) {
                // The settings store was reset below the handled marker; there is
                // no pending clear, so only re-baseline.
                handledGeneration = confirmed
                return
            }
            val report = runCatching(onClear).getOrNull()
            if (report == null) {
                logError("Apple 检索库清空失败，将在下次请求时重试")
                return
            }
            handledGeneration = confirmed
            runCatching { onHandled(confirmed) }
            log(
                "Apple 检索库已清空: namespaces=${HleMetadataCacheIndex.cacheNamespaces().size}, " +
                    "databases=${report.databases}, sidecars=${report.sidecars}, " +
                    "preferences=${report.preferences}, memoryEntries=${report.memoryEntries}",
            )
        }
    }

    internal fun handledGenerationForTest(): Long = handledGeneration
}
