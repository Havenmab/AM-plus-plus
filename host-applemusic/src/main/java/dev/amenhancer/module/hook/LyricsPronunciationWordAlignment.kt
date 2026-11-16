package dev.amenhancer.module.hook

import android.view.ViewGroup
import android.widget.TextView
import dev.amenhancer.module.lyrics.online.NativePronunciationSpan
import dev.amenhancer.module.lyrics.online.PronunciationBoundary
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Splices the per-mora readings of one lyric word into a single visual word form.
 *
 * Apple inflates one `layout/lyrics_pronunciation_vertical_group` per TTML span and puts that
 * span's romanization underneath it, so a word the source split per mora (`su` `a` `ppu`) is drawn as
 * three separate readings, each centred on its own kana. This object keeps every one of those reading
 * views — and therefore every one of their karaoke highlights — and only re-places them horizontally
 * so the fragments sit flush as one word (`suappu`), centred on the whole word's glyph run.
 *
 * Nothing else is written: the text, the widths, the container's layout params (except in
 * [ShiftMode.LAYOUT], see below) and Apple's own timing all stay as they are. A block that is not
 * spliced keeps exactly the correction [LyricsPronunciationRowCentring.alignToCoreText] applied
 * before this object existed, because [alignRow] answers false for it.
 *
 * ## Why the timing survives
 *
 * Apple's karaoke reveal is a horizontal `DST_IN` gradient per row band
 * (`FullWidthAlphaGradientFlexboxLayout`), advanced per **word** by that word's own
 * `begin`/`end` (`player/A.java`'s `d0(...)` rewrites the band's positions every frame).
 * Because the mask is spatial, a spliced block whose characters still live inside their own kana's
 * x-range is revealed kana by kana even though it reads as one word. That is why this route needs no
 * timing data of its own; see `.work/analysis/pronunciation-word-merge-rules.md`.
 *
 * ## Two shift modes
 *
 * `A.java:4114` derives the reading band's mask position from `utils/g1.z(readingView, flexbox)`,
 * a classes.dex-only helper whose inputs cannot be read statically. If it reads `getX()` the mask
 * follows a `translationX`; if it reads `getLeft()` it does not, and the splice has to move the view's
 * real frame instead. [ShiftMode] keeps both implementations behind one geometry, so a single device
 * experiment decides which one ships: [ShiftMode.TRANSLATION] is the default because it never
 * triggers a layout pass.
 */
internal object LyricsPronunciationWordAlignment {

    /** The centring hook's tag: this is one feature with one write path, so it logs under one name. */
    const val LOG_TAG = LyricsPronunciationRowCentring.LOG_TAG

    /** The most kana one visual word may hold; six reads as one word and still fits a row. */
    const val WORD_MAX_KANA = 6

    /** A silence this long between two fragments means two words, not one. Store mode only. */
    const val GAP_BREAK_MS = 800L

    /** A spliced block may not exceed this multiple of the glyph run it sits under. */
    const val MAX_BLOCK_WIDTH_RATIO = 1.6f

    /** Containers whose tops differ by no more than this are on the same wrapped line. */
    const val LINE_TOLERANCE_PX = 1

    /**
     * Centre-row traces on logcat ("grouped run=..."), off by default.
     *
     * They are development traces: the row re-groups on every layout pass, while what the user is
     * asked to watch is the model chain, which logs under AMPP-Bndry. Flip this to true for one build
     * when the splice itself needs tracing.
     */
    private const val VERBOSE_CENTRING_LOG = false

    /** How many remembered log states are kept before the maps are rebuilt. */
    const val LOG_MEMORY_LIMIT = 256

    /** How many distinct lines the one-shot "no plan here" log remembers before it forgets them. */
    const val BOUNDARY_MISS_LOG_LIMIT = 512

    /**
     * A line whose containers hold this many kana on average is already word-level
     * (`メイ` `キャップ` `ドレ` `スアップ`), so splicing it would join words the source kept
     * apart. Mora-level data holds about one kana per container.
     */
    const val SOURCE_WORD_LEVEL_KANA_PER_CONTAINER = 1.6f

