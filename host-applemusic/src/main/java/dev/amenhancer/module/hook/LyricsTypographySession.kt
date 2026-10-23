package dev.amenhancer.module.hook

import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.lyrics.LyricsTypographyPolicy
import dev.amenhancer.module.model.ModuleSettings
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Player lyric font size and line spacing scales.
 *
 * Every verified lyric layout is inflated through a LayoutInflationRegistry callback, so the session
 * sees each row (and each karaoke word) as the host builds it. The first time a view is seen its base
 * metrics are captured in a WeakHashMap; afterwards the row is re-derived from those bases, which
 * makes re-applying idempotent and lets 100% restore the original Apple Music rendering exactly.
 *
 * What is scaled (see .prep/lyrics-typography-plan.md §3):
 *  - TextView: textSize, lineSpacingExtra, lineSpacingMultiplier, includeFontPadding (off only while
 *    compressing);
 *  - ViewGroup: paddingTop/paddingBottom (the row's vertical breathing room).
 *
 * At 100%/100% nothing is written, and a row that was previously adjusted is restored.
 *
 * Refresh model: a main-thread ticker polls the current settings every 700 ms while at least one row
 * root is alive (the settings page has no live callback), plus an OnLayoutChangeListener per root so
 * recycled rows re-derive themselves after a rebind.
 */
internal class LyricsTypographySession : LyricsTypographyResourceBinding {
    private class TextBaseline(
        val textSizePx: Float,
        val lineSpacingExtra: Float,
        val lineSpacingMultiplier: Float,
        val includeFontPadding: Boolean,
    )

    private class PaddingBaseline(val top: Int, val bottom: Int)

    private val lock = Any()
    private var config: TargetConfigClient? = null
    private var resourcesRegistered = false

    @Volatile
    private var closed = false

    private val roots = Collections.synchronizedMap(WeakHashMap<View, Boolean>())
    private val rootListeners = Collections.synchronizedMap(WeakHashMap<View, View.OnLayoutChangeListener>())
    private val textBaselines = Collections.synchronizedMap(WeakHashMap<TextView, TextBaseline>())
    private val paddingBaselines = Collections.synchronizedMap(WeakHashMap<View, PaddingBaseline>())
    private val adjustedRoots = Collections.synchronizedMap(WeakHashMap<View, Boolean>())

    @Volatile
    private var appliedFontScale = 1f

    @Volatile
    private var appliedSpacingScale = 1f

    private val karaokeWordCache = Collections.synchronizedMap(WeakHashMap<View, Boolean>())

    @Volatile
    private var loggedKaraokeWord = false

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val ticker = object : Runnable {
        override fun run() {
            if (closed) return
            val hasRoots = synchronized(roots) { roots.isNotEmpty() }
            if (!hasRoots) return
            runCatching { pollSettings() }
                .onFailure { ModernXposedRuntime.log("Lyrics typography settings poll failed", it) }
            mainHandler.postDelayed(this, TICK_MILLIS)
        }
    }

    override fun registerResources(config: TargetConfigClient) {
        synchronized(lock) {
            this.config = config
            if (resourcesRegistered) return
            resourcesRegistered = true
        }
        LyricsTypefaceLayoutContract.layoutNames.forEach { layoutName ->
            runCatching {
                LayoutInflationRegistry.register(layoutName) { root -> observe(root) }
            }.onFailure {
                ModernXposedRuntime.log("Lyrics typography could not observe layout $layoutName", it)
            }
        }
    }

    /** Stops the ticker and drops every observed root (module shutdown / tests). */
    fun close() {
        closed = true
        mainHandler.removeCallbacksAndMessages(null)
        synchronized(roots) { roots.clear() }
        synchronized(rootListeners) { rootListeners.clear() }
        synchronized(textBaselines) { textBaselines.clear() }
        synchronized(paddingBaselines) { paddingBaselines.clear() }
        synchronized(adjustedRoots) { adjustedRoots.clear() }
    }

    private fun observe(root: View) {
        if (closed) return
        val firstRoot = synchronized(roots) {
            if (roots.containsKey(root)) return@synchronized false
            roots[root] = true
            roots.size == 1
        }
        installLayoutObserver(root)
        if (firstRoot) startTicker()
        applyToRoot(root)
    }

    private fun installLayoutObserver(root: View) {
        synchronized(rootListeners) {
            if (rootListeners.containsKey(root)) return
            val listener = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                applyToRoot(view)
            }
            rootListeners[root] = listener
            root.addOnLayoutChangeListener(listener)
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit

            override fun onViewDetachedFromWindow(view: View) {
                release(view)
            }
        })
    }

    private fun release(root: View) {
        val listener = synchronized(rootListeners) { rootListeners.remove(root) }
        if (listener != null) root.removeOnLayoutChangeListener(listener)
        synchronized(roots) { roots.remove(root) }
        synchronized(adjustedRoots) { adjustedRoots.remove(root) }
    }

    private fun startTicker() {
        mainHandler.removeCallbacks(ticker)
        mainHandler.postDelayed(ticker, TICK_MILLIS)
    }

    /** The settings page has no live callback, so the ticker compares the scales once per tick. */
    private fun pollSettings() {
        val scales = currentScales()
        if (scales.first == appliedFontScale && scales.second == appliedSpacingScale) return
        val snapshot = synchronized(roots) { roots.keys.toList() }
        snapshot.forEach(::applyToRoot)
    }

    private fun applyToRoot(root: View) {
        if (closed) return
        runCatching {
            val dualPane = isTabletDualPane(root)
            val settings = currentSettings()
            val fontPercent = settings.lyricsFontScalePercent
            val spacingPercent = settings.lyricsLineSpacingScalePercent
            appliedFontScale = LyricsTypographyPolicy.fontScale(fontPercent)
            appliedSpacingScale = LyricsTypographyPolicy.spacingScale(spacingPercent)
            val neutral = LyricsTypographyPolicy.isNeutral(fontPercent) &&
                LyricsTypographyPolicy.isNeutral(spacingPercent)
            val wasAdjusted = synchronized(adjustedRoots) { adjustedRoots.containsKey(root) }
            if (neutral && !wasAdjusted) return
            // Tablet landscape dual pane keeps TabletLyricTypography's size formula; this session
            // only applies the spacing half there so row padding follows the slider too.
            applyTree(root, fontPercent, spacingPercent, applyFont = !dualPane)
            logScalesOnce(fontPercent, spacingPercent, dualPane)
            synchronized(adjustedRoots) {
                if (neutral) adjustedRoots.remove(root) else adjustedRoots[root] = true
            }
        }.onFailure { ModernXposedRuntime.log("Lyrics typography could not apply to a lyric row", it) }
    }

    private fun applyTree(root: View, fontPercent: Int, spacingPercent: Int, applyFont: Boolean) {
        val textViews = ArrayList<TextView>(8)
        val groups = ArrayList<ViewGroup>(8)
        collect(root, textViews, groups)
        textViews.forEach { view -> applyText(view, fontPercent, spacingPercent, applyFont) }
        groups.forEach { group -> applyPadding(group, spacingPercent) }
    }

    private fun collect(view: View, textViews: MutableList<TextView>, groups: MutableList<ViewGroup>) {
        if (view is TextView) textViews += view
        if (view is ViewGroup) {
            groups += view
            repeat(view.childCount) { index -> collect(view.getChildAt(index), textViews, groups) }
        }
    }

    private fun applyText(view: TextView, fontPercent: Int, spacingPercent: Int, applyFont: Boolean) {
        val baseline = synchronized(textBaselines) {
            textBaselines.getOrPut(view) {
                TextBaseline(
                    textSizePx = view.textSize,
                    lineSpacingExtra = view.lineSpacingExtra,
                    lineSpacingMultiplier = view.lineSpacingMultiplier,
                    includeFontPadding = view.includeFontPadding,
                )
            }
        }
        // The policy returns the base value untouched at the neutral percentage, which keeps the
        // stock rendering byte-for-byte identical.
        val karaokeWord = isKaraokeWord(view)
        if (applyFont || karaokeWord) {
            val targetSize = LyricsTypographyPolicy.scaledTextSize(baseline.textSizePx, fontPercent)
            if (abs(view.textSize - targetSize) > 0.5f) {
                view.setTextSize(TypedValue.COMPLEX_UNIT_PX, targetSize)
                if (karaokeWord && !loggedKaraokeWord) {
                    loggedKaraokeWord = true
                    ModernXposedRuntime.log(
                        "lyrics_typography: karaoke word id=" + view.id + " textSize " +
                            baseline.textSizePx + " -> " + targetSize,
                    )
                }
            }
        }
        val spacing = LyricsTypographyPolicy.scaledLineSpacing(
            baseExtraPx = baseline.lineSpacingExtra,
            baseMultiplier = baseline.lineSpacingMultiplier,
            spacingPercent = spacingPercent,
        )
        if (abs(view.lineSpacingExtra - spacing.extra) > 0.01f ||
            abs(view.lineSpacingMultiplier - spacing.multiplier) > 0.001f
        ) {
            view.setLineSpacing(spacing.extra, spacing.multiplier)
        }
        // Compressing the leading is the only case that needs the font padding gone; at 100%/above
        // the original flag comes back so the row is pixel-identical to the stock layout.
        val compressed = LyricsTypographyPolicy.spacingScale(spacingPercent) < 1f
        val includeFontPadding = baseline.includeFontPadding && !compressed
        if (view.includeFontPadding != includeFontPadding) {
            view.includeFontPadding = includeFontPadding
        }
    }

    private fun applyPadding(group: ViewGroup, spacingPercent: Int) {
        val baseline = synchronized(paddingBaselines) {
            paddingBaselines.getOrPut(group) {
                PaddingBaseline(group.paddingTop, group.paddingBottom)
            }
        }
        val top = LyricsTypographyPolicy.scaledVerticalPadding(baseline.top.toFloat(), spacingPercent).roundToInt()
        val bottom = LyricsTypographyPolicy.scaledVerticalPadding(baseline.bottom.toFloat(), spacingPercent).roundToInt()
        if (group.paddingTop != top || group.paddingBottom != bottom) {
            group.setPadding(group.paddingLeft, top, group.paddingRight, bottom)
            if (!loggedPaddingSample) {
                loggedPaddingSample = true
                ModernXposedRuntime.log(
                    "lyrics_typography: row padding " + baseline.top + "/" + baseline.bottom +
                        " -> " + top + "/" + bottom + " group=" + group.javaClass.simpleName,
                )
            }
        }
    }

    @Volatile
    private var loggedFontPercent = -1

    @Volatile
    private var loggedSpacingPercent = -1

    @Volatile
    private var loggedDualPane: Boolean? = null

    @Volatile
    private var loggedPaddingSample = false

    /** One line per settings change, so a device log can prove which scale was applied. */
    private fun logScalesOnce(fontPercent: Int, spacingPercent: Int, dualPane: Boolean) {
        if (fontPercent == loggedFontPercent && spacingPercent == loggedSpacingPercent &&
            loggedDualPane == dualPane
        ) return
        loggedFontPercent = fontPercent
        loggedSpacingPercent = spacingPercent
        loggedDualPane = dualPane
        loggedPaddingSample = false
        ModernXposedRuntime.log(
            "lyrics_typography: font=" + fontPercent + "% spacing=" + spacingPercent +
                "% dualPane=" + dualPane + " roots=" + synchronized(roots) { roots.size },
        )
    }

    /** The registered client wins; the static fallback keeps the session usable before registration. */
    private fun currentSettings(): ModuleSettings =
        runCatching { config?.settings() }.getOrNull() ?: TargetConfigClient.currentSettings()

    private fun currentScales(): Pair<Float, Float> = runCatching {
        scalesOf(currentSettings())
    }.getOrDefault(1f to 1f)

    private fun scalesOf(settings: ModuleSettings): Pair<Float, Float> = Pair(
        LyricsTypographyPolicy.fontScale(settings.lyricsFontScalePercent),
        LyricsTypographyPolicy.spacingScale(settings.lyricsLineSpacingScalePercent),
    )

    /**
     * Tablet landscape dual pane owns its own typography (TabletLyricTypography). Skipping here keeps
     * the two formulas from fighting over the same TextViews.
     */
    private fun isTabletDualPane(view: View): Boolean = runCatching {
        TabletModeQualifier.isEligible(view.context) &&
            view.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }.getOrDefault(false)

    /**
     * The karaoke word views (the lit word and the faded backing copy) live outside the rows the
     * dual-pane path rewrites, so at stock they keep the full size while the rest of the line follows
     * the slider — the current word would then sit visibly larger than its neighbours. They are
     * matched by resource name, with the ids pinned from the 1.6.6 base as a fallback.
     */
    private fun isKaraokeWord(view: View): Boolean {
        val cached = synchronized(karaokeWordCache) { karaokeWordCache[view] }
        if (cached != null) return cached
        val resolved = resolveKaraokeWord(view)
        synchronized(karaokeWordCache) { karaokeWordCache[view] = resolved }
        return resolved
    }

    private fun resolveKaraokeWord(view: View): Boolean {
        val id = view.id
        if (id == View.NO_ID) return false
        val name = runCatching { view.resources.getResourceEntryName(id) }.getOrNull()
        if (name == KARAOKE_WORD_NAME || name == KARAOKE_BG_WORD_NAME) return true
        return id == KARAOKE_WORD_BASE_ID || id == KARAOKE_BG_WORD_BASE_ID
    }

    private companion object {
        const val TICK_MILLIS = 700L
        const val KARAOKE_WORD_NAME = "song_lyrics_word"
        const val KARAOKE_BG_WORD_NAME = "song_lyrics_bg_word"
        const val KARAOKE_WORD_BASE_ID = 0x7f0a0a17
        const val KARAOKE_BG_WORD_BASE_ID = 0x7f0a0a14
    }
}

/** Shared scale lookup for the two tablet/dual-pane lyric paths. */
internal object LyricsTypographyScales {
    fun current(): Pair<Float, Float> = runCatching {
        val settings = TargetConfigClient.currentSettings()
        LyricsTypographyPolicy.fontScale(settings) to LyricsTypographyPolicy.spacingScale(settings)
    }.getOrDefault(1f to 1f)
}
