package dev.amenhancer.module.hook

import android.text.Editable
import android.text.TextPaint
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.model.ModuleSettings
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Centres a per-word romanization row under the kana it reads, ignoring punctuation.
 *
 * Apple Music inflates every karaoke word as `layout/lyrics_pronunciation_vertical_group` — a
 * vertical `LinearLayout` that is `wrap_content` wide, `fill_parent` tall and
 * `baselineAligned=false` — and then adds the word's glyph views (`layout/lyrics_word_karaoke`) and,
 * below them, that word's romanization (`layout/lyrics_word_pronunciation`). One container is one
 * TTML span, so it can hold a whole word, a mark and a character (`「お`, `の？`) or nothing but
 * marks (`！`); a container of pure marks displays no reading and is never moved.
 *
 * The container is `wrap_content`, so it takes the wider child's width and the narrower child is
 * centred in it. Setting `gravity = CENTER_HORIZONTAL` centres both rows — but the word can carry
 * punctuation the reading never has, so centring within the container puts the reading under the
 * marks as well. The device screenshots show exactly that: `スアップ！` carries its `suappu` half a
 * full-width mark right of the kana, and so do `ボーン！` and `よう」`; measuring the glyphs of those
 * screenshots puts the reading's centre on the centre of the whole container, not on the kana's.
 *
 * The correction is measured from the laid-out views instead of being assumed from a layout shape:
 * the container's glyph views are collected in tree order, their concatenated text is trimmed of
 * the marks *and blanks* at both ends, and the reading is moved so the middle of its own visible
 * text lands on the middle of what is left over, in row coordinates. Blanks count because Apple
 * appends the whitespace that follows a word to that word's text and to its reading alike, so a
 * mark is regularly not the last character: `up! `, `る？ `. The trim therefore covers a mark behind
 * the word, in front of it, on both sides, behind a blank or nowhere, and it is a no-op when the
 * mark is its own container — Apple sometimes emits `?` as a separate span, which is why `の?`
 * already reads correctly on the device.
 *
 * The correction is re-run whenever the reading gets its text or its size, not only when the
 * container is laid out: Apple sets both texts through futures, and a reading narrower than its word
 * does not move the container when it arrives, so a container-only trigger would never see it.
 *
 * A word whose kana were split over several sibling containers is measured the same way. When the
 * reading is far wider than the glyphs of its own container (a whole-word reading sitting in a
 * one-kana container, `suappu` over `ス`), the run is extended over the following containers until
 * one displays a reading of its own — that is the next word — and containers holding nothing but
 * marks are left out of the run, so `スアップ` and `スアップ！` measure the same. Only a whole-word
 * reading may reach past its own container; a per-kana reading (`の`+`no`, `マ`+`ma`) is never
 * dragged onto a longer run.
 *
 * A word the source split per mora is then spliced into one visual form by
 * [LyricsPronunciationWordAlignment]: `su` `a` `ppu` move flush against each other and the block is
 * centred on the whole word's glyphs, while every fragment keeps its own view — and therefore its own
 * `begin`/`end`, its own highlight and its own share of the row's gradient mask. That splice can only
 * be a placement: it writes no text, no width and no timing. It is tried first, and a container it
 * cannot help falls through to the correction described here, unchanged.
 *
 * Nothing else is written: the children's own attributes, the container's width/height/baseline
 * flags, its padding, the Flexbox parent and the text size all stay Apple's. Only the container's
 * horizontal gravity and the reading's position change, and the position has exactly one writer —
 * [applyReadingOffset], which is what keeps the correction and the splice from overwriting each other.
 *
 * Gating follows the other lyric presentation hooks (`DualPaneResourceHook.hookTabletLyricTextLayout`):
 * the registration is fixed and the callback decides per inflation. It applies only while
 * [ModuleSettings.lyricEffectsOptimizationEnabled] is on — the same 「歌词效果优化」 switch the native
 * line alignment and the other lyric effects use — and is a no-op otherwise. Centring a reading under
 * its glyph is an *effect*, not a reading source: with the switch off Apple's own layout is left
 * untouched, which is exactly how the upstream build behaves.
 */
