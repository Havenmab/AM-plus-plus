package dev.amenhancer.module.hook.lyricsource

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import dev.amenhancer.module.hook.LayoutInflationRegistry
import dev.amenhancer.module.hook.LyricsSourceSheetEntry
import dev.amenhancer.module.lyrics.source.LyricLane
import dev.amenhancer.module.lyrics.source.LyricsSourceAccess
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Keeps Apple's own lyrics "more" button reachable and makes sure it opens the sheet.
 *
 * On a song whose document carries no translation/romanization lane, Apple hides the
 * button that opens the lyrics popup (there is nothing to toggle), and with it every
 * menu row this module appends. The page itself is still a normal, scrollable lyrics
 * page — only the button is gone.
 *
 * So this surface does two things, and nothing else:
 *
 * 1. **Force the button visible** — only when Apple left it hidden. A visible button is
 *    never touched (no flicker, no state drift), and the original visibility/enabled/
 *    alpha is recorded so leaving the page puts it back exactly as it was.
 * 2. **Take over the click only when there is no lane to toggle** — with a lane, the
 *    native click runs untouched (the popup opens and our appended row is there). With
 *    no lane, Apple's own handler would open an empty sheet or nothing, so the click
 *    opens this module's sheet instead.

 *
 * Expectations of the original handler are preserved: it is read through
 * `View.mListenerInfo.mOnClickListener` and invoked for the pass-through case, so
 * Apple's own bookkeeping (metrics, dismissal, refresh) still runs.
 */
internal object LyricsSourceButtonSurface {

    /** Lyric containers whose page hosts the "more" button. */
    private val CONTAINER_RESOURCES = listOf(
        "lyrics_main_content",
        "song_lyrics_list",
        "lyrics_recycler_view",
        "lyrics_list",
    )

    private const val MORE_BUTTON_ID_NAME = "list_left_icon"
    private const val MORE_BUTTON_DESCRIPTION_NAME = "more_button"

    /** Original state of every button we forced visible, so it can be restored. */
    private data class OriginalState(
        val visibility: Int,
        val enabled: Boolean,
        val alpha: Float,
        val listener: View.OnClickListener?,
    )

    private val recorded = java.util.Collections.synchronizedMap(WeakHashMap<View, OriginalState>())
    private val attachedHosts = java.util.Collections.synchronizedMap(WeakHashMap<View, Boolean>())
    private val installed = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var attachedButton: WeakReference<View>? = null

    /** Registers the lyric-container inflation observers; idempotent per process. */
    fun install(
        enabled: () -> Boolean,
        onOpen: (Activity) -> Boolean,
        logger: (String) -> Unit,
    ) {
        if (!installed.compareAndSet(false, true)) return
        CONTAINER_RESOURCES.forEach { name ->
            runCatching {
                LayoutInflationRegistry.register(name) { root -> attachFromContainer(root, enabled, onOpen, logger) }
            }.onFailure { error ->
                logger("lyrics-source button: register " + name + " failed: " + error.message)
            }
        }
    }

    /** Attaches from a lyrics fragment reached through the click-listener contract. */
    fun attachFromFragment(
        fragment: Any?,
        enabled: () -> Boolean,
        onOpen: (Activity) -> Boolean,
        logger: (String) -> Unit,
    ) {
        if (fragment == null) return
        val root = runCatching {
            fragment.javaClass.getMethod("getView").invoke(fragment) as? View
        }.getOrNull() ?: return
        attach(root, enabled, onOpen, logger)
    }

    private fun attachFromContainer(
        container: View,
        enabled: () -> Boolean,
        onOpen: (Activity) -> Boolean,
        logger: (String) -> Unit,
    ) {
        // The button sits in the page header above the lyric list; walk up a few levels
        // so the search covers the whole page, not just the list's own children.
        var root: View = container
        var depth = 0
        while (root.parent is View && depth < 4) {
            root = root.parent as View
            depth += 1
        }
        attach(root, enabled, onOpen, logger)
    }

