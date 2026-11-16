package dev.amenhancer.module.hook

import dev.amenhancer.module.lyrics.online.KanaDictionary
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dictionary's "split, never merge" pass over the rule blocks.
 *
 * The fixtures are real lyric lines the rules over-merge, fed as container tokens: every expected
 * range is the word boundary a native reader would draw, and every assertion that a line is left
 * alone is as important as the ones that assert a split (マジカル and メタモ are out of IPADIC, and
 * the katakana guard is what keeps them whole).
 *
 * This is a plain JVM test: the pass is pure over [LyricsPronunciationWordAlignment.ContainerToken]
 * and [LyricsPronunciationWordAlignment.Block], and the dictionary is the committed asset.
 */
class LyricsPronunciationDictionarySplitTest {

    private val dictionary: KanaDictionary by lazy { KanaDictionary.fromBytes(asset().readBytes()) }

    private fun asset(): File = sequenceOf(
        File("src/main/assets/" + KanaDictionaryAssets.ASSET_NAME),
        File("host-applemusic/src/main/assets/" + KanaDictionaryAssets.ASSET_NAME),
    ).firstOrNull(File::isFile)
        ?: error("kana_dict.bin was not found from the unit-test working directory")

    private fun tokens(vararg cores: String): List<LyricsPronunciationWordAlignment.ContainerToken> =
        cores.mapIndexed { index, core ->
            LyricsPronunciationWordAlignment.ContainerToken(index = index, kana = core, reading = core)
        }

    private fun ranges(blocks: List<LyricsPronunciationWordAlignment.Block>): List<IntRange> =
        blocks.map { it.firstIndex..it.lastIndex }

    private fun split(
        tokenList: List<LyricsPronunciationWordAlignment.ContainerToken>,
        dryRun: Boolean = false,
        log: MutableList<String> = mutableListOf(),
    ): List<IntRange> {
        val baseline = LyricsPronunciationWordAlignment.group(tokenList)
        val applied = LyricsPronunciationWordAlignment.applyDictionarySplits(
            tokens = tokenList,
            blocks = baseline,
            dictionary = dictionary,
            dryRun = dryRun,
            log = { message -> log += message },
        )
        return ranges(applied)
    }

    // ---- the four over-merges the dictionary can prove -------------------------------------

    @Test
    fun `a reduplicated katakana word splits on the repeat`() {
        val tokenList = tokens("ダ", "メ", "ダ", "メ")

        assertEquals(listOf(0..3), ranges(LyricsPronunciationWordAlignment.group(tokenList)))
        assertEquals(listOf(0..1, 2..3), split(tokenList))
    }

    @Test
    fun `a hiragana run splits into its word and its particle phrase`() {
        val tokenList = tokens("こ", "ん", "な", "ん", "じ", "ゃ")

        assertEquals(listOf(0..2, 3..5), split(tokenList))
    }

    @Test
    fun `a verb and its auxiliary split`() {
        val tokenList = tokens("し", "か", "け", "て", "る")

        assertEquals(listOf(0..2, 3..4), split(tokenList))
    }

    @Test
    fun `the split is logged once with its key, boundary and halves`() {
        val log = mutableListOf<String>()

        split(tokens("ダ", "メ", "ダ", "メ"), log = log)

        assertEquals(1, log.size)
        assertEquals(
            "dict split key=ダメダメ at=2 left=ダメ right=ダメ dry=false",
            log.single(),
        )
    }

    // ---- lines the dictionary must leave alone ---------------------------------------------

    @Test
    fun `a kanji word is never split because the index holds kana only`() {
        val tokenList = tokens("仕", "掛", "け", "て", "る")
        val log = mutableListOf<String>()

        assertEquals(listOf(0..4), split(tokenList, log = log))
        // The right half (てる) is a word; the left half carries kanji, so the index cannot prove it.
        assertTrue(log.any { it.startsWith("dict skip key=仕掛けてる") && it.endsWith("reason=no-split") })
    }

    @Test
    fun `an out-of-vocabulary katakana word is not split by two known neighbours`() {
        // マジ and カル are both words, so a plain two-word test would split マジカル apart; the
        // katakana guard refuses every non-repeating split inside a katakana run.
        val tokenList = tokens("マ", "ジ", "カ", "ル", "ト", "リッ", "ク", "で")

        assertEquals(
            ranges(LyricsPronunciationWordAlignment.group(tokenList)),
            split(tokenList),
        )
    }