    /**
     * A reading this much wider than its own kana is the reading of the **whole** word
     * (`suappu` over `ス`), so its container already ends the word and must not join a
     * neighbour. Shares [LyricsPronunciationRowCentring.WHOLE_WORD_RATIO]'s threshold on purpose.
     */
    const val WHOLE_WORD_READING_RATIO = LyricsPronunciationRowCentring.WHOLE_WORD_RATIO

    /** Katakana particles: a one-character token from this set always ends the word before it. */
    const val KATAKANA_PARTICLE = "トデハヲニガモノネヨ"

    /**
     * Hiragana particles, softer than the katakana set: `itsumo`/`dane`/`ohayou` only read
     * as one word if the particle may join, while a particle after a kanji still starts a new unit
     * (`全身` | `が`). `ん` is deliberately absent so `こん` is not split by it.
     */
    const val HIRAGANA_PARTICLE = "はがをにでとへのもやかねよなさぞぜわ"

    /** A token ending in one of these pairs with the token after it (`メタモ` `っ` `て`). */
    const val BRIDGE_TRAILING = "っゃゅょぁぃぅぇぉゎッャュョァィゥェォヮ"

    /** How a spliced reading is moved into place. Internal: no setting exposes it. */
    enum class ShiftMode {
        /** `translationX`: a draw-time offset, never a layout pass. */
        TRANSLATION,

        /** The reading's real frame (`offsetLeftAndRight`), for a mask that reads `getLeft()`. */
        LAYOUT,
    }

    /** The active shift mode; [ShiftMode.TRANSLATION] unless a device experiment says otherwise. */
    var shiftMode: ShiftMode = ShiftMode.TRANSLATION

    /** How strictly fragments are grouped: [GroupingMode.STRICT] is the data-layer rule set. */
    enum class GroupingMode { STRICT, LOOSE }

    /** The script a token is written in, for the "same script joins" rule. */
    enum class Script { KATAKANA, HIRAGANA, KANJI, LATIN, DIGIT, OTHER, MIXED }

    /**
     * Optional per-line timings, wired by the host once the overlay store can answer for a row.
     * Left null keeps [GAP_BREAK_MS] off, which is the right default while the view tree is the only
     * source: without timings the gap rule cannot be applied at all.
     */
    var lineSpans: ((String) -> List<NativePronunciationSpan>?)? = null

    /**
     * The model's plans for the current song, keyed by a line's kana join — the same key [alignRow]
     * builds for [lineSpans].
     *
     * Null keeps every line on the rules. The provider is only ever asked from [alignRow], and it is
     * expected to answer from memory: the playback path must never wait for a plan.
     */
    var boundaryPlans: ((String) -> PronunciationBoundary.BoundaryLine?)? = null

    /** Containers already pinned to their glyph width, so [ShiftMode.LAYOUT] pins each one once. */
    private val pinned = Collections.synchronizedMap(WeakHashMap<ViewGroup, Unit>())

    // ---------------------------------------------------------------------------------------
    // Model
    // ---------------------------------------------------------------------------------------

