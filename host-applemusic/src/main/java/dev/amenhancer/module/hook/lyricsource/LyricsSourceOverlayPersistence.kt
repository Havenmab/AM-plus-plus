package dev.amenhancer.module.hook.lyricsource

import dev.amenhancer.module.lyrics.online.AppleLyricTtmlReader
import dev.amenhancer.module.lyrics.online.OnlineTranslationExtraction
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
 * [OnlineTranslationExtraction.classifyLanes] on the way in and on the way out: Chinese goes back to the
 * translation lane, kana/romaji back to the romanization lane, and the row keys and
 * pronunciation spans are untouched, so old records are repaired without a
 * migration.
 *
 * This file is also the **single source of truth for which provider supplies each
 * lane**: the record carries `translationSource` / `pronunciationSource` /
 * `originalSource` beside the two lanes, and every publish path — the automatic
 * completion through [dev.amenhancer.module.hook.LyricsSourceOverlayCacheBridge],
 * a manual pick in the sheet, the displayed custom document — writes the same
 * record. [sources] is what the lyrics-source probes read, so the sheet names the
 * provider that really supplied the text: after an automatic completion (the
 * in-memory store may already have been replaced by a later refresh) and after a
 * restart (the store is empty), not only while this process happens to still hold
 * the lane.
 *
 * A null provider in a later save means "this caller does not know it", never
 * "forget it" — the same rule the overlay store applies to its own records — so a
 * re-save of the same merged body, or a displayed-document publish that knows only
 * the document's origin, cannot erase the provider a completion recorded.
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

        /** True when the record still names at least one lane's provider or lane tags. */
        val hasSources: Boolean
            get() = !translationSource.isNullOrBlank() ||
                !pronunciationSource.isNullOrBlank() ||
                !originalSource.isNullOrBlank() ||
                pronunciationLanguages.isNotEmpty()

        /** The record's lane providers, without the lane bodies. */
        fun sources(): LaneSources = LaneSources(
            translationSource = translationSource,
            pronunciationSource = pronunciationSource,
            originalSource = originalSource,
            pronunciationLanguages = pronunciationLanguages,
            hasTranslations = translations.isNotEmpty(),
            hasRomanizations = romanizations.isNotEmpty(),
        )
    }

    /**
     * Which provider supplies each of a song's three lanes, plus whether the lane
     * bodies are on disk at all.
     *
     * This is the projection the lyrics-source probes read: the sheet needs the
     * provider names and the lane presence, never the lines themselves, so the
     * answer stays cheap (an index built once per file read) and is available
     * before — and without — replaying anything into the in-memory store.
     */
    internal data class LaneSources(
        val translationSource: String? = null,
        val pronunciationSource: String? = null,
        val originalSource: String? = null,
        val pronunciationLanguages: List<String> = emptyList(),
        val hasTranslations: Boolean = false,
        val hasRomanizations: Boolean = false,
    )

    private val lock = Any()

    /** Per-song lane providers, built from the file once and kept in step with every write. */
    private var sourcesIndex: Map<Long, LaneSources>? = null

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
        val lines = runCatching { AppleLyricTtmlReader.read(mergedTtml) }.getOrNull().orEmpty()
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
        val existing = load(songId)
        // A lane the incoming body does not carry is *not* a request to forget it: the
        // automatic completion publishes a merged body that always carries both, while the
        // displayed-document publish (the same song, the raw custom document) carries only
        // what that document has. Dropping the richer lane there is what erased a completed
        // translation — and its provider — every time the page re-published its document.
        // Hiding a lane is explicit: [dropLane].
        val record = Record(
            songId = songId,
            translationSource = translationSource ?: existing?.translationSource,
            pronunciationSource = pronunciationSource ?: existing?.pronunciationSource,
            originalSource = originalSource ?: existing?.originalSource,
            // The lane tags name the pronunciation lane's language; a body without a
            // `<transliteration xml:lang>` tag must not erase the tags the app's
            // 显示发音 switch keys the published lane by.
            pronunciationLanguages = pronunciationLanguages.distinct()
                .ifEmpty { existing?.pronunciationLanguages.orEmpty() },
            translations = translations.ifEmpty { existing?.translations.orEmpty() },
            romanizations = romanizations.ifEmpty { existing?.romanizations.orEmpty() },
            updatedAt = clock(),
        )
        return save(record)
    }

    /**
     * Stores the document the page is showing (the user's custom lyrics) with its
     * own origin as the fallback provider of the lanes it carries.
     *
     * The difference from [saveMergedDocument] is the attribution rule: a lane the
     * record already names a provider for keeps that name. The displayed document's
     * origin is a fact about the **file**, while a completion records which provider
     * supplied the Chinese — and that completion may have merged a QQ translation
     * into a document that came from AMLL. Overwriting the lane provider with the
     * file's origin would make the sheet credit the wrong platform every time the
     * page republished its document, so the origin only fills in what is unknown.
     */
    fun saveDisplayedDocument(
        songId: Long,
        ttml: String,
        source: String?,
        pronunciationLanguages: List<String>,
    ): Boolean {
        if (songId <= 0L || ttml.isBlank()) return false
        val existing = load(songId)
        return saveMergedDocument(
            songId = songId,
            mergedTtml = ttml,
            translationSource = existing?.translationSource ?: source,
            pronunciationSource = existing?.pronunciationSource ?: source,
            originalSource = existing?.originalSource ?: source,
            pronunciationLanguages = pronunciationLanguages,
        )
    }

    /**
     * Saves (or replaces) the memory for the record's song.
     *
     * A record that carries a provider (or the lane tags) but no lane body is kept
     * on purpose: that is the state left by a body whose lanes the classifier
     * refused, and the sheet still has to be able to name the provider instead of
     * falling back to 「不显示」.
     */
    fun save(record: Record): Boolean = synchronized(lock) {
        val corrected = correctLanes(record)
        if (corrected.songId <= 0L) return false
        if (corrected.isEmptyLanes && !corrected.hasSources) return false
        runCatching {
            val entries = read().filterValues { it.songId != corrected.songId }.toMutableMap()
            entries[corrected.songId] = corrected
            write(entries.values.sortedByDescending { it.updatedAt }.take(MAX_SONGS))
            true
        }.getOrDefault(false)
    }

    /**
     * The lane providers stored for [songId], or null when this song has no record.
     *
     * Reads the index built from the file (see [LaneSources]) instead of the lanes,
     * so the sheet can ask on the UI thread; the first call parses the file once and
     * every later write keeps it in step.
     */
    fun sources(songId: Long): LaneSources? = synchronized(lock) {
        if (songId <= 0L) null else sourcesIndexLocked()[songId]
    }

    private fun sourcesIndexLocked(): Map<Long, LaneSources> {
        sourcesIndex?.let { return it }
        val built = read().mapValues { (_, record) -> record.sources() }
        sourcesIndex = built
        return built
    }

    /** The stored memory for [songId], or null. */
    fun load(songId: Long): Record? = synchronized(lock) {
        runCatching { read()[songId] }.getOrNull()
    }

    /**
     * Replays the stored lanes into [overlay], **per lane**.
     *
     * The old rule skipped the whole replay as soon as the store held *either*
     * lane. After a restart that is the normal case rather than the exception: the
     * custom/supplement path publishes the displayed document's own romanization
     * (and the native write publishes Apple's) before the sheet ever probes, so the
     * store had a pronunciation lane, the translation lane was refused, and the
     * page came back from a restart with 显示翻译 grey while the disk copy still
     * held the translation. Each lane is now restored only when the store is
     * missing **that** lane, through [NativeLyricOverlayStore.updateLane] so the
     * lane already present is never dropped on the way in.
     *
     * Returns true when at least one lane was replayed.
     */
    fun restoreInto(songId: Long, overlay: NativeLyricOverlayStore): Boolean {
        if (songId <= 0L) return false
        val key = songId.toString()
        val record = load(songId) ?: return false
        val wantsTranslation = record.translations.isNotEmpty() && !overlay.hasTranslation(key)
        val wantsPronunciation =
            record.romanizations.isNotEmpty() && !overlay.hasPronunciation(key)
        if (!wantsTranslation && !wantsPronunciation) return false
        var restored = false
        if (wantsTranslation) {
            val lines = mergeLanes(record, translation = true, romanization = false)
            if (lines.isNotEmpty()) {
                restored = runCatching {
                    overlay.updateLane(
                        songId = key,
                        lane = NativeLyricOverlayStore.Lane.TRANSLATION,
                        lines = lines,
                        source = record.translationSource,
                        originalSource = record.originalSource,
                    )
                }.getOrDefault(false) || restored
            }
        }
        if (wantsPronunciation) {
            val lines = mergeLanes(record, translation = false, romanization = true)
            if (lines.isNotEmpty()) {
                restored = runCatching {
                    overlay.updateLane(
                        songId = key,
                        lane = NativeLyricOverlayStore.Lane.PRONUNCIATION,
                        lines = lines,
                        source = record.pronunciationSource,
                        pronunciationLanguages = record.pronunciationLanguages,
                        originalSource = record.originalSource,
                    )
                }.getOrDefault(false) || restored
            }
        }
        return restored
    }

    /**
     * Forgets one lane of the song's memory, so a lane the user hid (or restored to
     * Apple) is not replayed on the next track change. The other lane's record — and
     * therefore the restart memory of the lane still showing — is kept; when both
     * lanes are gone the record is dropped entirely rather than stored as an empty
     * shell.
     */
    fun dropLane(songId: Long, lane: NativeLyricOverlayStore.Lane): Boolean = synchronized(lock) {
        if (songId <= 0L) return false
        runCatching {
            val entries = read()
            val existing = entries[songId] ?: return@runCatching false
            val next = when (lane) {
                NativeLyricOverlayStore.Lane.TRANSLATION -> existing.copy(
                    translations = emptyList(),
                    translationSource = null,
                )

                NativeLyricOverlayStore.Lane.PRONUNCIATION -> existing.copy(
                    romanizations = emptyList(),
                    pronunciationSource = null,
                    pronunciationLanguages = emptyList(),
                )
            }
            val remaining = entries.filterValues { it.songId != songId }.toMutableMap()
            // A record whose other lane still has a body — or still names a provider — is
            // kept: dropping it would take the surviving lane's provider from the sheet.
            if (!next.isEmptyLanes || next.hasSources) remaining[songId] = next.copy(updatedAt = clock())
            write(remaining.values.sortedByDescending { it.updatedAt }.take(MAX_SONGS))
            true
        }.getOrDefault(false)
    }

    /**
     * Joins the persisted lanes back into the store's per-line model, keeping only
     * the lanes the caller asks for: a lane the store already holds is never
     * re-supplied, because [NativeLyricOverlayStore.updateLane] would otherwise
     * overwrite it with the stale copy on disk.
     */
    private fun mergeLanes(
        record: Record,
        translation: Boolean = true,
        romanization: Boolean = true,
    ): List<NativeLyricLine> {
        val translations: Map<Triple<Long, Long, String>, TranslationLine> = if (translation) {
            record.translations.associateBy { Triple(it.begin, it.end, it.text) }
        } else {
            emptyMap()
        }
        val romanizations: Map<Triple<Long, Long, String>, RomanizationLine> = if (romanization) {
            record.romanizations.associateBy { Triple(it.begin, it.end, it.text) }
        } else {
            emptyMap()
        }
        val keys = LinkedHashSet<Triple<Long, Long, String>>()
        keys += translations.keys
        keys += romanizations.keys
        return keys.mapNotNull { key ->
            val translationLine = translations[key]
            val romanizationLine = romanizations[key]
            val begin = translationLine?.begin ?: romanizationLine?.begin ?: return@mapNotNull null
            val end = translationLine?.end ?: romanizationLine?.end ?: return@mapNotNull null
            val text = translationLine?.text ?: romanizationLine?.text ?: return@mapNotNull null
            NativeLyricLine(
                begin = begin,
                end = end,
                text = text,
                // The lanes stay in their own fields: a translation never becomes a
                // romanization and vice versa.
                translation = translationLine?.translation,
                roma = romanizationLine?.romanization,
                romaSpans = romanizationLine?.spans.orEmpty(),
            )
        }
    }

    /**
     * 把一条记录的两条车道按 [OnlineTranslationExtraction.classifyLanes] 重新归位。
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
            LaneRow(
                begin = key.begin,
                end = key.end,
                text = key.text,
                translation = translationsByKey[key]?.translation,
                romanization = romanizationsByKey[key]?.romanization,
            )
        }
        val corrected = rows.map { row ->
            val lanes = OnlineTranslationExtraction.classifyLanesOrDegrade(
                translation = row.translation,
                romanization = row.romanization,
                original = row.text,
            )
            row.copy(translation = lanes.translation, romanization = lanes.romanization)
        }
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

    /** One line's two lanes, as the restart replay re-homes them. */
    private data class LaneRow(
        val begin: Long,
        val end: Long,
        val text: String,
        val translation: String?,
        val romanization: String?,
    )

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
        if (!pending.renameTo(file)) return
        // The file is the truth: the lane-provider index is rebuilt from what was just
        // written, so a save/drop is visible to [sources] without another parse.
        sourcesIndex = records.associate { record -> record.songId to record.sources() }
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
