package dev.amenhancer.module.lyrics.source

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

/**
 * Per-song, per-lane memory of the source the user picked: the "按歌持久化" half
 * of the feature.
 *
 * One small JSON file holds every song ("lane -> option key"), written
 * atomically and read defensively. Everything about the format is a recovery
 * rule rather than a schema promise:
 *
 * - an unreadable, oversized, empty or non-JSON file behaves exactly like a
 *   missing one, and the next write replaces it;
 * - a file whose version is not [VERSION] is ignored instead of guessed at
 *   (only forward-compatible reads matter, and there is one version today);
 * - a song id, lane key or option key that does not parse is dropped, so a
 *   hand-edited file, a half-written file from a crash, or a stale key from a
 *   removed provider can never make the sheet crash or restore nonsense;
 * - the reserved [LyricSourceKeys.HIDDEN] marker is only kept for supplement
 *   lanes, because the original lane can never be hidden;
 * - at most [maxSongs] songs are kept, and the least recently written one is
 *   evicted first, so the file cannot grow without bound.
 *
 * A dropped key is not a lost feature: [LyricSourceCatalog] falls back to the
 * lane's default, which is what a user sees after deleting the custom file the
 * key pointed at.
 *
 * All methods are thread safe; the host calls them from the main thread and may
 * also read them from a background probe. The file is read lazily once per
 * instance and every mutation writes it back, so one instance owns the file:
 * the host creates it once (see [FILE_NAME]) and passes it to the catalog and
 * the controller instead of re-creating it per call.
 */
