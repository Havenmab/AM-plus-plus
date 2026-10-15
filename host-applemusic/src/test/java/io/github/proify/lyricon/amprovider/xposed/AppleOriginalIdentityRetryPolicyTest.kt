/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import com.juren233.hyperlyricsenhanced.common.lyric.AppleOriginalMetadataPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A timed-out catalog-identity lookup must stay retryable.
 *
 * Device evidence (session 12:30-12:38, `id=1523255476` "The Sixth Sense") showed the identity
 * probe finishing with `outcome=timeout`, which produces an empty `CatalogIdentity`.  Because
 * `isUsefulCatalogIdentity` refuses to cache that empty identity, no ISRC/genre/artist-language
 * facts survive, `languageTagsForOriginalMetadata` returns an empty language list, and the
 * original-region probe can never run.  The song was then concluded as permanently unnamed, so
 * every later visibility pass hit the "already resolved, no cache miss" gate and never probed
 * again for the whole session.
 *
 * These assertions pin the two halves of the recovery contract: an empty identity is never
 * cached as a usable fact, and a song whose identity never became known (`originKnown=false`,
 * `alias=null`) is reported as retryable so the existing [AppleMetadataResolutionEngine]
 * cache-miss policy (`ORIGINAL_METADATA_CACHE_MISS_RETRY_MS`) lets a later pass try again.
 */
class AppleOriginalIdentityRetryPolicyTest {

    /** The device-log song: Latin title, CJK featured artists, no genre row on the app path. */
    private val sixthSenseTitle = "The Sixth Sense"
    private val sixthSenseArtist = "ナナツカゼ, PIKASONIC, なこたんまる"

    private fun resolution(
        alias: AppleInternalCatalogResolver.Alias? = null,
        language: String? = null,
        originKnown: Boolean,
        artistIds: List<String> = emptyList(),
    ): AppleInternalCatalogResolver.OriginalResolution =
        AppleInternalCatalogResolver.OriginalResolution(
            alias = alias,
            language = language,
            originKnown = originKnown,
            artistIds = artistIds,
        )

    @Test
    fun `a timed-out identity resolution is retryable`() {
        // Exactly what resolveOriginalMetadataFromCatalog publishes when queryById times out:
        // no alias, no derived probe language, no identity facts.
        assertTrue(
            shouldRetryMissingOriginalSongIdentity(
                resolution(originKnown = false),
            ),
        )
    }

    @Test
    fun `a probed identity that yielded no alias stays conclusive`() {
        // The identity was known (ISRC or catalog genre), so every supported original region was
        // already probed; "no original name exists" is a real answer and must not be retried as
        // if the lookup had failed.
        assertFalse(
            shouldRetryMissingOriginalSongIdentity(
                resolution(language = "ja-JP", originKnown = true),
            ),
        )
    }

    @Test
    fun `an alias that did resolve is never reported as retryable`() {
        assertFalse(
            shouldRetryMissingOriginalSongIdentity(
                resolution(
                    alias = AppleInternalCatalogResolver.Alias("春めく", "ナナツカゼ", "ja-JP"),
                    language = "ja-JP",
                    originKnown = true,
                    artistIds = listOf("1685653199"),
                ),
            ),
        )
    }

    @Test
    fun `an empty timeout identity is not cached as a usable identity`() {
        val timedOut = AppleInternalCatalogResolver.CatalogIdentity(
            isrc = null,
            fallbackAliases = emptyList(),
            genres = emptyList(),
            artistIds = emptyList(),
        )

        // Nothing to remember: caching it would positively answer later probes with "no facts"
        // and hide the retry.
        assertFalse(
            AppleInternalCatalogResolver.shouldCacheCatalogIdentity(
                isrc = timedOut.isrc,
                genres = timedOut.genres,
            ),
        )
        // `isUsefulCatalogIdentity` is an extension over a resolver instance that delegates to
        // exactly that predicate, so the assertion above is the same statement without needing a
        // resolver.

        // The empty-identity retry predicate only relaxes for songs the CJK policy would probe
        // at all; it stays subject to that existing gate.
        assertTrue(
            AppleInternalCatalogResolver.shouldRetryEmptyCatalogIdentity(
                mediaId = "1523255476",
                title = sixthSenseTitle,
                artist = sixthSenseArtist,
                genre = null,
                isrc = timedOut.isrc,
                catalogGenres = timedOut.genres,
            ),
        )
        assertTrue(
            AppleOriginalMetadataPolicy.shouldResolveCjkOriginalMetadata(
                mediaId = "1523255476",
                title = sixthSenseTitle,
                artist = sixthSenseArtist,
                genre = null,
            ),
        )
        assertFalse(
            AppleInternalCatalogResolver.shouldRetryEmptyCatalogIdentity(
                mediaId = "1523255476",
                title = sixthSenseTitle,
                artist = sixthSenseArtist,
                genre = null,
                isrc = "JPDN12345678",
                catalogGenres = emptyList(),
            ),
        )
    }

    @Test
    fun `recording the miss reopens the probe under the existing retry policy`() {
        val missUptime = 1_000L
        val retryAfter = AppleMetadataResolutionEngine.ORIGINAL_METADATA_CACHE_MISS_RETRY_MS

        // Before the fix the resolved id had no miss timestamp, so the gate stayed closed
        // forever; a recorded miss reopens it after the existing 750 ms policy window.
        assertFalse(
            AppleMetadataResolutionEngine.shouldRetryOriginalMetadataCacheProbe(
                originalResolved = true,
                lastMissUptimeMillis = null,
                nowUptimeMillis = missUptime + retryAfter,
            ),
        )
        assertFalse(
            AppleMetadataResolutionEngine.shouldRetryOriginalMetadataCacheProbe(
                originalResolved = true,
                lastMissUptimeMillis = missUptime,
                nowUptimeMillis = missUptime + retryAfter - 1,
            ),
        )
        assertTrue(
            AppleMetadataResolutionEngine.shouldRetryOriginalMetadataCacheProbe(
                originalResolved = true,
                lastMissUptimeMillis = missUptime,
                nowUptimeMillis = missUptime + retryAfter,
            ),
        )
        // An unresolved id is retried immediately, which is why the first pass still works.
        assertTrue(
            AppleMetadataResolutionEngine.shouldRetryOriginalMetadataCacheProbe(
                originalResolved = false,
                lastMissUptimeMillis = null,
                nowUptimeMillis = missUptime,
            ),
        )
    }
}
