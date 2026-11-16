package dev.amenhancer.module.lyrics.online

import java.io.InputStream

/**
 * The kana word-boundary index: is this kana string a Japanese word, per IPADIC?
 *
 * This is deliberately not a morphological analyser. It answers one question for the lyric
 * splice's "only split, never merge" correction — whether a stretch of a line is a dictionary
 * word — and nothing else, so it can ship as a 350 KB asset with no native code, no
 * MeCab/Kuromoji/Sudachi dependency and no runtime allocation on the playback path.
 *
 * ## What the index contains
 *
 * A minimised DAWG (a trie whose identical subtrees are shared) over kana surfaces: every
 * IPADIC 2.7.0 entry whose surface is written entirely in kana (72,624 keys / 357,967 bytes for
 * the build in scripts/build_kana_dictionary.py). Kanji entries are deliberately absent: the rule
 * layer already proves a kanji/kana transition, and the lyric containers that need a boundary
 * decision are the kana runs.
 *
 * ## Folding
 *
 * Both the asset and every lookup are in one canonical form, produced by [foldKana]:
 *
 *  - half-width katakana U+FF66..U+FF9D are mapped to their full-width forms;
 *  - a half-width dakuten U+FF9E / handakuten U+FF9F combines with the character before it
 *    (half-width HA + dakuten is one voiced kana, not two), which is the only case where folding
 *    shortens the text;
 *  - full-width katakana U+30A1..U+30F6 become hiragana (subtract 0x60), so SHIKAKE written in
 *    katakana finds the hiragana key — the reason a reading-based index alone is not enough;
 *  - the long-vowel mark (U+30FC, half-width U+FF70) is kept as U+30FC in both scripts, so a
 *    katakana word with a long vowel folds to its hiragana key and still matches;
 *  - the sokuon and small kana follow the same katakana-to-hiragana rule, so they never need a
 *    special case;
 *  - anything else (kanji, latin, digits, punctuation) is left untouched and simply never matches,
 *    because the index holds kana keys only.
 *
 * [matchesAt] and [lookup] return lengths measured in the folded text. Full-width kana fold
 * one-to-one, which is what Apple's containers hold, so for them a returned length is also the
 * length in the source text; only half-width input with combining marks can be shorter after
 * folding, and that case is documented rather than silently remapped.
 *
 * ## File format
 *
 * The asset is written by scripts/build_kana_dictionary.py; the Kotlin reader validates every
 * offset it reads. All integers are big-endian:
 *
 *  - magic: the four bytes KND1
 *  - formatVersion: u16, currently 1
 *  - metaLength: u16, byte length of the metadata block
 *  - metadata: UTF-8 key=value lines — source, source_sha256, built, entries, fold, generator,
 *    alphabet (the folded characters, in label order)
 *  - stateCount: u32, edgeCount: u32
 *  - states: stateCount x { u8 flags, u8 reserved, u16 childCount, u32 childStart, i16 cost }
 *  - edges: edgeCount x { u8 label, u32 targetState }
 *
 * The cost field is reserved for a later format version (the v1 writer always stores 0): the first
 * shipped correction only needs membership, and shipping costs would need the writer to keep
 * per-entry costs through DAWG minimisation. A reader that sees a non-zero cost ignores it.
 */