    /** One inflated word container, reduced to the values the rules read. */
    data class ContainerToken(
        val index: Int,
        val kana: String,
        val reading: String?,
        val lineTop: Int = 0,
        val begin: Long? = null,
        val end: Long? = null,
        val readingIsWholeWord: Boolean = false,
    ) {
        /** The glyph text with the marks and blanks Apple appends at both ends cut off. */
        val core: String get() = LyricsPronunciationWordAlignment.coreText(kana)

        /** The reading with its own blanks cut off; its punctuation is kept (`Change!`). */
        val readingCore: String
            get() = LyricsPronunciationWordAlignment.coreText(reading.orEmpty())

        val hasReading: Boolean get() = readingCore.isNotEmpty()

        /** True when Apple appended the whitespace that follows the word to this word's text. */
        val trailingBlank: Boolean get() = LyricsPronunciationWordAlignment.trailingBlank(kana)

        /** Whitespace and nothing else: Apple's own word separator. */
        val isBlankOnly: Boolean
            get() = kana.isNotEmpty() && kana.all { it in LyricsPronunciationRowCentring.EDGE_BLANKS }

        /** Marks and blanks only: shows no reading and never a word (`！`). */
        val isPureMark: Boolean get() = core.isEmpty()

        val script: Script get() = LyricsPronunciationWordAlignment.scriptOf(core)

        /** Kana held by this container; the length unit of [WORD_MAX_KANA]. */
        val kanaCount: Int get() = core.length

        val isKatakanaParticle: Boolean
            get() = core.length == 1 && core[0] in LyricsPronunciationWordAlignment.KATAKANA_PARTICLE

        val isHiraganaParticle: Boolean
            get() = core.length == 1 && core[0] in LyricsPronunciationWordAlignment.HIRAGANA_PARTICLE
    }

    /** A run of consecutive containers that reads as one word. */
    data class Block(val tokens: List<ContainerToken>) {
        val firstIndex: Int get() = tokens.first().index
        val lastIndex: Int get() = tokens.last().index
        val kanaCount: Int get() = tokens.sumOf { it.kanaCount }
        val fragments: List<ContainerToken> get() = tokens.filter { it.hasReading }
        val isMerged: Boolean get() = tokens.size > 1
    }

    /** One reading view's geometry, in the row's coordinate space. */
    data class FragmentGeometry(
        val index: Int,
        val leftInRow: Float,
        val width: Float,
        val visibleWidth: Float,
        val leadingInset: Float = 0f,
    )

    /** A stretch of the row: the word's first glyph's left, its last glyph's right. */
    data class Span(val left: Float, val right: Float) {
        val centre: Float get() = (left + right) / 2f
        val width: Float get() = (right - left).coerceAtLeast(0f)
    }

    /** Where a block's fragments go, in row coordinates, with the block's own width. */
    data class Splice(val targetLefts: Map<Int, Float>, val blockWidth: Float)

    // ---------------------------------------------------------------------------------------
    // Rules (pure)
    // ---------------------------------------------------------------------------------------

    /** [text] without the marks and blanks a reading never covers at its ends. */
    fun coreText(text: String): String {
        var start = 0
        var end = text.length
        while (start < end && LyricsPronunciationRowCentring.isEdgeIgnorable(text[start])) start++
        while (end > start && LyricsPronunciationRowCentring.isEdgeIgnorable(text[end - 1])) end--
        return text.substring(start, end)
    }

    /** Whether Apple appended whitespace after the word: the source's own word boundary. */
    fun trailingBlank(text: String): Boolean =
        text.isNotEmpty() && text.last() in LyricsPronunciationRowCentring.EDGE_BLANKS

    /** The script of a token's core, or [Script.MIXED] when it spans more than one. */
    fun scriptOf(core: String): Script {
        if (core.isEmpty()) return Script.OTHER
        var script: Script? = null
        for (character in core) {
            val next = scriptOfChar(character)
            if (script == null) script = next else if (script != next) return Script.MIXED
        }
        return script ?: Script.OTHER
    }

    private fun scriptOfChar(character: Char): Script = when (character) {
        in '\u30A1'..'\u30FA', '\u30FC', '\u30FD', '\u30FE' -> Script.KATAKANA
        in '\u3041'..'\u3096', '\u309D', '\u309E' -> Script.HIRAGANA
        in '\u4E00'..'\u9FFF', in '\u3400'..'\u4DBF', '\u3005', '\u3006' -> Script.KANJI
        in 'a'..'z', in 'A'..'Z', in '\uFF21'..'\uFF3A', in '\uFF41'..'\uFF5A' -> Script.LATIN
        in '0'..'9', in '\uFF10'..'\uFF19' -> Script.DIGIT
        else -> Script.OTHER
    }

