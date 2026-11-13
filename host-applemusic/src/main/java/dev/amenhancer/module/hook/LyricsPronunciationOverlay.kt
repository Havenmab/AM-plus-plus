package dev.amenhancer.module.hook

import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.lyrics.online.LyricTokenMerger
import dev.amenhancer.module.lyrics.online.MergedPronunciation
import dev.amenhancer.module.lyrics.online.NativeLyricOverlayStore
import dev.amenhancer.module.lyrics.online.NativePronunciationSpan
import dev.amenhancer.module.lyrics.online.PronunciationToken
import dev.amenhancer.module.model.ModuleSettings
import io.github.proify.lyricon.amprovider.xposed.resolvePlaybackPositionSource
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * POC overlay that renders a line's romanization row as **merged words** and highlights it
 * per sub-token, without touching Apple's own per-word highlight of the lyric body.
 *
 * ## Why this cannot be done in Apple's view tree
 *
 * Each karaoke word is inflated as `layout/lyrics_pronunciation_vertical_group` — a vertical
 * `LinearLayout` holding `layout/lyrics_word_karaoke` (`id song_lyrics_word`) above
 * `layout/lyrics_word_pronunciation` (`id pronunciation_word`). A line is
 * `layout/karaoke_lyrics_line_container` with two `FullWidthAlphaGradientFlexboxLayout` children,
 * `id karaoke_line_flexbox` (the body words) and `id bg_line_flexbox`.
 *
 * Apple's karaoke highlight is an `AnimatorSet` of `View.ALPHA` plus a custom float property on
 * **each word's TextView** (`player/R0.java`). It is whole-view per word, so it cannot subdivide a
 * word that [LyricTokenMerger] merged out of several tokens: the merged romanization is one string
 * and needs its own per-character paint. That is the whole reason this overlay exists — the lyric
 * body stays exactly Apple's, and only the romanization row is re-presented.
 *
 * ## What this hook does
 *
 *  - registers `lyrics_pronunciation_vertical_group` to tag every word container it inflated;
 *  - registers the line layout to attach an `OnLayoutChangeListener`, which is the only moment
 *    the containers have real geometry;
 *  - on layout, collects the line's containers in order from `karaoke_line_flexbox`, reads each
 *    `song_lyrics_word` text, builds the line text and looks the stored row up with
 *    [NativeLyricOverlayStore.rowForText]. Pairing into [PronunciationToken]s is only accepted when
 *    the span count equals the container count — a partial lane is left alone rather than guessed;
 *  - runs [LyricTokenMerger.merge] once per line. Every merged group gets its own `TextView`
 *    spanning the group's horizontal extent, sitting on the old pronunciation row's vertical band,
 *    showing `group.romanization`; the group's original `pronunciation_word` children are set
 *    **INVISIBLE** (never GONE: the container's `wrap_content` width comes from the wider child, and
 *    a GONE child would collapse it and re-flow the line);
 *  - repaints each overlay from the playback position: characters of sub-tokens that already
 *    finished keep Apple's own pronunciation colour, the rest are the same colour dimmed.
 *
 * The view wiring is deliberately thin: every decision that can be decided from values alone is a
 * pure function on this object ([lineText], [pairTokens], [mergedGroups], [overlayGroups],
 * [characterRanges], [clip], [unionExtent], [placement], [dimmedColor]) so it is covered by JVM
 * tests and the only untested part left is `addView`/geometry plumbing.
 *
 * ## Gating and failure
 *
 * The registration is fixed and the per-inflation callback decides, exactly like
 * [LyricsPronunciationRowCentring]: everything is behind [isEnabled], the same
 * [ModuleSettings.onlineLyricsTranslationEnabled] switch the pronunciation/translation presentation
 * uses, and a module with the feature off leaves Apple's views untouched. Every lookup fails open —
 * no bound store, no stored row, an ambiguous row, a span/container count mismatch or a recycled
 * container all mean "no overlay", never a wrong one.
 */