class KanaDictionary private constructor(
    /** The dictionary this asset was built from, e.g. ipadic-2.7.0-20070801. */
    val source: String,
    /** SHA-256 of the dictionary archive the asset was generated from. */
    val sourceSha256: String,
    /** UTC build timestamp recorded in the asset header. */
    val builtAt: String,
    /** Number of kana words in the index. */
    val entries: Int,
    /** The folded characters that occur in keys, in label order. */
    val alphabet: String,
    private val finalState: BooleanArray,
    private val childStart: IntArray,
    private val childCount: IntArray,
    private val edgeLabel: ByteArray,
    private val edgeTarget: IntArray,
) {

    /** Number of DAWG states (root is state 0). */
    val stateCount: Int get() = finalState.size

    /** Number of DAWG edges. */
    val edgeCount: Int get() = edgeLabel.size

    /**
     * Lengths of the dictionary words that start at [start] of [text], in ascending order.
     *
     * The text is folded with [foldKana] first; the returned lengths are indices into the folded
     * string (identical to [text] for the full-width kana Apple's containers hold). An empty result
     * means "no dictionary word starts here", which the correction layer treats as "leave the
     * rule's grouping alone".
     */
    fun matchesAt(text: String, start: Int = 0): List<Int> {
        if (start < 0 || start > text.length) return emptyList()
        val folded = foldKana(text.substring(start))
        if (folded.isEmpty()) return emptyList()
        var state = ROOT
        var matches: ArrayList<Int>? = null
        for (index in folded.indices) {
            val label = labelAt(folded[index])
            if (label < 0) break
            state = child(state, label)
            if (state < 0) break
            if (finalState[state]) {
                val found = matches ?: ArrayList<Int>(4).also { matches = it }
                found.add(index + 1)
            }
        }
        return matches ?: emptyList()
    }

    /** [matchesAt] from the beginning of [text]: the lengths of its dictionary-word prefixes. */
    fun lookup(text: String): List<Int> = matchesAt(text, 0)

    /** Whether [text] — folded — is itself a dictionary word. An empty string is never a word. */
    fun contains(text: String): Boolean {
        val folded = foldKana(text)
        if (folded.isEmpty()) return false
        var state = ROOT
        for (character in folded) {
            val label = labelAt(character)
            if (label < 0) return false
            state = child(state, label)
            if (state < 0) return false
        }
        return finalState[state]
    }

    /** Whether text[start, end) is a dictionary word. Bounds outside the text are not a word. */
    fun contains(text: String, start: Int, end: Int): Boolean {
        if (start < 0 || end > text.length || start >= end) return false
        return contains(text.substring(start, end))
    }

    /** The child state reached from [state] by [label], or -1 when there is none. */
    private fun child(state: Int, label: Int): Int {
        var low = childStart[state]
        var high = low + childCount[state] - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val candidate = edgeLabel[middle].toInt() and 0xFF
            when {
                candidate < label -> low = middle + 1
                candidate > label -> high = middle - 1
                else -> return edgeTarget[middle]
            }
        }
        return -1
    }

    /** The alphabet position of [character], or -1 when it can never begin an edge. */
    private fun labelAt(character: Char): Int {
        val index = character.code
        return if (index in alphabetIndex.indices) alphabetIndex[index] else -1
    }

    /**
     * The alphabet as a dense character-to-label table. The alphabet is about 90 kana characters,
     * so a flat table is both smaller and faster than a hash map on the lookup path.
     */
    private val alphabetIndex: IntArray = IntArray(ALPHABET_TABLE_SIZE) { index ->
        alphabet.indexOf(index.toChar())
    }

    companion object {
        /** The asset format this reader understands. */
        const val FORMAT_VERSION = 1

        /** Largest character code the folding can produce (hiragana + the long-vowel mark). */
        private const val ALPHABET_TABLE_SIZE = 0x3100

        /** The root state of every DAWG; the writer guarantees it gets id 0. */
        private const val ROOT = 0

        private val MAGIC = byteArrayOf(0x4B, 0x4E, 0x44, 0x31) // "KND1"

        /** Half-width katakana to full-width katakana; consecutive (half, full) pairs. */
        private const val HALF_WIDTH_PAIRS = "｡。｢「｣」､、･・ｦヲｧァｨィｩゥｪェｫォｬャｭュｮョｯッｰーｱアｲイｳウｴエｵオｶカｷキｸクｹケｺコｻサｼシｽスｾセｿソﾀタﾁチﾂツﾃテﾄトﾅナﾆニﾇヌﾈネﾉノﾊハﾋヒﾌフﾍヘﾎホﾏマﾐミﾑムﾒメﾓモﾔヤﾕユﾖヨﾗラﾘリﾙルﾚレﾛロﾜワﾝン"

        /** Voiced forms used when a half-width dakuten follows a full-width katakana. */
        private const val DAKUTEN_PAIRS = "カガキギクグケゲコゴサザシジスズセゼソゾタダチヂツヅテデトドハバヒビフブヘベホボウヴワヷヲヺ"

        /** Semi-voiced forms used when a half-width handakuten follows a full-width katakana. */
        private const val HANDAKUTEN_PAIRS = "ハパヒピフプヘペホポ"

        private val halfWidth: Map<Char, Char> = pairs(HALF_WIDTH_PAIRS)
        private val dakuten: Map<Char, Char> = pairs(DAKUTEN_PAIRS)
        private val handakuten: Map<Char, Char> = pairs(HANDAKUTEN_PAIRS)

        private fun pairs(text: String): Map<Char, Char> {
            val table = HashMap<Char, Char>(text.length / 2)
            var index = 0
            while (index + 1 < text.length) {
                table[text[index]] = text[index + 1]
                index += 2
            }
            return table
        }

        /**
         * Folds [text] into the index's canonical form (see the class documentation for the rules).
         * Pure and allocation-light: one [StringBuilder] pass plus one katakana-to-hiragana pass.
         */
        fun foldKana(text: String): String {
            if (text.isEmpty()) return text
            val builder = StringBuilder(text.length)
            for (character in text) {
                when {
                    character.code in 0xFF66..0xFF9D -> builder.append(halfWidth[character] ?: character)
                    character == '\uFF9E' -> voice(builder, dakuten)
                    character == '\uFF9F' -> voice(builder, handakuten)
                    else -> builder.append(character)
                }
            }
            for (index in builder.indices) {
                val character = builder[index]
                if (character.code in 0x30A1..0x30F6) {
                    builder.setCharAt(index, (character.code - 0x60).toChar())
                }
            }
            return builder.toString()
        }

        private fun voice(builder: StringBuilder, table: Map<Char, Char>) {
            if (builder.isEmpty()) return
            val voiced = table[builder[builder.length - 1]] ?: return
            builder.setCharAt(builder.length - 1, voiced)
        }

        /** Reads an asset produced by scripts/build_kana_dictionary.py. */
        fun fromBytes(bytes: ByteArray): KanaDictionary = Reader(bytes).read()

        /** Reads an asset from [input]; the stream is not closed. */
        fun fromStream(input: InputStream): KanaDictionary = fromBytes(input.readBytes())
    }

    /** Bounds-checked big-endian reader; a malformed asset fails here, not in the lookup path. */
    private class Reader(private val bytes: ByteArray) {
        private var position = 0

        fun read(): KanaDictionary {
            val magic = take(4)
            require(magic.contentEquals(MAGIC)) { "not a kana dictionary asset (bad magic)" }
            val version = u16()
            require(version == FORMAT_VERSION) {
                "unsupported kana dictionary format version " + version + " (expected " + FORMAT_VERSION + ")"
            }
            val metadataLength = u16()
            val metadata = String(take(metadataLength), Charsets.UTF_8)
            val header = parseMetadata(metadata)
            val stateCount = u32()
            val edgeCount = u32()
            require(stateCount > 0) { "kana dictionary has no states" }
            require(edgeCount >= 0) { "kana dictionary has a negative edge count" }

            val finalState = BooleanArray(stateCount)
            val childStart = IntArray(stateCount)
            val childCount = IntArray(stateCount)
            val edgeLabel = ByteArray(edgeCount)
            val edgeTarget = IntArray(edgeCount)

            for (state in 0 until stateCount) {
                val flags = u8()
                u8() // reserved
                val children = u16()
                val start = u32()
                i16() // reserved cost, see the class documentation
                require(start >= 0 && children >= 0 && start + children <= edgeCount) {
                    "kana dictionary state " + state + " points outside the edge table"
                }
                finalState[state] = flags and 1 != 0
                childStart[state] = start
                childCount[state] = children
            }
            for (edge in 0 until edgeCount) {
                val label = u8()
                val target = u32()
                require(target in 0 until stateCount) {
                    "kana dictionary edge " + edge + " points at state " + target
                }
                require(label >= 0 && label < header.alphabet.length) {
                    "kana dictionary edge " + edge + " carries label " + label + " outside the alphabet"
                }
                edgeLabel[edge] = label.toByte()
                edgeTarget[edge] = target
            }
            require(position == bytes.size) {
                "kana dictionary asset has " + (bytes.size - position) + " trailing bytes"
            }
            return KanaDictionary(
                source = header.source,
                sourceSha256 = header.sourceSha256,
                builtAt = header.built,
                entries = header.entries,
                alphabet = header.alphabet,
                finalState = finalState,
                childStart = childStart,
                childCount = childCount,
                edgeLabel = edgeLabel,
                edgeTarget = edgeTarget,
            )
        }

        private fun parseMetadata(metadata: String): Header {
            val values = HashMap<String, String>(8)
            metadata.lineSequence().forEach { line ->
                val separator = line.indexOf('=')
                if (separator > 0) {
                    values[line.substring(0, separator)] = line.substring(separator + 1)
                }
            }
            return Header(
                source = values["source"].orEmpty(),
                sourceSha256 = values["source_sha256"].orEmpty(),
                built = values["built"].orEmpty(),
                entries = values["entries"]?.toIntOrNull() ?: 0,
                alphabet = values["alphabet"].orEmpty(),
            )
        }

        private fun take(count: Int): ByteArray {
            require(count >= 0 && position + count <= bytes.size) {
                "kana dictionary asset is truncated"
            }
            val slice = bytes.copyOfRange(position, position + count)
            position += count
            return slice
        }

        private fun u8(): Int {
            require(position < bytes.size) { "kana dictionary asset is truncated" }
            return bytes[position++].toInt() and 0xFF
        }

        private fun u16(): Int = (u8() shl 8) or u8()

        private fun u32(): Int {
            val value = (u16() shl 16) or u16()
            require(value >= 0) { "kana dictionary asset carries a value beyond Int range" }
            return value
        }

        private fun i16(): Int {
            val value = u16()
            return if (value and 0x8000 != 0) value - 0x10000 else value
        }

        private class Header(
            val source: String,
            val sourceSha256: String,
            val built: String,
            val entries: Int,
            val alphabet: String,
        )
    }
}