    /** A token that pairs with the token after it: `っ` `ゃ` `ょ` and their katakana forms. */
    fun bridgesSokuon(previous: ContainerToken, next: ContainerToken): Boolean {
        val core = previous.core
        if (core.isEmpty() || next.core.isEmpty()) return false
        if (previous.trailingBlank) return false
        return core.last() in BRIDGE_TRAILING
    }

    /** Whether [next] may join the block that currently ends with [previous]. */
    fun joins(previous: ContainerToken, next: ContainerToken, mode: GroupingMode): Boolean = when {
        bridgesSokuon(previous, next) -> true
        next.isHiraganaParticle -> mode == GroupingMode.LOOSE && previous.script == Script.HIRAGANA
        next.script == previous.script ->
            previous.script != Script.MIXED && previous.script != Script.OTHER
        mode == GroupingMode.LOOSE &&
            previous.script == Script.KANJI && next.script == Script.HIRAGANA -> true
        else -> false
    }

    /**
     * The word blocks of [tokens], in order. Every token lands in exactly one block, so a caller maps
     * a block back onto containers through [Block.firstIndex]/[Block.lastIndex].
     *
     * [plan] is a line the model grouped (see [PronunciationBoundary]): when it is present and
     * sound it decides inside the stretches the rules left open, and the rules keep everything they
     * can prove. A plan that crosses one of those proofs is refused whole and the rules' own grouping
     * is returned, so [plan] can only ever change a line the rules were guessing about. A null plan
     * takes the rules' path unchanged.
     */
    fun group(
        tokens: List<ContainerToken>,
        mode: GroupingMode = GroupingMode.LOOSE,
        maxKana: Int = WORD_MAX_KANA,
        gapBreakMs: Long = GAP_BREAK_MS,
        plan: PronunciationBoundary.BoundaryLine? = null,
    ): List<Block> {
        val baseline = groupByRules(tokens, mode, maxKana, gapBreakMs)
        if (plan == null) return baseline
        val boundaryTokens = boundaryTokens(tokens)
        val outcome = PronunciationBoundary.mergeLine(
            tokens = boundaryTokens,
            baseline = baseline.map { block -> block.firstIndex..block.lastIndex },
            plan = plan,
            hard = PronunciationBoundary.hardBoundaries(boundaryTokens),
            maxKana = maxKana,
        )
        return when (outcome) {
            is PronunciationBoundary.MergeOutcome.Rejected -> {
                android.util.Log.i(
                    LOG_TAG,
                    "plan rejected reason=" + outcome.error.code + " line=" + plan.lineKey,
                )
                baseline
            }
            is PronunciationBoundary.MergeOutcome.Groups -> {
                if (outcome.softened.isNotEmpty()) {
                    android.util.Log.i(
                        LOG_TAG,
                        "plan softened segments=" + outcome.softened + " line=" + plan.lineKey,
                    )
                }
                blocksOf(tokens, outcome.groups)
            }
        }
    }