internal object LyricsPronunciationOverlay {

    /** The verified per-word container Apple adds both lyric children to. */
    const val WORD_CONTAINER_LAYOUT_NAME = "lyrics_pronunciation_vertical_group"

    /** The verified line layout; see [LINE_LAYOUT_NAMES] for the second name it is known by. */
    const val LINE_LAYOUT_NAME = "karaoke_lyrics_line_container"

    /**
     * The line layouts this hook observes. `karaoke_lyrics_line_container` is the verified name;
     * `lyrics_line_karaoke` is the same line on the profiles that name it after its body row, and
     * observing both costs one extra registration while keeping the hook alive across versions.
     * [observeLine] is idempotent per view, so a line dispatched under both names is observed once.
     */
    val LINE_LAYOUT_NAMES: List<String> = listOf(LINE_LAYOUT_NAME, "lyrics_line_karaoke")

    /** Apple's id names, resolved against the target package at runtime. */
    const val WORD_TEXT_ID_NAME = "song_lyrics_word"
    const val PRONUNCIATION_TEXT_ID_NAME = "pronunciation_word"
    const val WORD_ROW_ID_NAME = "karaoke_line_flexbox"

    /** The alpha the still-unsung half of a merged romanization is painted at. */
    const val DIM_ALPHA = 0x66

    /** How often the highlight is repainted while at least one overlay is alive. */
    const val TICK_MS = 50L

    /** Guards the ancestor walk in [extentIn]; a deeper chain is not Apple's lyric tree. */
    private const val MAX_ANCESTOR_DEPTH = 32

    // ---------------------------------------------------------------------------------------
    // Pure model: everything below this line can be decided without a single View.
    // ---------------------------------------------------------------------------------------

    /** A horizontal/vertical box in the coordinate space of one line root. */
    data class Extent(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = (right - left).coerceAtLeast(0)
        val height: Int get() = (bottom - top).coerceAtLeast(0)
    }

    /**
     * One sub-token's character slice inside its group's merged romanization. [start] is inclusive
     * and [end] exclusive, so the slice can be handed straight to `Spannable.setSpan`; [sung] says
     * whether the sub-token's own interval has already elapsed at the sampled playback position.
     */
    data class CharacterRange(val start: Int, val end: Int, val sung: Boolean)

    /** The line text Apple's row would show: the word texts concatenated in order, no separator. */
    fun lineText(wordTexts: List<String>): String = wordTexts.joinToString("")

    /**
     * Pairs a line's word texts with the stored per-token romanization spans, in order.
     *
     * Returns null whenever the two sides disagree in count: a partial lane (a line whose stored
     * document romanized only some words) cannot be paired positionally, and guessing would paint
     * one word's reading under another word.
     *
     * The token's `text` is the container's own word text (what Apple rendered), while its
     * `romanization`/timing come from the span, so a merged group concatenates exactly the
     * romanizations of the containers it hides.
     */
    fun pairTokens(
        wordTexts: List<String>,
        spans: List<NativePronunciationSpan>,
    ): List<PronunciationToken>? {
        if (spans.size != wordTexts.size) return null
        return wordTexts.mapIndexed { index, text ->
            val span = spans[index]
            PronunciationToken(
                text = text,
                romanization = span.text,
                begin = span.begin,
                end = span.end,
            )
        }
    }

    /**
     * The line's merged groups, in order, or null when the line cannot be paired at all. Every
     * input token appears exactly once in the result (a group of one for a token that did not
     * merge), so the caller can map groups back onto containers by counting `parts`.
     */
    fun mergedGroups(
        wordTexts: List<String>,
        spans: List<NativePronunciationSpan>,
    ): List<MergedPronunciation>? =
        pairTokens(wordTexts, spans)?.let(LyricTokenMerger::merge)

    /** The groups that actually merged more than one token and therefore need an overlay. */
    fun overlayGroups(groups: List<MergedPronunciation>): List<MergedPronunciation> =
        groups.filter { it.isMerged }

