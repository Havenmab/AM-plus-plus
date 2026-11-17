package io.github.proify.lyricon.amprovider.xposed

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the D1/D3 wiring in the coordinator and store, which the pure policy tests cannot see:
 *
 *  - the resolved original **song** alias records its language as a per-media hint (D1);
 *  - the cache-miss branch of the original artist lookup records a retryable miss and never marks
 *    the artist resolved (D3);
 *  - the key diagnostic lines are events, not rate-limited lines, because a missing line must not
 *    be mistaken for a branch that did not run.
 */
class OriginalArtistRetryStructuralTest {

    private fun source(path: String): String = sequenceOf(
        File("src/main/java/" + path),
        File("../src/main/java/" + path),
        File("host-applemusic/src/main/java/" + path),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("Missing " + path)

    private val coordinator = source(
        "io/github/proify/lyricon/amprovider/xposed/metadata/AppleInAppMetadataResolutionCoordinator.kt",
    )
    private val playback = source(
        "io/github/proify/lyricon/amprovider/xposed/metadata/ApplePlaybackMetadataCoordinator.kt",
    )
    private val store = source(
        "io/github/proify/lyricon/amprovider/xposed/AppleMetadataOverrideStore.kt",
    )
    private val policies = source(
        "io/github/proify/lyricon/amprovider/xposed/metadata/AppleMetadataPolicies.kt",
    )
    private val diagnostics = source(
        "io/github/proify/lyricon/amprovider/xposed/MetadataDiagnostics.kt",
    )

    @Test
    fun `the song alias records its language as a per-media hint`() {
        assertTrue(policies.contains("internal fun noteOriginalSongLanguageHint("))
        assertTrue(coordinator.contains("noteOriginalSongLanguageHint(mediaId, resolution, metadataStore)"))
        assertTrue(playback.contains("noteOriginalSongLanguageHint(metadata.id, resolution, metadataStore)"))
        assertTrue(store.contains("fun rememberOriginalLanguageHint("))
        assertTrue(store.contains("fun originalLanguageHint("))
    }

    @Test
    fun `the cached artist miss is retryable and never marks the artist resolved`() {
        val start = coordinator.indexOf("private fun resolveCachedOriginalAssociatedArtist(")
        assertTrue("missing the cached artist lookup", start >= 0)
        val end = coordinator.indexOf("    private fun ", start + 1)
        assertTrue("missing the next function", end > start)
        val body = coordinator.substring(start, end)

        assertTrue("a miss must be recorded", body.contains("recordOriginalArtistMiss("))
        assertTrue("a miss must be logged", body.contains("meta artist-retry id="))
        assertTrue(
            "the localized fallback must not complete the original resolution",
            body.contains("completesOriginalArtistResolution = false"),
        )
        assertFalse(body.contains("completesOriginalArtistResolution = true"))
        // The success branch above may mark the artist resolved; the miss branch below must not.
        val missBranch = body.substringAfter("meta artist-retry id=")
        assertFalse(
            "a miss must not look like a resolution",
            missBranch.contains("markOriginalArtistResolved("),
        )
    }

    @Test
    fun `the artist lookup backs off instead of retrying every pass`() {
        assertTrue(coordinator.contains("shouldRetryOriginalArtist(mediaId"))
        assertTrue(coordinator.contains("meta artist-retry id="))
        assertTrue(store.contains("fun shouldRetryOriginalArtist("))
        assertTrue(store.contains("fun recordOriginalArtistMiss("))
        assertTrue(store.contains("originalArtistRetries"))
    }

    @Test
    fun `the key lines are events so the cap cannot hide them`() {
        assertFalse(coordinator.contains("MetadataDiagnostics.line(\n            \"meta effective id="))
        assertFalse(coordinator.contains("MetadataDiagnostics.line(\n            \"meta artist-origin id="))
        assertTrue(coordinator.contains("\"meta effective id=\""))
        assertTrue(coordinator.contains("\"meta artist-origin id=\""))
        assertTrue(diagnostics.contains("const val LIMIT = 4096"))
        assertTrue(diagnostics.contains("LIMIT_EVENT"))
    }
}
