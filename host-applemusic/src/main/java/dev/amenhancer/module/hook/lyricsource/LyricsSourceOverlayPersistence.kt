package dev.amenhancer.module.hook.lyricsource

import dev.amenhancer.module.lyrics.online.AppleLyricTtmlReader
import dev.amenhancer.module.lyrics.online.LyricLaneClassifier
import dev.amenhancer.module.lyrics.online.NativeLyricLine
import dev.amenhancer.module.lyrics.online.NativeLyricOverlayStore
import dev.amenhancer.module.lyrics.online.NativePronunciationSpan
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * On-disk memory for the native lyric overlay.
 *
 * The overlay store is process memory: it holds the per-line **translation** and
 * **romanization** lanes the native lyric model reads back, plus the provider each
 * lane came from. After a restart the lanes are gone while the user's stored
 * selection key ("online:qm:...") stays, so the sheet names a source whose content
 * no longer exists and the page renders nothing.
 *
 * The two lanes are persisted **separately and explicitly** - one array of lines per
 * lane, joined on (begin, end, text) when the overlay is rebuilt. Deriving both lanes
 * from one stored blob is what let a translation come back as a romanization after a
 * restart: the lanes are independent and must never stand in for each other.
 *
 * Keeping them apart is not enough on its own, because a stored lane can hold the
 * wrong kind of text: an older build wrote QQ Music's `trans` - the kana/romaji
 * reading of a Japanese song - straight into the translation lane, so the restart
 * replayed a romanization as the translation. Every record therefore passes through
 * [LyricLaneClassifier] on the way in and on the way out: Chinese goes back to the
 * translation lane, kana/romaji back to the romanization lane, and the row keys and
 * pronunciation spans are untouched, so old records are repaired without a
 * migration.
 *
 * Bounds: one record per song, [MAX_SONGS] songs, [MAX_LINES] per lane,
 * [MAX_FILE_BYTES] for the file, atomic replace (temp file + rename); a corrupt file
 * reads as "no memory". Runs on the caller's background executor.
 */