    /**
     * Every sub-token's character slice inside [group]'s merged romanization, in token order.
     *
     * The merged string is the concatenation of its parts' romanizations, so the slices are the
     * running offsets over `part.romanization.orEmpty()`; a part with no romanization contributes
     * an empty slice, which is what keeps the ranges aligned with the rendered text. A sub-token
     * counts as sung once its interval has elapsed (`position >= part.end`); an unknown position
     * ([positionMs] null) leaves every slice unsung instead of guessing.
     */
    fun characterRanges(
        group: MergedPronunciation,
        positionMs: Long?,
    ): List<CharacterRange> {
        val ranges = ArrayList<CharacterRange>(group.parts.size)
        var offset = 0
        group.parts.forEach { part ->
            val romanization = part.romanization.orEmpty()
            val end = offset + romanization.length
            ranges += CharacterRange(
                start = offset,
                end = end,
                sung = positionMs != null && positionMs >= part.end,
            )
            offset = end
        }
        return ranges
    }

    /** Clamps [range] to a text of [length] characters; null when nothing of it is left. */
    fun clip(range: CharacterRange, length: Int): CharacterRange? {
        val start = range.start.coerceIn(0, length)
        val end = range.end.coerceIn(0, length)
        if (end <= start) return null
        return CharacterRange(start = start, end = end, sung = range.sung)
    }

    /** The union of [extents], or null when there is none. */
    fun unionExtent(extents: List<Extent>): Extent? {
        if (extents.isEmpty()) return null
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        extents.forEach { extent ->
            if (extent.left < left) left = extent.left
            if (extent.top < top) top = extent.top
            if (extent.right > right) right = extent.right
            if (extent.bottom > bottom) bottom = extent.bottom
        }
        return Extent(left = left, top = top, right = right, bottom = bottom)
    }

    /**
     * Where the merged overlay sits: horizontally the union of the group's containers, vertically
     * the band the group's original pronunciation row occupied. Null when the group has no
     * containers to span.
     */
    fun placement(containers: List<Extent>, row: Extent): Extent? {
        val horizontal = unionExtent(containers) ?: return null
        return Extent(
            left = horizontal.left,
            top = row.top,
            right = horizontal.right,
            bottom = row.bottom,
        )
    }

    /**
     * The paint of a not-yet-sung character: Apple's own pronunciation colour at [DIM_ALPHA].
     * Keeping the RGB and moving only the alpha means the dimmed half is the same colour, so the
     * merged word still reads as one row.
     */
    fun dimmedColor(color: Int): Int = (color and 0x00FFFFFF) or (DIM_ALPHA shl 24)

    // ---------------------------------------------------------------------------------------
    // Host binding: the store and the playback position are injected, never invented here.
    // ---------------------------------------------------------------------------------------

    @Volatile
    private var store: NativeLyricOverlayStore? = null

    @Volatile
    private var positionSource: () -> Long? = { null }

    /**
     * Binds the live overlay store whose [NativeLyricOverlayStore.rowForText] this hook reads.
     * Called from `AppleMusicHostFactory.appleMusic` with the same instance the native lyric-model
     * delivery writes, so the overlay always sees the current track's row.
     */
    fun bindStore(store: NativeLyricOverlayStore?) {
        this.store = store
    }

    /**
     * Binds the playback position in ms. The POC gets it through [playbackPositionOf] and the
     * existing `PlaybackPositionSource` reflection helper; an unbound source simply leaves every
     * overlay dimmed rather than inventing a position.
     */
    fun bindPlaybackPosition(source: () -> Long?) {
        positionSource = source
    }

    /**
     * The existing playback-position reflection helper (`PlaybackPositionSource#readPosition`,
     * resolved by `resolvePlaybackPositionSource`), adapted to the nullable provider this hook
     * takes. Null for a player that does not answer `getCurrentPosition`.
     */
    fun playbackPositionOf(player: Any?): Long? =
        runCatching { resolvePlaybackPositionSource(player)?.readPosition() }.getOrNull()

