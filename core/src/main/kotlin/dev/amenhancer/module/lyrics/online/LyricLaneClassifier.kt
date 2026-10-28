package dev.amenhancer.module.lyrics.online

import java.util.Locale

/**
 * 判定一行文本属于哪条车道，并在两条车道错位时把它们重新归位。
 *
 * 第三方歌词源只有两个辅助字段：`trans`/`tlyric`（译文）与
 * `roma`/`romalrc`（发音）。字段的名字并不等于内容：QQ 音乐对日语歌的
 * `trans` 经常是**假名读音或罗马音**（实测 Lemon 的 `trans` 是 `[kana:…]` +
 * 罗马音），反过来「音译」字段里也偶尔混进中文。任何把这两个字段直接当成
 * translation / romanization 写进 overlay 或合并文档的代码，都会让页面把罗马音当
 * 译文显示、把译文当音译显示；同一行罗马音还会在两条车道里各出现一次，菜单的
 * 「显示翻译／显示发音」状态随之错乱。
 *
 * 这里是那条规则的唯一实现：**translation 车道只放中文译文，romanization 车道
 * 只放假名／罗马音**，其余内容两条车道都不收。纯函数、无 Android 依赖，core 的
 * jvmTest 直接覆盖；在线抓取（[OnlineTranslationExtraction]）、合并发布
 * （[OnlineTranslationEnrichment]）与重启恢复（`LyricsSourceOverlayPersistence`）
 * 都调用同一份判定，所以任何一条路径都不会再自行决定「这算译文还是音译」。
 */
object LyricLaneClassifier {

    /** 一行文本的脚本归属：车道判定的唯一依据。 */
    enum class LaneKind {
        /** 空白、纯标点或纯数字：不是任何车道的内容。 */
        EMPTY,

        /** 只有汉字、没有假名：中文译文（或中文原文）。 */
        CHINESE,

        /** 只有假名：日语读音，属于 romanization 车道。 */
        KANA,

        /** 只有拉丁字母且拼写像日语罗马音：属于 romanization 车道。 */
        ROMANIZATION,

        /** 只有拉丁字母但不像罗马音：英文（或其他拉丁语）原文，两条车道都不该收。 */
        LATIN,

        /** 多脚本混排：汉字＋假名是日语原文，汉字＋拉丁是带外文的译文。 */
        MIXED,

        /** 其他文字（谚文、西里尔、希腊、泰文…）：两条车道都不该收。 */
        OTHER,
    }

    /** 一行归位后的两条车道；null 表示这条车道不该收这一行。 */
    data class LaneContent(
        val translation: String? = null,
        val romanization: String? = null,
    ) {
        val isEmpty: Boolean get() = translation == null && romanization == null
    }

    /**
     * 车道行：(begin, end, text) 是两条车道共用的行键，[translation] /
     * [romanization] 是这两条车道为它各自记下的内容。与
     * `LyricsSourceOverlayPersistence` 落盘的两条数组一一对应，所以旧记录可以
     * 原样搬进来重新归位。
     */
    data class LaneRow(
        val begin: Long = 0L,
        val end: Long = 0L,
        val text: String = "",
        val translation: String? = null,
        val romanization: String? = null,
    )

    /**
     * 一行的脚本判定。
     *
     * - 先剥掉 QQ 的伪译文标签：QRC/LRC 译文正文里的 `[kana:…]`、`[roma:…]`
     *   是**读音标注**，不是译文；整行只有一个标签时按标签内容判定（`[kana:れもん]`
     *   是假名）。
     * - 汉字＋假名 = [LaneKind.MIXED]（日语原文，既不是译文也不是罗马音）。
     * - 只有拉丁字母时再看拼写：能整行拆成「辅音丛＋元音」的日语音节就是
     *   [LaneKind.ROMANIZATION]（`kimi no na wa`、`shitauchi`、`di yi`），否则是
     *   [LaneKind.LATIN]（`Hello world`、`Second line`）。
     */
    fun classify(text: String?): LaneKind {
        val visible = visibleLaneText(text)
        val counts = countScripts(visible) ?: return LaneKind.EMPTY
        return when {
            counts.other > 0 -> LaneKind.OTHER
            counts.han > 0 && counts.kana > 0 -> LaneKind.MIXED
            counts.han > 0 && counts.latin > 0 -> LaneKind.MIXED
            counts.han > 0 -> LaneKind.CHINESE
            counts.kana > 0 -> LaneKind.KANA
            counts.latin > 0 ->
                if (looksLikeRomanization(visible)) LaneKind.ROMANIZATION else LaneKind.LATIN
            else -> LaneKind.EMPTY
        }
    }

