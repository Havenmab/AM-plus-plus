package io.github.proify.lyricon.amprovider.xposed

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaMetadataCacheProfileTest {
    @After
    fun restoreDefaultProfile() {
        MediaMetadataCache.setProfile("account")
        MediaMetadataCache.clearProfile()
    }

    @Test
    fun `metadata is namespaced by the active profile`() {
        val metadata = MediaMetadataCache.Metadata(
            id = "42",
            title = "Song",
            artist = "Artist",
            genre = null,
            duration = 0L,
            queueId = 0L,
        )

        MediaMetadataCache.setProfile("cn_v1")
        MediaMetadataCache.put(metadata)
        assertEquals("Song", MediaMetadataCache.getMetadataById("42")?.title)

        MediaMetadataCache.setProfile("jp_v1")
        assertNull(MediaMetadataCache.getMetadataById("42"))
        MediaMetadataCache.put(metadata.copy(title = "曲"))
        assertEquals("曲", MediaMetadataCache.getMetadataById("42")?.title)
    }

    @Test
    fun `reset drops original resolution state but keeps the display metadata`() {
        val metadata = MediaMetadataCache.Metadata(
            id = "1685653199",
            title = "Harumeku",
            artist = "ナナツカゼ",
            genre = null,
            duration = 0L,
            queueId = 0L,
        )

        MediaMetadataCache.setProfile("jp_v1")
        MediaMetadataCache.put(metadata)
        MediaMetadataCache.updateOriginalMetadata(
            mediaId = "1685653199",
            title = "春めく",
            artist = "ナナツカゼ",
            album = "アルバム",
            resolved = true,
        )
        assertEquals(
            true,
            MediaMetadataCache.getMetadataById("1685653199")?.originalMetadataResolved,
        )

        assertEquals(1, MediaMetadataCache.resetOriginalMetadataResolutions())
        val reset = MediaMetadataCache.getMetadataById("1685653199")!!
        assertEquals(false, reset.originalMetadataResolved)
        assertNull(reset.originalTitle)
        assertNull(reset.originalArtist)
        assertNull(reset.originalAlbum)
        // The queue-provided display metadata is untouched.
        assertEquals("Harumeku", reset.title)
        assertEquals("ナナツカゼ", reset.artist)
        // A second pass finds nothing left to drop.
        assertEquals(0, MediaMetadataCache.resetOriginalMetadataResolutions())
    }
}
