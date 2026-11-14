package dev.amenhancer.module.lyrics.source

import org.json.JSONObject

/**
 * The self-hosted presence index: which bundled repositories carry which Apple Music ID.
 *
 * The server publishes one compact object, `{"<appleMusicId>": "<sources>"}`, where each
 * letter names a repository in preference order:
 *
 *  - `a` — amll-ttml-db (`am-lyrics/<id>.ttml`)
 *  - `n` — Lunabeat
 *  - `l` — Zennmn/am-lyrics
 *
 * It is ~48 KB of IDs (13 KB gzipped) and is refreshed by the server every few hours. The
 * point is not speed of a hit — that is already one request — but the **miss**: without this
 * file a song that no repository carries still costs a walk of every endpoint of every
 * source before the translation lane is even attempted.
 *
 * Only IDs are published, never lyric bodies.
 */
object LyricPresence {

    /** amll-ttml-db. */
    const val AMLL = "a"

    /** Lunabeat. */
    const val LUNABEAT = "n"

    /** Zennmn/am-lyrics. */
    const val AM_LYRICS = "l"

    /**
     * Parses the index, or null when [json] is not this shape.
     *
     * A null result means "no index" — callers then keep asking every repository. An index
     * that parses but does not name an ID answers "nothing carries it" through [indexOf].
     */
    fun parse(json: String): Map<Long, Set<String>>? = runCatching {
        val root = JSONObject(json)
        if (root.length() == 0) return@runCatching null
        val out = HashMap<Long, Set<String>>(root.length())
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val id = key.toLongOrNull() ?: continue
            val value = root.optString(key, "")
            if (value.isEmpty()) continue
            out[id] = value.map { ch -> ch.toString() }.toSet()
        }
        out.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /**
     * An in-memory [LyricPresenceIndex] over [parse]'s output.
     *
     * An ID the index does not name answers **empty**, not null: the index itself is the
     * answer, and "not listed" means "no repository carries it".
     */
    fun indexOf(entries: Map<Long, Set<String>>): LyricPresenceIndex =
        LyricPresenceIndex { appleMusicId -> entries[appleMusicId] ?: emptySet() }
}
