package dev.amenhancer.module.hook

import android.text.TextPaint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.model.ModuleSettings
import java.util.Collections
import java.util.WeakHashMap

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
 * leading and trailing marks, and the reading is moved so its centre lands on the centre of what is
 * left over, in row coordinates. That covers a mark behind the word, in front of it, on both sides
 * or nowhere, and it is a no-op when the mark is its own container — Apple sometimes emits `?` as a
 * separate span, which is why `の?` already reads correctly on the device.
 *
 * A word whose kana were split over several sibling containers is measured the same way. When the
 * reading is far wider than the glyphs of its own container (a whole-word reading sitting in a
 * one-kana container, `suappu` over `ス`), the run is extended over the following containers until
 * one displays a reading of its own — that is the next word — and containers holding nothing but
 * marks are left out of the run, so `スアップ` and `スアップ！` measure the same. Only a whole-word
 * reading may reach past its own container; a per-kana reading (`の`+`no`, `マ`+`ma`) is never
 * dragged onto a longer run.
 *
 * Nothing else is written: the children's own attributes, the container's width/height/baseline
 * flags, its padding, the Flexbox parent and the text size all stay Apple's. Only the container's
 * horizontal gravity and the reading's translation change.
 *
 * Gating follows the other lyric presentation hooks (`DualPaneResourceHook.hookTabletLyricTextLayout`):
 * the registration is fixed and the callback decides per inflation. It applies only while the
 * module's own pronunciation/translation presentation switch is on — the same
 * [ModuleSettings.onlineLyricsTranslationEnabled] switch `NativeLyricsFeature` returns early on — and
 * is a no-op otherwise, so a module that is off leaves Apple's layout untouched.
 */
internal object LyricsPronunciationRowCentring {
    /** Log tag for the centring corrections; one line per layout pass per word. */
    const val LOG_TAG = "AMPP-Centring"

    /** The verified per-word container Apple adds both lyric children to. */
    const val LAYOUT_NAME = "lyrics_pronunciation_vertical_group"

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
    const val PUNCTUATION = "！？!?。、，,．.：:；;…‥・「」『』（）()【】〔〕〜～〝〟＂＇\"'“”‘’"

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

    fun install() {
        LayoutInflationRegistry.register(LAYOUT_NAME) { view ->
            if (!isFeatureEnabled()) return@register
            applyToInflatedLayout(view)
        }
    }

    /** The module feature switch; off means Apple's per-word container is never touched. */
    fun isEnabled(settings: ModuleSettings): Boolean = settings.onlineLyricsTranslationEnabled

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
     * Aligns a container's reading with the glyphs it reads.
     *
     * A container that shows no reading — the `！` of `スアップ！`, or a kana whose reading Apple put
     * in another container — is left exactly as Apple laid it out.
     */
    fun alignToCoreText(group: ViewGroup) {
        val reading = readingChild(group) ?: return
        // A container can also carry a hidden per-kana reading; only the shown one is positioned.
        if (reading.visibility != View.VISIBLE) return
        val readingText = reading.text?.toString().orEmpty()
        if (readingText.isEmpty()) return
        val row = findRowContainer(group) ?: group
        val shift = readingShift(row, group, reading)
        if (reading.translationX != shift) reading.translationX = shift
        android.util.Log.i(
            LOG_TAG,
            "align reading='$readingText' shift=$shift" +
                " group=${group.left},${group.width} reading=${reading.left},${reading.width}",
        )
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
        val leading = leadingMarks(firstText).length
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
        val trailing = trailingMarks(lastText).length
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
            if (text.isEmpty() || text.all { it in PUNCTUATION }) continue
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

    /** Where the centre of [reading] sits in [row]'s coordinates, before the correction. */
    fun readingCentre(row: ViewGroup, reading: TextView): Float =
        leftIn(row, reading) + reading.width / 2f

    /** The x, in [row]'s coordinates, reached after [count] characters of [chunks] from its start. */
    private fun advanceFromStart(row: ViewGroup, chunks: List<TextView>, count: Int): Float {
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
    private fun retreatFromEnd(row: ViewGroup, chunks: List<TextView>, count: Int): Float {
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
        if (view is ViewGroup && hasEntryName(view, LAYOUT_NAME)) {
            found.add(view)
            return
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) collectContainers(view.getChildAt(index), found)
        }
    }

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

    /** The marks [text] opens with; a reading never covers them. */
    fun leadingMarks(text: String): String = text.takeWhile { it in PUNCTUATION }

    /** The marks [text] closes with; a reading never covers them. */
    fun trailingMarks(text: String): String = text.takeLastWhile { it in PUNCTUATION }

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
