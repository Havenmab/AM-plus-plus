package dev.amenhancer.module.lyrics.online

/**
 * Normalizes the wrapping parentheses of a **background** (`ttm:role="x-bg"`)
 * lane's text.
 *
 * Apple strips the parentheses that wrap a background vocal when it displays the
 * small row ("(ボーン！)" renders as "ボーン！", "(jibun … michi)" as
 * "jibun … michi"), so the module's own background romanization must match that
 * shape. The document spreads the wrapper across the group: the opening `(`/`（`
 * sits at the very start of the group's **first** slice and the closing `)`/`）`
 * at the very end of its **last** slice (`(jibun`, `de`, `erabu`, `michi)`), or
 * both sides share one slice (`(boon)`).
 *
 * [stripGroup] removes exactly those wrapping parentheses and nothing else:
 *
 *  - the first slice loses a leading opening, the last slice a trailing closing
 *    (so a one-sided `(誰` or `道)` is normalized);
 *  - any slice that is itself a wrapper (`(boon)` → `boon`) loses both sides, so
 *    a single-slice group works the same way;
 *  - a slice whose closing is not the last character of the text (`Re:born
 *    (Bon!)`) and a middle slice are left exactly as they were — an internal
 *    pair is part of the word.
 *
 * [strip] is [stripGroup] for one piece of text (the concatenated line-level
 * value the app's gate reads). A value that is only a wrapper, or only
 * whitespace, yields an empty string — the caller then treats the row as having
 * no content rather than handing the app an empty value.
 *
 * The main line's lane is never passed through here: it stays strictly
 * word-level and untouched. Pure and JVM-tested.
 */
object BackgroundParenthesisPolicy {

    private const val OPEN = "(（"
    private const val CLOSE = ")）"

    /** One group of background slices, each normalized in place and in order. */
    fun stripGroup(texts: List<String>): List<String> = texts.mapIndexed { index, text ->
        stripSlice(text, first = index == 0, last = index == texts.lastIndex)
    }

    /** [stripGroup] for one piece of text (a single-slice group). */
    fun strip(text: String): String = stripSlice(text, first = true, last = true)

    private fun stripSlice(text: String, first: Boolean, last: Boolean): String {
        var value = text.trim()
        if (value.isEmpty()) return value
        val startsOpen = value.first() in OPEN
        val endsClose = value.last() in CLOSE
        value = when {
            // A slice that is itself a wrapper, e.g. the single-slice `(boon)`.
            startsOpen && endsClose && isWrapper(value) -> value.substring(1, value.length - 1)
            // The group's opening on its first slice: `(誰`, `(jibun`.
            first && startsOpen && !endsClose -> value.substring(1)
            // The group's closing on its last slice: `道)`, `michi)`.
            last && endsClose && !startsOpen && value.none { it in OPEN } ->
                value.substring(0, value.length - 1)
            else -> value
        }
        return value.trim()
    }

    /** True when [text]'s first parenthesis closes only at its very last character. */
    private fun isWrapper(text: String): Boolean {
        var depth = 0
        text.forEachIndexed { index, character ->
            when {
                character in OPEN -> depth += 1
                character in CLOSE -> {
                    depth -= 1
                    if (depth <= 0) return index == text.lastIndex
                }
            }
        }
        return false
    }
}