internal class LyricsSourceOverlayPersistence(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** One line of the translation lane. */
    internal data class TranslationLine(val begin: Long, val end: Long, val text: String, val translation: String)

    /** One line of the romanization lane, with its per-word slices. */
    internal data class RomanizationLine(
        val begin: Long,
        val end: Long,
        val text: String,
        val romanization: String,
        val spans: List<NativePronunciationSpan> = emptyList(),
    )

    /** One song's overlay memory: the two lanes, kept apart. */
    internal data class Record(
        val songId: Long,
        val translationSource: String?,
        val pronunciationSource: String?,
        val originalSource: String?,
        val pronunciationLanguages: List<String>,
        val translations: List<TranslationLine> = emptyList(),
        val romanizations: List<RomanizationLine> = emptyList(),
        val updatedAt: Long = 0L,
    ) {
        val isEmptyLanes: Boolean get() = translations.isEmpty() && romanizations.isEmpty()
    }

    private val lock = Any()

    /**
     * Splits a merged document into the two lanes and stores them.
     *
     * [mergedTtml] is what the enricher produced (Apple's body plus the injected
     * translation); reading it once yields both lanes per line, which is the only way
     * to keep them independent on disk.
     */
    fun saveMergedDocument(
        songId: Long,
        mergedTtml: String,
        translationSource: String?,
        pronunciationSource: String?,
        originalSource: String?,
        pronunciationLanguages: List<String>,
    ): Boolean {
        if (songId <= 0L || mergedTtml.isBlank()) return false
        val lines = runCatching { AppleLyricTtmlReader.read(mergedTtml) }.getOrNull() ?: return false
        val translations = lines.mapNotNull { line ->
            val translation = line.translation?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            TranslationLine(line.begin, line.end, line.text, translation)
        }.take(MAX_LINES)
        val romanizations = lines.mapNotNull { line ->
            val roma = line.romanization?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            RomanizationLine(
                begin = line.begin,
                end = line.end,
                text = line.text,
                romanization = roma,
                spans = line.romanizationSpans.map { span ->
                    NativePronunciationSpan(begin = span.begin, end = span.end, text = span.text)
                }.take(MAX_SPANS_PER_LINE),
            )
        }.take(MAX_LINES)
        if (translations.isEmpty() && romanizations.isEmpty()) return false
        return save(
            Record(
                songId = songId,
                translationSource = translationSource,
                pronunciationSource = pronunciationSource,
                originalSource = originalSource,
                pronunciationLanguages = pronunciationLanguages,
                translations = translations,
                romanizations = romanizations,
                updatedAt = clock(),
            ),
        )
    }

    /** Saves (or replaces) the memory for the record's song. */
    fun save(record: Record): Boolean = synchronized(lock) {
        val corrected = correctLanes(record)
        if (corrected.songId <= 0L || corrected.isEmptyLanes) return false
        runCatching {
            val entries = read().filterValues { it.songId != corrected.songId }.toMutableMap()
            entries[corrected.songId] = corrected
            write(entries.values.sortedByDescending { it.updatedAt }.take(MAX_SONGS))
            true
        }.getOrDefault(false)
    }

    /** The stored memory for [songId], or null. */
    fun load(songId: Long): Record? = synchronized(lock) {
        runCatching { read()[songId] }.getOrNull()
    }

    /**
     * Replays the stored lanes into [overlay] when the store no longer holds them.
     *
     * Both lanes go into **one** [NativeLyricOverlayStore.update] call: that call
     * replaces the whole overlay, so issuing it twice would drop the first lane.
     */
    fun restoreInto(songId: Long, overlay: NativeLyricOverlayStore): Boolean {
        if (songId <= 0L) return false
        val key = songId.toString()
        val record = load(songId) ?: return false
        if (overlay.hasTranslation(key) || overlay.hasPronunciation(key)) return false
        val lines = mergeLanes(record)
        if (lines.isEmpty()) return false
        return runCatching {
            overlay.update(
                songId = key,
                lines = lines,
                translationSource = record.translationSource.takeIf { record.translations.isNotEmpty() },
                pronunciationSource = record.pronunciationSource.takeIf { record.romanizations.isNotEmpty() },
                originalSource = record.originalSource,
                pronunciationLanguages = record.pronunciationLanguages,
            )
        }.getOrDefault(false)
    }

    /** Joins the two persisted lanes back into the store's per-line model. */
    private fun mergeLanes(record: Record): List<NativeLyricLine> {
        val translations = record.translations.associateBy { Triple(it.begin, it.end, it.text) }
        val romanizations = record.romanizations.associateBy { Triple(it.begin, it.end, it.text) }
        val keys = LinkedHashSet<Triple<Long, Long, String>>()
        keys += translations.keys
        keys += romanizations.keys
        return keys.mapNotNull { key ->
            val translation = translations[key]
            val romanization = romanizations[key]
            val begin = translation?.begin ?: romanization?.begin ?: return@mapNotNull null
            val end = translation?.end ?: romanization?.end ?: return@mapNotNull null
            val text = translation?.text ?: romanization?.text ?: return@mapNotNull null
            NativeLyricLine(
                begin = begin,
                end = end,
                text = text,
                // The lanes stay in their own fields: a translation never becomes a
                // romanization and vice versa.
                translation = translation?.translation,
                roma = romanization?.romanization,
                romaSpans = romanization?.spans.orEmpty(),
            )
        }
    }

    /**
     * 把一条记录的两条车道按 [LyricLaneClassifier] 重新归位。
     *
     * 盘上的车道也可能本身就是错位的：旧版本把 QQ 日语歌的 `trans`（假名／罗马音）
     * 原样写进了 translation 车道，「补全歌词翻译与发音」随后把它当译文发布，重启
     * 恢复后就是「翻译变成音译」。读盘和写盘各过一次这里，中文回 translation、假名
     * 与罗马音回 romanization；行键 (begin, end, text) 和发音切片都不动，所以旧
     * 记录不需要迁移就能救回来。
     */
    private fun correctLanes(record: Record): Record {
        if (record.isEmptyLanes) return record
        val translationsByKey = record.translations.associateBy { line ->
            RowKey(line.begin, line.end, line.text)
        }
        val romanizationsByKey = record.romanizations.associateBy { line ->
            RowKey(line.begin, line.end, line.text)
        }
        val keys = LinkedHashSet<RowKey>()
        keys += translationsByKey.keys
        keys += romanizationsByKey.keys
        val rows = keys.map { key ->
            LyricLaneClassifier.LaneRow(
                begin = key.begin,
                end = key.end,
                text = key.text,
                translation = translationsByKey[key]?.translation,
                romanization = romanizationsByKey[key]?.romanization,
            )
        }
        val corrected = LyricLaneClassifier.rebalanceRows(rows)
        val translations = corrected.mapNotNull { row ->
            val text = row.translation?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            TranslationLine(begin = row.begin, end = row.end, text = row.text, translation = text)
        }
        val romanizations = corrected.mapNotNull { row ->
            val text = row.romanization?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            RomanizationLine(
                begin = row.begin,
                end = row.end,
                text = row.text,
                romanization = text,
                spans = romanizationsByKey[RowKey(row.begin, row.end, row.text)]?.spans.orEmpty(),
            )
        }
        return record.copy(translations = translations, romanizations = romanizations)
    }

    /** The (begin, end, text) triple both lanes are joined on. */
    private data class RowKey(val begin: Long, val end: Long, val text: String)

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
                val record = Record(
                    songId = songId,
                    translationSource = entry.optString("translationSource").takeIf { it.isNotBlank() },
                    pronunciationSource = entry.optString("pronunciationSource").takeIf { it.isNotBlank() },
                    originalSource = entry.optString("originalSource").takeIf { it.isNotBlank() },
                    pronunciationLanguages = strings(entry.optJSONArray("pronunciationLanguages")),
                    translations = lines(entry.optJSONArray("translations")) { json ->
                        TranslationLine(
                            begin = json.optLong("begin", 0L),
                            end = json.optLong("end", 0L),
                            text = json.optString("text"),
                            translation = json.optString("translation"),
                        )
                    },
                    romanizations = lines(entry.optJSONArray("romanizations")) { json ->
                        RomanizationLine(
                            begin = json.optLong("begin", 0L),
                            end = json.optLong("end", 0L),
                            text = json.optString("text"),
                            romanization = json.optString("romanization"),
                            spans = lines(json.optJSONArray("spans")) { span ->
                                NativePronunciationSpan(
                                    begin = span.optLong("begin", 0L),
                                    end = span.optLong("end", 0L),
                                    text = span.optString("text"),
                                )
                            },
                        )
                    },
                    updatedAt = entry.optLong("updatedAt", 0L),
                )
                // Old builds wrote a QQ Japanese song's reading (kana/romaji) into
                // the translation lane; re-home both lanes on the way in so a
                // restart cannot present a romanization as the translation.
                put(songId, correctLanes(record))
            }
        }
    }.getOrDefault(emptyMap())

    private fun write(records: Collection<Record>) {
        val songs = JSONArray()
        records.forEach { record ->
            songs.put(
                JSONObject()
                    .put("songId", record.songId)
                    .put("translationSource", record.translationSource.orEmpty())
                    .put("pronunciationSource", record.pronunciationSource.orEmpty())
                    .put("originalSource", record.originalSource.orEmpty())
                    .put("pronunciationLanguages", JSONArray().apply {
                        record.pronunciationLanguages.forEach { put(it) }
                    })
                    .put("translations", JSONArray().apply {
                        record.translations.forEach { line ->
                            put(
                                JSONObject()
                                    .put("begin", line.begin)
                                    .put("end", line.end)
                                    .put("text", line.text)
                                    .put("translation", line.translation),
                            )
                        }
                    })
                    .put("romanizations", JSONArray().apply {
                        record.romanizations.forEach { line ->
                            put(
                                JSONObject()
                                    .put("begin", line.begin)
                                    .put("end", line.end)
                                    .put("text", line.text)
                                    .put("romanization", line.romanization)
                                    .put("spans", JSONArray().apply {
                                        line.spans.forEach { span ->
                                            put(
                                                JSONObject()
                                                    .put("begin", span.begin)
                                                    .put("end", span.end)
                                                    .put("text", span.text),
                                            )
                                        }
                                    }),
                            )
                        }
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

    private fun strings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optString(index).takeIf { it.isNotBlank() }
        }
    }

    private fun <T> lines(array: JSONArray?, map: (JSONObject) -> T): List<T> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let(map)
        }
    }

    private companion object {
        const val VERSION = 2
        const val MAX_SONGS = 64
        const val MAX_LINES = 4096
        const val MAX_SPANS_PER_LINE = 512
        const val MAX_FILE_BYTES = 4L * 1024L * 1024L
    }
}