    /**
     * Finds the "more" button under [pageRoot], forces it visible when Apple hid it, and
     * installs the click fallback. Idempotent per page.
     */
    fun attach(
        pageRoot: View,
        enabled: () -> Boolean,
        onOpen: (Activity) -> Boolean,
        logger: (String) -> Unit,
    ) {
        if (attachedHosts.containsKey(pageRoot)) return
        if (!enabled()) return
        val button = runCatching { findMoreButton(pageRoot) }.getOrNull() ?: return
        runCatching {
            attachedHosts[pageRoot] = true
            attachedButton = WeakReference(button)
            val original = OriginalState(
                visibility = button.visibility,
                enabled = button.isEnabled,
                alpha = button.alpha,
                listener = originalClickListener(button),
            )
            // Only a button Apple hid is forced back; a visible one is left exactly as is.
            if (button.visibility != View.VISIBLE) {
                recorded[button] = original
                button.visibility = View.VISIBLE
                button.isEnabled = true
                button.alpha = 1f
                logger("lyrics-source button: forced visible (was " + original.visibility + ")")
            } else if (original.listener != null) {
                recorded[button] = original
            }
            installClickFallback(button, original.listener, onOpen, logger)
            pageRoot.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit
                override fun onViewDetachedFromWindow(view: View) {
                    if (attachedHosts.containsKey(view)) {
                        attachedHosts.remove(view)
                        restore(logger)
                    }
                }
            })
        }.onFailure { error ->
            logger("lyrics-source button: attach failed: " + (error.message ?: "unknown"))
        }
    }

    /**
     * Wrapper click: with a lane present the original handler runs untouched; without one
     * (Apple would open nothing) the module's sheet opens instead.
     */
    private fun installClickFallback(
        button: View,
        original: View.OnClickListener?,
        onOpen: (Activity) -> Boolean,
        logger: (String) -> Unit,
    ) {
        if (hasNativeLane()) return
        button.setOnClickListener { view ->
            if (hasNativeLane()) {
                original?.onClick(view)
                return@setOnClickListener
            }
            val activity = view.context.hostActivity()
            val opened = activity != null && onOpen(activity)
            logger("lyrics-source button: click fallback opened=" + opened)
            if (!opened) original?.onClick(view)
        }
        // A touch listener is the safety net for hosts that re-set their own click
        // listener later: it only consumes the gesture when the sheet actually opened.
        button.setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP && hasNativeLane().not() && view.hasOnClickListeners().not()) {
                view.performClick()
                true
            } else {
                false
            }
        }
    }

    /** True when Apple has a translation/romanization lane to toggle for the page. */
    private fun hasNativeLane(): Boolean {
        val snapshot = runCatching { LyricsSourceAccess.snapshot() }.getOrNull() ?: return true
        // Unknown ("no snapshot yet") falls back to the native behaviour: never steal a
        // click we cannot justify.
        val supplement = snapshot.lanes.filter { state -> state.lane != LyricLane.ORIGINAL }
        if (supplement.isEmpty()) return true
        // A degraded snapshot (the probe could not read the song) lists the three
        // lanes with no options at all. That is "we do not know", not "Apple has
        // nothing to toggle", so it must not take Apple's own popup over.
        if (supplement.all { state -> state.options.isEmpty() }) return true
        return supplement.any { state -> state.appleAvailable }
    }

    /** Puts every forced button back the way Apple left it. */
    fun restore(logger: (String) -> Unit = {}) {
        val entries = synchronized(recorded) {
            recorded.entries.map { it.key to it.value }.also { recorded.clear() }
        }
        attachedButton = null
        entries.forEach { (button, original) ->
            runCatching {
                button.visibility = original.visibility
                button.isEnabled = original.enabled
                button.alpha = original.alpha
                button.setOnClickListener(original.listener)
                logger("lyrics-source button: restored visibility=" + original.visibility)
            }.onFailure { error ->
                logger("lyrics-source button: restore failed: " + (error.message ?: "unknown"))
            }
        }
    }

    /** The "more" button under [root]: by resource id first, then by description. */
    private fun findMoreButton(root: View): View? {
        val moreId = runCatching {
            root.context.resources.getIdentifier(MORE_BUTTON_ID_NAME, "id", root.context.packageName)
        }.getOrDefault(0)
        val description = runCatching {
            val stringId = root.context.resources.getIdentifier(
                MORE_BUTTON_DESCRIPTION_NAME,
                "string",
                root.context.packageName,
            )
            if (stringId == 0) null else root.context.resources.getString(stringId)
        }.getOrNull()
        return findBy(root, moreId, description)
    }

    private fun findBy(root: View, moreId: Int, description: String?): View? {
        if (root !is ViewGroup) return null
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            val idMatches = moreId != 0 && child.id == moreId
            val descriptionMatches = description != null && child.contentDescription?.toString() == description
            if (idMatches || descriptionMatches) return child
            findBy(child, moreId, description)?.let { return it }
        }
        return null
    }

    /** The click listener Apple installed, read through the hidden listener record. */
    private fun originalClickListener(view: View): View.OnClickListener? = runCatching {
        val infoField = View::class.java.getDeclaredField("mListenerInfo")
        infoField.isAccessible = true
        val info = infoField.get(view) ?: return@runCatching null
        val listenerField = info.javaClass.getDeclaredField("mOnClickListener")
        listenerField.isAccessible = true
        listenerField.get(info) as? View.OnClickListener
    }.getOrNull()

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
