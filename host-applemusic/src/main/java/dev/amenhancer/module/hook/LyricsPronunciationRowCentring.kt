package dev.amenhancer.module.hook

import android.content.res.Resources
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
import java.util.concurrent.ConcurrentHashMap

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
    /** The verified per-word container Apple adds both lyric children to. */
    const val LAYOUT_NAME = "lyrics_pronunciation_vertical_group"

    /** Apple's ids for the two children of that container. */
    const val WORD_ID_NAME = "song_lyrics_word"
    const val PRONUNCIATION_ID_NAME = "pronunciation_word"

    /** Apple's application package, needed to resolve the ids above by name. */
    const val PACKAGE_NAME = "com.apple.android.music"

    /**
     * Marks that belong to the lyric text but never to a reading.
     *
     * Covers the full-width forms Apple uses (！ ？ 。 、 「 」 …) as well as their half-width
     * counterparts, so a word like `だめ！` or `(NO)` is measured by its core. The katakana
     * long-vowel mark ー is deliberately absent: it lengthens a reading.
     */
    const val PUNCTUATION = "！？!?。、，,．.：:；;…‥・「」『』（）()【】〔〕〜～〝〟＂＇"

    private val ids = ConcurrentHashMap<String, Int>()

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
        // The word's text arrives after inflation and changes again whenever Apple rebinds the row,
        // so the correction is recomputed on every layout pass instead of only once.
        group.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            (view as? ViewGroup)?.let(::alignToCoreText)
        }
        group.post { alignToCoreText(group) }
    }

    /** Shifts the reading so it sits under the word's core text rather than its marks. */
    fun alignToCoreText(group: ViewGroup) {
        val resources = group.resources
        val word = group.findViewById<TextView>(idOf(resources, WORD_ID_NAME)) ?: return
        val reading = group.findViewById<TextView>(idOf(resources, PRONUNCIATION_ID_NAME)) ?: return
        val shift = coreShift(word.text?.toString().orEmpty(), word.paint)
        if (reading.translationX != shift) reading.translationX = shift
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

    /** Resolves one of Apple's ids by name; ids are process-wide, so one lookup per name. */
    private fun idOf(resources: Resources, name: String): Int =
        ids.getOrPut(name) {
            runCatching { resources.getIdentifier(name, "id", PACKAGE_NAME) }.getOrDefault(0)
        }
}
