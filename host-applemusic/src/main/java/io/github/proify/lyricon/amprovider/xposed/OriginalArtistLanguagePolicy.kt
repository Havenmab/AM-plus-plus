/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import java.util.concurrent.ConcurrentHashMap

/**
 * Pure decisions for the original-artist language lookup and its retry policy.
 *
 * The artist entity can only be requested once a language is known, and the language is only
 * learned from an earlier resolution — a bootstrap gap. These helpers keep the two halves testable
 * without Android or the reflection-heavy resolver:
 *
 *  - [OriginalArtistLanguagePolicy.pick] orders the three language sources the coordinator has;
 *  - [OriginalLanguageHintStore] keeps the language of a resolved original **song** alias per media
 *    id, which is the source that breaks the bootstrap (the alias already knows its language, even
 *    when the cached-resolution branch carries no artist ids);
 *  - [OriginalArtistRetryPolicy] / [OriginalArtistRetryTracker] give a failed artist lookup a
 *    backoff instead of a terminal "resolved" marker: a miss is never a resolution.
 */
internal object OriginalArtistLanguagePolicy {

    /**
     * The first of [remembered], [cachedRegion] and [hint] that [supported] maps to a canonical
     * language, or null when none does. Order matters: a language remembered from an earlier
     * artist resolution is the most specific evidence, the cached region is next, and the song
     * alias's own language is the bootstrap fallback.
     */
    fun pick(
        remembered: String?,
        cachedRegion: String?,
        hint: String?,
        supported: (String?) -> String?,
    ): String? = supported(remembered) ?: supported(cachedRegion) ?: supported(hint)
}

/** Per-media original-language hints, learned from a resolved original song alias. */
internal class OriginalLanguageHintStore {

    private val hints = ConcurrentHashMap<String, String>()

    /** Records [language] for [mediaId]; blanks and non-ids are ignored. */
    fun remember(mediaId: String, language: String?) {
        val id = mediaId.trim()
        val value = language?.trim().orEmpty()
        if (id.isEmpty() || value.isEmpty()) return
        hints[id] = value
    }

    /** The hint for [mediaId], or null when the song alias was never resolved. */
    fun hint(mediaId: String): String? = hints[mediaId]

    /** Drops the hint for [mediaId] (identity or language evidence changed). */
    fun forget(mediaId: String) {
        hints.remove(mediaId)
    }

    /** Drops every hint (configuration/storefront change). */
    fun clear() {
        hints.clear()
    }
}

/** The backoff applied to a failed original-artist lookup. */
internal object OriginalArtistRetryPolicy {

    /** First retry waits this long after a miss. */
    const val BASE_DELAY_MS = 30_000L

    /** Later retries back off up to this ceiling. */
    const val MAX_DELAY_MS = 10 * 60_000L

    /** The delay before the [misses]-th retry; 0 when nothing failed yet. */
    fun delayMillis(misses: Int): Long {
        if (misses <= 0) return 0L
        var delay = BASE_DELAY_MS
        repeat((misses - 1).coerceAtMost(5)) {
            delay = (delay * 2).coerceAtMost(MAX_DELAY_MS)
        }
        return delay
    }

    /**
     * Whether a lookup may run at [now]. A first attempt always may; a later one only after the
     * backoff for the misses already recorded. A miss therefore never turns into "resolved".
     */
    fun shouldRetry(misses: Int, lastMissUptimeMillis: Long?, now: Long): Boolean {
        if (misses <= 0) return true
        val last = lastMissUptimeMillis ?: return true
        return now - last >= delayMillis(misses)
    }
}

/**
 * Per-media miss bookkeeping for the original-artist lookup. It deliberately has no "resolved"
 * concept: only [AppleMetadataOverrideStore] decides that, and a miss must never set it.
 */
internal class OriginalArtistRetryTracker {

    private val misses = ConcurrentHashMap<String, Int>()
    private val lastMissUptimeMillis = ConcurrentHashMap<String, Long>()

    /** How many misses [mediaId] has recorded. */
    fun misses(mediaId: String): Int = misses[mediaId] ?: 0

    /** Records one miss at [now] and returns the new count. */
    fun recordMiss(mediaId: String, now: Long): Int {
        val next = (misses[mediaId] ?: 0) + 1
        misses[mediaId] = next
        lastMissUptimeMillis[mediaId] = now
        return next
    }

    /** Whether the lookup may run at [now] under [OriginalArtistRetryPolicy]. */
    fun shouldRetry(mediaId: String, now: Long): Boolean = OriginalArtistRetryPolicy.shouldRetry(
        misses = misses(mediaId),
        lastMissUptimeMillis = lastMissUptimeMillis[mediaId],
        now = now,
    )

    /** The moment of the last miss, for tests and the retry log. */
    fun lastMiss(mediaId: String): Long? = lastMissUptimeMillis[mediaId]

    /** Forgets [mediaId]'s misses, so a changed identity gets a fresh attempt. */
    fun forget(mediaId: String) {
        misses.remove(mediaId)
        lastMissUptimeMillis.remove(mediaId)
    }

    /** Forgets every miss (configuration change). */
    fun clear() {
        misses.clear()
        lastMissUptimeMillis.clear()
    }
}
