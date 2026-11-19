package dev.amenhancer.module.hook

import kotlin.math.roundToInt

/**
 * Pure geometry and script decisions behind the pronunciation alignment seams.
 *
 * The Android seams (span centering, duet pivot, language requests) only gather
 * measurements; the arithmetic and the character decisions live here so they can be
 * exercised without a device or a Robolectric runtime.
 */
internal object PronunciationAlignmentMath {
    /**
     * Horizontal centre of the child inside its row, shifted by [correction] and clamped.
     *
     * The centre is always recomputed from the layout bounds, so a second layout pass never
     * adds to a previous offset.
     */
    fun centeredLeft(minLeft: Int, maxLeft: Int, correction: Float): Int {
        if (maxLeft <= minLeft) return minLeft
        val centered = minLeft + (maxLeft - minLeft) / 2
        return (centered + correction).roundToInt().coerceIn(minLeft, maxLeft)
    }

    /**
     * Half the difference between the leading and trailing edge advances.
     *
     * A larger leading advance shifts the body right in LTR, so the bias is positive there;
     * RTL paragraphs measure from the other side and flip the sign.
     */
    fun edgeBias(leading: Float, trailing: Float, rtl: Boolean): Float =
        (if (rtl) trailing - leading else leading - trailing) / 2f

    /** First index of the lyric body, skipping leading punctuation and symbols. */
    fun bodyStart(text: CharSequence): Int {
        var start = 0
        while (start < text.length) {
            val codePoint = Character.codePointAt(text, start)
            if (isBodyCharacter(codePoint)) break
            start += Character.charCount(codePoint)
        }
        return start
    }

    /**
     * End index of the lyric body, skipping trailing symbols while keeping combining marks
     * attached to the last letter (decomposed romanization relies on it).
     */
    fun bodyEnd(text: CharSequence): Int {
        var end = text.length
        while (end > 0) {
            val codePoint = Character.codePointBefore(text, end)
            if (isBodyCharacter(codePoint)) {
                while (end < text.length) {
                    val mark = Character.codePointAt(text, end)
                    if (Character.getType(mark) !in
                        Character.NON_SPACING_MARK.toInt()..Character.COMBINING_SPACING_MARK.toInt()
                    ) {
                        break
                    }
                    end += Character.charCount(mark)
                }
                return end
            }
            end -= Character.charCount(codePoint)
        }
        return 0
    }

    /** The host's first-letter direction predicate, without touching the TextView state. */
    fun isRtlText(text: CharSequence): Boolean {
        val first = text.indices.firstOrNull { Character.isLetter(Character.codePointAt(text, it)) }
            ?: return false
        return text[first] in '\u0590'..'\u06ff'
    }

    /** CJK app languages that request a Latin pronunciation lane. */
    fun isCjkLanguage(language: String): Boolean = when (language) {
        "zh", "yue", "ja", "ko" -> true
        else -> false
    }

    private fun isBodyCharacter(codePoint: Int): Boolean =
        Character.isLetterOrDigit(codePoint) || when (Character.getType(codePoint)) {
            Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt() -> true
            else -> false
        }
}
