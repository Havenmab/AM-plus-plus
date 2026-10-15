package dev.amenhancer.module.lyrics

import dev.amenhancer.module.config.CustomLyricsIndexPointer
import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsManifest
import dev.amenhancer.module.model.CustomLyricsSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage of the 「清空自定义歌词」 action: every entry file and the
 * published index file are enumerated for deletion, the index is reset before
 * the files are retired, and a failed reset changes nothing.
 */
class CustomLyricsClearTransactionTest {

    @Test
    fun `the plan drops every entry and the published index file`() {
        val plan = CustomLyricsClearPolicy.plan(
            manifest = manifest(
                entry(id = 42L, fileId = "lyrics_a"),
                entry(id = 43L, fileId = "lyrics_b"),
            ),
            pointer = pointer("index_7"),
        )

        assertEquals(CustomLyricsManifest.empty(), plan.remainingManifest)
        assertEquals(2, plan.removedEntries)
        assertEquals(listOf("lyrics_a", "lyrics_b", "index_7"), plan.fileIds)
    }

    @Test
    fun `a malformed entry still has its addressable file retired`() {
        // The entry's size/hash metadata is invalid, so a sanitized view would
        // drop it, but its file id is valid and its file would be an orphan.
        val malformed = CustomLyricsEntry(
            appleMusicId = 9L,
            displayName = "broken",
            fileId = "lyrics_broken",
            sizeBytes = 0L,
            sha256 = "not-a-hash",
            source = CustomLyricsSources.MANUAL,
            enabled = true,
        )

        val plan = CustomLyricsClearPolicy.plan(
            manifest = manifest(malformed),
            pointer = null,
        )

        assertEquals(1, plan.removedEntries)
        assertEquals(listOf("lyrics_broken"), plan.fileIds)
    }

    @Test
    fun `an unusable file id is never handed to the store`() {
        val plan = CustomLyricsClearPolicy.plan(
            manifest = manifest(entry(id = 5L, fileId = "lyrics_ok")).let {
                CustomLyricsManifest(
                    it.entries + it.entries.single().copy(appleMusicId = 6L, fileId = "bad/../id"),
                )
            },
            pointer = CustomLyricsIndexPointer(
                fileId = "index_ok",
                generation = 3L,
                sha256 = SHA,
                sizeBytes = 12L,
            ),
        )

        assertEquals(listOf("lyrics_ok", "index_ok"), plan.fileIds)
    }

    @Test
    fun `clearing resets the index first and then deletes every planned file`() {
        val files = linkedMapOf(
            "lyrics_a" to byteArrayOf(1),
            "lyrics_b" to byteArrayOf(2),
            "index_7" to byteArrayOf(3),
        )
        val order = mutableListOf<String>()
        val result = CustomLyricsClearTransaction(
            resetIndex = { order += "reset"; true },
            deleteRemoteFile = { fileId ->
                order += "delete:$fileId"
                files.remove(fileId) != null
            },
        ).clear(
            oldManifest = manifest(
                entry(id = 42L, fileId = "lyrics_a"),
                entry(id = 43L, fileId = "lyrics_b"),
            ),
            pointer = pointer("index_7"),
        )

        assertEquals(CustomLyricsClearResult.Cleared(removedEntries = 2, removedFiles = 3), result)
        assertTrue(files.isEmpty())
        assertEquals(listOf("reset", "delete:lyrics_a", "delete:lyrics_b", "delete:index_7"), order)
    }

    @Test
    fun `a failed index reset leaves every file in place`() {
        var deletes = 0
        val result = CustomLyricsClearTransaction(
            resetIndex = { false },
            deleteRemoteFile = { _ -> deletes++; true },
        ).clear(
            oldManifest = manifest(entry(id = 42L, fileId = "lyrics_a")),
            pointer = pointer("index_7"),
        )

        assertEquals(CustomLyricsClearResult.Failed("无法重置歌词索引"), result)
        assertEquals(0, deletes)
    }

    @Test
    fun `a throwing delete is contained and the remaining files are still retired`() {
        val attempted = mutableListOf<String>()
        val result = CustomLyricsClearTransaction(
            resetIndex = { true },
            deleteRemoteFile = { fileId ->
                attempted += fileId
                if (fileId == "lyrics_a") error("disk") else true
            },
        ).clear(
            oldManifest = manifest(
                entry(id = 42L, fileId = "lyrics_a"),
                entry(id = 43L, fileId = "lyrics_b"),
            ),
            pointer = null,
        )

        assertEquals(listOf("lyrics_a", "lyrics_b"), attempted)
        assertEquals(CustomLyricsClearResult.Cleared(removedEntries = 2, removedFiles = 1), result)
    }

    @Test
    fun `an empty store is a clean no-op`() {
        var reset = false
        val result = CustomLyricsClearTransaction(
            resetIndex = { reset = true; true },
            deleteRemoteFile = { _ -> false },
        ).clear(oldManifest = CustomLyricsManifest.empty(), pointer = null)

        assertTrue(reset)
        assertEquals(CustomLyricsClearResult.Cleared(removedEntries = 0, removedFiles = 0), result)
        assertFalse(result is CustomLyricsClearResult.Failed)
    }

    private fun manifest(vararg entries: CustomLyricsEntry) = CustomLyricsManifest(entries.toList())

    private fun entry(id: Long, fileId: String) = CustomLyricsEntry(
        appleMusicId = id,
        displayName = "歌 $id",
        fileId = fileId,
        sizeBytes = 1L,
        sha256 = SHA,
        source = CustomLyricsSources.MANUAL,
        enabled = true,
    )

    private fun pointer(fileId: String) = CustomLyricsIndexPointer(
        fileId = fileId,
        generation = 1L,
        sha256 = SHA,
        sizeBytes = 1L,
    )

    private companion object {
        const val SHA = "0cba697d61a21fb62408b2411aa2152d1bc24cc2414d2bd162f70e04d20c5e53"
    }
}