    @Test
    fun `a katakana word plus a hiragana tail is not split before its own tail`() {
        // メタ + モッて are both words and メタモ is not, but the boundary sits inside the katakana
        // run of an out-of-vocabulary compound, so the guard keeps メタモッ|て as the rules had it.
        val tokenList = tokens("メ", "タ", "モ", "ッ", "て")

        assertEquals(
            ranges(LyricsPronunciationWordAlignment.group(tokenList)),
            split(tokenList),
        )
    }

    @Test
    fun `dry run reports the split but keeps the rule grouping`() {
        val tokenList = tokens("ダ", "メ", "ダ", "メ")
        val log = mutableListOf<String>()

        val applied = split(tokenList, dryRun = true, log = log)

        assertEquals(listOf(0..3), applied)
        assertEquals(
            "dict split key=ダメダメ at=2 left=ダメ right=ダメ dry=true",
            log.single(),
        )
    }

    @Test
    fun `a block with a proven boundary inside it is refused whole`() {
        // The rule grouping never produces this: a katakana particle is a word of its own there.
        // The guard exists for callers that built their own blocks.
        val tokenList = tokens("マ", "ジ", "ト", "リック")
        val block = LyricsPronunciationWordAlignment.Block(tokenList)
        val log = mutableListOf<String>()

        val applied = LyricsPronunciationWordAlignment.applyDictionarySplits(
            tokens = tokenList,
            blocks = listOf(block),
            dictionary = dictionary,
            dryRun = false,
            log = { message -> log += message },
        )

        assertEquals(listOf(block), applied)
        assertTrue(log.single().endsWith("reason=hard-boundary"))
    }

    @Test
    fun `a block with one reading is not split`() {
        val tokenList = listOf(
            LyricsPronunciationWordAlignment.ContainerToken(index = 0, kana = "ダメ", reading = "dame"),
            LyricsPronunciationWordAlignment.ContainerToken(index = 1, kana = "ダメ", reading = null),
        )
        val log = mutableListOf<String>()

        val applied = LyricsPronunciationWordAlignment.applyDictionarySplits(
            tokens = tokenList,
            blocks = listOf(LyricsPronunciationWordAlignment.Block(tokenList)),
            dictionary = dictionary,
            dryRun = false,
            log = { message -> log += message },
        )

        assertEquals(1, applied.size)
        assertTrue(log.single().endsWith("reason=single-fragment"))
    }

    // ---- the baseline is untouched when the dictionary is absent ---------------------------

    @Test
    fun `no dictionary means the rule grouping byte for byte`() {
        val fixtures = listOf(
            tokens("ダ", "メ", "ダ", "メ"),
            tokens("こ", "ん", "な", "ん", "じ", "ゃ"),
            tokens("マ", "ジ", "カ", "ル", "ト", "リッ", "ク", "で"),
            tokens("し", "か", "け", "て", "る"),
        )
        fixtures.forEach { tokenList ->
            assertEquals(
                ranges(LyricsPronunciationWordAlignment.group(tokenList)),
                ranges(
                    LyricsPronunciationWordAlignment.group(
                        tokenList,
                        dictionary = null,
                        dryRun = true,
                    ),
                ),
            )
        }
    }

    @Test
    fun `dictionaryKey is a short log key not the whole line`() {
        val long = tokens("あ", "い", "う", "え", "お", "か", "き", "く", "け", "こ", "さ", "し", "す")

        assertEquals(12, LyricsPronunciationWordAlignment.dictionaryKey(long).length)
        assertEquals("あいうえおかきくけこさし", LyricsPronunciationWordAlignment.dictionaryKey(long))
    }

    @Test
    fun `the shipped asset proves the examples the split rule relies on`() {
        // If the asset is ever regenerated, these are the words the correction needs.
        listOf("ダメ", "こんな", "んじゃ", "しかけ", "てる", "あっぷ", "すてっぷ").forEach { word ->
            assertTrue("missing " + word, dictionary.contains(word))
        }
        assertFalse(dictionary.contains("マジカル"))
    }
}
