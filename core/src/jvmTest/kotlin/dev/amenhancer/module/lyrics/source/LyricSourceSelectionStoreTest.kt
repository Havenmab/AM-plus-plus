package dev.amenhancer.module.lyrics.source

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the persisted selection: a round trip through the file, per-song
 * isolation, and the recovery rules that keep a corrupt or outdated file from
 * crashing the sheet or restoring a key nobody can resolve.
 */
class LyricSourceSelectionStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `the selection round trips through the file`() {
        val file = selectionFile()
        val store = LyricSourceSelectionStore(file)

        assertTrue(store.select(42L, LyricLane.ORIGINAL, LyricSourceKeys.APPLE))
        assertTrue(
            store.select(
                42L,
                LyricLane.TRANSLATION,
                LyricSourceKeys.online(LyricSourceProviders.QQ, "1001"),
            ),
        )

        val reloaded = LyricSourceSelectionStore(file)
        assertEquals(
            mapOf(
                LyricLane.ORIGINAL to LyricSourceKeys.APPLE,
                LyricLane.TRANSLATION to LyricSourceKeys.online(LyricSourceProviders.QQ, "1001"),
            ),
            reloaded.selection(42L),
        )
        assertEquals(
            LyricSourceKeys.online(LyricSourceProviders.QQ, "1001"),
            reloaded.optionKey(42L, LyricLane.TRANSLATION),
        )
        assertNull(reloaded.optionKey(42L, LyricLane.ROMANIZATION))
        assertEquals(emptyMap<LyricLane, String>(), reloaded.selection(43L))
    }

    @Test
    fun `each song keeps its own lanes`() {
        val store = LyricSourceSelectionStore(selectionFile())

        store.select(1L, LyricLane.TRANSLATION, LyricSourceKeys.APPLE)
        store.select(2L, LyricLane.TRANSLATION, LyricSourceKeys.HIDDEN)

        assertEquals(LyricSourceKeys.APPLE, store.optionKey(1L, LyricLane.TRANSLATION))
        assertEquals(LyricSourceKeys.HIDDEN, store.optionKey(2L, LyricLane.TRANSLATION))
        assertEquals(listOf(1L, 2L), store.songIds())
    }

    @Test
    fun `clear drops one lane and keeps the others`() {
        val file = selectionFile()
        val store = LyricSourceSelectionStore(file)
        store.select(42L, LyricLane.ORIGINAL, LyricSourceKeys.APPLE)
        store.select(42L, LyricLane.TRANSLATION, LyricSourceKeys.APPLE)

        assertTrue(store.clear(42L, LyricLane.TRANSLATION))
        assertFalse(store.clear(42L, LyricLane.TRANSLATION))
        assertEquals(
            mapOf(LyricLane.ORIGINAL to LyricSourceKeys.APPLE),
            LyricSourceSelectionStore(file).selection(42L),
        )
    }

    @Test
    fun `clearSong drops every lane of that song only`() {
        val file = selectionFile()
        val store = LyricSourceSelectionStore(file)
        store.select(42L, LyricLane.ORIGINAL, LyricSourceKeys.APPLE)
        store.select(42L, LyricLane.TRANSLATION, LyricSourceKeys.APPLE)
        store.select(43L, LyricLane.TRANSLATION, LyricSourceKeys.HIDDEN)

        assertTrue(store.clearSong(42L))
        assertFalse(store.clearSong(42L))

        val reloaded = LyricSourceSelectionStore(file)
        assertEquals(emptyMap<LyricLane, String>(), reloaded.selection(42L))
        assertEquals(
            mapOf(LyricLane.TRANSLATION to LyricSourceKeys.HIDDEN),
            reloaded.selection(43L),
        )
    }

    @Test
    fun `clearAll drops every song`() {
        val file = selectionFile()
        val store = LyricSourceSelectionStore(file)
        store.select(1L, LyricLane.ORIGINAL, LyricSourceKeys.APPLE)
        store.select(2L, LyricLane.ORIGINAL, LyricSourceKeys.APPLE)

        assertTrue(store.clearAll())
        assertFalse(store.clearAll())
        assertTrue(LyricSourceSelectionStore(file).songIds().isEmpty())
    }

    @Test
    fun `a corrupt file behaves like an empty one and is replaced`() {
        val file = selectionFile()
        file.writeText("this is not json {", Charsets.UTF_8)
        val store = LyricSourceSelectionStore(file)

        assertTrue(store.selection(42L).isEmpty())

        assertTrue(store.select(42L, LyricLane.ORIGINAL, LyricSourceKeys.APPLE))
        assertEquals(
            mapOf(LyricLane.ORIGINAL to LyricSourceKeys.APPLE),
            LyricSourceSelectionStore(file).selection(42L),
        )
    }

    @Test
    fun `an unknown version is ignored instead of guessed`() {
        val file = selectionFile()
        file.writeText(
            JSONObject()
                .put("version", LyricSourceSelectionStore.VERSION + 1)
                .put(
                    "songs",
                    JSONArray().put(
                        JSONObject()
                            .put("songId", 42L)
                            .put("lanes", JSONObject().put("original", LyricSourceKeys.APPLE)),
                    ),
                )
                .toString(),
            Charsets.UTF_8,
        )

        assertTrue(LyricSourceSelectionStore(file).selection(42L).isEmpty())
    }

    @Test
    fun `unknown lanes and malformed keys are dropped on read`() {
        val file = selectionFile()
        file.writeText(
            JSONObject()
                .put("version", LyricSourceSelectionStore.VERSION)
                .put(
                    "songs",
                    JSONArray().put(
                        JSONObject()
                            .put("songId", 42L)
                            .put(
                                "lanes",
                                JSONObject()
                                    .put("bogus", LyricSourceKeys.APPLE)
                                    .put("translation", "key with spaces")
                                    .put("romanization", LyricSourceKeys.APPLE),
                            ),
                    ),
                )
                .toString(),
            Charsets.UTF_8,
        )

        assertEquals(
            mapOf(LyricLane.ROMANIZATION to LyricSourceKeys.APPLE),
            LyricSourceSelectionStore(file).selection(42L),
        )
    }

    @Test
    fun `a song entry without a usable lane is dropped`() {
        val file = selectionFile()
        file.writeText(
            JSONObject()
                .put("version", LyricSourceSelectionStore.VERSION)
                .put(
                    "songs",
                    JSONArray()
                        .put(JSONObject().put("songId", 0L).put("lanes", JSONObject().put("original", "apple")))
                        .put(JSONObject().put("songId", 42L).put("lanes", JSONObject())),
                )
                .toString(),
            Charsets.UTF_8,
        )

        assertTrue(LyricSourceSelectionStore(file).songIds().isEmpty())
    }

    @Test
    fun `the hidden marker is only kept for supplement lanes`() {
        val file = selectionFile()
        val store = LyricSourceSelectionStore(file)

        assertFalse(store.select(42L, LyricLane.ORIGINAL, LyricSourceKeys.HIDDEN))
        assertTrue(store.select(42L, LyricLane.TRANSLATION, LyricSourceKeys.HIDDEN))

        val reloaded = LyricSourceSelectionStore(file)
        assertEquals(
            mapOf(LyricLane.TRANSLATION to LyricSourceKeys.HIDDEN),
            reloaded.selection(42L),
        )
    }

    @Test
    fun `invalid input is rejected without writing`() {
        val file = selectionFile()
        val store = LyricSourceSelectionStore(file)

        assertFalse(store.select(0L, LyricLane.ORIGINAL, LyricSourceKeys.APPLE))
        assertFalse(store.select(42L, LyricLane.ORIGINAL, "  "))
        assertFalse(store.select(42L, LyricLane.ORIGINAL, "key with spaces"))
        assertFalse(store.select(42L, LyricLane.ORIGINAL, "k".repeat(LyricSourceKeys.MAX_KEY_LENGTH + 1)))
        assertFalse(file.exists())
        assertTrue(store.songIds().isEmpty())
    }

    @Test
    fun `the least recently written song is evicted`() {
        val store = LyricSourceSelectionStore(selectionFile(), maxSongs = 2)

        store.select(1L, LyricLane.ORIGINAL, LyricSourceKeys.APPLE)
        store.select(2L, LyricLane.ORIGINAL, LyricSourceKeys.APPLE)
        store.select(3L, LyricLane.ORIGINAL, LyricSourceKeys.APPLE)

        assertEquals(listOf(2L, 3L), store.songIds())
        assertTrue(store.selection(1L).isEmpty())
    }

    @Test
    fun `writing the same selection again does not rewrite the file`() {
        val file = selectionFile()
        val store = LyricSourceSelectionStore(file)
        store.select(42L, LyricLane.ORIGINAL, LyricSourceKeys.APPLE)
        val revision = store.revision()
        val stamp = file.lastModified()

        assertTrue(store.select(42L, LyricLane.ORIGINAL, LyricSourceKeys.APPLE))
        assertEquals(revision, store.revision())
        assertEquals(stamp, file.lastModified())
    }

    @Test
    fun `revision advances on every successful write`() {
        val store = LyricSourceSelectionStore(selectionFile())
        assertEquals(0L, store.revision())

        store.select(42L, LyricLane.ORIGINAL, LyricSourceKeys.APPLE)
        assertEquals(1L, store.revision())

        store.clearSong(42L)
        assertEquals(2L, store.revision())
    }

    @Test
    fun `a missing parent directory is created`() {
        val file = File(temp.newFolder("nested"), "a/b/" + LyricSourceSelectionStore.FILE_NAME)
        val store = LyricSourceSelectionStore(file)

        assertTrue(store.select(42L, LyricLane.ORIGINAL, LyricSourceKeys.APPLE))
        assertTrue(file.isFile)
        assertEquals(
            mapOf(LyricLane.ORIGINAL to LyricSourceKeys.APPLE),
            LyricSourceSelectionStore(file).selection(42L),
        )
    }

    @Test
    fun `an oversized file is treated as corrupt`() {
        val file = selectionFile()
        file.writeText("x".repeat((LyricSourceSelectionStore.MAX_FILE_BYTES + 1L).toInt()), Charsets.UTF_8)

        assertTrue(LyricSourceSelectionStore(file).selection(42L).isEmpty())
    }

    private fun selectionFile(): File = File(temp.newFolder(), LyricSourceSelectionStore.FILE_NAME)
}