    // ---------------------------------------------------------------------------------------
    // Registration
    // ---------------------------------------------------------------------------------------

    @Volatile
    private var installed = false

    private val lineStates = Collections.synchronizedMap(WeakHashMap<ViewGroup, LineState>())
    private val taggedSlots = Collections.synchronizedMap(WeakHashMap<View, WordSlot>())
    private val observedLines = Collections.synchronizedMap(WeakHashMap<View, Boolean>())
    private val resolvedIds = ConcurrentHashMap<String, Int>()

    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var ticking = false

    /**
     * Registers both layout callbacks. Idempotent: a second call (a resource stage re-run) adds no
     * duplicate handler, so a line never gets two overlay sets.
     */
    fun install() {
        if (installed) return
        installed = true
        LayoutInflationRegistry.register(WORD_CONTAINER_LAYOUT_NAME) { view ->
            if (!isFeatureEnabled()) return@register
            tagWordContainer(view)
        }
        LINE_LAYOUT_NAMES.forEach { layoutName ->
            LayoutInflationRegistry.register(layoutName) { view ->
                if (!isFeatureEnabled()) return@register
                observeLine(view)
            }
        }
    }

    /** The module feature switch; off means Apple's per-word views are never touched. */
    fun isEnabled(settings: ModuleSettings): Boolean = settings.onlineLyricsTranslationEnabled

    /** Live read of [isEnabled]; an unreadable configuration keeps the callback a no-op. */
    private fun isFeatureEnabled(): Boolean =
        runCatching { isEnabled(TargetConfigClient.currentSettings()) }.getOrDefault(false)

    /**
     * Tags an inflated word container: its two lyric children are resolved once, while the
     * container is still the freshly inflated tree, and kept so a line rebuild does not have to
     * walk the subtree again. A container whose shape is not Apple's karaoke word is not tagged,
     * and [wordSlots] still resolves a container that was inflated before this hook was installed.
     */
    fun tagWordContainer(container: View) {
        val slot = slotFor(container, container.resources) ?: return
        taggedSlots[container] = slot
    }

    /** Attaches the layout/attach observers to a line exactly once. */
    fun observeLine(line: View) {
        val group = line as? ViewGroup ?: return
        if (observedLines.put(group, true) != null) return
        group.addOnLayoutChangeListener { view, left, top, _, _, _, _, _, _ ->
            // The listener also fires for the overlays this hook itself adds. Rebuilding on those
            // would add another overlay on every pass, so only a real re-layout of the line counts.
            val state = (view as? ViewGroup)?.let { lineStates[it] }
            val unchanged = state != null &&
                state.lastLeft == left &&
                state.lastTop == top &&
                state.lastWidth == view.width &&
                state.lastHeight == view.height
            if (unchanged) return@addOnLayoutChangeListener
            state?.let {
                it.lastLeft = left
                it.lastTop = top
                it.lastWidth = view.width
                it.lastHeight = view.height
            }
            view.post { rebuildLine(view) }
        }
        group.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                view.post { rebuildLine(view) }
            }

