package dev.amenhancer.module.lyrics.online

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The kana index reader: folding, lookup, and the format checks that keep a malformed asset from
 * reaching the playback path.
 *
 * The fixtures build a real (if tiny) asset: a plain trie is a valid DAWG, so the same writer rules
 * apply and the parser is exercised exactly as it is on the shipped asset.
 */
class KanaDictionaryTest {

    @Test
    fun `folding maps katakana to hiragana and keeps the long vowel`() {
        assertEquals("まじかる", KanaDictionary.foldKana("マジカル"))
        assertEquals("しかけ", KanaDictionary.foldKana("シカケ"))
        assertEquals("こーひー", KanaDictionary.foldKana("コーヒー"))
        assertEquals("がっこう", KanaDictionary.foldKana("ガッコウ"))
        assertEquals("", KanaDictionary.foldKana(""))
    }

    @Test
    fun `folding normalises half-width katakana including combining dakuten`() {
        assertEquals("ばん", KanaDictionary.foldKana("ﾊﾞﾝ"))
        assertEquals("ぱん", KanaDictionary.foldKana("ﾊﾟﾝ"))
        assertEquals("かたかな", KanaDictionary.foldKana("ｶﾀｶﾅ"))
        // A lone combining mark has nothing to voice and is dropped.
        assertEquals("", KanaDictionary.foldKana("ﾞ"))
    }

    @Test
    fun `folding leaves non-kana untouched`() {
        assertEquals("一二", KanaDictionary.foldKana("一二"))
        assertEquals("abc", KanaDictionary.foldKana("abc"))
        assertEquals("", KanaDictionary.foldKana(""))
    }

    @Test
    fun `contains accepts inserted words and rejects everything else`() {
        val dictionary = dictionaryOf("とまと", "だめ", "しかけ", "てる")

        assertTrue(dictionary.contains("とまと"))
        assertTrue(dictionary.contains("だめ"))
        // Katakana input finds the hiragana key.
        assertTrue(dictionary.contains("トマト"))
        assertTrue(dictionary.contains("ｼｶｹ"))
        // Prefixes and longer strings are not words.
        assertFalse(dictionary.contains("とま"))
        assertFalse(dictionary.contains("とまとだめ"))
        assertFalse(dictionary.contains("まじかる"))
        assertFalse(dictionary.contains(""))
    }

    @Test
    fun `contains checks a character range inside a larger text`() {
        val dictionary = dictionaryOf("とまと", "だめ")

        assertTrue(dictionary.contains("あとまと", 1, 4))
        assertFalse(dictionary.contains("あとまと", 0, 3))
        assertFalse(dictionary.contains("あとまと", 2, 4))
        assertFalse(dictionary.contains("あとまと", 3, 2))
        assertFalse(dictionary.contains("あとまと", -1, 2))
        assertFalse(dictionary.contains("あとまと", 1, 99))
    }

    @Test
    fun `lookup returns every dictionary-word prefix length in order`() {
        val dictionary = dictionaryOf("あ", "あい", "あいう", "とまと")

        assertEquals(listOf(1, 2, 3), dictionary.lookup("あいうえお"))
        assertEquals(listOf(3), dictionary.lookup("とまとです"))
        assertEquals(emptyList<Int>(), dictionary.lookup("かきくけこ"))
        assertEquals(emptyList<Int>(), dictionary.lookup(""))
    }

    @Test
    fun `matchesAt starts looking at the requested character`() {
        val dictionary = dictionaryOf("とまと", "まと")

        assertEquals(listOf(3), dictionary.matchesAt("ですとまと", 2))
        assertEquals(listOf(2), dictionary.matchesAt("ですとまと", 3))
        assertEquals(emptyList<Int>(), dictionary.matchesAt("ですとまと", 99))
        assertEquals(emptyList<Int>(), dictionary.matchesAt("ですとまと", -1))
    }

    @Test
    fun `a very long run is rejected without recursion or allocations blowing up`() {
        val dictionary = dictionaryOf("とまと")
        val long = "あ".repeat(5_000)

        assertEquals(emptyList<Int>(), dictionary.lookup(long))
        assertFalse(dictionary.contains(long))
    }

    @Test
    fun `metadata round-trips through the header`() {
        val dictionary = dictionaryOf("とまと", "だめ")

        assertEquals("test-dictionary", dictionary.source)
        assertEquals("test-sha256", dictionary.sourceSha256)
        assertEquals("2026-11-16T00:00:00Z", dictionary.builtAt)
        assertEquals(2, dictionary.entries)
        assertTrue(dictionary.alphabet.contains('と'))
        assertTrue(dictionary.stateCount > 1)
        assertTrue(dictionary.edgeCount > 0)
    }

