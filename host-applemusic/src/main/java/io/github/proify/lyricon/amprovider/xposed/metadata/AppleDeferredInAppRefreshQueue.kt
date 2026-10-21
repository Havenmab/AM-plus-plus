/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

/**
 * Coalesces in-app metadata refreshes by media id onto the next main-thread
 * message.
 *
 * Ported from HLE v8.2.1 (`1a665f69`). When a page resolves a batch of names, the
 * same id is applied two to four times in the same message (configured
 * metadata, both artist aliases, artist-id propagation) while the effective
 * alias usually does not change; each application re-bound the whole in-app
 * surface. A refresh is now offered here once per id and only the latest
 * alias/policy is executed by the drain, so repeated applications in one message
 * collapse into one surface refresh.
 *
 * The queue is intentionally tiny and lock-guarded: it is only touched from the
 * metadata applier path, and [drain]/[restore] are called on the main handler.
 */
internal class AppleDeferredInAppRefreshQueue {
    data class Drained(val pending: Map<String, Boolean>, val offers: Int)

    private val pending = LinkedHashMap<String, Boolean>()
    private var offers = 0
    private var scheduled = false

    /** Offers one refresh; returns true when a drain must be scheduled. */
    @Synchronized
    fun offer(mediaId: String, forceRebind: Boolean): Boolean {
        pending[mediaId] = (pending[mediaId] ?: false) || forceRebind
        offers += 1
        if (scheduled) return false
        scheduled = true
        return true
    }

    /** Drops a pending entry because a synchronous refresh already covered that id. */
    @Synchronized
    fun cancel(mediaId: String) {
        pending.remove(mediaId)
    }

    /**
     * Puts back the entries a budgeted drain did not reach, ahead of newly
     * offered ones; returns true when another drain must be scheduled. Restored
     * entries do not count as new offers.
     */
    @Synchronized
    fun restore(entries: Map<String, Boolean>): Boolean {
        if (entries.isEmpty()) return false
        val merged = LinkedHashMap<String, Boolean>(entries.size + pending.size)
        entries.forEach { (mediaId, forceRebind) ->
            merged[mediaId] = forceRebind || pending[mediaId] == true
        }
        pending.forEach { (mediaId, forceRebind) ->
            if (mediaId !in merged) merged[mediaId] = forceRebind
        }
        pending.clear()
        pending.putAll(merged)
        if (scheduled) return false
        scheduled = true
        return true
    }

    @Synchronized
    fun drain(): Drained = Drained(LinkedHashMap(pending), offers).also {
        pending.clear()
        offers = 0
        scheduled = false
    }

    @Synchronized
    fun size(): Int = pending.size
}
