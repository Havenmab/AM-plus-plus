package dev.amenhancer.module.lyrics.online

/**
 * Removes the lines providers ship that are not lyrics.
 *
 * Every platform mixes its own bookkeeping into the timed body: NetEase and QQ Music put a
 * block of `作词 / 作曲 / 编曲 / …` credits at 0-10 s, QQ Music opens both lanes with a
 * copyright notice (`QQ音乐享有本翻译作品的著作权`, `TME享有本翻译作品的著作权`,
 * `以下歌词翻译由…提供`), QQ opens with a `歌名 - 歌手` header line, Kugou injects an app
 * advertisement, instrumentals carry `纯音乐，请欣赏`, and empty translation slots arrive as
 * `//`. Left in place they render as lyric lines and push every later line out of sync.
 *
 * The filter is deliberately conservative: credit-shaped lines are only dropped while the
 * song is still starting (credits always sit in the first seconds), they must be short, and
 * the content rules match whole lines rather than substrings, so a real lyric that happens to
 * contain a colon survives.
 */
object LyricLineFilter {

    /** Credits never sit later than this; a later line with a colon is treated as a lyric. */
    const val CREDIT_WINDOW_MS = 20_000L

    /** A credit line is short; longer lines are treated as lyrics even inside the window. */
    const val CREDIT_MAX_LENGTH = 40

    /** Two identical lines closer than this are a provider duplicate. */
    const val DUPLICATE_WINDOW_MS = 1_500L

    /** How many leading lines are inspected to decide whether a credit block exists. */
    const val CREDIT_BLOCK_SCAN = 12

    /** How many credit-shaped lines inside that scan turn the lane into a "has credits" lane. */
    const val CREDIT_BLOCK_MIN = 3

    private val CREDIT_PREFIX = Regex(
        "^\\s*(作词|作曲|编曲|制作人|监制|出品|发行|OP|SP|词|曲|演唱|原唱|和声|和声编写|" +
            "吉他|贝斯|鼓|键盘|弦乐|录音|录音师|录音室|录音助理|人声录音|人声编辑|混音|母带|" +
            "统筹|企划|宣发|翻译|校对|LRC制作|歌词制作)\\s*[:：]",
        RegexOption.IGNORE_CASE,
    )

    /** Lines a platform adds about itself instead of the song. */
    private val PLATFORM_NOISE = listOf(
        "享有本翻译作品的著作权",
        "以下歌词翻译由",
        "本翻译作品",
        "纯音乐，请欣赏",
        "纯音乐,请欣赏",
        "此歌曲为没有填词",
        "此歌曲暂无歌词",
        "该歌曲暂无歌词",
        "歌词来自网络",
        "歌词由",
        "未经许可不得翻唱",
        "此歌曲由",
        "APP制作",
        "音乐创作如此简单",
    )

    /** Placeholder bodies a provider writes for a line it has no content for. */
    private val PLACEHOLDER = Regex("^[\\s/／\\\\|·.\\-—–_~～]*$")

    private val HEADER_SEPARATOR = Regex("\\s+[-–—]\\s+")

    /**
     * Filters every lane of [result]. [songTitle] and [songArtist], when known, let the
     * filter drop the `歌名 - 歌手` header line providers put above the credits.
     */
    fun filter(
        result: LyricsResult,
        songTitle: String? = null,
        songArtist: String? = null,
    ): LyricsResult {
        val original = filterLane(result.original, songTitle, songArtist)
        if (original.size == result.original.size &&
            result.translated.orEmpty().isEmpty() &&
            result.romanization.orEmpty().isEmpty()
        ) {
            return result
        }
        // Auxiliary lanes are keyed back onto the original lines by start time, so dropping a
        // translation line whose text was only noise is safe: the alignment never uses indices.
        return result.copy(
            original = original,
            translated = result.translated?.let(::filterAuxiliary),
            romanization = result.romanization?.let(::filterAuxiliary),
        )
    }

    private fun filterAuxiliary(lines: List<LyricsLine>): List<LyricsLine> =
        lines.filterNot { line ->
            val text = text(line)
            text.isEmpty() || isPlaceholder(text) || isPlatformNoise(text)
        }

    private fun filterLane(
        lines: List<LyricsLine>,
        songTitle: String?,
        songArtist: String?,
    ): List<LyricsLine> {
        // Providers put a whole credit block at the top; a single `作曲：` further down may be a
        // real lyric, so credits are only removed when the lane actually opens with a block.
        val creditBlock = lines.take(CREDIT_BLOCK_SCAN).count { line ->
            isCreditShape(text(line), line.start)
        } >= CREDIT_BLOCK_MIN
        val kept = ArrayList<LyricsLine>(lines.size)
        var previousText = ""
        var previousStart = Long.MIN_VALUE
        for (line in lines) {
            val text = text(line)
            if (text.isEmpty() || isPlaceholder(text) || isPlatformNoise(text)) continue
            if (creditBlock && isCreditShape(text, line.start)) continue
            if (isHeader(text, line.start, songTitle, songArtist)) continue
            if (text == previousText && line.start - previousStart <= DUPLICATE_WINDOW_MS) continue
            kept += line
            previousText = text
            previousStart = line.start
        }
        return kept
    }

    private fun text(line: LyricsLine): String =
        line.words.joinToString("") { it.text }.trim()

    private fun isPlaceholder(text: String): Boolean =
        text.isEmpty() ||
            PLACEHOLDER.matches(text) ||
            // A line with no letter, digit or CJK glyph is decoration (`♪`, `...`, `——`).
            text.none { it.isLetterOrDigit() }

    private fun isPlatformNoise(text: String): Boolean =
        PLATFORM_NOISE.any { marker -> text.contains(marker, ignoreCase = true) }

    private fun isCreditShape(text: String, startMs: Long): Boolean =
        startMs <= CREDIT_WINDOW_MS &&
            text.length <= CREDIT_MAX_LENGTH &&
            CREDIT_PREFIX.containsMatchIn(text)

    private fun isHeader(
        text: String,
        startMs: Long,
        songTitle: String?,
        songArtist: String?,
    ): Boolean {
        if (startMs > CREDIT_WINDOW_MS) return false
        val parts = HEADER_SEPARATOR.split(text)
        if (parts.size < 2) return false
        val left = parts.first().trim()
        val right = parts.last().trim()
        if (left.isEmpty() || right.isEmpty()) return false
        val title = songTitle?.trim().orEmpty()
        val artist = songArtist?.trim().orEmpty()
        if (title.isEmpty() && artist.isEmpty()) return false
        val leftMatches = title.isNotEmpty() && left.contains(title, ignoreCase = true)
        val rightMatches = artist.isNotEmpty() && right.contains(artist, ignoreCase = true)
        return leftMatches && rightMatches
    }
}