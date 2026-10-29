package dev.amenhancer.module.lyrics.online

/**
 * 判定一行文本属于哪条车道，并在两条车道错位时把它们重新归位。
 *
 * **实现已经搬到 [OnlineTranslationExtraction]**，本对象只保留原 API 并转发过去。
 * 原因写在那边：设备上这个类存在于构建出的 dex 里却无法加载（NoClassDefFoundError），
 * 任何直接调用它的代码都会在 call site 抛异常，于是译文车道永远是空的、「从来源面板
 * 选译文」发不出去、自动补全也写不了 overlay / 存不了文件。把规则搬到每个抓取都会用到
 * 的 [OnlineTranslationExtraction] 之后，生产调用点不再链接这个类；它留在这里是为了
 * 既有调用方和 LyricLaneClassifierTest —— 判定只有一份实现，不再是第二个真相。
 */
object LyricLaneClassifier {

    /** 一行文本的脚本归属：车道判定的唯一依据。保留该类型以兼容既有调用方。 */
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
     * [romanization] 是这两条车道为它各自记下的内容。
     */
    data class LaneRow(
        val begin: Long = 0L,
        val end: Long = 0L,
        val text: String = "",
        val translation: String? = null,
        val romanization: String? = null,
    )

    /** 一行的脚本判定，转发到 [OnlineTranslationExtraction.classifyKind]。 */
    fun classify(text: String?): LaneKind =
        when (OnlineTranslationExtraction.classifyKind(text)) {
            OnlineTranslationExtraction.LaneKind.EMPTY -> LaneKind.EMPTY
            OnlineTranslationExtraction.LaneKind.CHINESE -> LaneKind.CHINESE
            OnlineTranslationExtraction.LaneKind.KANA -> LaneKind.KANA
            OnlineTranslationExtraction.LaneKind.ROMANIZATION -> LaneKind.ROMANIZATION
            OnlineTranslationExtraction.LaneKind.LATIN -> LaneKind.LATIN
            OnlineTranslationExtraction.LaneKind.MIXED -> LaneKind.MIXED
            OnlineTranslationExtraction.LaneKind.OTHER -> LaneKind.OTHER
        }

    /** 这一行能不能当**中文译文**发布，转发到 [OnlineTranslationExtraction.isTranslationLike]。 */
    fun isTranslationLike(text: String?): Boolean =
        OnlineTranslationExtraction.isTranslationLike(text)

    /** 这一行能不能当**罗马音**发布，转发到 [OnlineTranslationExtraction.isRomanizationLike]。 */
    fun isRomanizationLike(text: String?): Boolean =
        OnlineTranslationExtraction.isRomanizationLike(text)

    /** 带原文判断的罗马音版本，转发到 [OnlineTranslationExtraction.isRomanizationLike]。 */
    fun isRomanizationLike(text: String?, original: String?): Boolean =
        OnlineTranslationExtraction.isRomanizationLike(text, original)

    /** 剥掉行首标签与行内读音标签，转发到 [OnlineTranslationExtraction.stripLaneTags]。 */
    fun stripLaneTags(text: String?): String = OnlineTranslationExtraction.stripLaneTags(text)

    /** 把一行的两条车道重新归位，转发到 [OnlineTranslationExtraction.classifyLanes]。 */
    fun rebalance(
        translation: String?,
        romanization: String?,
        original: String? = null,
    ): LaneContent {
        val lanes = OnlineTranslationExtraction.classifyLanes(translation, romanization, original)
        return LaneContent(translation = lanes.translation, romanization = lanes.romanization)
    }

    /** 一组车道行整体归位，顺序与行键都不变。 */
    fun rebalanceRows(rows: List<LaneRow>): List<LaneRow> = rows.map { row ->
        val lanes = rebalance(
            translation = row.translation,
            romanization = row.romanization,
            original = row.text,
        )
        row.copy(translation = lanes.translation, romanization = lanes.romanization)
    }
}
