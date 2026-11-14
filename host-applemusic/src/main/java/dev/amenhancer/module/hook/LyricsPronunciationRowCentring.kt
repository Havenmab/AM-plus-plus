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
 * Centres the per-word romanization row inside its own container, ignoring punctuation.
 *
 * Apple Music inflates every karaoke word as `layout/lyrics_pronunciation_vertical_group` — a
 * vertical `LinearLayout` that is `wrap_content` wide, `fill_parent` tall and
 * `baselineAligned=false` — and then adds two children of its own: `layout/lyrics_word_karaoke`
 * (the kanji/kana, `wrap_content` × `wrap_content`, gravity bottom) and
 * `layout/lyrics_word_pronunciation` (the romanization, `wrap_content` × `fill_parent`, gravity
 * bottom).
 *
 * The container's width is `wrap_content`, so it takes the wider child's width and the narrower
 * child sits at the start edge. Setting `gravity = CENTER_HORIZONTAL` centres both children inside
 * it — but the word can carry punctuation the reading never has, and a full-width mark is wide
 * enough to push the reading visibly off the kana: `キラキラ！` is much wider than `kiraki ra`, so
 * centring in that width leaves the reading half a mark to the right of the kana it belongs to. The
 * reading is therefore nudged by half the difference between the word's leading and trailing marks,
 * which puts it back under the kana itself.
 *
 * Nothing else is written. The children's own layout attributes, the container's
 * `layout_width`/`layout_height`/`baselineAligned`, the Flexbox parent, padding and text size all
 * stay Apple's; only the container's horizontal gravity and the reading's translation change.
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

    /**
     * Marks that belong to the lyric text but never to a reading.
     *
     * Covers the full-width forms Apple uses (！ ？ 。 、 「 」 …) as well as their half-width
     * counterparts, so a word like `だめ！` or `(NO)` is measured by its core. The katakana
     * long-vowel mark ー is deliberately absent: it lengthens a reading.
     */
    const val PUNCTUATION = "！？!?。、，,．.：:；;…‥・「」『』（）()【】〔〕〜～〝〟＂＇"

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
        // The text can already be there when this runs, can arrive a moment later, and changes again
        // whenever Apple rebinds the row: try immediately, once more after the first layout, and on
        // every later layout pass.
        alignToCoreText(group)
        group.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            (view as? ViewGroup)?.let(::alignToCoreText)
        }
        group.post { alignToCoreText(group) }
    }

    /** Shifts the reading so it sits under the word's core text rather than its marks. */
    /**
     * Aligns a whole-word reading with the kana it belongs to.
     *
     * Apple gives every kana its own container, and only the first container of a word carries the
     * reading — `ス` holds `suappu`, the `ア`/`ッ`/`プ` containers hold nothing. Centring inside one
     * container therefore cannot put that reading under the whole word: the container is only one
     * kana wide. The correction is computed across the container's siblings instead: the reading's
     * own centre is moved onto the centre of the kana run it reads, with pure-punctuation containers
     * (the `！` that trails `スアップ`) left out of the run, which is exactly the room they should
     * not occupy.
     */
    fun alignToCoreText(group: ViewGroup) {
        val reading = readingChild(group) ?: return
        // A container can also carry a hidden per-kana reading; only the shown one is positioned.
        if (reading.visibility != View.VISIBLE) return
        val readingText = reading.text?.toString().orEmpty()
        if (readingText.isEmpty()) return
        val parent = group.parent as? ViewGroup
        val shift = if (parent != null) readingShift(parent, group, reading) else 0f
        if (reading.translationX != shift) reading.translationX = shift
        android.util.Log.i(
            LOG_TAG,
            "align reading='$readingText' group=${group.left},${group.width}" +
                " reading=${reading.left},${reading.width}" +
                " parent=${parent?.javaClass?.simpleName}/${parent?.childCount}" +
                " shift=$shift",
        )
    }

    /**
     * How far [reading] has to move so its centre lands on the centre of the kana run that starts at
     * [group], in [parent]'s coordinates.
     *
     * The run ends at the next sibling that carries a reading of its own (the next word) or at the
     * end of the row, and containers whose word is nothing but punctuation are skipped.
     */
    fun readingShift(parent: ViewGroup, group: ViewGroup, reading: TextView): Float {
        val siblings = (0 until parent.childCount).map { parent.getChildAt(it) }
        val start = siblings.indexOf(group)
        if (start < 0) return 0f
        val run = ArrayList<View>(4)
        for (index in start until siblings.size) {
            val sibling = siblings[index] as? ViewGroup ?: break
            if (index > start) {
                // Only a reading Apple actually shows starts the next word. The containers of the
                // remaining kana keep a hidden per-kana reading of their own, and treating those as
                // word breaks cut every run down to its first container — which is why the measured
                // shift stayed at half a pixel.
                val next = readingChild(sibling)
                val shown = next != null &&
                    next.visibility == View.VISIBLE &&
                    !next.text.isNullOrEmpty()
                if (shown) break
            }
            val kana = wordChild(sibling)?.text?.toString().orEmpty()
            if (kana.isNotEmpty() && kana.any { it !in PUNCTUATION }) run += sibling
        }
        if (run.isEmpty()) return 0f
        val runLeft = run.first().left.toFloat()
        val runRight = run.last().left.toFloat() + run.last().width
        android.util.Log.i(
            LOG_TAG,
            "run start=$start size=${run.size} siblings=${siblings.size}" +
                " span=$runLeft..$runRight",
        )
        val runCentre = (runLeft + runRight) / 2f
        // The reading sits inside the first container; its centre in parent coordinates is the
        // container's left plus its own centre, because the reading's parent is that container.
        val readingCentre = run.first().left + reading.left + reading.width / 2f
        return runCentre - readingCentre
    }

    /**
     * The child holding the kana/kanji.
     *
     * Child order is not reliable: a container can carry more than two text views, and picking by
     * position then measured the reading against itself, where there is no punctuation to find. The
     * word is the child whose text is made of Japanese characters.
     */
    fun wordChild(group: ViewGroup): TextView? {
        val texts = textChildren(group)
        return texts.firstOrNull { it.visibility == View.VISIBLE && it.text?.any(::isJapanese) == true }
            ?: texts.firstOrNull { it.text?.any(::isJapanese) == true }
    }

    /** The child holding the romanization: the one whose text is Latin letters. */
    fun readingChild(group: ViewGroup): TextView? {
        val texts = textChildren(group)
        return texts.firstOrNull { it.visibility == View.VISIBLE && it.text?.any(::isLatin) == true }
            ?: texts.firstOrNull { it.text?.any(::isLatin) == true }
    }

    /** Japanese script, which a reading never uses. */
    fun isJapanese(character: Char): Boolean = when (character) {
        in '぀'..'ヿ', in '㐀'..'䶿', in '一'..'鿿', '々', '〆' -> true
        else -> false
    }

    /** Latin letters, which the word never uses. */
    fun isLatin(character: Char): Boolean = character in 'a'..'z' || character in 'A'..'Z'

    /** Every TextView inside the container, in tree order. */
    private fun textChildren(group: ViewGroup): List<TextView> {
        val found = ArrayList<TextView>(2)
        val pending = ArrayDeque<View>()
        pending.addLast(group)
        while (pending.isNotEmpty()) {
            val view = pending.removeFirst()
            if (view is TextView) found += view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) pending.addLast(view.getChildAt(index))
            }
        }
        return found
    }

    /**
     * Finds one of Apple's children by its resource entry name.
     *
     * `resources.getIdentifier(name, "id", package)` is not usable here: it resolves against the
     * resource package, which need not equal the application package, and when it misses it returns
     * 0 silently — which is exactly how the first version of this correction ended up applied to
     * nothing. Walking the (tiny) container tree and comparing entry names works regardless of how
     * the resources are packaged.
     */
    fun findChildByEntryName(group: ViewGroup, entryName: String): TextView? {
        val pending = ArrayDeque<View>()
        pending.addLast(group)
        while (pending.isNotEmpty()) {
            val view = pending.removeFirst()
            if (view is TextView && view.id != View.NO_ID) {
                val name = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
                if (name == entryName) return view
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) pending.addLast(view.getChildAt(index))
            }
        }
        return null
    }

    /**
     * Horizontal compensation, in pixels, for a word's punctuation.
     *
     * A trailing mark makes the container half a mark wider than the core, so the reading moves left
     * by that half; a leading mark moves it right. Marks on both sides cancel out, and a word that is
     * nothing but marks gets no correction at all.
     */
    fun coreShift(text: String, paint: TextPaint): Float {
        if (text.isEmpty()) return 0f
        val leading = text.takeWhile { it in PUNCTUATION }
        val trailing = text.takeLastWhile { it in PUNCTUATION }
        if (leading.length + trailing.length >= text.length) return 0f
        if (leading.isEmpty() && trailing.isEmpty()) return 0f
        val leadingWidth = if (leading.isEmpty()) 0f else paint.measureText(leading)
        val trailingWidth = if (trailing.isEmpty()) 0f else paint.measureText(trailing)
        return (leadingWidth - trailingWidth) / 2f
    }


}
