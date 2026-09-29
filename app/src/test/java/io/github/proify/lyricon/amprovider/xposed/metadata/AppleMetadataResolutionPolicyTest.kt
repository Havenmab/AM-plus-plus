package io.github.proify.lyricon.amprovider.xposed.metadata

import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver
import io.github.proify.lyricon.amprovider.xposed.AppleMetadataOverrideStore
import io.github.proify.lyricon.amprovider.xposed.AppleMetadataResolutionEngine
import io.github.proify.lyricon.amprovider.xposed.shouldForceSharedArtistTargetRebind
import io.github.proify.lyricon.amprovider.xposed.shouldRebindInAppMetadataRefs
import io.github.proify.lyricon.amprovider.xposed.shouldRetryOriginalMetadataCacheProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure policy coverage for the metadata-override surface-rebind decisions.
 *
 * Device logs showed the same media id re-applied ~21 times a minute because the coordinator ran
 * the full surface walk before comparing the alias with the previously applied one.  These tests
 * pin the decision that must happen first, the shared-artist fan-out skip, and the original-region
 * retry bound.
 */
class AppleMetadataResolutionPolicyTest {
    // ------------------------------------------------------------------ Fix 1: unchanged alias

    @Test
    fun `unchanged alias without an explicit force does not rebind the in-app surfaces`() {
        assertFalse(
            shouldRebindInAppMetadataRefs(
                aliasChanged = false,
                requestedForceInAppRebind = false,
            ),
        )
    }

    @Test
    fun `first application rebinds because the effective alias changed`() {
        // No previously applied alias: effectiveAlias() returns a value that differs from null.
        assertTrue(
            shouldRebindInAppMetadataRefs(
                aliasChanged = true,
                requestedForceInAppRebind = false,
            ),
        )
    }

    @Test
    fun `changed alias rebinds`() {
        assertTrue(
            shouldRebindInAppMetadataRefs(
                aliasChanged = true,
                requestedForceInAppRebind = false,
            ),
        )
    }

    @Test
    fun `explicit forced rebind still rebinds an unchanged alias`() {
        assertTrue(
            shouldRebindInAppMetadataRefs(
                aliasChanged = false,
                requestedForceInAppRebind = true,
            ),
        )
    }

    // ------------------------------------------------- Fix 2: shared-artist fan-out force skip

    @Test
    fun `fan-out target that already carries the alias is not force-rebound`() {
        assertFalse(
            shouldForceSharedArtistTargetRebind(
                targetAlreadyCarriesAlias = true,
                sharedAliasChanged = false,
            ),
        )
    }

    @Test
    fun `fan-out target that does not carry the alias still receives it`() {
        assertTrue(
            shouldForceSharedArtistTargetRebind(
                targetAlreadyCarriesAlias = false,
                sharedAliasChanged = false,
            ),
        )
    }

    @Test
    fun `fan-out forces every target when the shared alias changed`() {
        assertTrue(
            shouldForceSharedArtistTargetRebind(
                targetAlreadyCarriesAlias = true,
                sharedAliasChanged = true,
            ),
        )
    }

    // ------------------------------------------------------- Fix 3: original-region retry bound

    @Test
    fun `original region retry allows the first probes before any miss is recorded`() {
        assertTrue(
            shouldRetryOriginalMetadataCacheProbe(
                originalResolved = false,
                lastMissUptimeMillis = null,
                nowUptimeMillis = 0L,
                attemptedRetries = 0,
            ),
        )
    }

    @Test
    fun `original region retry still respects the interval before the cap`() {
        assertFalse(
            shouldRetryOriginalMetadataCacheProbe(
                originalResolved = true,
                lastMissUptimeMillis = 1_000L,
                nowUptimeMillis = 1_001L,
                attemptedRetries = 1,
            ),
        )
        assertTrue(
            shouldRetryOriginalMetadataCacheProbe(
                originalResolved = true,
                lastMissUptimeMillis = 1_000L,
                nowUptimeMillis = 1_750L,
                attemptedRetries = 1,
            ),
        )
    }

    @Test
    fun `original region retry stops after the attempt cap`() {
        val cap = AppleMetadataResolutionEngine.ORIGINAL_METADATA_CACHE_MISS_MAX_RETRIES
        assertFalse(
            shouldRetryOriginalMetadataCacheProbe(
                originalResolved = true,
                lastMissUptimeMillis = 1_000L,
                nowUptimeMillis = 1_000L + 10_000L,
                attemptedRetries = cap,
            ),
        )
        // An unresolved id is capped too, so a probe that never settles cannot loop forever.
        assertFalse(
            shouldRetryOriginalMetadataCacheProbe(
                originalResolved = false,
                lastMissUptimeMillis = null,
                nowUptimeMillis = 1_000_000L,
                attemptedRetries = cap,
            ),
        )
    }

    @Test
    fun `override store counts original cache misses and re-arms on reset`() {
        val store = AppleMetadataOverrideStore()
        store.recordOriginalCacheMiss("42", 1_000L)
        store.recordOriginalCacheMiss("42", 2_000L)
        assertEquals(2, store.originalCacheMissAttempts("42"))

        store.resetOriginalResolutionState("42")
        assertEquals(0, store.originalCacheMissAttempts("42"))
    }

    @Test
    fun `successful original metadata clears the cache miss counter`() {
        val store = AppleMetadataOverrideStore()
        store.recordOriginalCacheMiss("42", 1_000L)
        store.rememberOriginalMetadata(
            mediaId = "42",
            alias = AppleInternalCatalogResolver.Alias(
                title = "original",
                artist = "artist",
                language = "ja-JP",
            ),
            confirmed = true,
        )
        assertEquals(0, store.originalCacheMissAttempts("42"))
    }
}
