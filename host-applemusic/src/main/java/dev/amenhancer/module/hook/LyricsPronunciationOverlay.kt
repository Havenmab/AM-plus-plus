package dev.amenhancer.module.hook

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
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
    /** Apple's unsung mask keeps only 18% of the text (color/white_alpha_18). */
    const val DIM_ALPHA = 0x2E

    /**
     * Width of the sweep's gradient band, as a fraction of the rendered text width.
     *
     * Apple's mask is four evenly spaced stops (sung/sung/unsung/unsung), so the transition spans
     * roughly a third of the row rather than a hairline.
     */
    const val FEATHER_WIDTH_FRACTION = 1f / 3f

    /** Apple's sung text keeps 94% of the white (color/white_alpha_94). */
    const val SUNG_ALPHA = 0xF0

    /** How often the highlight is repainted while at least one overlay is alive. */
    /** One frame: the sweep is a continuous position, so it should move every frame. */
    const val TICK_MS = 16L

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
    data class CharacterRange(val start: Int, val end: Int, val progress: Float) {
        /** True once the sub-token has finished; kept for the older assertions. */
        val sung: Boolean get() = progress >= 1f
    }

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
        pairTokens(wordTexts, spans)?.let { tokens -> LyricTokenMerger.merge(tokens) }

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
        lineBegin: Long? = null,
        lineEnd: Long? = null,
    ): List<CharacterRange> {
        val ranges = ArrayList<CharacterRange>(group.parts.size)
        // A document may ship its readings without per-token timing (every span at 0/0). Spread the
        // line's own interval across the tokens in order so the row still sweeps instead of being
        // judged "finished" the moment position 0 >= end 0.
        val timed = group.parts.any { part -> part.end > part.begin }
        val fallback = if (!timed && lineBegin != null && lineEnd != null && lineEnd > lineBegin) {
            spread(lineBegin, lineEnd, group.parts.size)
        } else {
            null
        }
        var offset = 0
        group.parts.forEachIndexed { index, part ->
            val romanization = part.romanization.orEmpty()
            val end = offset + romanization.length
            val window = fallback?.getOrNull(index)
            ranges += CharacterRange(
                start = offset,
                end = end,
                progress = if (window != null) {
                    progressOf(window.first, window.second, positionMs)
                } else {
                    progressOf(part.begin, part.end, positionMs)
                },
            )
            offset = end
        }
        return ranges
    }

    /** [count] equal sub-intervals of [begin]..[end], used when a row carries no token timing. */
    fun spread(begin: Long, end: Long, count: Int): List<Pair<Long, Long>> {
        if (count <= 0 || end <= begin) return emptyList()
        val total = end - begin
        return (0 until count).map { index ->
            val from = begin + total * index / count
            val to = begin + total * (index + 1) / count
            from to to
        }
    }

    /**
     * How far [positionMs] has travelled through [begin]..[end], clamped to 0..1.
     *
     * This is what makes the row advance smoothly: a sub-token that is being sung right now is
     * already partly lit, so the colour moves character by character instead of jumping when the
     * token ends.
     */
    fun progressOf(begin: Long, end: Long, positionMs: Long?): Float {
        if (positionMs == null) return 0f
        // A degenerate window carries no information; treating it as "already finished" is what
        // made a whole merged word light up at position 0.
        if (end <= begin) return 0f
        if (positionMs <= begin) return 0f
        if (positionMs >= end) return 1f
        val span = (end - begin).coerceAtLeast(1L)
        return ((positionMs - begin).toFloat() / span).coerceIn(0f, 1f)
    }

    /** Clamps [range] to a text of [length] characters; null when nothing of it is left. */
    fun clip(range: CharacterRange, length: Int): CharacterRange? {
        val start = range.start.coerceIn(0, length)
        val end = range.end.coerceIn(0, length)
        if (end <= start) return null
        return CharacterRange(start = start, end = end, progress = range.progress)
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
        val lookup = runCatching {
            currentStore.textRow(currentStore.currentSongId(), lineText(wordTexts))
        }.getOrNull() ?: return
        val content = lookup.first.content ?: return
        val lineTiming = lookup.second
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
            val overlayPlacement = placement(containers, rowExtent) ?: return@forEach
            val overlay = addOverlay(parent, group, groupSlots.first(), overlayPlacement)
            state.overlays += Overlay(group, overlay, lineTiming?.begin, lineTiming?.end)
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
    ): SungRomanizationView {
        val template = source.pronunciation
        // Apple's lyric row is white at 94% alpha; the template's own colour can carry a theme
        // tint or a parent alpha, which is what made a fully-lit word read yellow instead of white.
        val brightColor = Color.argb(SUNG_ALPHA, 255, 255, 255)
        val overlay = SungRomanizationView(line.context)
        overlay.configure(
            text = group.romanization,
            textSizePx = template.textSize,
            typeface = template.typeface,
            brightColor = brightColor,
            dimColor = dimmedColor(brightColor),
            featherPx = 0f,
        )
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
        // The ramp is measured against the row, so hand the view the row width and its own offset.
        val row = overlay.view.parent as? View
        if (row != null && row.width > 0) {
            overlay.view.setLineMetrics(row.width.toFloat(), overlay.view.x)
        }
        val ranges = characterRanges(
            group = overlay.group,
            positionMs = positionMs,
            lineBegin = overlay.lineBegin,
            lineEnd = overlay.lineEnd,
        )
        // Apple dims every row that is not the one being sung, so a finished line must not keep
        // its highlight. Without line timing there is nothing to compare against, so the row is
        // treated as current and its own progress decides.
        val begin = overlay.lineBegin
        val end = overlay.lineEnd
        val current = begin == null || end == null ||
            (positionMs != null && positionMs >= begin && positionMs <= end)
        val text = overlay.group.romanization
        val tokens = if (!current) {
            overlay.group.parts.map { part -> TokenPaint(part.romanization.orEmpty(), 0f) }
        } else {
            ranges.mapNotNull { range ->
                val start = range.start.coerceIn(0, text.length)
                val end = range.end.coerceIn(start, text.length)
                if (end <= start) null else TokenPaint(text.substring(start, end), range.progress)
            }
        }
        overlay.view.setTokenProgress(tokens)
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
    /** One sub-token as the painter needs it: its text and how much of it already played. */
    data class TokenPaint(val text: String, val progress: Float)

    private class Overlay(
        val group: MergedPronunciation,
        val view: SungRomanizationView,
        val lineBegin: Long?,
        val lineEnd: Long?,
    )

    /**
     * One merged reading, painted by hand so the sweep can be sub-character.
     *
     * Apple's own rows are a TextView lit through a whole-view animator; a merged word has to show
     * several readings inside one string, so this draws the text twice - the dim colour once, then
     * the bright colour clipped to the lit width - and feathers the boundary with a few alpha steps
     * so the edge is a gradient instead of a hard cut.
     */
    private class SungRomanizationView(context: android.content.Context) : View(context) {
        private val label = Paint(Paint.ANTI_ALIAS_FLAG)
        private var labelText = ""
        private var brightColor = Color.WHITE
        private var dimColor = Color.GRAY
        private var featherPx = 0f
        private var tokens: List<TokenPaint> = emptyList()
        /** The whole lyric row's width: Apple's ramp spans a third of the ROW, not of the word. */
        private var lineWidthPx = 0f
        /** This overlay's left edge inside the row, so the ramp can be expressed in row pixels. */
        private var lineOffsetPx = 0f

        fun configure(
            text: String,
            textSizePx: Float,
            typeface: Typeface?,
            brightColor: Int,
            dimColor: Int,
            featherPx: Float,
        ) {
            this.labelText = text
            this.brightColor = brightColor
            this.dimColor = dimColor
            this.featherPx = featherPx
            label.textSize = textSizePx
            label.typeface = typeface
            invalidate()
        }

        /**
         * Each sub-token with its own 0..1 progress.
         *
         * Per-token progress is what keeps a pause inside a merged word: while the clock sits
         * between two tokens their progresses are 1 and 0, so the lit edge stops at the boundary
         * instead of gliding across it.
         */
        fun setTokenProgress(next: List<TokenPaint>) {
            if (next == tokens) return
            tokens = next
            invalidate()
        }

        /**
         * The row geometry the ramp is measured against.
         *
         * Apple's mask is one gradient across the whole row, so a short word like "tsuka" sits
         * entirely inside the ramp and fades across all five letters, while a long word only
         * crosses part of it. Measuring the ramp against this view instead made every word fade
         * over its own width, which is why a five-letter word showed a band barely one letter wide.
         */
        fun setLineMetrics(widthPx: Float, offsetPx: Float) {
            if (widthPx == lineWidthPx && offsetPx == lineOffsetPx) return
            lineWidthPx = widthPx
            lineOffsetPx = offsetPx
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (labelText.isEmpty() || width == 0) return
            val metrics = label.fontMetrics
            val baseline = height / 2f - (metrics.ascent + metrics.descent) / 2f
            val textWidth = label.measureText(labelText)
            val startX = (width - textWidth) / 2f

            // The lit edge is the pixel width of the sub-tokens that already played, plus the
            // fraction of the one currently playing — never a fraction of the whole string, so a
            // gap between two readings genuinely holds the edge still.
            var cursor = startX
            var litX = startX
            tokens.forEach { token ->
                val width = label.measureText(token.text)
                litX = when {
                    token.progress >= 1f -> cursor + width
                    token.progress > 0f -> cursor + width * token.progress
                    else -> litX
                }
                cursor += width
            }

            // One gradient over the whole string, exactly like Apple's mask: four stops that keep
            // the played part bright, ramp across the feather band, then hold the dim colour. Two
            // passes cannot express this — painting the bright text first and ramping on top of it
            // stacks white on white and reads as a hard edge.
            val span = (litX - startX).coerceIn(0f, textWidth)
            // Ramp over a third of the row; fall back to the word's own width when the row is not
            // measured yet (first frame, or a detached view).
            val rampBasis = if (lineWidthPx > 0f) lineWidthPx else textWidth
            val feather = rampBasis * FEATHER_WIDTH_FRACTION
            val fadeStart = ((span - feather) / textWidth).coerceIn(0f, 1f)
            val fadeEnd = (span / textWidth).coerceIn(0f, 1f)
            val colours: IntArray
            val stops: FloatArray
            if (fadeEnd <= fadeStart) {
                colours = intArrayOf(if (span >= textWidth) brightColor else dimColor, if (span >= textWidth) brightColor else dimColor)
                stops = floatArrayOf(0f, 1f)
            } else {
                colours = intArrayOf(brightColor, brightColor, dimColor, dimColor)
                stops = floatArrayOf(0f, fadeStart, fadeEnd, 1f)
            }
            label.color = brightColor
            label.shader = LinearGradient(
                startX,
                0f,
                startX + textWidth,
                0f,
                colours,
                stops,
                Shader.TileMode.CLAMP,
            )
            canvas.drawText(labelText, startX, baseline, label)
            label.shader = null
        }
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