    /**
     * The rules' own grouping, with no model involved: every call [group] makes with a null plan
     * takes exactly this path, so the rules cannot drift behind the plan's back.
     *
     * The order of the checks is what keeps the hard cases apart: nothing may cross a word boundary
     * ([ContainerToken.trailingBlank], a wrapped line, a katakana particle), and a source that already
     * wrote the whole word's reading in one container must not join its neighbour.
     */
    private fun groupByRules(
        tokens: List<ContainerToken>,
        mode: GroupingMode,
        maxKana: Int,
        gapBreakMs: Long,
    ): List<Block> {
        val blocks = ArrayList<Block>(tokens.size)
        var current = ArrayList<ContainerToken>(4)

        fun flush() {
            if (current.isEmpty()) return
            blocks += Block(current.toList())
            current = ArrayList(4)
        }

        for (token in tokens) {
            // A container holding only Apple's own separator whitespace ends the word before it.
            if (token.isBlankOnly) {
                flush()
                continue
            }
            // A container of nothing but marks shows no reading and is not a word: it neither joins
            // nor breaks the run in front of it (Apple emits a lone mark as a span of its own).
            if (token.isPureMark) continue
            if (current.isEmpty()) {
                current += token
                continue
            }
            val previous = current.last()
            val breaks = when {
                previous.trailingBlank -> true
                abs(previous.lineTop - token.lineTop) > LINE_TOLERANCE_PX -> true
                token.isKatakanaParticle -> true
                previous.readingIsWholeWord -> true
                else -> !joins(previous, token, mode)
            }
            if (breaks) {
                flush()
                current += token
                // A katakana particle that ended the word before it is a word of its own: it must not
                // be swallowed by the word after it either (`マジカル` | `ト` | `リック`, never
                // `majikaru` | `torikku`). The hiragana set is different — a particle that may join
                // still has to be able to (`でも` stays one word) — so only this set closes on itself.
                // A particle with nothing in front of it is not a particle but a loanword's head
                // (`トリック`, `トマト`), which is why the block-initial branch above does not flush.
                if (token.isKatakanaParticle) flush()
                continue
            }
            val gapBreaks = previous.end != null && token.begin != null &&
                token.begin - previous.end > gapBreakMs
            val tooLong = current.sumOf { it.kanaCount } + token.kanaCount > maxKana
            if (gapBreaks || tooLong) flush()
            current += token
        }
        flush()
        return blocks
    }

    /**
     * The view-tree tokens as [PronunciationBoundary] sees them.
     *
     * [PronunciationBoundary.BoundaryToken.kanaCount] is the core's own length and never "has a
     * reading": the ー of ステージ and a Latin word Apple left unromanized are one kana each, while a
     * container of nothing but marks (！) is the zero that makes it a token of its own. Passing a
     * zero for a kana that merely lacks a reading would lock it as if it were punctuation.
     */
    fun boundaryTokens(tokens: List<ContainerToken>): List<PronunciationBoundary.BoundaryToken> =
        tokens.map { token ->
            PronunciationBoundary.BoundaryToken(
                index = token.index,
                kana = token.core,
                kanaCount = token.core.length,
                reading = token.readingCore.ifEmpty { null },
                trailingBlank = token.trailingBlank,
                lineTop = token.lineTop,
                wholeWordReading = token.readingIsWholeWord,
            )
        }

    /** The blocks a partition of container indices describes, in ascending order. */
    fun blocksOf(tokens: List<ContainerToken>, groups: List<IntRange>): List<Block> {
        val blocks = ArrayList<Block>(groups.size)
        for (group in groups) {
            val slice = tokens.filter { it.index in group }
            if (slice.isNotEmpty()) blocks += Block(slice)
        }
        return blocks
    }

    /**
     * Whether this line is already word-level, in which case splicing it would *join* words the source
     * kept apart (`メイ` `キャップ` `ドレ` `スアップ`). Judged from the containers
     * themselves: mora-level data holds about one kana per container, word-level data several.
     */
    fun isWordLevelLine(
        tokens: List<ContainerToken>,
        threshold: Float = SOURCE_WORD_LEVEL_KANA_PER_CONTAINER,
    ): Boolean {
        val carriers = tokens.filter { !it.isPureMark }
        if (carriers.size < 2) return true
        val kana = carriers.sumOf { it.kanaCount }
        return kana.toFloat() / carriers.size > threshold
    }

    /** The block [index] falls in, or null when the container is a mark-only one. */
    fun blockOf(blocks: List<Block>, index: Int): Block? =
        blocks.firstOrNull { index >= it.firstIndex && index <= it.lastIndex }

    // ---------------------------------------------------------------------------------------
    // Geometry (pure numbers; the view layer measures and calls these)
    // ---------------------------------------------------------------------------------------

