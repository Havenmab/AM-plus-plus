package dev.amenhancer.module.hook

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HleArtistComposeProfileStructuralTest {
    private fun source(relative: String): String = sequenceOf(
        File(relative),
        File("../$relative"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("Missing $relative")

    private val hooks by lazy {
        source(
            "host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/metadata/AppleArtistSurfaceHooks.kt",
        )
    }

    private fun composeBody(): String =
        hooks.substringAfter("fun installComposeProfileHooks()").substringBefore("fun handleFinalBinding(")

    @Test
    fun composeArtistHeaderReusesTheArtistAliasPathAndSongRegionEvidence() {
        val body = composeBody()
        assertTrue(body.contains("kind = InAppLibraryEntityKind.ARTIST"))
        assertTrue(body.contains("librarySurfaceHooks.applyAliasToEntity("))
        assertTrue(body.contains("host.scheduleMetadataResolution("))
        assertTrue(body.contains("associateTopSongWithProfileArtist(owner, mediaId)"))
        assertTrue(body.contains("requestPageOriginalMetadata(songIds)"))
        // P1: associate before enrich so missing song artists still get the profile candidate.
        val associateAt = body.indexOf("associateTopSongWithProfileArtist(owner, mediaId)")
        val enrichAt = body.indexOf("host.enrichLibraryEntity(", associateAt)
        assertTrue("associateTopSong must precede enrichLibraryEntity", associateAt >= 0 && enrichAt > associateAt)
    }

    @Test
    fun composeProfileClearsSavedSuccessWhenLeavingSuccessAndIsolatesRefreshQueueIdentity() {
        val body = composeBody()
        assertTrue(body.contains("clearComposeProfileOwner(owner)"))
        assertTrue(body.contains("slot = COMPOSE_PROFILE_REFRESH_SLOT"))
        assertTrue(body.contains("composeProfileRefreshPending.remove(owner)"))
        assertTrue(
            source(
                "host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/metadata/AppleArtistSurfaceHooks.kt",
            ).contains("private const val COMPOSE_PROFILE_REFRESH_SLOT = 1"),
        )
    }

    @Test
    fun composeArtistHeaderNeverInfersLanguageFromTheArtistNameOrSwapsLogos() {
        val body = composeBody()
        assertFalse(body.contains("containsHanCharacters"))
        assertFalse(body.contains("hasCjkArtistScript"))
        assertFalse(body.contains("containsJapaneseKana"))
        assertFalse(body.contains("containsHangul"))
        assertFalse(body.contains("AppleMediaApiTextAttribute.NAME"))
        assertFalse(body.contains("ARTIST_LOGO"))
        assertFalse(body.contains("editorialArtwork"))
    }

    @Test
    fun resolvedAliasesRepublishLiveComposeArtistPages() {
        val applier = source(
            "host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/metadata/AppleInAppMetadataApplier.kt",
        )
        assertTrue(applier.contains("artistSurfaceHooks.refreshComposeProfiles(mediaId)"))
        assertTrue(applier.contains("artistSurfaceHooks.hasComposeProfileRefs(mediaId)"))
        val bridge = source("host-applemusic/src/main/java/dev/amenhancer/module/hook/HleMetadataSurfaceBridge.kt")
        assertTrue(bridge.contains("artistSurfaceHooks.hasComposeProfileSurface()"))
    }

    @Test
    fun only1609DeclaresTheComposeArtistRoles() {
        val directory = "host-applemusic/src/main/resources/host-profiles"
        val roles = listOf("compose_profile_view_model", "compose_profile_success_state")
        listOf("6.5.0-1580", "6.5.1-1583", "6.5.2-1586", "6.5.3-1599", "7.0.0-beta-1606", "7.0.0-beta-1609")
            .forEach { name ->
                val profile = JSONObject(source("$directory/$name.json"))
                val targets = profile.getJSONObject("hookTargets").getJSONArray("ARTIST_SURFACE_CLASSES")
                val declared = (0 until targets.length()).mapNotNull { index ->
                    targets.getJSONObject(index).getJSONObject("runtimeMemberNames")
                        .optString("ARTIST_RUNTIME_ROLE").takeIf(roles::contains)
                }
                assertEquals(name, if (name.endsWith("1609")) roles else emptyList<String>(), declared)
            }
    }

    @Test
    fun unownedSuccessAndArtistChangesDropTheOwnersPreviousRefs() {
        val hooks = source(
            "host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/metadata/AppleArtistSurfaceHooks.kt",
        )
        val state = hooks.substringAfter("private fun onComposeProfileState(").substringBefore("registerComposeProfile(mediaId, owner)")
        assertEquals(4, Regex("return clearComposeProfileOwner\\(owner\\)").findAll(state).count())
        assertTrue(state.contains("composeProfileRefs.unregister(owner)"))
        assertTrue(hooks.contains("private val composeProfileRefs = ComposeProfileRefRegistry()"))
        assertFalse(hooks.contains("ConcurrentLinkedQueue<WeakReference<Any>>"))
    }
}
