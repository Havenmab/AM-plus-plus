package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.*
import org.junit.Test

/**
 * The collection2 Compose album page exists only on 7.0.0-beta (1606/1607). Its hooks must stay
 * pinned to the verified publication method and must never install on builds whose profile has no
 * exact target — the 6.5.x Epoxy collection surface owns those hosts.
 */
class AppleAlbumComposeProfileTest {
    private val version700 = AppleMusicVersion("7.0.0-beta", 1606L)
    private val version653 = AppleMusicVersion("6.5.3", 1599L)

    @Test
    fun `album header has its own binary verified publication method`() {
        val header = AppleMusicHookProfiles
            .exactTargets(version700, AppleMusicHookPoint.ALBUM_COMPOSE_HEADER_REFRESH)
            .single()
        assertEquals(
            "com.apple.android.music.collection2.viewmodel.BaseCollectionViewModel",
            header.className,
        )
        assertEquals("publishHeader", header.methodName)
        assertEquals(emptyList<String>(), header.parameterTypeNames)
        assertEquals("void", header.returnTypeName)
        assertEquals(false, header.isStatic)
        assertFalse(header.includeSynthetic)
        assertNotEquals("refreshData", header.methodName)
        assertNotEquals("refreshState", header.methodName)
        // The track-list publication stays the separate, pre-existing refreshState owner.
        assertEquals(
            "refreshState",
            AppleMusicHookProfiles
                .exactTargets(version700, AppleMusicHookPoint.ALBUM_COMPOSE_REFRESH)
                .single()
                .methodName,
        )
    }

    @Test
    fun `album compose page is pinned to the collection2 fragment and view model`() {
        val content = AppleMusicHookProfiles
            .exactTargets(version700, AppleMusicHookPoint.ALBUM_COMPOSE_CONTENT)
            .single()
        assertEquals("com.apple.android.music.collection2.fragment.AlbumPageFragment", content.className)
        assertEquals("r1", content.methodName)
        assertEquals(
            "com.apple.android.music.collection2.viewmodel.AlbumViewModel",
            AppleMusicHookProfiles
                .exactTargets(version700, AppleMusicHookPoint.ALBUM_COMPOSE_VIEW_MODEL_GETTER)
                .single()
                .returnTypeName,
        )
        val row = AppleMusicHookProfiles
            .exactTargets(version700, AppleMusicHookPoint.ALBUM_COMPOSE_ROW)
            .single()
        assertEquals(
            "a",
            row.runtimeMemberName(AppleMusicRuntimeMember.ALBUM_COMPOSE_ROW_KEY_FIELD),
        )
        assertEquals(
            "a",
            row.runtimeMemberName(AppleMusicRuntimeMember.ALBUM_COMPOSE_KEY_ID_FIELD),
        )
    }

    @Test
    fun `compose album hooks are declared only on the verified beta and stay fail-open elsewhere`() {
        val composePoints = AppleMusicHookPoint.entries.filter { it.name.startsWith("ALBUM_COMPOSE_") }
        assertEquals(11, composePoints.size)
        composePoints.forEach { point ->
            assertEquals(1, AppleMusicHookProfiles.exactTargets(version700, point).size)
            assertTrue(
                "6.5.3 must not pin $point",
                AppleMusicHookProfiles.exactTargets(version653, point).isEmpty(),
            )
        }
        // Installation guards on the content target, so 6.5.x never resolves the Compose chain.
        assertTrue(
            AppleMusicHookProfiles
                .exactTargets(version653, AppleMusicHookPoint.ALBUM_COMPOSE_CONTENT)
                .isEmpty(),
        )
    }

    @Test
    fun `entity kind mapping matches the library surface kinds`() {
        assertEquals(InAppLibraryEntityKind.ALBUM, artistComposeEntityKind("albums"))
        assertEquals(InAppLibraryEntityKind.ALBUM, artistComposeEntityKind("library-albums"))
        assertEquals(InAppLibraryEntityKind.SONG, artistComposeEntityKind("songs"))
        assertEquals(InAppLibraryEntityKind.SONG, artistComposeEntityKind("library-songs"))
        assertEquals(InAppLibraryEntityKind.ARTIST, artistComposeEntityKind("artists"))
        assertEquals(InAppLibraryEntityKind.ARTIST, artistComposeEntityKind("library-artists"))
        assertNull(artistComposeEntityKind(null))
        assertNull(artistComposeEntityKind("playlists"))
    }
}
