package dev.amenhancer.module.hook.lyricsource

import dev.amenhancer.module.lyrics.online.NativeLyricOverlayStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * On-disk memory for the native lyric overlay.
 *
 * The overlay store itself is process memory: it holds the per-line translation /
 * romanization lanes the native lyric model reads back, plus the providers those
 * lanes came from. That is enough for the current session, but after a restart the
 * lanes are gone while the user's stored selection key ("online:qm:...") stays -
 * the sheet then shows a source whose content no longer exists, and Apple renders
 * nothing.
 *
 * So the fact that matters is persisted here: the *merged document* that produced
 * the lanes (which is exactly what [dev.amenhancer.module.lyrics.online.DisplayedLyricPublication]
 * turns back into lanes), the lane sources and the original document's provider.
 * [restoreInto] replays it through the public store API, so the overlay is rebuilt
 * with the same content the user had applied.
 *
 * Bounds: one entry per song, [MAX_SONGS] songs, [MAX_TTML_BYTES] per document,
 * atomic replace (temp file + rename) and a corrupt file reads as "no memory".
 * Everything here runs on the caller's background executor.
 */
internal class LyricsSourceOverlayPersistence(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** One song's overlay memory. */
    internal data class Record(
        val songId: Long,
        val ttml: String,
        val translationSource: String?,
        val originalSource: String?,
        val pronunciationLanguages: List<String>,
        val updatedAt: Long,
    )

    private val lock = Any()

    /** Saves (or replaces) the memory for [songId]. False when the document is too large. */
    fun save(record: Record): Boolean = synchronized(lock) {
        if (record.ttml.isBlank()) return false
        if (record.ttml.toByteArray(Charsets.UTF_8).size > MAX_TTML_BYTES) return false
        runCatching {
            val entries = read().filterValues { it.songId != record.songId }.toMutableMap()
            entries[record.songId] = record.copy(updatedAt = clock())
            write(entries.values.sortedByDescending { it.updatedAt }.take(MAX_SONGS))
            true
        }.getOrDefault(false)
    }

    /** The stored memory for [songId], or null. */
    fun load(songId: Long): Record? = synchronized(lock) {
        runCatching { read()[songId] }.getOrNull()
    }

    /**
     * Replays the stored memory for [songId] into [overlay] when the store no longer
     * holds those lanes. Returns true when lanes were restored.
     */
    fun restoreInto(songId: Long, overlay: NativeLyricOverlayStore): Boolean {
        if (songId <= 0L) return false
        val key = songId.toString()
        val record = load(songId) ?: return false
        if (overlay.hasTranslation(key) || overlay.hasPronunciation(key)) return false
        val lines = runCatching {
            dev.amenhancer.module.lyrics.online.DisplayedLyricPublication.lines(record.ttml)
        }.getOrNull() ?: return false
        if (lines.isEmpty()) return false
        return runCatching {
            overlay.update(
                songId = key,
                lines = lines,
                translationSource = record.translationSource,
                // The document's own provider is carried over; the store keeps it even
                // when this call passes null (core preserves a recorded source).
                originalSource = record.originalSource,
                pronunciationLanguages = record.pronunciationLanguages,
            )
        }.getOrDefault(false)
    }

    // -------------------------------------------------------------------- json

    private fun read(): Map<Long, Record> = runCatching {
        if (!file.isFile || file.length() !in 1..MAX_FILE_BYTES) return emptyMap()
        val root = JSONObject(file.readText(Charsets.UTF_8))
        if (root.optInt("version", 0) != VERSION) return emptyMap()
        val songs = root.optJSONArray("songs") ?: return emptyMap()
        buildMap {
            for (index in 0 until songs.length()) {
                val entry = songs.optJSONObject(index) ?: continue
                val songId = entry.optLong("songId", 0L).takeIf { it > 0L } ?: continue
                val ttml = entry.optString("ttml").takeIf { it.isNotBlank() } ?: continue
                put(
                    songId,
                    Record(
                        songId = songId,
                        ttml = ttml,
                        translationSource = entry.optString("translationSource").takeIf { it.isNotBlank() },
                        originalSource = entry.optString("originalSource").takeIf { it.isNotBlank() },
                        pronunciationLanguages = entry.optJSONArray("pronunciationLanguages")
                            ?.let { array ->
                                (0 until array.length()).mapNotNull { i ->
                                    array.optString(i).takeIf { it.isNotBlank() }
                                }
                            }
                            .orEmpty(),
                        updatedAt = entry.optLong("updatedAt", 0L),
                    ),
                )
            }
        }
    }.getOrDefault(emptyMap())

    private fun write(records: Collection<Record>) {
        val songs = JSONArray()
        records.forEach { record ->
            songs.put(
                JSONObject()
                    .put("songId", record.songId)
                    .put("ttml", record.ttml)
                    .put("translationSource", record.translationSource.orEmpty())
                    .put("originalSource", record.originalSource.orEmpty())
                    .put("pronunciationLanguages", JSONArray().apply {
                        record.pronunciationLanguages.forEach { put(it) }
                    })
                    .put("updatedAt", record.updatedAt),
            )
        }
        val body = JSONObject().put("version", VERSION).put("songs", songs).toString()
        if (body.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES) return
        file.parentFile?.let { parent -> if (!parent.exists()) parent.mkdirs() }
        val pending = File(file.parentFile, file.name + ".tmp")
        pending.writeText(body, Charsets.UTF_8)
        if (file.exists()) file.delete()
        pending.renameTo(file)
    }

    private companion object {
        const val VERSION = 1
        const val MAX_SONGS = 64
        const val MAX_TTML_BYTES = 512 * 1024
        const val MAX_FILE_BYTES = 4L * 1024L * 1024L
    }
}
