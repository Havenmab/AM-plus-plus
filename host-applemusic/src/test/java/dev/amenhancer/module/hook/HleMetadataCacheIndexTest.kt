package dev.amenhancer.module.hook

import dev.amenhancer.module.config.RegionSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HleMetadataCacheIndexTest {
    @Test
    fun `every region namespace and the retired names are cleared`() {
        val namespaces = RegionSelection.values().map(RegionSelection::cacheNamespace)
        assertTrue(namespaces.contains("original_hyper_v1"))
        assertTrue(namespaces.contains("jp_v1"))
        assertEquals(namespaces.toSet(), HleMetadataCacheIndex.cacheNamespaces().toSet())

        val databases = HleMetadataCacheIndex.databaseNames()
        assertEquals(databases.distinct(), databases)
        namespaces.forEach { namespace ->
            assertTrue(
                "missing localized database for $namespace",
                databases.contains("hyperlyricsenhanced_apple_metadata_$namespace.db"),
            )
            assertTrue(
                "missing original database for $namespace",
                databases.contains("hyperlyricsenhanced_apple_original_metadata_$namespace.db"),
            )
        }
        // The retired un-namespaced databases may still exist on an old install.
        assertTrue(databases.contains("hyperlyricsenhanced_apple_metadata.db"))
        assertTrue(databases.contains("hyperlyricsenhanced_apple_original_metadata.db"))
        // The fork's pre-namespace v5 files are legacy leftovers too.
        assertTrue(databases.contains("hyperlyricsenhanced_apple_original_metadata_v5.db"))

        val preferences = HleMetadataCacheIndex.artistRegionPreferencesNames()
        namespaces.forEach { namespace ->
            assertTrue(
                "missing artist-region preferences for $namespace",
                preferences.contains(
                    "hyperlyricsenhanced_apple_original_artist_regions_$namespace",
                ),
            )
        }
        assertTrue(preferences.contains("hyperlyricsenhanced_apple_original_artist_regions"))
        assertTrue(preferences.contains("hyperlyricsenhanced_apple_original_artist_regions_v5"))
    }

    @Test
    fun `the clear observer fires once per generation`() {
        var generation = 0L
        var clears = 0
        val logs = mutableListOf<String>()
        val observer = HleMetadataCacheClearObserver(
            signal = { generation },
            onClear = {
                clears++
                HleMetadataCacheClearReport(
                    databases = 2,
                    sidecars = 3,
                    preferences = 1,
                    memoryEntries = 4,
                )
            },
            log = { logs += it },
            logError = { error("unexpected error log: $it") },
        )

        // A fresh runtime starts from the current generation: no pending clear.
        observer.observe()
        assertEquals(0, clears)

        generation = 1L
        observer.observe()
        observer.observe()
        observer.observe()
        assertEquals(1, clears)
        assertEquals(1L, observer.handledGenerationForTest())
        assertEquals(1, logs.size)
        assertTrue(logs.single().startsWith("Apple 检索库已清空:"))
        assertTrue(logs.single().contains("databases=2"))
        assertTrue(logs.single().contains("sidecars=3"))
        assertTrue(logs.single().contains("preferences=1"))
        assertTrue(logs.single().contains("memoryEntries=4"))
        assertTrue(logs.single().contains("namespaces="))

        generation = 2L
        observer.observe()
        assertEquals(2, clears)
    }

    @Test
    fun `a pending clear survives a cold start`() {
        var generation = 4L
        var handled = 0L
        var clears = 0

        fun newObserver() = HleMetadataCacheClearObserver(
            signal = { generation },
            onClear = {
                clears++
                HleMetadataCacheClearReport(
                    databases = 1,
                    sidecars = 0,
                    preferences = 0,
                    memoryEntries = 0,
                )
            },
            initialHandledGeneration = handled,
            onHandled = { handled = it },
            log = {},
            logError = { error("unexpected error log: $it") },
        )

        newObserver().observe()
        assertEquals(1, clears)
        assertEquals(4L, handled)

        // A fresh process reads the persisted marker: no spurious re-clear.
        newObserver().observe()
        assertEquals(1, clears)

        generation = 5L
        newObserver().observe()
        assertEquals(2, clears)
        assertEquals(5L, handled)
    }

    @Test
    fun `a failed clear stays pending for the next request`() {
        var generation = 0L
        var attempts = 0
        val errors = mutableListOf<String>()
        val observer = HleMetadataCacheClearObserver(
            signal = { generation },
            onClear = {
                attempts++
                throw IllegalStateException("disk busy")
            },
            log = { error("unexpected success log: $it") },
            logError = { errors += it },
        )

        generation = 1L
        observer.observe()
        assertEquals(1, attempts)
        assertEquals(0L, observer.handledGenerationForTest())

        observer.observe()
        assertEquals(2, attempts)
        assertEquals(2, errors.size)
    }
}
