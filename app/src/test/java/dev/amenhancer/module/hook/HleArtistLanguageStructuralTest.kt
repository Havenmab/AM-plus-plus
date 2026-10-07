package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HleArtistLanguageStructuralTest {
    private fun source(relative: String): String = sequenceOf(
        File(relative),
        File("../$relative"),
    ).firstOrNull(File::isFile)?.readRefactorComponent()
        ?: error("Missing $relative")

    @Test
    fun resolverNeverTreatsStorefrontLocalizedArtistNamesAsOriginalRegionEvidence() {
        val resolver = source(
            "host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/AppleInternalCatalogResolver.kt",
        )
        assertFalse(resolver.contains("probeOriginalArtistLanguage"))
        assertFalse(resolver.contains("artistLanguages = listOfNotNull(cachedArtistLanguage)"))
        assertFalse(resolver.contains("originKnownFromArtist"))
        assertFalse(resolver.contains("trustedArtistOnlyLanguages"))
        assertTrue(resolver.contains("originKnown = isrc != null"))
        assertTrue(resolver.contains("isConfidentOriginalSongAlias"))
    }

    @Test
    fun confirmedArtistRegionRequeuesAssociatedSongs() {
        val coordinator = source(
            "host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/metadata/AppleInAppMetadataResolutionCoordinator.kt",
        )
        assertTrue(coordinator.contains("resetOriginalResolutionState"))
        assertTrue(coordinator.contains("associatedMediaIds"))
        assertTrue(coordinator.contains("RequestPriority.VISIBLE"))
    }

    @Test
    fun inconclusiveIdentityLookupsStayRetryableInsteadOfConcludingTheSong() {
        // A timed-out catalog-identity lookup publishes an alias-less, origin-unknown
        // resolution.  Concluding the song permanently there is what left the device-log song
        // uncorrected for the whole session, so both song owners must mark the inconclusive
        // probe as a cache miss; the existing 750 ms policy then reopens the next visible pass.
        val coordinator = source(
            "host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/metadata/AppleInAppMetadataResolutionCoordinator.kt",
        )
        assertTrue(coordinator.contains("shouldRetryMissingOriginalSongIdentity(resolution)"))
        assertTrue(coordinator.contains("metadataStore.recordOriginalCacheMiss("))

        val playback = source(
            "host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/metadata/ApplePlaybackMetadataCoordinator.kt",
        )
        assertTrue(playback.contains("shouldRetryMissingOriginalSongIdentity(resolution)"))
        assertTrue(playback.contains("metadataStore.recordOriginalCacheMiss("))

        // The retry gate itself must keep reading the miss timestamp the fix writes.
        assertTrue(coordinator.contains("metadataStore.originalCacheMissUptimeMillis(mediaId)"))
    }
}