    /**
     * 这一行能不能当**中文译文**发布：必须含汉字，且不含其他文字；假名只允许出现在
     * **括注**里。
     *
     * 汉字＋拉丁（`感谢你曾来过 (Thanks)`）仍算译文——带外文词的译文很常见；
     * 假名在正文里则不算——`君の名は` 是日语原文，把它写进译文车道正是要修的错位。
     *
     * 但括注是另一回事：译者常在译文后补上原文或专名，
     * `别回头 (振り向かないで)`、`和 ミク 一起唱` 这类行正文是中文。整行丢弃会让
     * 这首诗的译文少几行，只有这类行的歌更会因此 `translationLines=0`、整个候选被
     * `reason=no_meaningful_lane` 排掉。所以规则是：**汉字在括注之外仍然存在、括注
     * 之外没有假名**时才收——`夢ならば`（假名在正文）、`（振り向かないで）`（括注
     * 外没有汉字）都仍然不算译文。标点（`，。！？「」`）从来不是字母，不影响判定，
     * 中文译文带日文标点本来就能通过。
     */
    fun isTranslationLike(text: String?): Boolean {
        val visible = visibleLaneText(text)
        val counts = countScripts(visible) ?: return false
        if (counts.han == 0 || counts.other > 0) return false
        if (counts.kana == 0) return true
        val outside = BRACKETED_SEGMENT.replace(visible, " ")
        val outsideCounts = countScripts(outside) ?: return false
        return outsideCounts.han > 0 && outsideCounts.kana == 0 && outsideCounts.other == 0
    }

    /** 这一行能不能当**罗马音**发布：只有假名，或只有像罗马音的拉丁字母。 */
    fun isRomanizationLike(text: String?): Boolean = when (classify(text)) {
        LaneKind.KANA, LaneKind.ROMANIZATION -> true
        else -> false
    }

    /**
     * 带原文判断的罗马音版本：假名／罗马音还必须确实是**这一行**的读音。
     *
     * - 原文是拉丁文字（英文歌）时恒为 false：同脚本的「罗马音」只是原文的重复，
     *   [RomanizationPolicy] 也是这么判的，英文歌因此永远没有发音车道。
     * - 与原文逐字相同（去标点、忽略大小写）时也为 false：那是原文被抄进了发音
     *   车道，不是读音。
     */
    fun isRomanizationLike(text: String?, original: String?): Boolean {
        if (!isRomanizationLike(text)) return false
        if (original == null) return true
        if (!hasNonLatinLetter(original)) return false
        return comparableText(text) != comparableText(original)
    }

    /**
     * 把一行的两条车道重新归位。
     *
     * 规则按顺序生效，每一条都只搬内容、不造内容：
     *
     * 1. 两条车道写着同一段文本（截图里罗马音出现两次）时只留一条：是罗马音就留
     *    romanization，否则留 translation。
     * 2. translation 里不是中文译文的内容离开这条车道：能当这一行的罗马音就搬进
     *    空的 romanization，否则丢弃。QQ 日语歌的 `trans`＝罗马音走的就是这一步。
     * 3. romanization 里是中文译文的内容搬回空的 translation——这是「音译被当成
     *    译文」的方向，旧 overlay 记录里可能就是这么存的。
     *
     * [original] 是该行的原文（歌词本身），只用于 [isRomanizationLike] 的同脚本／
     * 同文本判断；缺省时退化为纯文本判定。
     */
    fun rebalance(
        translation: String?,
        romanization: String?,
        original: String? = null,
    ): LaneContent {
        // 两车道的内容都先过一遍标签剥离：搬去 romanization 的内容不该带着
        // `[kana:…]` 这种标注。
        var translationLane = clean(translation)
        var romanizationLane = clean(romanization)
        if (
            translationLane != null &&
            romanizationLane != null &&
            translationLane == romanizationLane
        ) {
            if (isRomanizationLike(romanizationLane, original)) {
                translationLane = null
            } else {
                romanizationLane = null
            }
        }
        if (translationLane != null && !isTranslationLike(translationLane)) {
            if (romanizationLane.isNullOrBlank() && isRomanizationLike(translationLane, original)) {
                romanizationLane = translationLane
            }
            translationLane = null
        }
        if (romanizationLane != null && isTranslationLike(romanizationLane)) {
            if (translationLane.isNullOrBlank()) translationLane = romanizationLane
            romanizationLane = null
        }
        return LaneContent(translation = translationLane, romanization = romanizationLane)
    }

