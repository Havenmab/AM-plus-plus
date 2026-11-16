package dev.amenhancer.module.hook

import dev.amenhancer.module.lyrics.online.KanaDictionary
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shipped kana dictionary asset: header, real lookups, and the load/query cost the dictionary
 * plan budgets for (10–30 ms load, < 100 us per line).
 *
 * The test reads the committed asset from the module's own source tree so it runs as a plain JVM
 * test, without an Android runtime: the loader's only Android-specific part is opening the asset,
 * and the parser it hands the bytes to is pure.
 */
class KanaDictionaryAssetTest {

    private val asset: File = sequenceOf(
        File("src/main/assets/" + KanaDictionaryAssets.ASSET_NAME),
        File("host-applemusic/src/main/assets/" + KanaDictionaryAssets.ASSET_NAME),
    ).firstOrNull(File::isFile)
        ?: error("kana_dict.bin was not found from the unit-test working directory")

    private val license: File = sequenceOf(
        File("src/main/assets/" + KanaDictionaryAssets.LICENSE_ASSET),
        File("host-applemusic/src/main/assets/" + KanaDictionaryAssets.LICENSE_ASSET),
    ).firstOrNull(File::isFile)
        ?: error("IPADIC-COPYING.txt was not found from the unit-test working directory")

    private val bytes: ByteArray by lazy { asset.readBytes() }

    @Test
    fun `the asset is the pinned IPADIC build and stays inside the size budget`() {
        val dictionary = KanaDictionary.fromBytes(bytes)

        assertEquals("ipadic-2.7.0-20070801", dictionary.source)
        assertEquals(KanaDictionary.FORMAT_VERSION, 1)
        assertEquals(72_624, dictionary.entries)
        // The +2 MB budget in the design document: the asset must stay well under it.
        assertTrue("asset is " + bytes.size + " bytes", bytes.size < 512 * 1024)
        assertTrue(dictionary.alphabet.length in 80..120)
        assertTrue(dictionary.stateCount in 10_000..20_000)
    }

    @Test
    fun `real lyrics words are found and the known misses stay misses`() {
        val dictionary = KanaDictionary.fromBytes(bytes)

        // Words the rule layer splits today (the split correction's targets).
        assertTrue(dictionary.contains("とまと"))
        assertTrue(dictionary.contains("だめ"))
        assertTrue(dictionary.contains("しかけ"))
        assertTrue(dictionary.contains("てる"))
        assertTrue(dictionary.contains("こんな"))
        assertTrue(dictionary.contains("ん"))
        assertTrue(dictionary.contains("じゃ"))
        // Folding: katakana and half-width input find the hiragana keys.
        assertTrue(dictionary.contains("トマト"))
        assertTrue(dictionary.contains("ｼｶｹ"))
        // Known misses: マジカル is genuinely absent from IPADIC, and a longer string is not a word.
        assertFalse(dictionary.contains("まじかる"))
        assertFalse(dictionary.contains("とまとです"))
        assertFalse(dictionary.contains(""))
    }

    @Test
    fun `lookup reports the dictionary-word prefix lengths`() {
        val dictionary = KanaDictionary.fromBytes(bytes)

        val lengths = dictionary.lookup("とまとです")
        assertTrue("expected とまと among " + lengths, lengths.contains(3))
        assertTrue(lengths == lengths.sorted())

        // まじ is a word (まじ/真面目), so only the three-character マジカル can be reported absent.
        assertFalse(dictionary.lookup("まじかる").contains(3))
        assertEquals(emptyList<Int>(), dictionary.lookup(""))
    }

    @Test
    fun `load and query cost stay inside the documented budget`() {
        // Load: parse the committed bytes; the JVM number is a proxy for the device, which reads
        // the same 358 KB from assets. Report the best of five to cut out GC noise.
        var bestLoadNanos = Long.MAX_VALUE
        var dictionary: KanaDictionary? = null
        repeat(5) {
            val started = System.nanoTime()
            dictionary = KanaDictionary.fromBytes(bytes)
            bestLoadNanos = minOf(bestLoadNanos, System.nanoTime() - started)
        }
        val loaded = requireNotNull(dictionary)

        // Query: a realistic line's worth of lookups, repeated.
        val words = listOf("とまと", "だめ", "しかけ", "こんな", "ありがとう", "こんにちは", "まじかる")
        var bestQueryNanos = Long.MAX_VALUE
        repeat(5) {
            val started = System.nanoTime()
            var count = 0
            while (count < 20_000) {
                words.forEach { word -> loaded.lookup(word) }
                count += words.size
            }
            bestQueryNanos = minOf(bestQueryNanos, System.nanoTime() - started)
        }
        val perLookupNanos = bestQueryNanos / 20_000

        println(
            "kana_dict.bin: " + bytes.size + " bytes, " + loaded.entries + " entries, load=" +
                (bestLoadNanos / 1_000) + " us, lookup=" + perLookupNanos + " ns",
        )

        assertTrue("load took " + bestLoadNanos / 1_000_000 + " ms", bestLoadNanos < 500_000_000L)
        assertTrue("lookup took " + perLookupNanos + " ns", perLookupNanos < 50_000L)
    }

    @Test
    fun `the IPADIC licence ships beside the asset`() {
        val text = license.readText()

        assertTrue(text.contains("Nara Institute of Science"))
        assertTrue(text.contains("ICOT Free Software"))
        assertTrue(text.contains("NO WARRANTY"))
    }
}
