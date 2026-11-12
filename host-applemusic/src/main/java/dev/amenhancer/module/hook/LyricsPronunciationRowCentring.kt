package dev.amenhancer.module.hook

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.model.ModuleSettings

/**
 * Centres the per-word romanization row inside its own container.
 *
 * Apple Music inflates every karaoke word as
 * `layout/lyrics_pronunciation_vertical_group` — a vertical `LinearLayout` that is
 * `wrap_content` wide, `fill_parent` tall and `baselineAligned=false` — and then adds two
 * children of its own: `layout/lyrics_word_karaoke` (the kanji/kana, `wrap_content` ×
 * `wrap_content`, gravity bottom) and `layout/lyrics_word_pronunciation` (the romanization,
 * `wrap_content` × `fill_parent`, gravity bottom).
 *
 * The container's width is `wrap_content`, so it takes the wider child's width and the narrower
 * child sits at the start edge. Setting `gravity = CENTER_HORIZONTAL` on the container centres both
 * children inside it, which is the presentation this hook installs.
 *
 * Nothing else is written. The children's own layout attributes, the container's
 * `layout_width`/`layout_height`/`baselineAligned`, the Flexbox parent, padding and text size all
 * stay Apple's; only the container's horizontal gravity changes.
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

    fun install() {
        LayoutInflationRegistry.register(LAYOUT_NAME) { view ->
            if (!isFeatureEnabled()) return@register
            apply(view)
        }
    }

    /** The module feature switch; off means Apple's per-word container is never touched. */
    fun isEnabled(settings: ModuleSettings): Boolean = settings.onlineLyricsTranslationEnabled

    /** Live read of [isEnabled]; an unreadable configuration keeps the callback a no-op. */
    private fun isFeatureEnabled(): Boolean =
        runCatching { isEnabled(TargetConfigClient.currentSettings()) }.getOrDefault(false)

    /**
     * Centres the inflated container horizontally. A root that is not the vertical `LinearLayout`
     * (Apple's layout is the only known shape, but a recycled or differently named root must never
     * be rewritten) is left alone, and an already-centred container is not written twice.
     */
    fun apply(root: View) {
        val group = root as? LinearLayout ?: return
        if (group.gravity == Gravity.CENTER_HORIZONTAL) return
        group.gravity = Gravity.CENTER_HORIZONTAL
    }
}
