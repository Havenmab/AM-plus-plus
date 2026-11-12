package dev.amenhancer.module.lyrics.online

/**
 * One timed token of a lyric line: its text, the romanization that belongs to it, and the
 * interval the app highlights while it is sung.
 */
data class PronunciationToken(
    val text: String,
    val romanization: String?,
    val begin: Long,
    val end: Long,
)

/**
 * A group of tokens that is displayed as one unit.
 *
 * [parts] keeps the original tokens and their order, so the renderer can still highlight the
 * group token by token inside the merged string; [text] and [romanization] are the
 * concatenations the layout uses.
 */
data class MergedPronunciation(
    val text: String,
    val romanization: String,
    val begin: Long,
    val end: Long,
    val parts: List<PronunciationToken>,
) {
    /** True when this group actually merged more than one token. */
    val isMerged: Boolean get() = parts.size > 1
}

/**
 * Groups per-kana lyric tokens into word-like units so the romanization row reads as words
 * instead of one fragment per character, while every token keeps its own timing.
 *
 * The rules are deliberately narrow. Continuous katakana merges (loan words), continuous kanji
 * merges, continuous hiragana merges except where a single-character particle splits it, and
 * anything of a different class breaks the group. Okurigana after a kanji is **not** absorbed:
 * deciding `食べる` / `書く` needs a morphological dictionary, and a wrong merge shows a wrong
 * reading, which is worse than a slightly fragmented row.
 *
 * A group is abandoned (its tokens come back one per group) when any token has no
 * romanization, when the merged text would exceed [MAX_MERGED_CHARS], or when the tokens are
 * separated by a silence longer than [MAX_GAP_MS] — that usually means two different words were
 * adjacent in the layout only by accident.
 */
object LyricTokenMerger {

    /** Longer groups stop reading as one word and their centre drifts; six characters is the cap. */
    const val MAX_MERGED_CHARS = 6

    /** A gap this large inside a candidate group means two words, not one. */
    const val MAX_GAP_MS = 800L

    /** Single-character particles never join the hiragana run around them. */
    private val PARTICLE = setOf(
        'は', 'が', 'を', 'に', 'で', 'と', 'へ', 'の', 'も', 'や', 'か', 'ね', 'よ', 'な', 'さ',
        'ぞ', 'ぜ', 'わ', 'ん',
    )

    /**
     * Katakana particle spellings. Lyrics written entirely in katakana spell their particles
     * that way too, and a one-character token that is one of these is a particle, not the head
     * of a loan word: `マジカル` `ト` `リック` must not become `マジカルト`.
     *
     * Deliberately shorter than [PARTICLE]: `カ` `エ` `ヤ` `サ` `ワ` also open ordinary loan
     * words (`カード`, `エア`, `ヤング`), so listing them would split those words apart.
     */
    private val KATAKANA_PARTICLE = setOf(
        'ト', 'デ', 'ハ', 'ヲ', 'ニ', 'ガ', 'モ', 'ノ', 'ネ', 'ヨ',
    )

    /** Small kana, the sokuon and the long-vowel mark attach to whatever precedes them. */
    private val ATTACHING = setOf(
        'ァ', 'ィ', 'ゥ', 'ェ', 'ォ', 'ッ', 'ャ', 'ュ', 'ョ', 'ヮ', 'ヵ', 'ヶ', 'ー',
        'ぁ', 'ぃ', 'ぅ', 'ぇ', 'ぉ', 'っ', 'ゃ', 'ゅ', 'ょ', 'ゎ', 'ゕ', 'ゖ',
    )

    private enum class Kind { KATAKANA, HIRAGANA, KANJI, LATIN, DIGIT, OTHER }