    /**
     * Where a block's fragments go, or null when the block must keep Apple's placement: fewer than
     * two usable fragments, no visible width, no glyph run, a fragment with no measured width, or a
     * block too wide for the run it sits under.
     */
    fun placement(
        span: Span,
        fragments: List<FragmentGeometry>,
        maxRatio: Float = MAX_BLOCK_WIDTH_RATIO,
    ): Splice? {
        if (fragments.size < 2) return null
        if (span.width <= 0f) return null
        if (fragments.any { it.width <= 0f }) return null
        val blockWidth = fragments.sumOf { it.visibleWidth.toDouble() }.toFloat()
        if (blockWidth <= 0f) return null
        if (blockWidth > span.width * maxRatio) return null
        val blockLeft = span.centre - blockWidth / 2f
        var cursor = blockLeft
        val targets = LinkedHashMap<Int, Float>(fragments.size)
        for (fragment in fragments) {
            targets[fragment.index] = cursor - fragment.leadingInset
            cursor += fragment.visibleWidth
        }
        return Splice(targets, blockWidth)
    }

    /** The distance a fragment's visible text advances: its width without Apple's trailing blanks. */
    fun fragmentAdvance(
        reading: TextView,
        measure: (TextView, Int, Int) -> Float = ::measureAdvance,
    ): Float {
        val text = reading.text?.toString().orEmpty()
        val range = visibleRange(text) ?: return 0f
        if (range.first == 0 && range.last + 1 == text.length) return reading.width.toFloat()
        return measure(reading, range.first, range.last + 1)
    }

    /** The advance of the marks and blanks a fragment opens with, which the word does not cover. */
    fun leadingInset(
        reading: TextView,
        measure: (TextView, Int, Int) -> Float = ::measureAdvance,
    ): Float {
        val text = reading.text?.toString().orEmpty()
        val leading = text.takeWhile(LyricsPronunciationRowCentring::isEdgeIgnorable).length
        if (leading == 0 || leading >= text.length) return 0f
        return measure(reading, 0, leading)
    }

    /** The first and last character index a fragment actually shows, or null when it shows nothing. */
    fun visibleRange(text: String): IntRange? {
        var start = 0
        var end = text.length
        while (start < end && LyricsPronunciationRowCentring.isEdgeIgnorable(text[start])) start++
        while (end > start && LyricsPronunciationRowCentring.isEdgeIgnorable(text[end - 1])) end--
        if (start >= end) return null
        return start..(end - 1)
    }

    private fun measureAdvance(reading: TextView, start: Int, end: Int): Float {
        val paint = runCatching { reading.paint }.getOrNull() ?: return 0f
        val text = reading.text?.toString().orEmpty()
        if (start < 0 || end > text.length || start >= end) return 0f
        return runCatching { paint.measureText(text, start, end) }.getOrDefault(0f)
    }

    // ---------------------------------------------------------------------------------------
    // View layer
    // ---------------------------------------------------------------------------------------

