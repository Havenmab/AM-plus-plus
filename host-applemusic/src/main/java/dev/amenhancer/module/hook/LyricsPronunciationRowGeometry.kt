package dev.amenhancer.module.hook

import android.text.TextPaint
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * The read side of Apple's per-word pronunciation container, plus the one place a reading is moved.
 *
 * Apple Music inflates every karaoke word as `layout/lyrics_pronunciation_vertical_group` — a
 * vertical `LinearLayout` that is `wrap_content` wide, `fill_parent` tall and
 * `baselineAligned=false` — and then adds the word's glyph views (`layout/lyrics_word_karaoke`) and,
 * below them, that word's romanization (`layout/lyrics_word_pronunciation`). One container is one
 * TTML span, so it can hold a whole word, a mark and a character (`「お`, `の？`) or nothing but
 * marks (`！`); a container of pure marks displays no reading and is never moved.
 *
 * **Centring is not decided here any more.** Where a reading sits under its kana is the host's own
 * pair alignment (`lyrics_pronunciation_vertical_group` → `LyricPronunciationPairAlignment`): it
 * centres the container, measures the punctuation at both ends and moves the narrower child, all on
 * its own layout callback. This module only *places* what the host cannot: a word the source split
 * per mora, which [LyricsPronunciationWordAlignment] splices into one word form out of an LLM
 * boundary plan. Everything else in this file is a measurement — [wordContainers], [shownReading],
 * [readingChild], [leftIn], [advanceFromStart], [retreatFromEnd] — which both the splice and the
 * tests read.
 *
 * ## One writer per reading
 *
 * Two routes can move a reading: the host's pair compensation (which writes
 * `offsetLeftAndRight` on a group's narrower child) and the splice (which places each fragment).
 * They must never both write the same view, because one writes the frame the other measures, and the
 * two would then correct each other on every layout pass. [applyReadingOffset] is the module's only
 * write, and [LyricsPronunciationWordAlignment.ownsGroup] tells the host which groups the splice has
 * taken over; `compensateLyricPronunciationEdges` asks it and returns before writing. One reading,
 * one writer, one function that touches a position.
 */
internal object LyricsPronunciationRowGeometry {
    /** Log tag for everything that moves a reading; one line per decision, never per layout pass. */
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
     * at the very last character therefore sees no mark at all.
     */
    const val EDGE_BLANKS = " \u00A0\u3000\t"

    /** Whether [character] is a mark or a blank — neither is text a reading covers. */
    fun isEdgeIgnorable(character: Char): Boolean =
        character in PUNCTUATION || character in EDGE_BLANKS

    /**
     * How much wider than its own container's glyphs a reading must be to read the whole word.
     *
     * A whole-word reading dwarfs the single kana it is attached to (`suappu` over `ス`), while a
     * per-kana reading is about as wide as its own kana (`no` over `の`, `ma` over `マ`). The
     * splice uses the same threshold to keep a per-kana reading from reaching into its neighbours.
     */
    const val WHOLE_WORD_RATIO = 1.8f

    /**
     * The module's single write of a reading's horizontal position.
     *
     * [targetLeft] is an absolute x in [row]'s coordinates, so both the splice's targets and any
     * later caller share one formula and repeated calls converge: [leftIn] never includes a
     * translation, and in `LAYOUT` mode it follows the frame that was moved.
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

    /** The run of marks and blanks [text] opens with; a reading never covers either. */
    fun leadingEdge(text: String): String = text.takeWhile(::isEdgeIgnorable)

    /** The run of marks and blanks [text] closes with; a reading never covers either. */
    fun trailingEdge(text: String): String = text.takeLastWhile(::isEdgeIgnorable)

    /**
     * The karaoke row [group] belongs to: Apple's foreground or background flexbox.
     *
     * Scoping a run to one row matters because a line inflates both rows from the same word list, so
     * a run that escaped its row would walk into the other row's copy of the same line. The line
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

    /** The reading [container] displays, or null when it shows none. */
    fun shownReading(container: ViewGroup): TextView? {
        val reading = readingChild(container) ?: return null
        if (reading.visibility != View.VISIBLE) return null
        return if (reading.text.isNullOrEmpty()) null else reading
    }

    /** Whether [view] is one of Apple's word views. */
    private fun isWord(view: TextView): Boolean = hasEntryName(view, WORD_ID_NAME)

    /** Whether [view] is one of Apple's pronunciation views. */
    private fun isPronunciation(view: TextView): Boolean =
        hasEntryName(view, PRONUNCIATION_ID_NAME) || hasEntryName(view, PRONUNCIATION_BG_ID_NAME)

    /** Latin letters, which a reading uses and kana do not. */
    private fun isLatin(character: Char): Boolean = character in 'a'..'z' || character in 'A'..'Z'

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
