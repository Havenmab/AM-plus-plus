package dev.amenhancer.module.hook.lyricsource

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import dev.amenhancer.module.hook.LayoutInflationRegistry
import dev.amenhancer.module.hook.LyricsSourceSheetEntry
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The always-present entry into the lyrics-source sheet.
 *
 * Apple only renders the lyrics "more" popup when its own document carries the
 * translation/romanization rows, and the player page's "..." is a *track* action
 * menu, not a lyrics menu — so on a song with static lyrics every native menu that
 * could host our row is absent. The sheet therefore gets an entry of its own: a
 * small, low-contrast button floated over the lyrics page, plus a long press on the
 * lyrics list as a second way in.
 *
 * Design rules:
 *
 * - **The page is never modified.** The button is added to the Activity's decor
 *   overlay with end/center gravity, so Apple's layout, scrolling and gestures are
 *   untouched. It is `tag`-deduplicated, so re-entering the page cannot stack copies.
 * - **It leaves with the page.** The button is removed when its host container is
 *   detached or the host Activity is finishing; a song change re-attaches it.
 * - **Long press never steals a gesture.** A container that already declares itself
 *   long-clickable is left alone; otherwise the listener returns true only when the
 *   sheet actually opened.
 * - **Nothing is added while the feature is off** ([enabled]).
 */
internal object LyricsSourceFloatingEntry {

    /** Resource names of the lyric containers across phone and tablet layouts. */
    private val CONTAINER_RESOURCES = listOf(
        "lyrics_main_content",
        "song_lyrics_list",
        "lyrics_recycler_view",
        "lyrics_list",
    )

    private const val BUTTON_TAG = "ampp_lyrics_source_floating_entry"
    private const val BUTTON_TEXT = "歌词源"

    private val installed = AtomicBoolean(false)

    /** The overlay currently attached, and the container it belongs to. */
    @Volatile
    private var attachedButton: WeakReference<View>? = null

    @Volatile
    private var attachedHost: WeakReference<View>? = null

    private val longPressed = java.util.Collections.synchronizedMap(WeakHashMap<View, Boolean>())

    /** Registers the inflation observers; idempotent for the process. */
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
                logger("lyrics-source floating: register " + name + " failed: " + error.message)
            }
        }
    }

    /**
     * Attaches from a lyrics fragment reached through the click-listener contract:
     * reflects its view and looks for the first scrollable lyric container. This is
     * the path used when Apple's popup does exist (so the fragment is in hand).
     */
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
        val container = runCatching { firstLyricContainer(root) }.getOrNull() ?: root
        attach(container, enabled, onOpen, logger)
    }

    /**
     * Attaches to [host] (the lyric list, or a fragment root): adds the floating
     * button and the long-press listener. Idempotent per host.
     */
    fun attach(
        host: View,
        enabled: () -> Boolean,
        onOpen: (Activity) -> Boolean,
        logger: (String) -> Unit,
    ) {
        attachLongPress(host, enabled, onOpen, logger)
        if (attachedHost?.get() === host && attachedButton?.get()?.isAttachedToWindow == true) return
        val activity = host.context.hostActivity() ?: return
        val decor = runCatching { activity.window?.decorView as? ViewGroup }.getOrNull() ?: return
        remove(logger)
        if (!enabled()) return
        runCatching {
            val button = createButton(decor.context) { view ->
                val opened = view.context.hostActivity()?.let(onOpen) ?: false
                if (opened) logger("lyrics-source floating: sheet opened")
                opened
            }
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL,
            ).apply {
                marginEnd = dp(decor.context, 12)
                topMargin = 0
            }
            decor.addView(button, params)
            attachedButton = WeakReference(button)
            attachedHost = WeakReference(host)
            // The button belongs to this page: drop it when the page goes away.
            host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit
                override fun onViewDetachedFromWindow(view: View) {
                    if (attachedHost?.get() === view) remove(logger)
                }
            })
            logger("lyrics-source floating: attached to " + host.javaClass.simpleName)
        }.onFailure { error ->
            logger("lyrics-source floating: attach failed: " + (error.message ?: "unknown"))
        }
    }

    /** Removes the floating button (song change, page teardown, feature off). */
    fun remove(logger: (String) -> Unit = {}) {
        val button = attachedButton?.get()
        attachedButton = null
        attachedHost = null
        if (button == null) return
        runCatching {
            (button.parent as? ViewGroup)?.removeView(button)
            logger("lyrics-source floating: removed")
        }.onFailure { error ->
            logger("lyrics-source floating: remove failed: " + (error.message ?: "unknown"))
        }
    }

    private fun createButton(context: Context, onClick: (View) -> Boolean): TextView =
        TextView(context).apply {
            tag = BUTTON_TAG
            text = BUTTON_TEXT
            textSize = 12f
            setTextColor(Color.WHITE)
            isSingleLine = true
            gravity = Gravity.CENTER
            setPadding(dp(context, 14), dp(context, 8), dp(context, 14), dp(context, 8))
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 20).toFloat()
                setColor(Color.argb(0x66, 0x00, 0x00, 0x00))
            }
            alpha = 0.72f
            isClickable = true
            isFocusable = true
            contentDescription = BUTTON_TEXT
            setOnClickListener { view -> onClick(view) }
        }

    private fun attachLongPress(
        host: View,
        enabled: () -> Boolean,
        onOpen: (Activity) -> Boolean,
        logger: (String) -> Unit,
    ) {
        if (longPressed.containsKey(host)) return
        if (host.isLongClickable) {
            logger("lyrics-source long-press: host is long-clickable, left untouched")
            return
        }
        runCatching {
            host.setOnLongClickListener { view ->
                if (!enabled()) return@setOnLongClickListener false
                val activity = view.context.hostActivity() ?: return@setOnLongClickListener false
                val opened = onOpen(activity)
                if (opened) logger("lyrics-source long-press: sheet opened")
                opened
            }
            longPressed[host] = true
            logger("lyrics-source long-press: attached to " + host.javaClass.simpleName)
        }.onFailure { error ->
            logger("lyrics-source long-press: attach failed: " + (error.message ?: "unknown"))
        }
    }

    /** The first scrollable child that looks like the lyric list, for the fragment path. */
    private fun firstLyricContainer(root: View): View? {
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                val child = root.getChildAt(index)
                if (child is androidx.recyclerview.widget.RecyclerView) return child
                if (child is android.widget.ScrollView) return child
                firstLyricContainer(child)?.let { return it }
            }
        }
        return null
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

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
