package dev.amenhancer.module.hook.lyricsource

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import dev.amenhancer.module.hook.LayoutInflationRegistry
import dev.amenhancer.module.hook.LyricsSourceSheetEntry
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The entry that does not depend on any menu existing.
 *
 * Apple only shows the lyrics "more" popup (隐藏发音 / 隐藏翻译) when its own document
 * actually has those lanes, so on a song without them the popup — and with it every
 * menu item we append — simply never appears. The sheet must still be reachable: a
 * long press on the lyrics area opens it directly.
 *
 * It is attached per inflated lyrics container (the RecyclerView Apple binds lines
 * into), never to a line, so vertical scrolling is untouched. A view that already
 * has a long-click handler is left alone (Apple's own behaviour wins over ours),
 * and the listener returns true only when the sheet actually opened — a rejected
 * open leaves the gesture to the platform.
 */
internal object LyricsSourceLongPressEntry {

    /** Resource names of the lyric containers across the supported builds. */
    private val CONTAINER_RESOURCES = listOf("lyrics_main_content", "song_lyrics_list")

    private val installed = AtomicBoolean(false)

    /** Containers already carrying our listener, weakly so a recycled page can go away. */
    private val attached = java.util.Collections.synchronizedMap(WeakHashMap<View, Boolean>())

    /**
     * Registers for every lyrics container inflation. Idempotent: the registry is
     * keyed by resource name and the hooks live for the process.
     */
    fun install(
        enabled: () -> Boolean,
        onOpen: (Activity) -> Boolean,
        logger: (String) -> Unit,
    ) {
        if (!installed.compareAndSet(false, true)) return
        CONTAINER_RESOURCES.forEach { name ->
            runCatching {
                LayoutInflationRegistry.register(name) { root -> attach(root, enabled, onOpen, logger) }
            }.onFailure { error ->
                logger("lyrics-source long-press: register " + name + " failed: " + error.message)
            }
        }
    }

    private fun attach(
        root: View,
        enabled: () -> Boolean,
        onOpen: (Activity) -> Boolean,
        logger: (String) -> Unit,
    ) {
        if (attached.containsKey(root)) return
        if (root.isLongClickable) {
            // Apple (or another module) already owns long press on this container.
            logger("lyrics-source long-press: container is long-clickable, left untouched")
            return
        }
        runCatching {
            root.setOnLongClickListener { view ->
                if (!enabled()) return@setOnLongClickListener false
                val activity = view.context.hostActivity() ?: return@setOnLongClickListener false
                // True only when the sheet really opened, so a rejection falls through
                // to the platform instead of swallowing the gesture.
                val opened = onOpen(activity)
                if (opened) logger("lyrics-source long-press: sheet opened")
                opened
            }
            attached[root] = true
            logger("lyrics-source long-press: attached to " + root.javaClass.simpleName)
        }.onFailure { error ->
            logger("lyrics-source long-press: attach failed: " + (error.message ?: "unknown"))
        }
    }

    /** Opens the sheet from an arbitrary host view; false when nothing was opened. */
    fun openFrom(view: View, logger: (String) -> Unit): Boolean {
        val activity = view.context.hostActivity() ?: run {
            logger("lyrics-source: no Activity context for the sheet")
            return false
        }
        return LyricsSourceSheetEntry.open(activity)
    }

    private fun Context.hostActivity(): Activity? {
        var context: Context? = this
        var depth = 0
        while (context is ContextWrapper && depth < 8) {
            if (context is Activity) return context
            context = context.baseContext
            depth += 1
        }
        return context as? Activity
    }
}
