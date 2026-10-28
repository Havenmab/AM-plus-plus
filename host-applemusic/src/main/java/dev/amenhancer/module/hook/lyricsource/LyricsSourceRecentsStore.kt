package dev.amenhancer.module.hook.lyricsource

import dev.amenhancer.module.lyrics.online.LyricRecentSource
import dev.amenhancer.module.lyrics.online.LyricSourceRecents
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the sources the user actually applied, so the next search can put them first.
 *
 * The file is tiny (at most [LyricSourceRecents.MAX_ENTRIES] rows) and every read is
 * best-effort: a corrupt file just means "no history", never a failed search.
 */
internal class LyricsSourceRecentsStore(
    private val file: File,
    private val logger: (String) -> Unit,
) {

    private val lock = Any()
    private var cache: List<LyricRecentSource>? = null

    fun all(): List<LyricRecentSource> = synchronized(lock) {
        cache ?: read().also { cache = it }
    }

    fun record(entry: LyricRecentSource) {
        synchronized(lock) {
            val next = LyricSourceRecents.record(all(), entry)
            cache = next
            write(next)
        }
    }

    fun providers(limit: Int = LyricSourceRecents.QUICK_PICK_LIMIT): List<String> =
        LyricSourceRecents.quickPickProviders(all(), limit)

    private fun read(): List<LyricRecentSource> = runCatching {
        if (!file.isFile) return@runCatching emptyList<LyricRecentSource>()
        val root = JSONObject(file.readText(Charsets.UTF_8))
        val entries = root.optJSONArray(KEY_ENTRIES) ?: return@runCatching emptyList<LyricRecentSource>()
        buildList {
            for (index in 0 until entries.length()) {
                val row = entries.optJSONObject(index) ?: continue
                val providerId = row.optString("providerId").takeIf(String::isNotBlank) ?: continue
                val remoteId = row.optString("remoteId").takeIf(String::isNotBlank) ?: continue
                add(
                    LyricRecentSource(
                        providerId = providerId,
                        label = row.optString("label"),
                        title = row.optString("title"),
                        artist = row.optString("artist"),
                        album = row.optString("album"),
                        remoteId = remoteId,
                        usedAtMs = row.optLong("usedAtMs", 0L),
                    ),
                )
            }
        }
    }.onFailure { error -> logger("lyrics-source recents read failed: " + error.message) }
        .getOrDefault(emptyList())

    private fun write(entries: List<LyricRecentSource>) {
        runCatching {
            file.parentFile?.mkdirs()
            val rows = JSONArray()
            entries.forEach { entry ->
                rows.put(
                    JSONObject()
                        .put("providerId", entry.providerId)
                        .put("label", entry.label)
                        .put("title", entry.title)
                        .put("artist", entry.artist)
                        .put("album", entry.album)
                        .put("remoteId", entry.remoteId)
                        .put("usedAtMs", entry.usedAtMs),
                )
            }
            file.writeText(JSONObject().put(KEY_ENTRIES, rows).toString(), Charsets.UTF_8)
        }.onFailure { error -> logger("lyrics-source recents write failed: " + error.message) }
    }

    private companion object {
        const val KEY_ENTRIES = "entries"
    }
}