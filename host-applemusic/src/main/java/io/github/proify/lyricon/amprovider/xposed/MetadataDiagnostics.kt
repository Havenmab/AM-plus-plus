/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import java.util.Collections

/**
 * The `AMPP-Meta` diagnostic lines for the original-title/artist pipeline.
 *
 * This is a **read-only** probe: every call site only observes values it already computed, so the
 * pipeline behaves exactly as it did before. The lines exist to answer three questions from one
 * device log:
 *
 *  - which alias was composed, and is its artist kana, kanji or latin ([script] marks it);
 *  - whether the MediaSession rewrite ran, and whether it changed anything;
 *  - which in-app surfaces actually received the alias (the player page reads the in-app model, not
 *    the MediaSession metadata).
 *
 * A device log is finite, so [line] remembers what it already wrote and stops after [LIMIT] distinct
 * lines; [event] is for the low-frequency seams where every update must be visible (and for the one
 * "limit reached" sentinel, so a truncated log says so instead of silently missing lines). Neither
 * call throws: a diagnostic failure must never change playback.
 */
internal object MetadataDiagnostics {

    /** The tag a device log is filtered by. */
    const val TAG = "AMPP-Meta"

    /**
     * Distinct deduplicated lines kept per process before [line] goes quiet. A long session log
     * reaches this, which is why the sentinel below exists: a missing line must never be mistaken
     * for a branch that did not run.
     */
    const val LIMIT = 4096

    /** The one event written when [line] hits [LIMIT]; also the text a device log is grepped for. */
    const val LIMIT_EVENT = "meta log limit reached limit="

    private val written: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet<String>())

    @Volatile
    private var limitReported = false

    /**
     * Test seam: when set, lines go here instead of logcat. Kept out of the hot path with one
     * volatile read, and never cleared by [resetForTest] so a test can install it once.
     */
    @Volatile
    var sink: ((String) -> Unit)? = null

    /** Writes [message] unless an identical line was already written or [LIMIT] was reached. */
    fun line(message: String) {
        var overflow = false
        val accepted = synchronized(written) {
            if (written.size >= LIMIT) {
                overflow = true
                false
            } else {
                written.add(message)
            }
        }
        if (overflow) {
            reportLimitOnce()
            return
        }
        if (!accepted) return
        emit(message)
    }

    /** Writes [message] every time; for the low-frequency seams and the limit sentinel. */
    fun event(message: String) {
        emit(message)
    }

    private fun reportLimitOnce() {
        val first = synchronized(written) {
            if (limitReported) {
                false
            } else {
                limitReported = true
                true
            }
        }
        if (first) emit(LIMIT_EVENT + LIMIT)
    }

    private fun emit(message: String) {
        val target = sink
        if (target != null) {
            target(message)
            return
        }
        android.util.Log.i(TAG, message)
    }

    /**
     * The script of [value] as one log word: `kana` (hiragana/katakana), `kanji`, `hangul`,
     * `latin`, `empty`, or `mixed`/`other`. The artist report is decided by this marker.
     */
    fun script(value: String?): String {
        if (value.isNullOrEmpty()) return "empty"
        var kana = false
        var kanji = false
        var hangul = false
        var latin = false
        var other = false
        for (character in value) {
            when {
                character.code in 0x3041..0x30FF -> kana = true
                character.code in 0x3400..0x9FFF -> kanji = true
                character.code in 0xAC00..0xD7AF -> hangul = true
                character.code < 0x80 && character.isLetterOrDigit() -> latin = true
                character.isLetterOrDigit() -> other = true
            }
        }
        val kinds = listOf(kana, kanji, hangul, latin, other).count { it }
        return when {
            kinds == 0 -> "empty"
            kinds > 1 -> "mixed"
            kana -> "kana"
            kanji -> "kanji"
            hangul -> "hangul"
            latin -> "latin"
            else -> "other"
        }
    }

    /** A media id as a log token; an absent id is explicit, never blank. */
    fun id(mediaId: String?): String = mediaId?.takeIf(String::isNotBlank) ?: "unknown"

    /** One alias as title/artist/lang with the artist's script, for a compact line. */
    fun alias(alias: AppleInternalCatalogResolver.Alias?): String =
        if (alias == null) {
            "none"
        } else {
            "title=" + alias.title + ",artist=" + alias.artist +
                ",artistScript=" + script(alias.artist) + ",lang=" + alias.language
        }

    /** Test-only: drop the deduplication memory and re-arm the limit sentinel. */
    fun resetForTest() {
        synchronized(written) {
            written.clear()
            limitReported = false
        }
    }
}