class LyricSourceSelectionStore(
    private val file: File,
    maxSongs: Int = MAX_SONGS,
) {

    /** One song's stored lanes plus the write order used for eviction. */
    private class SongEntry(
        val lanes: MutableMap<LyricLane, String> = LinkedHashMap(),
        var updatedAt: Long = 0L,
    )

    private val lock = Any()
    private val capacity = maxSongs.coerceIn(1, MAX_SONGS)
    private val songs = LinkedHashMap<Long, SongEntry>()

    private var loaded = false
    private var revision = 0L

    /** Monotonic counter of successful writes; lets a caller notice external changes. */
    fun revision(): Long = synchronized(lock) { revision }

    /** Every stored lane for [songId]; empty when the song has no override. */
    fun selection(songId: Long): Map<LyricLane, String> {
        if (songId <= 0L) return emptyMap()
        synchronized(lock) {
            ensureLoaded()
            val entry = songs[songId] ?: return emptyMap()
            return LinkedHashMap(entry.lanes)
        }
    }

    /**
     * True when [songId] has at least one stored lane choice: the user locked this
     * song's sources and the automatic completion must leave it alone.
     *
     * An explicit Apple preference ([LyricSourceKeys.APPLE]) counts exactly like any
     * other key, and a key that no longer resolves still counts until it is cleared:
     * it is a recorded choice, and the sheet's restore action is what releases it.
     */
    fun isUserLocked(songId: Long): Boolean = selection(songId).isNotEmpty()

    /** The stored key for one lane, or null when the lane is not overridden. */
    fun optionKey(songId: Long, lane: LyricLane): String? {
        if (songId <= 0L) return null
        synchronized(lock) {
            ensureLoaded()
            return songs[songId]?.lanes?.get(lane)
        }
    }

    /**
     * Stores [optionKey] for [songId]/[lane]. Returns false (and writes nothing)
     * when the song id or the key is invalid, or when the file could not be
     * written - in which case the in-memory state is rolled back so a caller
     * that treats false as "not stored" and a later read agree.
     */
    fun select(songId: Long, lane: LyricLane, optionKey: String): Boolean {
        if (songId <= 0L) return false
        val key = LyricSourceKeys.sanitize(optionKey) ?: return false
        if (LyricSourceKeys.isHidden(key) && !lane.isSupplement) return false
        synchronized(lock) {
            ensureLoaded()
            if (songs[songId]?.lanes?.get(lane) == key) return true
            return commit {
                val entry = songs[songId] ?: SongEntry()
                entry.lanes[lane] = key
                entry.updatedAt = System.currentTimeMillis()
                // Re-insert so the map's order stays "least recently written first".
                songs.remove(songId)
                songs[songId] = entry
                trim()
                true
            }
        }
    }

    /** Drops one lane's override. Returns true when something was stored before. */
    fun clear(songId: Long, lane: LyricLane): Boolean {
        if (songId <= 0L) return false
        synchronized(lock) {
            ensureLoaded()
            val entry = songs[songId] ?: return false
            if (!entry.lanes.containsKey(lane)) return false
            return commit {
                entry.lanes.remove(lane)
                if (entry.lanes.isEmpty()) {
                    songs.remove(songId)
                } else {
                    entry.updatedAt = System.currentTimeMillis()
                    songs.remove(songId)
                    songs[songId] = entry
                }
                true
            }
        }
    }

    /** Drops every lane of [songId] ("恢复 Apple 原生"). */
    fun clearSong(songId: Long): Boolean {
        if (songId <= 0L) return false
        synchronized(lock) {
            ensureLoaded()
            if (!songs.containsKey(songId)) return false
            return commit {
                songs.remove(songId)
                true
            }
        }
    }

    /** Drops every song's overrides. */
    fun clearAll(): Boolean {
        synchronized(lock) {
            ensureLoaded()
            if (songs.isEmpty()) return false
            return commit {
                songs.clear()
                true
            }
        }
    }

    /** The stored song ids, least recently written first. For diagnostics and tests. */
    fun songIds(): List<Long> = synchronized(lock) {
        ensureLoaded()
        songs.keys.toList()
    }

    /**
     * Applies one mutation and persists it. When the write fails the in-memory
     * state is restored from a copy, so a caller that sees false and a later
     * read of the store still agree on what is stored.
     */
    private fun commit(mutation: () -> Boolean): Boolean {
        val backup = LinkedHashMap<Long, SongEntry>()
        songs.forEach { (songId, entry) ->
            backup[songId] = SongEntry(LinkedHashMap(entry.lanes), entry.updatedAt)
        }
        if (!mutation()) return false
        if (write()) return true
        songs.clear()
        songs.putAll(backup)
        return false
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        songs.clear()
        val parsed = runCatching { read() }.getOrDefault(emptyList())
        parsed.forEach { (songId, entry) -> songs[songId] = entry }
        trim()
    }

    /** Reads the file into ordered entries; any structural problem yields an empty list. */
    private fun read(): List<Pair<Long, SongEntry>> {
        if (!file.isFile) return emptyList()
        val length = file.length()
        if (length <= 0L || length > MAX_FILE_BYTES) return emptyList()
        val root = JSONObject(file.readText(Charsets.UTF_8))
        if (root.optInt(KEY_VERSION, 0) != VERSION) return emptyList()
        val array = root.optJSONArray(KEY_SONGS) ?: return emptyList()

        val parsed = LinkedHashMap<Long, SongEntry>()
        for (index in 0 until array.length()) {
            val raw = array.optJSONObject(index) ?: continue
            val songId = parseSongId(raw.opt(KEY_SONG_ID)) ?: continue
            val lanesJson = raw.optJSONObject(KEY_LANES) ?: continue
            val lanes = LinkedHashMap<LyricLane, String>()
            val laneKeys = lanesJson.keys()
            while (laneKeys.hasNext()) {
                val laneKey = laneKeys.next()
                val lane = LyricLane.fromKey(laneKey) ?: continue
                val stored = LyricSourceKeys.sanitize(lanesJson.optString(laneKey, "")) ?: continue
                if (LyricSourceKeys.isHidden(stored) && !lane.isSupplement) continue
                lanes[lane] = stored
            }
            if (lanes.isEmpty()) continue
            parsed[songId] = SongEntry(lanes, raw.optLong(KEY_UPDATED_AT, 0L))
        }
        // Least recently written first, so both eviction and the on-disk order are stable.
        return parsed.entries
            .sortedBy { it.value.updatedAt }
            .map { it.key to it.value }
    }

    /** Keeps at most [capacity] songs, dropping the least recently written. */
    private fun trim() {
        val overflow = songs.size - capacity
        if (overflow <= 0) return
        val iterator = songs.keys.iterator()
        repeat(overflow) {
            if (!iterator.hasNext()) return
            iterator.next()
            iterator.remove()
        }
    }

    private fun write(): Boolean = runCatching {
        val root = JSONObject()
        root.put(KEY_VERSION, VERSION)
        root.put(KEY_REVISION, revision + 1L)
        val array = org.json.JSONArray()
        songs.forEach { (songId, entry) ->
            val lanes = JSONObject()
            entry.lanes.forEach { (lane, key) -> lanes.put(lane.key, key) }
            array.put(
                JSONObject()
                    .put(KEY_SONG_ID, songId)
                    .put(KEY_UPDATED_AT, entry.updatedAt)
                    .put(KEY_LANES, lanes),
            )
        }
        root.put(KEY_SONGS, array)
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size.toLong() > MAX_FILE_BYTES) return@runCatching false
        val directory = file.parentFile
        if (directory != null && !directory.exists() && !directory.mkdirs()) return@runCatching false
        atomicWrite(directory, bytes)
        revision += 1L
        true
    }.getOrDefault(false)

    /** Temp-file + rename, so a crash mid-write can never truncate the selection file. */
    private fun atomicWrite(directory: File?, bytes: ByteArray) {
        if (directory == null) {
            file.writeBytes(bytes)
            return
        }
        val pending = File.createTempFile(PENDING_PREFIX, PENDING_SUFFIX, directory)
        try {
            FileOutputStream(pending).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(
                    pending.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    pending.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            pending.delete()
        }
    }

    private fun parseSongId(value: Any?): Long? = when (value) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }?.takeIf { it > 0L }

    companion object {
        /** File name inside the module's storage directory. */
        const val FILE_NAME = "lyrics_source_selection.json"

        /** Format version written today; a different version is ignored on read. */
        const val VERSION = 1

        /** Upper bound of remembered songs. */
        const val MAX_SONGS = 500

        /** Upper bound of the file; a larger file is treated as corrupt. */
        const val MAX_FILE_BYTES = 256L * 1024L

        private const val PENDING_PREFIX = "pending_"
        private const val PENDING_SUFFIX = ".tmp"
        private const val KEY_VERSION = "version"
        private const val KEY_REVISION = "revision"
        private const val KEY_SONGS = "songs"
        private const val KEY_SONG_ID = "songId"
        private const val KEY_LANES = "lanes"
        private const val KEY_UPDATED_AT = "updatedAt"
    }
}
