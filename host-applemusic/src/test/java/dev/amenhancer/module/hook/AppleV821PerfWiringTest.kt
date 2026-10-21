package dev.amenhancer.module.hook

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source pins for the HLE v8.2.1 (`1a665f69`) performance mechanisms ported into
 * the fork's metadata/lyric hot paths. The runtime seams are reflection over the
 * real Apple Music process (or Android handlers) and cannot run on the JVM, so
 * this class pins the wiring that makes each optimization effective; the pure
 * behavior lives in the queue/cache unit tests.
 */
class AppleV821PerfWiringTest {

    private val overrideCoordinator = projectFile(
        "host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/metadata/" +
            "AppleMetadataOverrideApplicationCoordinator.kt",
    )

    private val surfaceRuntime = projectFile(
        "host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/metadata/" +
            "AppleMetadataSurfaceRuntime.kt",
    )

    private val resolutionCoordinator = projectFile(
        "host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/metadata/" +
            "AppleInAppMetadataResolutionCoordinator.kt",
    )

    private val contentItemHooks = projectFile(
        "host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/metadata/" +
            "AppleContentItemMetadataHooks.kt",
    )

    private val lyricHooks = projectFile(
        "host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleNativeLyricModelHooks.kt",
    )

    @Test
    fun `non-active in-app refreshes are coalesced by media id`() {
        assertTrue(overrideCoordinator.contains("deferredInAppRefreshes.offer(mediaId"))
        assertTrue(overrideCoordinator.contains("runtime.mainHandler.post(::flushDeferredInAppRefreshes)"))
        assertTrue(overrideCoordinator.contains("deferredInAppRefreshes.cancel(mediaId)"))
        assertTrue(overrideCoordinator.contains("DEFERRED_REFRESH_BUDGET_NS"))
        assertTrue(overrideCoordinator.contains("isSharedArtistAliasCurrent"))
    }

    @Test
    fun `row visibility marks sync the request scope once per message`() {
        assertTrue(surfaceRuntime.contains("scheduleVisibleScopeSync(snapshot)"))
        assertTrue(surfaceRuntime.contains("visibleScopeSyncScheduled"))
        assertTrue(surfaceRuntime.contains("associatedArtistIdsRevision()"))
    }

    @Test
    fun `first-seen override requests are chunked and budgeted`() {
        assertTrue(resolutionCoordinator.contains("AppleBudgetedResultQueue("))
        assertTrue(resolutionCoordinator.contains("ENSURE_OVERRIDES_CHUNK"))
        assertTrue(resolutionCoordinator.contains("onlyUnresolved"))
    }

    @Test
    fun `content item getters skip repeated analysis and repeated alias writes`() {
        assertTrue(contentItemHooks.contains("processedClasses.add(contentItemClass)"))
        assertTrue(contentItemHooks.contains("getterItemStates"))
        assertTrue(contentItemHooks.contains("!itemState.analyzed"))
        assertTrue(contentItemHooks.contains("resolvedAlias != itemState.appliedAlias"))
    }

    @Test
    fun `native word vectors are item-cached per model generation`() {
        assertTrue(lyricHooks.contains("vectorItemsCache"))
        assertTrue(lyricHooks.contains("vectorItemsCache.clear()"))
        assertTrue(lyricHooks.contains("readVectorItems"))
        // The cache must be tied to the model/track key, not a global map.
        assertTrue(lyricHooks.contains("modelSongIdText"))
    }

    private fun projectFile(relativePath: String): String {
        val roots = sequenceOf(
            System.getProperty("user.dir"),
            System.getProperty("user.dir") + "/..",
            System.getProperty("user.dir") + "/../..",
        )
        roots.forEach { root ->
            val file = java.io.File(root, relativePath)
            if (file.isFile) return file.readText()
        }
        error("project file not found: $relativePath")
    }
}