    /**
     * 一组车道行整体归位，顺序与行键都不变——重启恢复时把盘上那两条数组搬进来
     * 就能救回旧记录里写错车道的内容。
     */
    fun rebalanceRows(rows: List<LaneRow>): List<LaneRow> = rows.map { row ->
        val lanes = rebalance(
            translation = row.translation,
            romanization = row.romanization,
            original = row.text,
        )
        row.copy(translation = lanes.translation, romanization = lanes.romanization)
    }

    /**
     * 剥掉行首的 `[key:value]` 标签（QQ 的 `[kana:…]`、LRC 的 `[ti:…]` 等）以及
     * 行内残留的读音标签。时间戳 `[00:12.00]` 以数字开头，不会被当成标签。
     */
    fun stripLaneTags(text: String?): String {
        val value = text ?: return ""
        return value
            .replace(LEADING_TAGS, "")
            .replace(VOICE_TAG, " ")
            .trim()
    }

    // ------------------------------------------------------------------ scripts

    private class ScriptCounts(val han: Int, val kana: Int, val latin: Int, val other: Int)

    /** 去掉标签后的可见文本；整行只有一个读音标签时用标签内容。 */
    private fun visibleLaneText(text: String?): String {
        val value = text ?: return ""
        val stripped = stripLaneTags(value)
        if (stripped.isNotEmpty()) return stripped
        val tagBody = VOICE_TAG.find(value)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        return stripLaneTags(tagBody)
    }

    /** 车道内容：去掉读音标签后的可见文本，空串等同没有内容。 */
    private fun clean(value: String?): String? {
        val text = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return visibleLaneText(text).takeIf(String::isNotEmpty)
    }

    private fun countScripts(visible: String): ScriptCounts? {
        if (visible.isEmpty()) return null
        var han = 0
        var kana = 0
        var latin = 0
        var other = 0
        var index = 0
        while (index < visible.length) {
            val codePoint = visible.codePointAt(index)
            index += Character.charCount(codePoint)
            if (!Character.isLetter(codePoint)) continue
            val script = Character.UnicodeScript.of(codePoint)
            // ー ・ 々 这类字符是 COMMON：它们跟随前一个字符的脚本，不能算「其他文字」，
            // 否则 コーヒー 会被判成 OTHER。
            if (
                script == Character.UnicodeScript.COMMON ||
                script == Character.UnicodeScript.INHERITED
            ) {
                continue
            }
            when {
                isKana(codePoint) -> kana += 1
                isHan(codePoint) -> han += 1
                script == Character.UnicodeScript.LATIN -> latin += 1
                else -> other += 1
            }
        }
        if (han + kana + latin + other == 0) return null
        return ScriptCounts(han = han, kana = kana, latin = latin, other = other)
    }

    private fun isKana(codePoint: Int): Boolean = when (codePoint) {
        in 0x3040..0x309F, // hiragana
        in 0x30A0..0x30FF, // katakana
        in 0x31F0..0x31FF, // katakana phonetic extensions
        in 0xFF66..0xFF9F, // halfwidth katakana
        in 0x1B000..0x1B16F, // kana supplement / kana extended-a
        -> true

        else -> false
    }

    private fun isHan(codePoint: Int): Boolean =
        Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN

    private fun hasNonLatinLetter(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            if (!Character.isLetter(codePoint)) continue
            val script = Character.UnicodeScript.of(codePoint)
            if (
                script == Character.UnicodeScript.COMMON ||
                script == Character.UnicodeScript.INHERITED
            ) {
                continue
            }
            if (script != Character.UnicodeScript.LATIN) return true
        }
        return false
    }

    // ------------------------------------------------------------------- romaji

    /**
     * 整行能不能按日语罗马音的拼写拆开：每个词都是「（辅音丛）＋元音」的音节，
     * 单个 `n`（ん）算一个音节，双写辅音只表示促音「っ」，不允许其他复辅音和词尾
     * 辅音。
     *
     * 英文词几乎都违反这条：`world`（rl）、`second`（nd）、`clouds`（ds）、
     * `thanks`（nks）都拆不开；`kimi no na wa`、`shitauchi`、`arigatou`、
     * `yokatta`、`Haro warudo` 则能整句拆开。
     */
    private fun looksLikeRomanization(text: String): Boolean {
        val tokens = text.lowercase(Locale.ROOT)
            .split(TOKEN_SEPARATOR)
            .filter(String::isNotEmpty)
        if (tokens.isEmpty()) return false
        return tokens.all(::isRomanizedToken)
    }

    private fun isRomanizedToken(token: String): Boolean {
        val value = normalizeMacrons(token)
        if (value.isEmpty()) return false
        var index = 0
        var syllables = 0
        while (index < value.length) {
            val character = value[index]
            if (character !in 'a'..'z') return false
            if (character == 'n' && (index + 1 >= value.length || value[index + 1] !in VOWELS)) {
                index += 1
                syllables += 1
                continue
            }
            // 促音：双写辅音只表示一个「っ」，不占音节（`yokatta`、`kitto`）。
            if (
                index + 1 < value.length &&
                character != 'n' &&
                character !in VOWELS &&
                value[index + 1] == character
            ) {
                index += 1
                continue
            }
            val onset = onsetAt(value, index)
            val vowelIndex = index + onset.length
            if (vowelIndex >= value.length || value[vowelIndex] !in VOWELS) return false
            index = vowelIndex + 1
            syllables += 1
        }
        return syllables > 0
    }

    private fun onsetAt(value: String, index: Int): String {
        for (onset in ONSETS) {
            if (value.startsWith(onset, index)) return onset
        }
        return ""
    }

    private fun normalizeMacrons(value: String): String = buildString(value.length) {
        value.forEach { character -> append(MACRONS[character] ?: character) }
    }

    /** 去标点、忽略大小写后的文本，用来判断「发音车道里写的其实就是原文」。 */
    private fun comparableText(text: String?): String {
        val normalized = text.orEmpty().lowercase(Locale.ROOT)
        return buildString(normalized.length) {
            var index = 0
            while (index < normalized.length) {
                val codePoint = normalized.codePointAt(index)
                if (Character.isLetterOrDigit(codePoint)) appendCodePoint(codePoint)
                index += Character.charCount(codePoint)
            }
        }
    }

    /**
     * 括注：`(…)`、`（…）`、`[…]`、`【…】`、`{…}`。半角与全角都要认，译文里的
     * 补注两种写法都很常见。
     */
    private val BRACKETED_SEGMENT = Regex("\\([^)]*\\)|（[^）]*）|\\[[^\\]]*]|【[^】]*】|\\{[^}]*}")

    /** 行首的 `[key:value]` 标签；键必须以字母开头，所以时间戳不算标签。 */
    private val LEADING_TAGS = Regex("""^\s*(?:\[[A-Za-z][A-Za-z0-9_]*\s*:[^\[\]]*]\s*)+""")

    /** 任意位置的读音标签：QQ 的 `[kana:…]` / `[roma:…]` 是标注，不是正文。 */
    private val VOICE_TAG = Regex(
        """\[\s*(?:kana|roma|romaji|trans|translation)\s*:([^\[\]]*)]""",
        RegexOption.IGNORE_CASE,
    )

    private val TOKEN_SEPARATOR = Regex("[^\\p{L}\\p{N}]+")

    private val VOWELS = setOf('a', 'e', 'i', 'o', 'u')

    private val MACRONS = mapOf(
        'ā' to 'a',
        'ē' to 'e',
        'ī' to 'i',
        'ō' to 'o',
        'ū' to 'u',
    )

    /** 辅音丛排在单辅音之前，`shi` 才不会先被拆成 `s`+`hi`。 */
    private val ONSETS = listOf(
        "ky", "gy", "sh", "ch", "ts", "ny", "hy", "by", "py", "my", "ry", "sy", "zy", "dy", "ty",
        "b", "c", "d", "f", "g", "h", "j", "k", "l", "m", "n", "p", "q", "r", "s", "t", "v", "w",
        "x", "y", "z",
    )
}