    /**
     * Splices [anchor]'s word when it needs splicing, and reports whether it did.
     *
     * False means "nothing to splice here": the container is alone, its word holds a single reading,
     * the line is already word-level, or a guard rejected the block. The caller then keeps the
     * single-container correction, so a row this object cannot help never changes behaviour.
     */
    fun alignRow(row: ViewGroup, anchor: ViewGroup): Boolean {
        val containers = LyricsPronunciationRowCentring.wordContainers(row)
        if (containers.size < 2) return false
        val anchorIndex = containers.indexOf(anchor)
        if (anchorIndex < 0) return false

        val tokens = containers.mapIndexed { index, container -> token(container, index) }
        // One key for both lookups: the line's kana join is how a plan and a timing row are found.
        val lineKey = tokens.joinToString("") { it.core }
        val timed = attachTimings(tokens, lineSpans?.invoke(lineKey))
        if (isWordLevelLine(timed)) return false

        val provider = boundaryPlans
        val plan = provider?.invoke(lineKey)
        if (provider != null && plan == null) logBoundaryMiss(lineKey)
        val block = blockOf(group(timed, plan = plan), anchorIndex) ?: return false
        if (block.fragments.size < 2) return false
        // A silent container inside the block would be skipped by the splice, which would slide every
        // fragment after it off its own kana; a block therefore only splices when every container it
        // covers shows a reading (Apple's whole-word reading in the first container never does).
        if (block.fragments.size != block.tokens.size) return false
        val span = span(row, containers, block) ?: return false

        val moves = ArrayList<Pair<ContainerToken, TextView>>(block.fragments.size)
        for (fragment in block.fragments) {
            val reading = LyricsPronunciationRowCentring.shownReading(containers[fragment.index]) ?: return false
            if (!isLeftToRight(fragment.readingCore)) return false
            if (reading.width <= 0) return false
            moves += fragment to reading
        }
        val geometry = moves.map { (fragment, reading) ->
            FragmentGeometry(
                index = fragment.index,
                leftInRow = LyricsPronunciationRowCentring.leftIn(row, reading).toFloat(),
                width = reading.width.toFloat(),
                visibleWidth = fragmentAdvance(reading),
                leadingInset = leadingInset(reading),
            )
        }
        val splice = placement(span, geometry) ?: return false

        if (shiftMode == ShiftMode.LAYOUT && pinWidths(containers, block)) {
            // The containers just changed width, so this pass's geometry is stale: the row's own
            // layout callback re-enters and computes the splice against the pinned widths.
            return true
        }

        for ((fragment, reading) in moves) {
            val target = splice.targetLefts[fragment.index] ?: return false
            LyricsPronunciationRowCentring.applyReadingOffset(row, reading, shiftMode, target)
        }
        if (VERBOSE_CENTRING_LOG) {
            logSpliceOnce(
                lineKey,
                "grouped run=" + block.firstIndex + ".." + block.lastIndex +
                    " fragments=" + block.fragments.size + " mode=" + shiftMode +
                    " span=" + span.left + ".." + span.right + " block=" + splice.blockWidth +
                    " targets=" + splice.targetLefts,
            )
        }
        return true
    }

    /**
     * In [ShiftMode.LAYOUT] the reading is no longer positioned by its own bounds, so the container can
     * be pinned to the glyphs it holds; without that a `wrap_content` recompute would re-measure the
     * reading-widened container and move the kana under it. Pins each container at most once.
     */
    private fun pinWidths(containers: List<ViewGroup>, block: Block): Boolean {
        var pinnedAny = false
        for (token in block.fragments) {
            val container = containers[token.index]
            if (pinned.put(container, Unit) != null) continue
            val glyphs = LyricsPronunciationRowCentring.wordChunks(
                container,
                LyricsPronunciationRowCentring.shownReading(container),
            )
            if (glyphs.isEmpty()) continue
            val glyphWidth = glyphs.fold(0f) { total, chunk -> total + chunk.width } +
                container.paddingStart + container.paddingEnd
            val width = glyphWidth.roundToInt()
            if (width <= 0 || container.width <= width) continue
            val params = container.layoutParams ?: continue
            params.width = width
            container.layoutParams = params
            container.requestLayout()
            pinnedAny = true
            android.util.Log.i("AMPP-Bndry", "pinned container=${token.index} width=$width")
        }
        return pinnedAny
    }

    /** Lines already reported as having no plan, so one missing plan logs once, not per layout pass. */
    private val boundaryMisses = Collections.synchronizedSet(HashSet<String>())

    /** One logcat line per line that the provider has no plan for; the key is truncated, never read. */
    private fun logBoundaryMiss(lineKey: String) {
        if (boundaryMisses.size > BOUNDARY_MISS_LOG_LIMIT) boundaryMisses.clear()
        if (!boundaryMisses.add(lineKey)) return
        android.util.Log.i("AMPP-Bndry", "bndry miss key=" + lineKey.take(24))
    }


    /** Last splice summary printed per line, so a layout pass does not repeat an unchanged one. */
    private val loggedSplices = Collections.synchronizedMap(HashMap<String, String>())