    /**
     * The merged groups for [tokens], in order. Every input token appears exactly once, either
     * inside a merged group or as a group of its own, so the caller can rebuild the line from the
     * result without losing anything.
     */
    fun merge(
        tokens: List<PronunciationToken>,
        maxChars: Int = MAX_MERGED_CHARS,
        maxGapMs: Long = MAX_GAP_MS,
    ): List<MergedPronunciation> {
        if (tokens.isEmpty()) return emptyList()
        val groups = ArrayList<MergedPronunciation>(tokens.size)
        var current = ArrayList<PronunciationToken>(4)

        fun flush() {
            if (current.isEmpty()) return
            groups += build(current, maxChars, maxGapMs)
            current = ArrayList(4)
        }

        for (token in tokens) {
            val kind = kindOf(token.text)
            val attaches = token.text.length == 1 && token.text[0] in ATTACHING

            if (kind == Kind.OTHER && !attaches) {
                flush()
                groups += single(token)
                continue
            }
            if (current.isEmpty()) {
                current += token
                continue
            }
            val head = current.first()
            val headKind = kindOf(head.text)
            when {
                // Small kana / sokuon / long vowel follow whatever came before them.
                attaches -> current += token
                // A single-character particle always stands alone, in either script.
                kind == Kind.HIRAGANA && token.text.length == 1 && token.text[0] in PARTICLE -> {
                    flush()
                    groups += single(token)
                }
                kind == Kind.KATAKANA && token.text.length == 1 && token.text[0] in KATAKANA_PARTICLE -> {
                    flush()
                    groups += single(token)
                }
                else -> if (kind == headKind) {
                    current += token
                } else {
                    flush()
                    current += token
                }
            }
        }
        flush()
        return groups
    }

    /** Builds one group, or splits it back into singles when any guard rejects the merge. */
    private fun build(
        tokens: List<PronunciationToken>,
        maxChars: Int,
        maxGapMs: Long,
    ): List<MergedPronunciation> {
        if (tokens.size == 1) return listOf(single(tokens.single()))
        val text = tokens.joinToString("") { it.text }
        val romanization = tokens.joinToString("") { it.romanization.orEmpty() }
        val gap = tokens.zipWithNext().maxOfOrNull { (a, b) -> (b.begin - a.end).coerceAtLeast(0L) } ?: 0L
        val complete = tokens.all { token ->
            !token.romanization.isNullOrBlank() || (token.text.length == 1 && token.text[0] in ATTACHING)
        }
        val mergeable = complete && text.length <= maxChars && gap <= maxGapMs
        if (!mergeable) return tokens.map(::single)
        return listOf(
            MergedPronunciation(
                text = text,
                romanization = romanization,
                begin = tokens.first().begin,
                end = tokens.last().end,
                parts = tokens.toList(),
            ),
        )
    }

    private fun single(token: PronunciationToken) = MergedPronunciation(
        text = token.text,
        romanization = token.romanization.orEmpty(),
        begin = token.begin,
        end = token.end,
        parts = listOf(token),
    )

    private fun kindOf(text: String): Kind {
        if (text.isEmpty()) return Kind.OTHER
        var kind: Kind? = null
        for (ch in text) {
            val next = kindOfChar(ch)
            if (kind == null) kind = next else if (kind != next) return Kind.OTHER
        }
        return kind ?: Kind.OTHER
    }

    private fun kindOfChar(ch: Char): Kind = when (ch) {
        in '\u30A1'..'\u30FA', '\u30FC', '\u30FD', '\u30FE' -> Kind.KATAKANA
        in '\u3041'..'\u3096', '\u309D', '\u309E' -> Kind.HIRAGANA
        in '\u4E00'..'\u9FFF', in '\u3400'..'\u4DBF', '\u3005', '\u3006' -> Kind.KANJI
        in 'a'..'z', in 'A'..'Z', in '\uFF21'..'\uFF3A', in '\uFF41'..'\uFF5A' -> Kind.LATIN
        in '0'..'9', in '\uFF10'..'\uFF19' -> Kind.DIGIT
        else -> Kind.OTHER
    }
}