internal object LyricsPronunciationRowCentring {
    /** Log tag for the centring corrections; one line per layout pass per word. */
    const val LOG_TAG = "AMPP-Centring"

    /** The verified per-word container Apple adds both lyric children to. */
    const val LAYOUT_NAME = "lyrics_pronunciation_vertical_group"

    /**
     * The id Apple puts on that container itself.
     *
     * The layout is named `lyrics_pronunciation_vertical_group`, but its root view carries the id
     * `lyrics_pronunciation_vertical_container` (`android:id="@id/lyrics_pronunciation_vertical_container"`
     * in `res/layout/lyrics_pronunciation_vertical_group.xml`). Looking for containers by the
     * layout name is therefore a comparison that can never match on the device, which is why the
     * row walk has to look for this id.
     */
    const val CONTAINER_ID_NAME = "lyrics_pronunciation_vertical_container"

    /** Apple's ids for the two children of that container. */
    const val WORD_ID_NAME = "song_lyrics_word"
    const val PRONUNCIATION_ID_NAME = "pronunciation_word"
    const val PRONUNCIATION_BG_ID_NAME = "pronunciation_bg_word"

    /** Apple's id for the line that holds the foreground and background karaoke rows. */
    const val LINE_CONTAINER_ID_NAME = "karaoke_lyrics_line_container"

    /**
     * Apple's ids for the two rows a line container holds.
     *
     * Every line inflates both a highlighted row and a background row from the same word list, each
     * with its own containers, so a word run must never be measured across the two.
     */
    val ROW_ID_NAMES: List<String> = listOf("karaoke_line_flexbox", "bg_line_flexbox")

    /**
     * Marks that belong to the lyric text but never to a reading.
     *
     * Covers the full-width forms Apple uses (！ ？ 。 、 「 」 …) as well as their half-width
     * counterparts, so a word like `だめ！` or `(NO)` is measured by its core. The katakana
     * long-vowel mark ー is deliberately absent: it lengthens a reading.
     */
    const val PUNCTUATION = "！？!?。、，,．.：:；;…‥・「」『』（）()【】〔〕〜～〝〟＂＇“”‘’"

    /**
     * Blanks that a reading never covers either.
     *
     * Apple appends the whitespace that follows a word to that word's own text (`A.java`, the word
     * loop copies the following whitespace entries into the same string) and does the same for the
     * reading, so a mark is regularly followed by a space: `up! `, `る？ `. A trim that only looks
     * at the very last character therefore sees no mark at all, and the reading keeps the mark's
     * half plus the blank's half of offset to the right.
     */
    const val EDGE_BLANKS = " \u00A0\u3000\t"

    /** Whether [character] is a mark or a blank — neither is text a reading covers. */
    fun isEdgeIgnorable(character: Char): Boolean =
        character in PUNCTUATION || character in EDGE_BLANKS

    /**
     * How much wider than its own container's glyphs a reading must be to read the whole word.
     *
     * A whole-word reading dwarfs the single kana it is attached to (`suappu` over `ス`), while a
     * per-kana reading is about as wide as its own kana (`no` over `の`, `ma` over `マ`). Without
     * this gate a per-kana reading would extend over the punctuation container that follows it and
     * be pushed by a mark that is not part of its word.
     */
    const val WHOLE_WORD_RATIO = 1.8f

    /**
     * Containers this hook already listens to.
     *
     * A view tag cannot be used for this: DataBinding stores its own binding object in the view's
     * tag and rejects anything else with "The tag for lyrics_pronunciation_vertical_group is
     * invalid", which crashes the lyrics screen. A weak set keys the bookkeeping off the view
     * itself and disappears together with it.
     */
    private val observed = Collections.synchronizedMap(WeakHashMap<View, Unit>())

    /** Readings this hook already listens to, keyed weakly by the view itself (see [watchReading]). */
    private val watchedReadings = Collections.synchronizedMap(WeakHashMap<TextView, Unit>())