    /**
     * Prints one splice line per (line, whole-pixel targets).
     *
     * A row re-runs this on every layout pass, but the targets only move when a reading lands or the
     * widths change, so whole pixels are what is compared and sub-pixel jitter does not re-log. The
     * memory is bounded and rebuilt past [LOG_MEMORY_LIMIT].
     */
    private fun logSpliceOnce(lineKey: String, summary: String) {
        if (loggedSplices.size > LOG_MEMORY_LIMIT) loggedSplices.clear()
        if (loggedSplices.put(lineKey, summary) == summary) return
        android.util.Log.i(LOG_TAG, summary)
    }

    /** The values the rules read, taken from one inflated container. */
    fun token(container: ViewGroup, index: Int): ContainerToken {
        val reading = LyricsPronunciationRowCentring.shownReading(container)
        // The reading view must be excluded by identity: a container built without Apple's ids is
        // recognised by shape only, and there the reading is just another text child.
        val glyphs = LyricsPronunciationRowCentring.wordChunks(container, reading)
        val kana = glyphs.fold("") { text, chunk -> text + chunk.text.toString() }
        val glyphWidth = glyphs.fold(0f) { total, chunk -> total + chunk.width }
        val wholeWord = reading != null && glyphWidth > 0f &&
            reading.width > glyphWidth * WHOLE_WORD_READING_RATIO
        return ContainerToken(
            index = index,
            kana = kana,
            reading = reading?.text?.toString(),
            lineTop = container.top,
            readingIsWholeWord = wholeWord,
        )
    }

    /** The glyph stretch a block covers, with the marks and blanks at both ends cut off. */
    fun span(row: ViewGroup, containers: List<ViewGroup>, block: Block): Span? {
        if (block.firstIndex !in containers.indices || block.lastIndex !in containers.indices) return null
        val first = containers[block.firstIndex]
        val last = containers[block.lastIndex]
        val firstGlyphs = LyricsPronunciationRowCentring.wordChunks(
            first,
            LyricsPronunciationRowCentring.shownReading(first),
        )
        val lastGlyphs = LyricsPronunciationRowCentring.wordChunks(
            last,
            LyricsPronunciationRowCentring.shownReading(last),
        )
        if (firstGlyphs.isEmpty() || lastGlyphs.isEmpty()) return null
        val firstText = firstGlyphs.fold("") { text, chunk -> text + chunk.text.toString() }
        val lastText = lastGlyphs.fold("") { text, chunk -> text + chunk.text.toString() }
        val left = LyricsPronunciationRowCentring.advanceFromStart(
            row,
            firstGlyphs,
            LyricsPronunciationRowCentring.leadingEdge(firstText).length,
        )
        val right = LyricsPronunciationRowCentring.retreatFromEnd(
            row,
            lastGlyphs,
            LyricsPronunciationRowCentring.trailingEdge(lastText).length,
        )
        if (right <= left) return null
        return Span(left, right)
    }

    /** Whether a reading's visible text is left-to-right throughout; an RTL reading is never spliced. */
    fun isLeftToRight(text: String): Boolean = text.none { character ->
        character in '\u0590'..'\u08FF' ||
            character in '\uFB1D'..'\uFDFF' ||
            character in '\uFE70'..'\uFEFF'
    }

    /** Fills the tokens' timings from [spans], when the host has wired them. */
    fun attachTimings(
        tokens: List<ContainerToken>,
        spans: List<NativePronunciationSpan>?,
    ): List<ContainerToken> {
        if (spans.isNullOrEmpty()) return tokens
        return tokens.mapIndexed { index, token ->
            val span = spans.getOrNull(index) ?: return@mapIndexed token
            token.copy(begin = span.begin, end = span.end)
        }
    }

    /** The distance from a reading's current left to where the splice wants it. */
    fun offsetFor(targetLeft: Float, currentLeftInRow: Float): Float = targetLeft - currentLeftInRow
}