    @Test
    fun `a bad magic is refused`() {
        val bytes = assetOf("とまと")
        bytes[0] = 'X'.code.toByte()

        assertThrows(IllegalArgumentException::class.java) { KanaDictionary.fromBytes(bytes) }
    }

    @Test
    fun `an unsupported version is refused`() {
        val bytes = assetOf("とまと")
        bytes[5] = 9

        assertThrows(IllegalArgumentException::class.java) { KanaDictionary.fromBytes(bytes) }
    }

    @Test
    fun `a truncated asset is refused`() {
        val bytes = assetOf("とまと")

        assertThrows(IllegalArgumentException::class.java) {
            KanaDictionary.fromBytes(bytes.copyOfRange(0, bytes.size - 3))
        }
        assertThrows(IllegalArgumentException::class.java) {
            KanaDictionary.fromBytes(bytes.copyOfRange(0, 6))
        }
    }

    @Test
    fun `trailing bytes are refused`() {
        val bytes = assetOf("とまと")

        assertThrows(IllegalArgumentException::class.java) {
            KanaDictionary.fromBytes(bytes + byteArrayOf(0, 1, 2))
        }
    }

    // -----------------------------------------------------------------------------------------
    // Fixtures: a real asset over a handful of words.
    // -----------------------------------------------------------------------------------------

    private fun dictionaryOf(vararg words: String): KanaDictionary = KanaDictionary.fromBytes(assetOf(*words))

    private fun assetOf(vararg words: String): ByteArray = TestAsset.build(words.toList())

    /**
     * Writes the shipped format over a plain trie. A trie is a DAWG with no shared suffixes, so it
     * exercises the same reader without re-implementing the generator's minimiser here.
     */
    private object TestAsset {
        private class Node {
            val children = HashMap<Char, Node>()
            var final = false
        }

        fun build(words: List<String>): ByteArray {
            val keys = words.map(KanaDictionary::foldKana).filter { it.isNotEmpty() }.distinct().sorted()
            val alphabet = keys.flatMap { it.toList() }.distinct().sorted().joinToString("")
            val labelOf = alphabet.withIndex().associate { (index, character) -> character to index }

            val root = Node()
            keys.forEach { key ->
                var node = root
                key.forEach { character -> node = node.children.getOrPut(character) { Node() } }
                node.final = true
            }

            val order = ArrayList<Node>()
            val ids = HashMap<Node, Int>()
            val queue = ArrayDeque<Node>()
            queue.add(root)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                ids[node] = order.size
                order.add(node)
                node.children.values.forEach(queue::add)
            }

            val edges = ArrayList<Pair<Int, Int>>()
            val stateRecords = order.map { node ->
                val sorted = node.children.entries.sortedBy { labelOf.getValue(it.key) }
                val start = edges.size
                sorted.forEach { (character, child) ->
                    edges.add(labelOf.getValue(character) to ids.getValue(child))
                }
                Triple(node.final, start, sorted.size)
            }

            val metadata = buildString {
                append("source=test-dictionary\n")
                append("source_sha256=test-sha256\n")
                append("built=2026-11-16T00:00:00Z\n")
                append("entries=").append(keys.size).append('\n')
                append("fold=1\n")
                append("generator=test\n")
                append("alphabet=").append(alphabet).append('\n')
            }.toByteArray(Charsets.UTF_8)

            return ByteArrayOutputStream().apply {
                write("KND1".toByteArray(Charsets.US_ASCII))
                u16(1)
                u16(metadata.size)
                write(metadata)
                u32(stateRecords.size)
                u32(edges.size)
                stateRecords.forEach { (final, start, count) ->
                    u8(if (final) 1 else 0)
                    u8(0)
                    u16(count)
                    u32(start)
                    i16(0)
                }
                edges.forEach { (label, target) ->
                    u8(label)
                    u32(target)
                }
            }.toByteArray()
        }

        private fun ByteArrayOutputStream.u8(value: Int) = write(value and 0xFF)

        private fun ByteArrayOutputStream.u16(value: Int) {
            u8(value ushr 8)
            u8(value)
        }

        private fun ByteArrayOutputStream.u32(value: Int) {
            u16(value ushr 16)
            u16(value)
        }

        private fun ByteArrayOutputStream.i16(value: Int) = u16(value and 0xFFFF)
    }
}