    fun install() {
        LayoutInflationRegistry.register(LAYOUT_NAME) { view ->
            if (!isFeatureEnabled()) return@register
            applyToInflatedLayout(view)
        }
    }

    /** The 「歌词效果优化」 switch; off means Apple's per-word container is never touched. */
    fun isEnabled(settings: ModuleSettings): Boolean = settings.lyricEffectsOptimizationEnabled

    /** Live read of [isEnabled]; an unreadable configuration keeps the callback a no-op. */
    private fun isFeatureEnabled(): Boolean =
        runCatching { isEnabled(TargetConfigClient.currentSettings()) }.getOrDefault(false)

    /**
     * Centres the inflated container horizontally and keeps the reading under the kana. A root that
     * is not the vertical `LinearLayout` (Apple's layout is the only known shape, but a recycled or
     * differently named root must never be rewritten) is left alone, and an already-centred
     * container is not written twice.
     */
    fun applyToInflatedLayout(root: View) {
        val group = root as? LinearLayout ?: return
        if (group.gravity != Gravity.CENTER_HORIZONTAL) group.gravity = Gravity.CENTER_HORIZONTAL
        if (observed.put(group, Unit) != null) return
        // The text can already be there when this runs, can arrive a moment later (Apple sets it
        // through a future), and changes again whenever Apple rebinds the row: try immediately,
        // once more after the first layout, and on every later layout pass.
        alignToCoreText(group)
        group.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            (view as? ViewGroup)?.let(::alignToCoreText)
        }
        group.post { alignToCoreText(group) }
    }

    /**
     * Re-runs the correction when [reading] gets its text or its size.
     *
     * Apple sets the romanization through a text future, so the text can arrive after the
     * container's last layout pass — and the reading is usually narrower than the word it belongs
     * to, so the container's own bounds do not change when it arrives: a correction scheduled only
     * from the container's layout would never see the text at all and would leave the reading at
     * the container's centre, half a mark right of the kana.
     */
    private fun watchReading(group: ViewGroup, reading: TextView) {
        if (watchedReadings.put(reading, Unit) != null) return
        reading.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> alignToCoreText(group) }
        reading.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(text: Editable?) {
                // The new text is not measured yet; the container's layout pass may also still be
                // pending, so try now and again once the queue has run the layout.
                alignToCoreText(group)
                group.post { alignToCoreText(group) }
            }
        })
    }

    /**
     * Aligns a container's reading with the glyphs it reads.
     *
     * A container that shows no reading — the `！` of `スアップ！`, or a kana whose reading Apple put
     * in another container — is left exactly as Apple laid it out.
     */
    fun alignToCoreText(group: ViewGroup) {
        val reading = readingChild(group) ?: return
        // The reading's text can still be on its way, so listen to the reading before looking at
        // what it currently holds.
        watchReading(group, reading)
        // A container can also carry a hidden per-kana reading; only the shown one is positioned.
        if (reading.visibility != View.VISIBLE) return
        val readingText = reading.text?.toString().orEmpty()
        if (readingText.isEmpty()) return
        val row = findRowContainer(group) ?: group
        // A word the source split per mora is spliced into one visual form first. Its fragments keep
        // their own views, and therefore their own highlights and their own begin/end. A container
        // this cannot help — a lone kana, a word whose reading Apple already put in one container, a
        // source that is already word-level — falls through to the correction below, unchanged.
        if (LyricsPronunciationWordAlignment.alignRow(row, group)) return
        val shift = readingShift(row, group, reading)
        applyReadingOffset(
            row,
            reading,
            LyricsPronunciationWordAlignment.shiftMode,
            leftIn(row, reading) + shift,
        )
        if (VERBOSE_CENTRING_LOG) logAlignOnce(
            "align reading='$readingText' shift=$shift" +
                " mode=${LyricsPronunciationWordAlignment.shiftMode}" +
                " group=${group.left},${group.width} reading=${reading.left},${reading.width}",
            readingText,
            shift,
        )
    }

    /**
     * The one place a reading's horizontal position is written.
     *
     * [targetLeft] is an absolute x in [row]'s coordinates — [readingShift]'s value added to the
     * reading's own left — so both callers share one formula and repeated calls converge: [leftIn]
     * never includes a translation, and in `LAYOUT` mode it follows the frame that was moved.
     *
     * `TRANSLATION` writes the draw-time offset; `LAYOUT` moves the reading's real frame, for a mask
     * that reads `getLeft()` rather than `getX()` (see [LyricsPronunciationWordAlignment.ShiftMode]).
     */
    fun applyReadingOffset(
        row: ViewGroup,
        reading: TextView,
        mode: LyricsPronunciationWordAlignment.ShiftMode,
        targetLeft: Float,
    ) {
        val current = leftIn(row, reading).toFloat()
        val offset = targetLeft - current
        when (mode) {
            LyricsPronunciationWordAlignment.ShiftMode.TRANSLATION -> {
                if (reading.translationX != offset) reading.translationX = offset
            }
            LyricsPronunciationWordAlignment.ShiftMode.LAYOUT -> {
                val delta = offset.roundToInt()
                if (delta != 0) reading.offsetLeftAndRight(delta)
            }
        }
    }

    /**
     * Re-runs the correction for every container that is still inflated.
     *
     * A plan arriving from the model changes how the rows of a song should be grouped, and the rows
     * that are already laid out were grouped before it arrived. Walking the containers this hook
     * already listens to is the cheapest way to bring them in line: the correction is idempotent, so a
     * row whose target did not move is not written again, and a row that is no longer inflated has
     * already fallen out of the weak map.
     *
     * Called on the main thread; it never touches the network and never waits for one.
     */
    fun reapplyObserved() {
        val containers = synchronized(observed) { observed.keys.toList() }
        for (view in containers) {
            val group = view as? ViewGroup ?: continue
            runCatching { alignToCoreText(group) }
        }
    }


    /**
     * Centre-row traces on logcat ("align reading=... shift=..."), off by default.
     *
     * The correction re-runs on every layout pass; the user-facing trace is the model chain under
     * AMPP-Bndry. Flip this to true for one build when the correction itself needs tracing.
     */
    private const val VERBOSE_CENTRING_LOG = false

    /** How many remembered log states the fallback trace keeps before it is rebuilt. */
    private const val LOG_MEMORY_LIMIT = 256

    /** Last shift printed per reading text, so a reading that has not moved is not logged again. */
    private val loggedAligns = Collections.synchronizedMap(HashMap<String, String>())

    /**
     * Prints one fallback line per (reading, shift), and never for a reading that stays put.
     *
     * The correction re-checks every reading on each layout pass; a zero shift means it did nothing,
     * and the same shift on the same text means nothing has changed since it was last reported.
     */
    fun logAlignOnce(summary: String, readingText: String, shift: Float) {
        if (shift == 0f) return
        if (loggedAligns.size > LOG_MEMORY_LIMIT) loggedAligns.clear()
        if (loggedAligns.put(readingText, shift.toString()) == shift.toString()) return
        android.util.Log.i(LOG_TAG, summary)
    }

    /**
     * How far [reading] must move so its centre lands on the centre of the glyphs it reads.
     *
     * [row] is both the coordinate space and the scope the run may be extended in. Everything is
     * measured from the laid-out views, so the arithmetic holds for one glyph view or several, and
     * for marks inside a container or inside containers of their own.
     */
    fun readingShift(row: ViewGroup, group: ViewGroup, reading: TextView): Float {
        // A container that has not been laid out yet has no widths to measure against; the first
        // layout pass calls back in with real geometry.
        if (reading.width <= 0) return 0f
        val span = coreSpan(row, group, reading) ?: return 0f
        return span.centre - readingCentre(row, reading)
    }

    /**
     * The stretch of the row that [reading] reads, or null when there is nothing to centre on.
     *
     * The stretch starts at the container's first glyph and ends at the last glyph of the word,
     * with that word's leading and trailing marks cut off. A whole-word reading also reaches into
     * the containers after its own (see [lastContainerOf]); a container that holds nothing but
     * marks has no core, so nothing moves.
     */
    fun coreSpan(row: ViewGroup, group: ViewGroup, reading: TextView): CoreSpan? {
        val containers = wordContainers(row)
        val start = containers.indexOf(group)
        val ownGlyphs = wordChunks(group, reading)
        if (ownGlyphs.isEmpty()) return null
        val firstText = ownGlyphs.fold("") { text, glyph -> text + glyph.text.toString() }
        val leading = leadingEdge(firstText).length
        // A container whose glyphs are nothing but marks shows no reading of its own and has no core
        // text to centre on.
        if (leading >= firstText.length) return null
        val lastContainer = if (start >= 0) lastContainerOf(containers, start, group, reading) else start
        val lastGlyphs = if (lastContainer < 0 || lastContainer == start) {
            ownGlyphs
        } else {
            wordChunks(containers[lastContainer], reading = null)
        }
        if (lastGlyphs.isEmpty()) return null
        val lastText = lastGlyphs.fold("") { text, glyph -> text + glyph.text.toString() }
        val trailing = trailingEdge(lastText).length
        val left = advanceFromStart(row, ownGlyphs, leading)
        val right = retreatFromEnd(row, lastGlyphs, trailing)
        if (right <= left) return null
        return CoreSpan(left, right)
    }

    /**
     * The index of the container that ends the word [reading] belongs to.
     *
     * Only a whole-word reading reaches past its own container: the remaining containers of its word
     * carry no reading, so the walk passes over them until one displays a reading of its own — that
     * is where the next word starts — or the row ends. Containers holding nothing but marks are left
     * out of the run, so the `！` after `スアップ` changes nothing.
     */
    private fun lastContainerOf(
        containers: List<ViewGroup>,
        start: Int,
        group: ViewGroup,
        reading: TextView,
    ): Int {
        val glyphs = wordChunks(group, reading)
        if (glyphs.isEmpty()) return start
        val wordWidth = glyphs.fold(0f) { total, glyph -> total + glyph.width }
        val wholeWord = wordWidth > 0f && reading.width > wordWidth * WHOLE_WORD_RATIO
        if (!wholeWord) return start
        var last = start
        for (index in start + 1 until containers.size) {
            val container = containers[index]
            if (shownReading(container) != null) break
            val next = wordChunks(container, reading = null)
            val text = next.fold("") { acc, glyph -> acc + glyph.text.toString() }
            if (text.isEmpty() || text.all(::isEdgeIgnorable)) continue
            last = index
        }
        return last
    }

    /** The reading [container] displays, or null when it shows none. */
    fun shownReading(container: ViewGroup): TextView? {
        val reading = readingChild(container) ?: return null
        if (reading.visibility != View.VISIBLE) return null
        return if (reading.text.isNullOrEmpty()) null else reading
    }

    /**
     * Where the middle of the reading's visible text sits in [row]'s coordinates, before the
     * correction.
     *
     * The marks and blanks at the reading's own ends are not text it shows (Apple appends the
     * following whitespace to it as well), so they are left out of the middle; otherwise the box
     * measured here would be wider than the text the user sees and the correction would be short by
     * half of that padding.
     */
    fun readingCentre(row: ViewGroup, reading: TextView): Float {
        val text = reading.text?.toString().orEmpty()
        val left = leftIn(row, reading).toFloat()
        val right = left + reading.width
        val leading = leadingEdge(text).length
        val trailing = trailingEdge(text).length
        if (text.isEmpty() || leading + trailing >= text.length) return (left + right) / 2f
        val visibleLeft = left + advanceOf(reading, 0, leading)
        val visibleRight = right - advanceOf(reading, text.length - trailing, text.length)
        return (visibleLeft + visibleRight) / 2f
    }

    /** The x, in [row]'s coordinates, reached after [count] characters of [chunks] from its start. */
    fun advanceFromStart(row: ViewGroup, chunks: List<TextView>, count: Int): Float {
        var remaining = count
        for (chunk in chunks) {
            val text = chunk.text?.toString().orEmpty()
            if (remaining == 0) return leftIn(row, chunk).toFloat()
            if (remaining < text.length) return leftIn(row, chunk) + advanceOf(chunk, 0, remaining)
            remaining -= text.length
            if (remaining == 0) return (leftIn(row, chunk) + chunk.width).toFloat()
        }
        val last = chunks.last()
        return (leftIn(row, last) + last.width).toFloat()
    }

    /** The x, in [row]'s coordinates, reached after [count] characters of [chunks] from its end. */
    fun retreatFromEnd(row: ViewGroup, chunks: List<TextView>, count: Int): Float {
        var remaining = count
        for (chunk in chunks.asReversed()) {
            val text = chunk.text?.toString().orEmpty()
            if (remaining == 0) return (leftIn(row, chunk) + chunk.width).toFloat()
            if (remaining < text.length) {
                return leftIn(row, chunk) + chunk.width - advanceOf(chunk, text.length - remaining, text.length)
            }
            remaining -= text.length
            if (remaining == 0) return leftIn(row, chunk).toFloat()
        }
        return leftIn(row, chunks.first()).toFloat()
    }

    /** A glyph view's advance over a slice of its text; the whole view when the slice is its text. */
    private fun advanceOf(chunk: TextView, start: Int, end: Int): Float {
        val text = chunk.text?.toString().orEmpty()
        if (start <= 0 && end >= text.length) return chunk.width.toFloat()
        val paint: TextPaint? = runCatching { chunk.paint }.getOrNull()
        if (paint == null) return 0f
        return runCatching { paint.measureText(text, start, end) }.getOrDefault(0f)
    }

    /**
     * [view]'s left edge in [ancestor]'s coordinates: the sum of the lefts on the path between.
     *
     * Apple nests a word's glyph views one wrapper deep inside the container and may nest the
     * containers themselves inside Apple's non-breaking span, so a bare `view.left` would drop every
     * wrapper's offset between the two views being compared.
     */
    fun leftIn(ancestor: View, view: View): Int {
        var offset = 0
        var current: View? = view
        while (current != null && current !== ancestor) {
            offset += current.left
            current = current.parent as? View
        }
        return if (current === ancestor) offset else 0
    }

    /**
     * The karaoke row [group] belongs to: Apple's foreground or background flexbox.
     *
     * Scoping the run to one row matters because a line inflates both rows from the same word list,
     * so a run that escaped its row would walk into the other row's copy of the same line. The line
     * container and then the container's own parent are the fallbacks for a build whose flexboxes
     * carry no readable id.
     */
    fun findRowContainer(group: ViewGroup): ViewGroup? {
        var line: ViewGroup? = null
        var current: View? = group
        while (current != null) {
            val view: View = current
            val parent = view as? ViewGroup
            if (parent != null) {
                if (ROW_ID_NAMES.any { hasEntryName(parent, it) }) return parent
                if (line == null && hasEntryName(parent, LINE_CONTAINER_ID_NAME)) line = parent
            }
            current = view.parent as? View
        }
        return line ?: group.parent as? ViewGroup
    }

    /** Every karaoke word container inside [row], in tree order, without descending into one. */
    fun wordContainers(row: ViewGroup): List<ViewGroup> {
        val found = ArrayList<ViewGroup>(8)
        collectContainers(row, found)
        if (found.isNotEmpty()) return found
        // Fallback for a row whose ids carry no readable entry name (a differently packaged build,
        // or a test double): its own children are then the karaoke containers.
        return (0 until row.childCount).mapNotNull { row.getChildAt(it) as? ViewGroup }
    }

    private fun collectContainers(view: View, found: MutableList<ViewGroup>) {
        if (view is ViewGroup && isContainer(view)) {
            found.add(view)
            return
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) collectContainers(view.getChildAt(index), found)
        }
    }

    /**
     * Whether [view] is one of Apple's per-word containers.
     *
     * The id is the container's own (`lyrics_pronunciation_vertical_container`); the layout's name
     * is accepted as well so a differently built tree — and the test doubles — still resolve.
     */
    fun isContainer(view: View): Boolean =
        hasEntryName(view, CONTAINER_ID_NAME) || hasEntryName(view, LAYOUT_NAME)

    /**
     * The glyph views of the word [group] holds — its kana, kanji or letters, in reading order.
     *
     * Everything visible and non-empty that is not the reading itself is a glyph view. That covers
     * Apple's shapes without guessing at them: one word view holding `スアップ！`, one view per
     * character, and a Latin word whose reading is Latin as well.
     */
    fun wordChunks(group: ViewGroup, reading: TextView?): List<TextView> =
        textChildren(group).filter { child ->
            child !== reading &&
                child.visibility == View.VISIBLE &&
                !child.text.isNullOrEmpty() &&
                !isPronunciation(child)
        }

    /**
     * The child holding the romanization, or null when the container shows none.
     *
     * Apple gives the reading its own id, so the id is the answer on the device. A container built
     * without readable ids (a test double) is read by shape instead: a per-word container holds its
     * glyphs plus at most one Latin child below them, and a glyph-only container is not a reading.
     */
    fun readingChild(group: ViewGroup): TextView? {
        val named = textChildren(group).filter { isPronunciation(it) }
        if (named.isNotEmpty()) {
            // Apple can inflate the hidden per-kana reading first, so the shown one wins.
            return named.firstOrNull { it.visibility == View.VISIBLE } ?: named.first()
        }
        val texts = textChildren(group).filter {
            it.visibility == View.VISIBLE && !it.text.isNullOrEmpty() && !isWord(it)
        }
        if (texts.size < 2) return null
        return texts.lastOrNull { view -> view.text?.any(::isLatin) == true }
    }

    /** Whether [view] is one of Apple's word views. */
    private fun isWord(view: TextView): Boolean = hasEntryName(view, WORD_ID_NAME)

    /** Whether [view] is one of Apple's pronunciation views. */
    private fun isPronunciation(view: TextView): Boolean =
        hasEntryName(view, PRONUNCIATION_ID_NAME) || hasEntryName(view, PRONUNCIATION_BG_ID_NAME)

    /** Latin letters, which a reading uses and kana do not. */
    fun isLatin(character: Char): Boolean = character in 'a'..'z' || character in 'A'..'Z'

    /**
     * Whether [view] carries the given resource entry name.
     *
     * `resources.getIdentifier(name, "id", package)` is not usable here: it resolves against the
     * resource package, which need not equal the application package, and when it misses it returns
     * 0 silently — which is exactly how the first version of this correction ended up applied to
     * nothing. Comparing the entry name of the view's own id works regardless of how the resources
     * are packaged.
     */
    fun hasEntryName(view: View, name: String): Boolean {
        if (view.id == View.NO_ID) return false
        val entry = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
        return entry == name
    }

    /** The run of marks and blanks [text] opens with; a reading never covers either. */
    fun leadingEdge(text: String): String = text.takeWhile(::isEdgeIgnorable)

    /** The run of marks and blanks [text] closes with; a reading never covers either. */
    fun trailingEdge(text: String): String = text.takeLastWhile(::isEdgeIgnorable)

    /** A stretch of one word in row coordinates: its first glyph's left, its last glyph's right. */
    class CoreSpan(val left: Float, val right: Float) {
        val centre: Float get() = (left + right) / 2f

        override fun toString(): String = "$left..$right"
    }

    /** Every TextView inside [group], in tree order. */
    private fun textChildren(group: ViewGroup): List<TextView> {
        val found = ArrayList<TextView>(2)
        collectTextChildren(group, found)
        return found
    }

    private fun collectTextChildren(view: View, found: MutableList<TextView>) {
        if (view is TextView) found.add(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) collectTextChildren(view.getChildAt(index), found)
        }
    }
}
