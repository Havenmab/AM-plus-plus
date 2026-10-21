/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

/**
 * Runs queued tasks in submission order on the main thread, spending at most
 * about [budgetNs] per message and continuing in the next one.
 *
 * Ported from HLE v8.2.1 (`1a665f69`). A batch of catalog-name results used to
 * be applied one by one inside a single message (30–90 ms measured); issuing a
 * batch of first-seen ids cost about 1 ms each. Chunking both through this queue
 * keeps every message within the budget while preserving order and the exact
 * per-id work. A throwing task is logged and does not stop the drain.
 */
internal class AppleBudgetedResultQueue(
    private val post: (Runnable) -> Unit,
    private val clock: () -> Long = System::nanoTime,
    private val budgetNs: Long = DEFAULT_BUDGET_NS,
    private val failureMessage: String = "Apple 名称查询结果回调失败",
) {
    private val pending = ArrayDeque<() -> Unit>()
    private var drainScheduled = false

    fun enqueue(task: () -> Unit) {
        val schedule = synchronized(pending) {
            pending.addLast(task)
            (!drainScheduled).also { drainScheduled = true }
        }
        if (schedule) post(Runnable(::drain))
    }

    /** Queued tasks not yet executed; exposed for the budget test and diagnostics. */
    fun size(): Int = synchronized(pending) { pending.size }

    private fun drain() {
        val startNs = clock()
        var executed = 0
        while (true) {
            // Always run at least one task, even when it alone exceeds the budget.
            if (executed > 0 && clock() - startNs >= budgetNs) break
            val task = synchronized(pending) { pending.removeFirstOrNull() } ?: break
            executed += 1
            runCatching(task).onFailure {
                ProviderLogger.error(failureMessage, it)
            }
        }
        val reschedule = synchronized(pending) {
            drainScheduled = pending.isNotEmpty()
            drainScheduled
        }
        if (reschedule) post(Runnable(::drain))
    }

    companion object {
        const val DEFAULT_BUDGET_NS = 6_000_000L
    }
}