            override fun onViewDetachedFromWindow(view: View) {
                // RecyclerView recycling: a detached line's overlays must not survive into the
                // next bind, and the pronunciation rows it hid are Apple's again.
                (view as? ViewGroup)?.let { lineStates.remove(it) }?.let(::clearLine)
                observedLines.remove(view)
            }
        })
        lineStates.getOrPut(group) { LineState() }
    }

    /**
     * The line's current state, rebuilt from scratch: the store row is looked up by the line's own
     * text, the merged groups are laid out as overlays, and every group of one is left to Apple.
     */
    private fun rebuildLine(line: View) {
        val parent = line as? ViewGroup ?: return
        val state = lineStates.getOrPut(parent) { LineState() }
        clearLine(state)
        val resources = line.resources
        val rowId = idOf(resources, WORD_ROW_ID_NAME)
        if (rowId == 0) return
        val row = line.findViewById<View>(rowId) ?: return
        val slots = wordSlots(row, resources)
        if (slots.isEmpty()) return
        val wordTexts = slots.map { it.word.text?.toString().orEmpty() }
        val currentStore = store ?: return
        val content = runCatching {
            currentStore.rowForText(currentStore.currentSongId(), lineText(wordTexts)).content
        }.getOrNull() ?: return
        val groups = mergedGroups(wordTexts, content.pronunciationSpans) ?: return
        var index = 0
        groups.forEach { group ->
            val start = index.coerceIn(0, slots.size)
            val end = (index + group.parts.size).coerceIn(start, slots.size)
            val groupSlots = slots.subList(start, end)
            index += group.parts.size
            if (!group.isMerged || groupSlots.isEmpty()) return@forEach
            groupSlots.forEach { slot -> hidePronunciation(state, slot) }
            val containers = groupSlots.map { extentIn(line, it.container) }
            val rowExtent = extentIn(line, groupSlots.first().pronunciation)
            val placement = placement(containers, rowExtent) ?: return@forEach
            val overlay = addOverlay(parent, group, groupSlots.first(), placement)
            state.overlays += Overlay(group, overlay)
        }
        renderLine(state)
        startTickerIfNeeded()
    }

    /** Removes a line's overlays and gives back every pronunciation row this hook hid. */
    private fun clearLine(state: LineState) {
        state.overlays.forEach { overlay ->
            (overlay.view.parent as? ViewGroup)?.removeView(overlay.view)
        }
        state.overlays.clear()
        state.hidden.forEach { view ->
            if (view.visibility == View.INVISIBLE) view.visibility = View.VISIBLE
        }
        state.hidden.clear()
    }

    /** Hides one container's romanization row. INVISIBLE keeps the `wrap_content` width. */
    private fun hidePronunciation(state: LineState, slot: WordSlot) {
        if (slot.pronunciation.visibility == View.INVISIBLE) return
        slot.pronunciation.visibility = View.INVISIBLE
        state.hidden += slot.pronunciation
    }

    /** Creates the merged overlay for one group, spanning [placement] inside [line]. */
    private fun addOverlay(
        line: ViewGroup,
        group: MergedPronunciation,
        source: WordSlot,
        placement: Extent,
    ): TextView {
        val overlay = TextView(line.context)
        val template = source.pronunciation
        overlay.typeface = template.typeface
        overlay.setTextSize(TypedValue.COMPLEX_UNIT_PX, template.textSize)
        overlay.setTextColor(template.currentTextColor)
        overlay.includeFontPadding = false
        overlay.gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
        overlay.text = group.romanization
        val params = if (line is FrameLayout) {
            FrameLayout.LayoutParams(placement.width, placement.height)
        } else {
            ViewGroup.LayoutParams(placement.width, placement.height)
        }
        line.addView(overlay, params)
        overlay.x = placement.left.toFloat()
        overlay.y = placement.top.toFloat()
        return overlay
    }

    /** Paints one line's overlays from the current playback position. */
    private fun renderLine(state: LineState, positionMs: Long? = readPosition()) {
        state.overlays.forEach { overlay -> renderOverlay(overlay, positionMs) }
    }

    /**
     * Paints the sub-tokens that already finished in Apple's colour and the remaining characters
     * dimmed. A [Spannable] of per-sub-token [ForegroundColorSpan]s is enough for the POC.
     */
    private fun renderOverlay(overlay: Overlay, positionMs: Long?) {
        val text = overlay.group.romanization
        val spannable = SpannableString(text)
        val base = overlay.baseColor
        characterRanges(overlay.group, positionMs).forEach { range ->
            val clipped = clip(range, text.length) ?: return@forEach
            spannable.setSpan(
                ForegroundColorSpan(if (clipped.sung) base else dimmedColor(base)),
                clipped.start,
                clipped.end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        overlay.view.text = spannable
    }

    /** The sampled playback position, or null while nothing is bound. */
    private fun readPosition(): Long? = runCatching { positionSource() }.getOrNull()

    /**
     * One main-thread ticker for the whole overlay: it repaints every live line and stops as soon
     * as none is left, so a paused or recycled page costs nothing.
     */
    private fun startTickerIfNeeded() {
        if (ticking) return
        if (lineStates.values.none { it.overlays.isNotEmpty() }) return
        ticking = true
        mainHandler.post(ticker)
    }

    private val ticker = object : Runnable {
        override fun run() {
            val position = readPosition()
            pruneDetachedLines()
            var alive = false
            lineStates.values.forEach { state ->
                if (state.overlays.isEmpty()) return@forEach
                alive = true
                renderLine(state, position)
            }
            if (alive) {
                mainHandler.postDelayed(this, TICK_MS)
            } else {
                ticking = false
            }
        }
    }

    /** Drops overlays of lines that are no longer attached (RecyclerView recycling). */
    private fun pruneDetachedLines() {
        lineStates.keys.filter { line -> !line.isAttachedToWindow }.forEach { line ->
            lineStates.remove(line)?.let(::clearLine)
            observedLines.remove(line)
        }
    }

    /** The line's word containers in body order, resolved from the body row's children. */
    private fun wordSlots(row: View, resources: Resources): List<WordSlot> {
        if (row !is ViewGroup) return emptyList()
        val slots = ArrayList<WordSlot>(row.childCount)
        for (index in 0 until row.childCount) {
            val child = row.getChildAt(index)
            val slot = taggedSlots[child] ?: slotFor(child, resources) ?: continue
            slots += slot
        }
        return slots
    }

    /** One word container's two children; null when the view is not Apple's karaoke word. */
    private fun slotFor(container: View, resources: Resources): WordSlot? {
        if (container !is ViewGroup) return null
        val wordId = idOf(resources, WORD_TEXT_ID_NAME)
        val pronunciationId = idOf(resources, PRONUNCIATION_TEXT_ID_NAME)
        if (wordId == 0 || pronunciationId == 0) return null
        val word = container.findViewById<TextView>(wordId) ?: return null
        val pronunciation = container.findViewById<TextView>(pronunciationId) ?: return null
        return WordSlot(container, word, pronunciation)
    }

    /** Resolves one of Apple's ids by name; ids are process-wide, so one lookup per name once found. */
    private fun idOf(resources: Resources, name: String): Int {
        resolvedIds[name]?.let { return it }
        val resolved = runCatching {
            resources.getIdentifier(name, "id", ModuleConstants.TARGET_PACKAGE)
        }.getOrDefault(0)
        if (resolved != 0) resolvedIds[name] = resolved
        return resolved
    }

    /** The extent of [target] expressed in [root]'s coordinate space. */
    private fun extentIn(root: View, target: View): Extent {
        var left = 0
        var top = 0
        var view: View? = target
        var depth = 0
        while (view != null && view !== root && depth++ < MAX_ANCESTOR_DEPTH) {
            left += view.left
            top += view.top
            view = view.parent as? View
        }
        return Extent(left = left, top = top, right = left + target.width, bottom = top + target.height)
    }

    /** One word container and its two lyric children. */
    private class WordSlot(
        val container: View,
        val word: TextView,
        val pronunciation: TextView,
    )

    /** One merged group's overlay view, keeping the group for the per-tick repaint. */
    private class Overlay(val group: MergedPronunciation, val view: TextView) {
        /** Apple's pronunciation colour, read once when the overlay was created. */
        val baseColor: Int = view.currentTextColor
    }

    /** Per-line bookkeeping: the overlays on screen and the rows this hook hid for them. */
    private class LineState {
        val overlays = ArrayList<Overlay>(4)
        val hidden = ArrayList<TextView>(4)
        var lastWidth = -1
        var lastHeight = -1
        var lastLeft = Int.MIN_VALUE
        var lastTop = Int.MIN_VALUE
    }
}
