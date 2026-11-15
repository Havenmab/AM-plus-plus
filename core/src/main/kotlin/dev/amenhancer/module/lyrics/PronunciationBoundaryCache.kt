package dev.amenhancer.module.lyrics

import dev.amenhancer.module.config.CustomLyricsFilePolicy
import dev.amenhancer.module.lyrics.online.PronunciationBoundary
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent cache for model-planned word boundaries, kept beside the translated-lyrics cache and
 * deliberately separate from it: an entry is a grouping, never a document, and it is never published
 * or listed anywhere.
 *
 * One file per song. An entry is reused only while the song, the sung kana, the rules version, the
 * normalized endpoint and the model still match; the API key is never written, so rotating a key
 * keeps the cache while switching provider or model drops it. All I/O is atomic and bounded,
 * unreadable entries are ignored rather than trusted, and a write adopts orphan files so the
 * directory cannot grow past [maxEntries] even when the index was lost.
 */
class FilePronunciationBoundaryCache(
    private val directory: File,
    private val maxEntries: Int = MAX_ENTRIES,
) {
    private val indexFile = File(directory, INDEX_FILE_NAME)
    private val lock = Any()
    private val capacity = maxEntries.coerceIn(1, MAX_ENTRIES)

    /** The cached grouping for this exact identity, or null. */
    fun read(
        appleMusicId: Long,
        kanaHash: String,
        tokenCounts: List<Int>,
        config: LlmLyricsConfig,
    ): List<PronunciationBoundary.BoundaryLine>? {
        val identity = identityOf(appleMusicId, kanaHash, config) ?: return null
        return synchronized(lock) {
            val file = entryFile(appleMusicId) ?: return@synchronized null
            val entry = readEntry(file) ?: return@synchronized null
            if (!entry.matches(identity) || entry.counts != tokenCounts) {
                runCatching { file.delete() }
                reorder(appleMusicId, dropped = true)
                return@synchronized null
            }
            if (entry.lines.isEmpty()) return@synchronized null
            reorder(appleMusicId, dropped = false)
            entry.lines
        }
    }

    /** Stores one song's grouping, evicting the oldest entry. */
    fun write(
        appleMusicId: Long,
        kanaHash: String,
        tokenCounts: List<Int>,
        config: LlmLyricsConfig,
        lines: List<PronunciationBoundary.BoundaryLine>,
    ): Boolean {
        val identity = identityOf(appleMusicId, kanaHash, config) ?: return false
        if (lines.isEmpty() || lines.size != tokenCounts.size) return false
        val bytes = runCatching { encodeEntry(identity, tokenCounts, lines) }.getOrNull() ?: return false
        if (bytes.size.toLong() > MAX_ENTRY_BYTES) return false
        return synchronized(lock) {
            runCatching {
                if (!directory.exists() && !directory.mkdirs()) return@runCatching false
                val file = entryFile(appleMusicId) ?: return@runCatching false
                val ordered = orderedIds().toMutableList()
                ordered.remove(appleMusicId)
                ordered.add(appleMusicId)
                val keep = ordered.takeLast(capacity)
                for (evicted in ordered.dropLast(keep.size)) {
                    val old = entryFile(evicted) ?: continue
                    if (old.exists() && !old.delete()) return@runCatching false
                }
                atomicWrite(file, bytes)
                writeIndex(keep)
                pruneUnexpected(keep.toSet())
                true
            }.getOrDefault(false)
        }
    }

    private fun identityOf(appleMusicId: Long, kanaHash: String, config: LlmLyricsConfig): Identity? {
        if (appleMusicId <= 0L || kanaHash.length != SHA256_LENGTH) return null
        val endpoint = config.endpoint ?: return null
        val model = config.normalizedModel
        if (model.isEmpty()) return null
        return Identity(
            endpoint = endpoint,
            model = model,
            rulesVersion = PronunciationBoundary.RULES_VERSION,
            kanaHash = kanaHash,
        )
    }

    private fun encodeEntry(
        identity: Identity,
        tokenCounts: List<Int>,
        lines: List<PronunciationBoundary.BoundaryLine>,
    ): ByteArray = JSONObject()
        .put("version", ENTRY_VERSION)
        .put("endpoint", identity.endpoint)
        .put("model", identity.model)
        .put("rules", identity.rulesVersion)
        .put("kana", identity.kanaHash)
        .put(
            "lines",
            JSONArray().apply {
                lines.forEachIndexed { index, line ->
                    put(
                        JSONObject()
                            .put("k", line.lineKey)
                            .put("n", tokenCounts.getOrElse(index) { 0 })
                            .put(
                                "g",
                                JSONArray().apply {
                                    line.groups.forEach { group ->
                                        put(JSONArray().apply { group.forEach { put(it) } })
                                    }
                                },
                            ),
                    )
                }
            },
        )
        .toString()
        .toByteArray(Charsets.UTF_8)

    private fun readEntry(file: File): Entry? = runCatching {
        if (!file.isFile || file.length() !in 1L..MAX_ENTRY_BYTES) return@runCatching null
        val raw = JSONObject(file.readText(Charsets.UTF_8))
        if (raw.optInt("version", 0) != ENTRY_VERSION) return@runCatching null
        val kanaHash = raw.optString("kana")
        if (kanaHash.length != SHA256_LENGTH) return@runCatching null
        val array = raw.optJSONArray("lines") ?: return@runCatching null
        val lines = ArrayList<PronunciationBoundary.BoundaryLine>(array.length())
        val counts = ArrayList<Int>(array.length())
        for (position in 0 until array.length()) {
            val line = array.optJSONObject(position) ?: return@runCatching null
            val key = line.optString("k")
            if (key.isEmpty()) return@runCatching null
            val groups = ArrayList<IntRange>(4)
            val rawGroups = line.optJSONArray("g") ?: return@runCatching null
            for (slot in 0 until rawGroups.length()) {
                val group = rawGroups.optJSONArray(slot) ?: return@runCatching null
                val indices = ArrayList<Int>(group.length())
                for (index in 0 until group.length()) indices += group.optInt(index, -1)
                if (indices.isEmpty() || indices.any { it < 0 }) return@runCatching null
                indices.sort()
                if (indices.last() - indices.first() + 1 != indices.size) return@runCatching null
                groups += indices.first()..indices.last()
            }
            if (groups.isEmpty()) return@runCatching null
            groups.sortBy { it.first }
            lines += PronunciationBoundary.BoundaryLine(key, groups)
            counts += line.optInt("n", -1)
        }
        Entry(
            endpoint = raw.optString("endpoint"),
            model = raw.optString("model"),
            rulesVersion = raw.optInt("rules", -1),
            kanaHash = kanaHash,
            lines = lines,
            counts = counts,
        )
    }.getOrNull()

    private fun entryFile(appleMusicId: Long): File? =
        if (appleMusicId <= 0L) null else File(directory, ENTRY_PREFIX + appleMusicId + ENTRY_SUFFIX)

    private fun entryFiles(): Map<Long, File> = runCatching {
        directory.listFiles().orEmpty()
            .mapNotNull { file ->
                val name = file.name
                if (!name.startsWith(ENTRY_PREFIX) || !name.endsWith(ENTRY_SUFFIX)) return@mapNotNull null
                val id = name.removePrefix(ENTRY_PREFIX).removeSuffix(ENTRY_SUFFIX).toLongOrNull()
                if (id == null || id <= 0L) null else id to file
            }
            .toMap()
    }.getOrDefault(emptyMap())

    private fun orderedIds(): List<Long> = runCatching {
        if (!indexFile.isFile || indexFile.length() !in 1L..MAX_INDEX_BYTES) return@runCatching emptyList()
        val array = JSONArray(indexFile.readText(Charsets.UTF_8))
        (0 until array.length()).mapNotNull { array.optLong(it, 0L).takeIf { id -> id > 0L } }
    }.getOrDefault(emptyList())

    private fun writeIndex(ids: List<Long>) {
        runCatching {
            if (!directory.exists()) directory.mkdirs()
            atomicWrite(indexFile, JSONArray().apply { ids.forEach { put(it) } }.toString().toByteArray())
        }
    }

    private fun reorder(appleMusicId: Long, dropped: Boolean) {
        runCatching {
            val ordered = orderedIds().toMutableList()
            ordered.remove(appleMusicId)
            if (!dropped) ordered.add(appleMusicId)
            writeIndex(ordered.takeLast(capacity))
        }
    }

    private fun pruneUnexpected(keep: Set<Long>) {
        runCatching {
            for ((id, file) in entryFiles()) {
                if (id !in keep) file.delete()
            }
        }
    }

    private fun atomicWrite(destination: File, bytes: ByteArray) {
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
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    pending.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            pending.delete()
        }
    }

    private data class Identity(
        val endpoint: String,
        val model: String,
        val rulesVersion: Int,
        val kanaHash: String,
    )

    private data class Entry(
        val endpoint: String,
        val model: String,
        val rulesVersion: Int,
        val kanaHash: String,
        val lines: List<PronunciationBoundary.BoundaryLine>,
        val counts: List<Int>,
    ) {
        fun matches(identity: Identity): Boolean =
            endpoint == identity.endpoint &&
                model == identity.model &&
                rulesVersion == identity.rulesVersion &&
                kanaHash.equals(identity.kanaHash, ignoreCase = true)
    }

    private companion object {
        const val ENTRY_PREFIX = "boundary_"
        const val ENTRY_SUFFIX = ".json"
        const val INDEX_FILE_NAME = "index.json"
        const val ENTRY_VERSION = 1
        const val MAX_ENTRIES = 200
        const val SHA256_LENGTH = 64
        const val MAX_INDEX_BYTES = 16 * 1024L
        const val MAX_ENTRY_BYTES = 256L * 1024L
        const val PENDING_PREFIX = "pending_"
        const val PENDING_SUFFIX = ".tmp"
    }
}
