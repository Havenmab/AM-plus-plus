package dev.amenhancer.module.hook.lyricsource

import android.view.View
import java.lang.ref.WeakReference

/**
 * The two native rows of Apple Music's lyrics "more" popup, remembered while the
 * popup was open.
 *
 * The sheet cannot reach those rows itself: the popup closes as soon as the sheet
 * opens. Keeping weak references is still useful, because tapping a native row is
 * exactly what the sheet's 不显示 switch means — and a row's click listener only
 * flips Apple's own "translation/pronunciation selected" preference, which is a
 * global setting, so invoking it later performs the same action the user would
 * have performed in the menu.
 *
 * The order comes from the popup builder itself (on 1609 the lyrics-page click
 * listener adds the pronunciation row first and the translation row second), not
 * from the row text: the text is localized and alternates between 显示/隐藏 with
 * the current state.
 *
 * Everything fails closed: a collected view, a row from a build with a different
 * order, or a listener that throws all report false, and the caller keeps its
 * NOT_SUPPORTED answer together with the "use the system menu" hint.
 */
internal object LyricsSourceNativeMenu {

    private var pronunciationRow: WeakReference<View>? = null
    private var translationRow: WeakReference<View>? = null

    /** Remembers the rows of the popup that is being shown right now. */
    @Synchronized
    fun remember(pronunciation: View?, translation: View?) {
        pronunciationRow = pronunciation?.let(::WeakReference)
        translationRow = translation?.let(::WeakReference)
    }

    /** Clicks Apple's own 隐藏翻译 / 显示翻译 row; false when it is gone. */
    fun clickTranslationRow(): Boolean = click(translationRow)

    /** Clicks Apple's own 隐藏发音 / 显示发音 row; false when it is gone. */
    fun clickPronunciationRow(): Boolean = click(pronunciationRow)

    @Synchronized
    fun clear() {
        pronunciationRow = null
        translationRow = null
    }

    /**
     * One-line state of both rows for the device log. Diagnosis only: "Apple's
     * 显示发音 row is greyed out while our lane says the lane is available" is a
     * host-state question, and answering it needs the row's own enabled/clickable
     * flags next to our lane flags.
     */
    fun describe(): String =
        "pronunciationRow=" + describe(pronunciationRow) +
            " translationRow=" + describe(translationRow)

    private fun describe(reference: WeakReference<View>?): String {
        val view = reference?.get() ?: return "none"
        val text = (view as? android.widget.TextView)?.text?.toString()?.take(24) ?: "-"
        return buildString {
            append("text=").append(text)
            append(",enabled=").append(view.isEnabled)
            append(",clickable=").append(view.isClickable)
            append(",alpha=").append(view.alpha)
            append(",visible=").append(view.visibility)
            append(",shown=").append(view.isShown)
            append(",attached=").append(view.isAttachedToWindow)
        }
    }

    private fun click(reference: WeakReference<View>?): Boolean {
        val row = reference?.get() ?: return false
        return runCatching { row.performClick() }.getOrDefault(false)
    }
}
