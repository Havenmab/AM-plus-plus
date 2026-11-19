package dev.amenhancer.module.lyrics

import dev.amenhancer.module.hook.TtmlTimingPolicy
import dev.amenhancer.module.lyrics.online.RomanizationPolicy
import java.math.BigDecimal

data class TtmlFormatConversion(
    val ttml: String,
    val converted: Boolean,
)

/**
 * Rewrites the AMLL TTML format into the Apple Music format.
 *
 * The formats differ in where the translation and romanization tracks live.
 * AMLL keeps them inline in every lyric line; Apple declares them once in the
 * head, linked back through `itunes:key`, and leaves the body carrying lyrics:
 *
 * ```
 * <p itunes:key="L1"> …word spans… <span ttm:role="x-translation">…</span></p>
 *
 * <iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
 *   <translations><translation type="subtitle" xml:lang="zh-Hans"><text for="L1">…</text></translation></translations>
 *   <transliterations><transliteration xml:lang="ja-Latn"><text for="L1">…</text></transliteration></transliterations>
 * </iTunesMetadata>
 * ```
 *
 * The transliteration tag is **per song**, derived from the lyrics language
 * ([AppleRomanizationLanguage.transliterationLanguageTag]): `ja-Latn`, `ko-Latn` or
 * `zh-Latn` for a Japanese, Korean or Chinese document, else the neutral
 * `und-Latn`. The tag is the lane's name inside the app's own lyric model — its
 * native TTML parser keys each pronunciation by the element's language and
 * `getPronunciationLanguages` reports those keys — so a neutral tag leaves the
 * romanization parsed but unnamed, and nothing can then select it. The tag is
 * therefore written twice over: on a track this converter builds, and on a head
 * `<transliterations>` track the source already declared but left unnamed —
 * which is the shape AMLL itself ships
 * (`<transliterations><transliteration><text for="L1">…`), and the one whose
 * romanization the app parses under no language. An entry that already names a
 * language is never rewritten.
 *
 * A `<text>` carries whatever the source span held, so a line-by-line track
 * stays plain text and a word-by-word one keeps its timed `<span>`s. Spans
 * nested in a background vocal (`ttm:role="x-bg"`) join the same `<text>`,
 * wrapped in an `x-bg` span and in parentheses, which is how Apple sets a
 * background translation apart from the line it accompanies.
 *
 * A track that is written lists every keyed line: Android Apple Music walks the
 * entries alongside the lines instead of resolving `for`, so a line skipped
 * there shifts every later entry onto the wrong lyric — and a track with holes
 * is rejected outright. An untranslated line therefore holds a single space, and
 * a line translated only in its background opens with that same space ahead of
 * the `x-bg` span. A track no line contributed to is left out altogether.
 *
 * The tracks join the `<iTunesMetadata>` AMLL writes for `<songwriters>`, ahead
 * of what it holds, rather than opening a second one beside it. A file can also
 * arrive half in each format — an Apple transliteration in the head beside
 * translations still inline — so each kind is judged on its own: a kind the head
 * declares keeps its inline **translation** spans, because migrating it again
 * would duplicate the track, while its inline `x-roman` spans are **dropped**.
 *
 * The app's native parser knows only `x-bg` and `translation` role values
 * (`libandroidappmusic.so`'s only role literals), so an inline `x-roman` span is
 * never read as a pronunciation lane. On a Word-timed body the app's
 * `LyricsLineParser::processSpanElements` walks it as one more lyric span — with
 * no timing — which corrupts the line's word vector, while the romanization the
 * app does parse lives only in the head `<transliteration>` track. A declared
 * head track is therefore authoritative and the redundant, harmful body copy is
 * removed; an undeclared one is still migrated into the head as before.
 *
 * The background vocal left in the body loses its `begin` / `end`, because Apple
 * reads the vocal's extent from its own syllables, and moves to the front of the
 * line when it starts ahead of the first lyric syllable, because Apple places
 * the highlight in document order.
 *
 * Two shapes the AMLL database's axis tool left behind are normalized before
 * anything reads the document: a `<p>` whose only word is bare text gets that
 * word wrapped in a span carrying the line's own timing, and every `x-bg`
 * wrapper is put in the canonical shape — role on the outer span with no timing
 * there, the text and timing on an inner span. An already two-layer group loses
 * the redundant timing on its wrapper, so the whole output is one shape. Both
 * rewrites only touch a shape that is not yet canonical, so running the
 * converter twice changes nothing the second time.
 *
 * A reading AMLL wrote **for the line** is then given the per-word timing that
 * Apple's pronunciation row is built from. Apple inflates one pronunciation view
 * per body span and pairs it with the head entry's span at that place — the
 * AMLL files that already render carry one timed head span per lyric word,
 * timed exactly as the word it reads (see [expandLineLevelReadings]). AMLL's
 * inline form is one span of space-separated syllables for the whole line, which
 * used to migrate verbatim into the head as an untimed entry: the app then had
 * no span to put under any word, so the row stayed empty. Such a reading is now
 * split into one span per lyric word of its own line, every span carrying that
 * word's own `begin` / `end` — no timing is invented — with the
 * syllables handed out in order, proportional to the words' sung durations so
 * the reading advances with the highlight. A word count that matches the
 * syllable count maps one to one, which is exact. A reading that already
 * carries timing is left byte for byte, so the pass changes nothing on a second
 * run.
 *
 * The root language is resolved per song, not pinned. The document's own
 * `xml:lang`, else the caller's explicit language, names the **lyrics** language;
 * only a document that names none falls back to the script the body is written
 * in (kana → `ja`, Hangul → `ko`, Han → `zh`), and a body with no decisive script
 * keeps the neutral `und`. The old hardcoded `ko` named a language the song was
 * not in, so a Japanese document could advertise a Korean root beside its own
 * `ja-Latn` lane. Translations keep `zh-Hans`; because only one track of each
 * kind survives that constraint, the first translation and first romanization of
 * each line win — except inside a background group, where every `x-roman` span
 * is kept and concatenated, so a reading split across words loses nothing. The
 * transliterations tag follows the same lyrics language (see
 * above). The root also gets the `itunes:timing` Apple expects — `Word` when the
 * body carries timed syllables, `Line` otherwise. AMLL's `xmlns=""` on
 * `<metadata>` and `<div>` goes too: it drops the lyric subtree out of the TTML
 * namespace, leaving Apple no lines.
 *
 * Lines are then stably sorted by their numeric begin time, with head entries
 * following their keys. Same-start translations join with newlines in the
 * first entry; subsequent entries keep their space placeholders. Simultaneous
 * lyric voices, romanization, word timing and section ownership are retained.
 * Existing Apple tracks participate in this formatting as well.
 *
 * The rewrite is textual on purpose: whitespace between `<span>` tags carries
 * word separation, and a DOM round-trip would not preserve it. Only markup is
 * examined; lyric text nodes are copied verbatim.
 */
object AmllTtmlFormatConverter {

    private const val TTML_NAMESPACE = "http://www.w3.org/ns/ttml"
    private const val TTML_METADATA_NAMESPACE = "http://www.w3.org/ns/ttml#metadata"
    private const val ITUNES_NAMESPACE = "http://music.apple.com/lyric-ttml-internal"
    private const val ROLE_TRANSLATION = "x-translation"
    private const val ROLE_ROMAN = "x-roman"
    private const val ROLE_BACKGROUND = "x-bg"

    private const val TRANSLATION_LANGUAGE = "zh-Hans"

    /** How Apple labels a translation meant to be shown under the lyric. */
    private const val TRANSLATION_TYPE = "subtitle"

    /** The head lane item element, used to locate the attribute insertion point. */
    private const val TRANSLITERATION_ELEMENT = "transliteration"
    /** Apple's translated copy of a line, including its background words. */
    private const val REPLACEMENT_TYPE = "replacement"

    /** Stands in for a line the track has no text for, keeping the entry there. */
    private const val ABSENT_TEXT = " "

    /** A CDATA section's delimiters, whose inner text is lyric text like any other. */
    private const val CDATA_OPEN = "<![CDATA["
    private const val CDATA_CLOSE = "]]>"

    private val EMPTY_DEFAULT_NAMESPACE = Regex("""\s+xmlns\s*=\s*(?:""|'')""")
    private val DEFAULT_NAMESPACE = attributePattern("xmlns")
    private val TTM_DECLARATION = attributePattern("xmlns:ttm")
    private val ROLE_ATTRIBUTE = attributePattern("ttm:role")
    private val LANGUAGE_ATTRIBUTE = attributePattern("xml:lang")
    private val KEY_ATTRIBUTE = attributePattern("itunes:key")
    private val TYPE_ATTRIBUTE = attributePattern("type")
    private val FOR_ATTRIBUTE = attributePattern("for")
    private val BEGIN_ATTRIBUTE_VALUE = attributePattern("begin")
    private val END_ATTRIBUTE_VALUE = attributePattern("end")
    private val TIMING_ATTRIBUTE_VALUE = attributePattern("itunes:timing")
    private val ITUNES_DECLARATION = attributePattern("xmlns:itunes")
    private val SPAN_TIMING =
        Regex("""\s(?:begin|end)\s*=\s*"[^"]*"|\s(?:begin|end)\s*=\s*'[^']*'""")
    private val PARENTHESIZED = Regex("""^\s*[(（].*[)）]\s*$""", RegexOption.DOT_MATCHES_ALL)
    private val WHITESPACE = Regex("""\s+""")
    private val HEAD_SPAN = Regex("""(?is)<span\b""")
    private val TIMELINE_OFFSET = Regex("([0-9]+(?:\\.[0-9]+)?)(ms|s|m|h)")
    private val TIMELINE_NUMBER = Regex("[0-9]+(?:\\.[0-9]+)?")

    private val AUXILIARY_ROLES = setOf(ROLE_TRANSLATION, ROLE_ROMAN, ROLE_BACKGROUND)

    private fun attributePattern(name: String) =
        Regex("""\s${Regex.escape(name)}\s*=\s*"([^"]*)"|\s${Regex.escape(name)}\s*=\s*'([^']*)'""")

    /** Source attribute content already carries entities; choose a safe delimiter. */
    private fun quoteRawAttributeValue(value: String): String =
        if ('"' in value) "'$value'" else "\"$value\""

    /**
     * Rewrites [ttml] into Apple's format; [lyricLanguage] names the lyrics
     * language when the caller knows it better than the document does.
     *
     * [debug] is a debug-build sink: it receives exactly two lines about this one
     * conversion — the source document's shape on entry and the head lane and
     * timing verdict on exit — so a device log can tell "the source carried no
     * reading" from "the reading was lost on the way in". It is null by default,
     * which keeps the converter silent and adds no work to the rewrite; a sink
     * that throws is swallowed, because a diagnostic must never fail a
     * conversion.
     */
    fun toAppleFormat(
        ttml: String,
        lyricLanguage: String? = null,
        debug: ((String) -> Unit)? = null,
    ): TtmlFormatConversion {
        if (ttml.isEmpty()) return TtmlFormatConversion(ttml, false)
        if (debug != null) emitDebug(debug) { "amll convert in " + sourceSummary(ttml) }
        // The AMLL database still carries shapes an axis tool left behind: a
        // `<p>` whose only word is bare text, and `x-bg` groups whose
        // role-carrying wrapper also holds the timing. Neither is the canonical
        // shape the app reads, so they are normalized before anything else. The
        // pass is idempotent and is the only source of the indices the rest of
        // the rewrite uses. A line-level reading is then given per-word timing,
        // so the head lane can carry it to the word Apple draws it under.
        val expansion = expandLineLevelReadings(normalizeLegacyShapes(ttml))
        val conversion = convert(ttml, expansion.ttml, lyricLanguage)
        if (debug != null) {
            emitDebug(debug) { "amll convert out " + conversionSummary(conversion, expansion.readings) }
        }
        return conversion
    }

    /** The rewrite itself, once [normalized] carries canonical body shapes. */
    private fun convert(
        original: String,
        normalized: String,
        lyricLanguage: String?,
    ): TtmlFormatConversion {
        // Judged per kind, so a head that already declares one track does not
        // hide the other kind still sitting inline. An `<iTunesMetadata>` on its
        // own is no such signal: AMLL writes one to carry `<songwriters>`.
        val declared = declaredTracks(normalized)
        // A declared `<transliterations>` block whose entries name no language is
        // still a rewrite: the native parser registers a pronunciation lane only
        // under the language its `<transliteration>` element carries, so an
        // unnamed block parses the romanization yet advertises no lane to select.
        val unnamed = unnamedTransliterations(normalized)
        // The head already speaks for every kind the body carries, so this is
        // Apple's own document and nothing, the root included, is rewritten —
        // but a legacy shape normalized above is still a conversion. Its
        // timeline is still formatted: the sort is idempotent and Apple's own
        // documents benefit from it too.
        if (declared.any && unnamed.isEmpty() && !hasUndeclaredInlineTrack(normalized, declared)) {
            val formatted = formatTimeline(normalized)
            return TtmlFormatConversion(formatted, formatted != original)
        }
        // Read before `normalizeTags` pins the root: the source document's own
        // language is the song's lyrics language. A document that names an
        // unsupported (or no) language falls back to the script its lyrics are
        // written in, and only an undeterminable one keeps the neutral tag.
        val writtenLanguage = transliterationLanguage(normalized, lyricLanguage)
        val rootLanguage = lyricsLanguage(normalized, lyricLanguage)
        val migrated = migrateAuxiliaryTracks(normalized, declared, writtenLanguage, unnamed)
        // Migrating a line's only auxiliary span away can leave its lyric bare
        // where the source hid it beside that span, so the body's lines are
        // wrapped once more. This pass is deliberately body-only: a head lane is
        // a `<text for=…>` entry, not a body `<p>`, and must never be given a
        // span — the app reads its translation text verbatim.
        val canonical = wrapExposedBareLines(migrated)
        val body = nextElement(canonical, "body", 0)
        val normalizedRoot = normalizeTags(
            canonical,
            wordTimed = body != null && hasTimedSpan(canonical, body),
            language = rootLanguage,
        )
        // Lines and head entries are ordered by their start time last, once the
        // document is in Apple's shape.
        val result = formatTimeline(normalizedRoot)
        return TtmlFormatConversion(
            ttml = result,
            converted = result != original,
        )
    }

    /**
     * Normalizes the shapes AMLL's axis tool left behind into the canonical ones
     * the app reads:
     *
     *  - a `<p>` whose lyric is bare text (`<p …>Yeah</p>`) gets one inner
     *    `<span>` carrying the line's own `begin` / `end`, so the single word the
     *    line holds is timed. A `<p>` that already has child elements is
     *    untouched.
     *  - an `x-bg` wrapper is put in the canonical shape its corpus already
     *    mostly uses: `ttm:role="x-bg"` on the outer span with **no timing
     *    there**, and the text plus timing on an inner `<span>`. A single-layer
     *    group (`<span ttm:role="x-bg" begin end>text</span>`) gets the inner
     *    span it is missing, taking the outer's timing; an already two-layer
     *    group keeps its inner spans and only loses the now-redundant `begin` /
     *    `end` from the wrapper, so the whole output is one shape. Only the outer
     *    `x-bg` wrapper is touched — a plain word or line span keeps its timing.
     *
     * The inner span deliberately takes the parent's `begin` / `end`: the
     * per-word matching that consumes the converted document aligns a lane by
     * span start and end, so the line's only word must carry the line's own
     * extent — exactly the extent the two-layer groups give each of their words.
     *
     * Every rule fires only on a shape that is not yet canonical, so a second
     * pass finds nothing to do and returns the document byte-for-byte. A
     * background group with neither an inner element to lean on nor text and
     * timing to move (blank text) is left as it is, so the wrapper's timing is
     * never dropped on its own. The `x-bg` rule covers the whole document, so a
     * legacy `x-bg` inside a declared translation or transliteration lane in the
     * head is normalized by it; the line rule is confined to the body, and a
     * `<text for=…>` head entry is never wrapped.
     */
    private fun normalizeLegacyShapes(ttml: String): String {
        val edits = mutableListOf<Edit>()
        wrapBareLines(ttml, edits)
        normalizeBackgroundSpans(ttml, edits)
        return applyEdits(ttml, edits)
    }

    /**
     * Wraps the bare-text **body** lines migration exposed, and nothing else.
     *
     * Only the body is scanned: the head lanes this runs after are already
     * canonical, and a `<translations>` / `<transliterations>` entry is a
     * `<text for=…>`, not a body `<p>`. Wrapping one would hand the app a span
     * where it expects the translation text itself, which is precisely the
     * failure mode this pass must not have.
     */
    private fun wrapExposedBareLines(ttml: String): String {
        val edits = mutableListOf<Edit>()
        wrapBareLines(ttml, edits)
        return applyEdits(ttml, edits)
    }

    /**
     * Gives a bare-text `<p>` in the **body** the timed inner span it is missing.
     * The head is out of scope: no head lane holds a `<p>`, and none may be
     * wrapped even if a document carried one there.
     */
    private fun wrapBareLines(ttml: String, edits: MutableList<Edit>) {
        val body = nextElement(ttml, "body", 0) ?: return
        var cursor = body.contentStart
        while (true) {
            val line = nextElement(ttml, "p", cursor) ?: break
            if (line.end > body.contentEnd) break
            cursor = line.end
            if (line.selfClosing) continue
            if (childElements(ttml, line).isNotEmpty()) continue
            val text = content(ttml, line)
            if (text.isBlank()) continue
            val begin = attribute(line.attributes, BEGIN_ATTRIBUTE_VALUE)
            val end = attribute(line.attributes, END_ATTRIBUTE_VALUE)
            if (begin == null && end == null) continue
            edits += Edit(
                line.contentStart,
                line.contentEnd,
                "<span${timingAttributes(begin, end)}>$text</span>",
            )
        }
    }

    /**
     * Puts every `x-bg` wrapper into the canonical shape the app accepts: the
     * role on the outer span and no timing there, the text and its timing on an
     * inner span. A single-layer group gains the inner span it is missing; an
     * already two-layer group only loses the redundant `begin` / `end` from its
     * wrapper, because its inner spans already carry the timing the app reads.
     * A plain word or line span is never touched.
     */
    private fun normalizeBackgroundSpans(ttml: String, edits: MutableList<Edit>) {
        elementsNamed(ttml, "span").forEach { span ->
            if (span.selfClosing) return@forEach
            if (attribute(span.attributes, ROLE_ATTRIBUTE) != ROLE_BACKGROUND) return@forEach
            val begin = attribute(span.attributes, BEGIN_ATTRIBUTE_VALUE)
            val end = attribute(span.attributes, END_ATTRIBUTE_VALUE)
            val openTag = ttml.substring(span.start, span.contentStart)
            if (childElements(ttml, span).isNotEmpty()) {
                // Inner spans already time the group, so the wrapper's own copy
                // is redundant; drop only it and keep every other attribute.
                if (begin == null && end == null) return@forEach
                edits += Edit(span.start, span.contentStart, SPAN_TIMING.replace(openTag, ""))
                return@forEach
            }
            // A wrapper with no inner span keeps its timing as the fallback when
            // there is nothing to move into one.
            val text = content(ttml, span)
            if (text.isBlank()) return@forEach
            if (begin == null && end == null) return@forEach
            edits += Edit(
                span.start,
                span.end,
                "${SPAN_TIMING.replace(openTag, "")}<span${timingAttributes(begin, end)}>$text</span></span>",
            )
        }
    }

    /**
     * Splits a line-level reading into one timed span per lyric word of its line.
     *
     * Apple draws a Word-timed line's pronunciation row one view per **body** span
     * and reads each view's text from the head entry's span at that place; the
     * AMLL files that render today carry one head span per lyric word, timed
     * exactly as the word it reads. AMLL's inline form instead writes the whole
     * line's syllables into one `x-roman` span with no timing, which the
     * migration carries into the head verbatim — the entry then has no span for
     * any word, so nothing is ever drawn. This pass gives that reading the
     * per-word shape before it is migrated.
     *
     * The split only ever **reuses body timing**: each span takes its word's own
     * `begin` / `end`, so the reading advances with Apple's highlight,
     * and the syllables keep their order. A reading whose syllable count equals
     * the line's word count maps one to one; otherwise each syllable goes to the
     * word whose share of the line's sung duration contains its mid-point, which
     * is the closest guess a source without timing allows and never moves a
     * syllable past a later one. A word left without a syllable keeps an empty
     * span so the head and body stay one-to-one, which is the pairing the app
     * reads. A reading that already carries its own timing and a line with no
     * timed words (Apple reads those from the line's own text) both stay
     * untouched, so a second pass finds nothing to split.
     *
     * The background lane is handled the same way with the `x-bg` group's own
     * words: its reading belongs under the small row, not under the lyric.
     */
    private fun expandLineLevelReadings(ttml: String): ReadingExpansion {
        val body = nextElement(ttml, "body", 0) ?: return ReadingExpansion(ttml, 0)
        val edits = mutableListOf<Edit>()
        var cursor = body.contentStart
        while (true) {
            val line = nextElement(ttml, "p", cursor) ?: break
            if (line.end > body.contentEnd) break
            cursor = line.end
            if (line.selfClosing) continue
            val children = childElements(ttml, line)
            val words = lyricWords(ttml, children)
            children.forEach { child ->
                when (attribute(child.attributes, ROLE_ATTRIBUTE)) {
                    ROLE_ROMAN -> expandReading(ttml, child, words)?.let(edits::add)
                    ROLE_BACKGROUND -> {
                        val nested = childElements(ttml, child)
                        val groupWords = lyricWords(ttml, nested)
                        nested
                            .filter { attribute(it.attributes, ROLE_ATTRIBUTE) == ROLE_ROMAN }
                            .forEach { reading ->
                                expandReading(ttml, reading, groupWords)?.let(edits::add)
                            }
                    }
                }
            }
        }
        return ReadingExpansion(applyEdits(ttml, edits), edits.size)
    }

    /** The line's lyric words: timed spans that carry no role of their own. */
    private fun lyricWords(ttml: String, children: List<Element>): List<Element> =
        children.filter { child ->
            isElement(ttml, child.start, "span") &&
                attribute(child.attributes, ROLE_ATTRIBUTE) == null &&
                attribute(child.attributes, BEGIN_ATTRIBUTE_VALUE) != null &&
                attribute(child.attributes, END_ATTRIBUTE_VALUE) != null
        }

    /**
     * [reading]'s content rewritten as one timed span per word in [words], or null
     * when it already carries timing, holds markup, or has no word to sit under.
     */
    private fun expandReading(ttml: String, reading: Element, words: List<Element>): Edit? {
        if (reading.selfClosing || words.isEmpty()) return null
        val inner = content(ttml, reading).trim()
        if (inner.isEmpty() || '<' in inner) return null
        val tokens = inner.split(WHITESPACE).filter(String::isNotEmpty)
        if (tokens.isEmpty()) return null
        // Only a real reading is worth placing under the words. AMLL's database
        // also carries files whose "romanization" is a translation, and a line
        // whose body is Latin has nothing to romanize; the module's own
        // pronunciation gate already answers both questions, so a reading it would
        // refuse is left exactly as the source wrote it.
        val lyric = words.joinToString(separator = "") { content(ttml, it) }
        if (RomanizationPolicy.sanitize(lyric, inner) == null) return null
        val extents = words.map { word ->
            val begin = attribute(word.attributes, BEGIN_ATTRIBUTE_VALUE) ?: return null
            val end = attribute(word.attributes, END_ATTRIBUTE_VALUE) ?: return null
            val start = timelineTime(begin) ?: return null
            val finish = timelineTime(end) ?: return null
            WordExtent(begin, end, (finish - start).toDouble())
        }
        val owners = assignTokens(tokens.size, extents.map(WordExtent::seconds))
        val grouped = List(extents.size) { mutableListOf<String>() }
        owners.forEachIndexed { index, owner -> grouped[owner] += tokens[index] }
        val replacement = buildString {
            extents.forEachIndexed { index, extent ->
                append("<span begin=\"").append(extent.begin).append("\" end=\"")
                append(extent.end).append("\">")
                append(grouped[index].joinToString(" "))
                append("</span>")
            }
        }
        return Edit(reading.contentStart, reading.contentEnd, replacement)
    }

    /**
     * Which word owns each syllable, in order. An equal count maps one to one;
     * otherwise the syllable's own mid-point position decides, so the result is
     * always an ordered partition of the reading over the words. A line with no
     * readable duration falls back to one equal share per word.
     */
    private fun assignTokens(count: Int, seconds: List<Double>): List<Int> {
        if (count == seconds.size) return List(count) { it }
        val total = seconds.sum()
        val weights = if (total > 0.0) seconds else List(seconds.size) { 1.0 }
        val sum = weights.sum()
        val bounds = MutableList(weights.size + 1) { 0.0 }
        weights.forEachIndexed { index, weight -> bounds[index + 1] = bounds[index] + weight / sum }
        bounds[bounds.lastIndex] = 1.0
        return List(count) { token ->
            val position = (token + 0.5) / count
            var owner = 0
            while (owner < weights.size - 1 && bounds[owner + 1] <= position) owner += 1
            owner
        }
    }

    /** One word's own extent, in the source's own time strings. */
    private data class WordExtent(val begin: String, val end: String, val seconds: Double)

    /** The body after the reading pass, plus how many readings it rewrote. */
    private data class ReadingExpansion(val ttml: String, val readings: Int)

    // --- Debug-only diagnostics -------------------------------------------

    /**
     * Hands one debug line to [sink]. The rewrite never depends on it, so a sink
     * that throws (a closing log, a full queue) is swallowed rather than failing
     * the conversion.
     */
    private fun emitDebug(sink: (String) -> Unit, line: () -> String) {
        runCatching { sink(line()) }
    }

    /**
     * What a source document offers the pronunciation lane, counted before the
     * rewrite: how many inline role spans each kind has, whether the head already
     * declares a lane and under which language, and how Apple will time it.
     */
    private fun sourceSummary(ttml: String): String {
        val declared = declaredTracks(ttml)
        return buildString {
            append("bytes=").append(ttml.length)
            append(" inlineRoman=").append(countRole(ttml, ROLE_ROMAN))
            append(" inlineTranslation=").append(countRole(ttml, ROLE_TRANSLATION))
            append(" background=").append(countRole(ttml, ROLE_BACKGROUND))
            append(' ').append(headSummary(ttml))
            append(' ').append(timingSummary(ttml))
            if (declared.any) append(" declared=yes")
        }
    }

    /** What the rewritten document hands Apple, with the timing verdict it will be read under. */
    private fun conversionSummary(conversion: TtmlFormatConversion, readings: Int): String =
        buildString {
            append("bytes=").append(conversion.ttml.length)
            append(" converted=").append(conversion.converted)
            append(" readable=").append(TtmlInputPolicy.isAcceptable(conversion.ttml))
            append(' ').append(timingSummary(conversion.ttml))
            append(" expandedReadings=").append(readings)
            append(' ').append(headSummary(conversion.ttml))
        }

    /**
     * The head `<transliterations>` lane as Apple reads it: its language tags,
     * how many `<text>` entries it holds, how many of those carry timed spans, and
     * how many are still one untimed line — the number that decides whether any
     * word can show a reading.
     */
    private fun headSummary(ttml: String): String {
        val block = nextElement(ttml, "transliterations", 0) ?: return "head=none"
        val tags = mutableListOf<String>()
        var entries = 0
        var timed = 0
        var spans = 0
        var cursor = block.contentStart
        while (true) {
            val track = nextElement(ttml, "transliteration", cursor) ?: break
            if (track.end > block.contentEnd) break
            cursor = track.end
            tags += attribute(track.attributes, LANGUAGE_ATTRIBUTE)?.takeIf(String::isNotBlank) ?: "none"
            childElements(ttml, track).forEach { entry ->
                if (!isElement(ttml, entry.start, "text")) return@forEach
                entries += 1
                val inner = content(ttml, entry)
                if (SPAN_TIMING.containsMatchIn(inner)) timed += 1
                spans += HEAD_SPAN.findAll(inner).count()
            }
        }
        return "headTags=" + tags.joinToString(",").ifEmpty { "none" } +
            " headEntries=" + entries + " headTimedEntries=" + timed + " headSpans=" + spans
    }

    /** How many elements carry one role value; the diagnostic's only count. */
    private fun countRole(ttml: String, role: String): Int {
        var count = 0
        var index = 0
        while (true) {
            val open = ttml.indexOf('<', index)
            if (open < 0) return count
            index = open + 1
            val literal = literalEnd(ttml, open)
            if (literal > 0) {
                index = literal
                continue
            }
            val end = tagEnd(ttml, open)
            if (end < 0) return count
            if (attribute(ttml.substring(open, end + 1), ROLE_ATTRIBUTE) == role) count += 1
        }
    }

    /**
     * The timing verdict: the root declaration the source gate reads, beside
     * whether the body actually carries timed syllables. A source can declare
     * nothing and still be word-timed, which is exactly what the converter then
     * writes onto the root.
     */
    private fun timingSummary(ttml: String): String {
        val body = nextElement(ttml, "body", 0)
        return buildString {
            append("timing=").append(if (TtmlTimingPolicy.isWord(ttml)) "WORD" else "LINE")
            append(" words=").append(if (body != null && hasTimedSpan(ttml, body)) "yes" else "no")
        }
    }
    /** The `begin` / `end` attributes for a synthesized span, in that order. */
    private fun timingAttributes(begin: String?, end: String?): String = buildString {
        if (begin != null) append(" begin=\"").append(begin).append('"')
        if (end != null) append(" end=\"").append(end).append('"')
    }

    /**
     * Every [name] element in [text], in document order and including nested
     * ones, so a legacy `x-bg` is found wherever it sits.
     */
    private fun elementsNamed(text: String, name: String): List<Element> {
        val elements = mutableListOf<Element>()
        var index = 0
        while (true) {
            val element = nextElement(text, name, index) ?: break
            elements += element
            index = if (element.selfClosing) element.end else element.contentStart
        }
        return elements
    }

    /**
     * The `<transliteration xml:lang>` tag for [ttml]: the caller's explicit
     * [declared] language when it names one of HLE's romanization languages, else
     * the document's own root `xml:lang`, else the script the lyrics are actually
     * written in (kana → Japanese, Hangul → Korean, Han → Chinese). A document
     * that offers none of those — an English song, or a body the converter cannot
     * read — keeps the neutral tag, because naming a language the song is not in
     * would be worse than naming none.
     */
    private fun transliterationLanguage(ttml: String, declared: String?): String {
        listOfNotNull(declared, documentLanguage(ttml))
            .firstNotNullOfOrNull(AppleRomanizationLanguage::transliterationScriptLanguage)
            ?.let { return "$it-Latn" }
        return AppleRomanizationLanguage.transliterationLanguageTag(inferredLyricsLanguage(ttml))
    }

    /** The root `<tt>`'s own `xml:lang`, which AMLL uses for the lyric language. */
    private fun documentLanguage(ttml: String): String? =
        nextElement(ttml, "tt", 0)?.let { attribute(it.attributes, LANGUAGE_ATTRIBUTE) }

    /**
     * The language the root `<tt xml:lang>` is written with: the caller's
     * explicit language, else the document's own declaration, else the script the
     * body's **lyrics** are actually written in (kana → `ja`, Hangul → `ko`, Han
     * → `zh`). The auxiliary lanes are not read, so the song's own language can
     * never be taken from a translation or a romanization.
     *
     * The root names the **lyrics** language, not the transliteration tag, so a
     * declared language is preserved even when it is outside HLE's romanization
     * set (an English song still declares `en`). Only a document that names none
     * and whose body has no decisive script falls back to the neutral `und` — the
     * old hardcoded `ko` named a language the song was not in.
     */
    private fun lyricsLanguage(ttml: String, declared: String?): String {
        val explicit = declared?.trim()?.takeIf(String::isNotEmpty)
        val root = documentLanguage(ttml)?.trim()?.takeIf(String::isNotEmpty)
        return explicit ?: root ?: inferredLyricsLanguage(ttml)
    }

    /**
     * The language the **body's lyric text** is written in, from the first
     * script-bearing code point that is not Latin: kana is decisive for Japanese
     * even beside the Han it shares, Hangul for Korean, and bare Han for Chinese.
     *
     * Only the lyrics are read. The content of an `x-translation` span is the
     * translation and of an `x-roman` span the romanization, and neither names
     * the language the song is sung in. Reading them is what made an English
     * song whose only Han sits in its Chinese translation declare `zh` — the
     * same primary language as its own `zh-Hans` translation lane, which the app
     * then declines to offer, because a Chinese song needs no Chinese
     * translation. Disregarding the auxiliary lanes keeps the root on the
     * language of the lyrics (here `und`, a Latin body), so the translation lane
     * is never named by the song's own language. A body with no decisive script
     * is `und`.
     */
    private fun inferredLyricsLanguage(ttml: String): String {
        val body = nextElement(ttml, "body", 0) ?: return "und"
        val lyrics = StringBuilder()
        appendLyricText(ttml, body.contentStart, body.contentEnd, lyrics)
        var hasHan = false
        var index = 0
        while (index < lyrics.length) {
            val codePoint = lyrics.codePointAt(index)
            when (Character.UnicodeScript.of(codePoint)) {
                Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA,
                -> return "ja"
                Character.UnicodeScript.HANGUL -> return "ko"
                Character.UnicodeScript.HAN -> hasHan = true
                else -> Unit
            }
            index += Character.charCount(codePoint)
        }
        return if (hasHan) "zh" else "und"
    }

    /**
     * Appends the lyric text of `[from, to)` to [out], leaving out the content of
     * every translation and romanization span and every tag, comment and
     * attribute value. A background vocal (`x-bg`) is a lyric and is read; an
     * auxiliary span nested inside it is still skipped.
     */
    private fun appendLyricText(ttml: String, from: Int, to: Int, out: StringBuilder) {
        var index = from
        while (index < to) {
            val open = ttml.indexOf('<', index)
            if (open < 0 || open >= to) {
                out.append(ttml, index, to)
                return
            }
            out.append(ttml, index, open)
            val literal = literalEnd(ttml, open)
            if (literal > 0) {
                if (ttml.startsWith(CDATA_OPEN, open) &&
                    literal >= open + CDATA_OPEN.length + CDATA_CLOSE.length
                ) {
                    out.append(ttml, open + CDATA_OPEN.length, literal - CDATA_CLOSE.length)
                }
                index = literal
                continue
            }
            val end = tagEnd(ttml, open)
            if (end < 0) return
            val name = elementName(ttml, open, end)
            val child = name?.let { parseElement(ttml, it, open, end) }
            if (child == null) {
                index = end + 1
                continue
            }
            val role = attribute(child.attributes, ROLE_ATTRIBUTE)
            if (role != ROLE_TRANSLATION && role != ROLE_ROMAN) {
                appendLyricText(ttml, child.contentStart, child.contentEnd, out)
            }
            index = child.end
        }
    }

    /**
     * Copies background words from Apple's simplified replacement track into
     * the timed words already present in the body. Only the word text changes;
     * foreground text, tags, attributes, timing and surrounding whitespace stay
     * byte-for-byte intact. Ambiguous or unsupported structure is left alone.
     */
    fun repairSimplifiedReplacementBackgrounds(ttml: String): String {
        if (!ttml.contains(ROLE_BACKGROUND) || !ttml.contains(REPLACEMENT_TYPE) ||
            !TtmlInputPolicy.isAcceptable(ttml)
        ) {
            return ttml
        }
        val root = documentRoot(ttml) ?: return ttml
        if (attribute(root.attributes, DEFAULT_NAMESPACE) != TTML_NAMESPACE ||
            attribute(root.attributes, TTM_DECLARATION) != TTML_METADATA_NAMESPACE ||
            attribute(root.attributes, ITUNES_DECLARATION) != ITUNES_NAMESPACE
        ) {
            return ttml
        }

        val rootChildren = childElements(ttml, root)
        val head = rootChildren.singleOrNull { isElement(ttml, it.start, "head") } ?: return ttml
        val body = rootChildren.singleOrNull { isElement(ttml, it.start, "body") } ?: return ttml
        if (!namespacesAreSupported(head.attributes, TTML_NAMESPACE) ||
            !namespacesAreSupported(body.attributes, TTML_NAMESPACE)
        ) {
            return ttml
        }

        val headChildren = childElements(ttml, head)
        val metadata = headChildren.singleOrNull { isElement(ttml, it.start, "metadata") }
            ?: return ttml
        if (!namespacesAreSupported(metadata.attributes, TTML_NAMESPACE)) return ttml

        val metadataChildren = childElements(ttml, metadata)
        val appleMetadata = metadataChildren.singleOrNull {
            isElement(ttml, it.start, "iTunesMetadata")
        } ?: return ttml
        if (attribute(appleMetadata.attributes, DEFAULT_NAMESPACE) != ITUNES_NAMESPACE ||
            !namespacesAreSupported(appleMetadata.attributes, ITUNES_NAMESPACE)
        ) {
            return ttml
        }

        val appleChildren = childElements(ttml, appleMetadata)
        val translations = appleChildren.singleOrNull {
            isElement(ttml, it.start, "translations")
        } ?: return ttml
        if (!namespacesAreSupported(translations.attributes, ITUNES_NAMESPACE)) return ttml

        val replacements = childElements(ttml, translations).filter { child ->
            isElement(ttml, child.start, "translation") &&
                attribute(child.attributes, TYPE_ATTRIBUTE) == REPLACEMENT_TYPE &&
                attribute(child.attributes, LANGUAGE_ATTRIBUTE) == TRANSLATION_LANGUAGE
        }
        val replacement = replacements.singleOrNull() ?: return ttml
        if (!namespacesAreSupported(replacement.attributes, ITUNES_NAMESPACE)) return ttml

        val replacementByKey = linkedMapOf<String, Element>()
        childElements(ttml, replacement).forEach { text ->
            if (!isElement(ttml, text.start, "text") ||
                !namespacesAreSupported(text.attributes, ITUNES_NAMESPACE)
            ) {
                return ttml
            }
            val key = attribute(text.attributes, FOR_ATTRIBUTE)?.takeIf(String::isNotBlank)
                ?: return ttml
            if (replacementByKey.put(key, text) != null) return ttml
        }
        if (replacementByKey.isEmpty()) return ttml

        val lines = bodyLines(ttml, body) ?: return ttml
        val linesByKey = linkedMapOf<String, Element>()
        lines.forEach { line ->
            val key = attribute(line.attributes, KEY_ATTRIBUTE)?.takeIf(String::isNotBlank)
            if (key != null && linesByKey.put(key, line) != null) return ttml
        }

        val edits = mutableListOf<Edit>()
        linesByKey.forEach { (key, line) ->
            val replacementText = replacementByKey[key] ?: return@forEach
            edits += replacementBackgroundEdits(ttml, line, replacementText) ?: return@forEach
        }
        return applyEdits(ttml, edits)
    }

    /** Skips leading XML literals and accepts a canonical document element. */
    private fun documentRoot(ttml: String): Element? {
        var index = if (ttml.isNotEmpty() && ttml[0] == '\uFEFF') 1 else 0
        while (index < ttml.length) {
            while (index < ttml.length && ttml[index].isWhitespace()) index += 1
            if (index >= ttml.length) return null
            if (ttml.startsWith("<?", index) || ttml.startsWith("<!--", index)) {
                index = literalEnd(ttml, index)
                continue
            }
            if (!ttml.startsWith("<", index) || ttml.startsWith("<!", index) ||
                ttml.startsWith("</", index)
            ) {
                return null
            }
            val end = tagEnd(ttml, index)
            if (end < 0 || elementName(ttml, index, end) != "tt") return null
            return parseElement(ttml, "tt", index, end)
        }
        return null
    }

    /** Finds keyed lyric lines through the body's canonical div nests. */
    private fun bodyLines(ttml: String, body: Element): List<Element>? {
        val lines = mutableListOf<Element>()

        fun collect(parent: Element): Boolean {
            childElements(ttml, parent).forEach { child ->
                when {
                    isElement(ttml, child.start, "div") -> {
                        if (!namespacesAreSupported(child.attributes, TTML_NAMESPACE) ||
                            !collect(child)
                        ) {
                            return false
                        }
                    }
                    isElement(ttml, child.start, "p") -> {
                        if (!namespacesAreSupported(child.attributes, TTML_NAMESPACE)) return false
                        lines += child
                    }
                    else -> return false
                }
            }
            return true
        }

        return if (collect(body)) lines else null
    }

    /**
     * A local namespace may be repeated with its canonical value, but changing
     * the default or Apple/TTML prefixes makes the lexical structure ambiguous.
     */
    private fun namespacesAreSupported(attributes: String, vararg defaults: String): Boolean {
        val defaultNamespace = attribute(attributes, DEFAULT_NAMESPACE)
        if (defaultNamespace != null && defaults.none { it == defaultNamespace }) return false
        val ttm = attribute(attributes, TTM_DECLARATION)
        if (ttm != null && ttm != TTML_METADATA_NAMESPACE) return false
        val itunes = attribute(attributes, ITUNES_DECLARATION)
        return itunes == null || itunes == ITUNES_NAMESPACE
    }

    /** Direct x-bg groups. A renamed or prefixed group is unsupported. */
    private fun backgroundGroups(
        ttml: String,
        parent: Element,
        vararg defaults: String,
    ): List<Element>? {
        val groups = mutableListOf<Element>()
        childElements(ttml, parent).forEach { child ->
            if (attribute(child.attributes, ROLE_ATTRIBUTE) != ROLE_BACKGROUND) return@forEach
            if (!isElement(ttml, child.start, "span") ||
                !namespacesAreSupported(child.attributes, *defaults)
            ) {
                return null
            }
            groups += child
        }
        return groups
    }

    /**
     * Timed leaf spans in one background group. Their begin/end pair must be
     * readable and unique so a replacement word cannot be attached twice.
     */
    private fun timedWords(
        ttml: String,
        background: Element,
        vararg defaults: String,
    ): List<Element>? {
        val words = mutableListOf<Element>()
        val ranges = mutableSetOf<Pair<BigDecimal, BigDecimal>>()

        fun hasTimingBelow(element: Element): Boolean =
            childElements(ttml, element).any { child ->
                attribute(child.attributes, BEGIN_ATTRIBUTE_VALUE) != null ||
                    attribute(child.attributes, END_ATTRIBUTE_VALUE) != null ||
                    hasTimingBelow(child)
            }

        fun collect(parent: Element): Boolean {
            childElements(ttml, parent).forEach { child ->
                if (!isElement(ttml, child.start, "span") ||
                    !namespacesAreSupported(child.attributes, *defaults) ||
                    attribute(child.attributes, ROLE_ATTRIBUTE) != null
                ) {
                    return false
                }
                val begin = attribute(child.attributes, BEGIN_ATTRIBUTE_VALUE)
                val end = attribute(child.attributes, END_ATTRIBUTE_VALUE)
                if ((begin == null) != (end == null)) return false
                if (begin == null) {
                    if (!collect(child)) return false
                } else {
                    val start = timelineTime(begin) ?: return false
                    val finish = timelineTime(end) ?: return false
                    if (finish <= start || hasTimingBelow(child) || !ranges.add(start to finish)) {
                        return false
                    }
                    words += child
                }
            }
            return true
        }

        if (!collect(background) || words.isEmpty()) return null
        return words
    }

    /** Raw text of a word span, or null when it contains unsupported markup. */
    private fun wordText(ttml: String, word: Element): String? {
        if (word.selfClosing) return null
        return content(ttml, word).takeUnless { '<' in it }
    }

    /**
     * Copies only the source x-bg words whose begin/end pair exists in the
     * matching body group. Both groups must have the same complete time map.
     */
    private fun replacementBackgroundEdits(
        ttml: String,
        line: Element,
        replacementText: Element,
    ): List<Edit>? {
        val targetGroups = backgroundGroups(ttml, line, TTML_NAMESPACE) ?: return null
        if (targetGroups.isEmpty()) return emptyList()
        val sourceGroups = backgroundGroups(
            ttml,
            replacementText,
            TTML_NAMESPACE,
        ) ?: return null
        if (sourceGroups.size != targetGroups.size) return null
        // The metadata parent is Apple XML; each source group must explicitly switch to TTML.
        if (sourceGroups.any { attribute(it.attributes, DEFAULT_NAMESPACE) != TTML_NAMESPACE }) return null

        val edits = mutableListOf<Edit>()
        targetGroups.zip(sourceGroups).forEach { (targetGroup, sourceGroup) ->
            listOf(BEGIN_ATTRIBUTE_VALUE, END_ATTRIBUTE_VALUE).forEach { pattern ->
                val targetTime = attribute(targetGroup.attributes, pattern)
                val sourceTime = attribute(sourceGroup.attributes, pattern)
                if (targetTime != null && sourceTime != null) {
                    val targetValue = timelineTime(targetTime) ?: return null
                    val sourceValue = timelineTime(sourceTime) ?: return null
                    if (targetValue.compareTo(sourceValue) != 0) return null
                }
            }
            val targetWords = timedWords(ttml, targetGroup, TTML_NAMESPACE) ?: return null
            val sourceWords = timedWords(
                ttml,
                sourceGroup,
                TTML_NAMESPACE,
            ) ?: return null
            if (sourceWords.size != targetWords.size) return null

            val sourceByTime = sourceWords.associateBy { word ->
                val start = timelineTime(attribute(word.attributes, BEGIN_ATTRIBUTE_VALUE))
                    ?: return null
                val end = timelineTime(attribute(word.attributes, END_ATTRIBUTE_VALUE))
                    ?: return null
                start to end
            }
            if (sourceByTime.size != sourceWords.size) return null

            targetWords.forEach { target ->
                val start = timelineTime(attribute(target.attributes, BEGIN_ATTRIBUTE_VALUE))
                    ?: return null
                val end = timelineTime(attribute(target.attributes, END_ATTRIBUTE_VALUE))
                    ?: return null
                val source = sourceByTime[start to end] ?: return null
                val replacement = wordText(ttml, source)?.takeIf(String::isNotBlank) ?: return null
                val current = wordText(ttml, target) ?: return null
                if (replacement != current) {
                    edits += Edit(target.contentStart, target.contentEnd, replacement)
                }
            }
        }
        return edits
    }

    /** Sort lines and head entries together, retaining simultaneous voices. */
    private fun formatTimeline(ttml: String): String {
        val body = nextElement(ttml, "body", 0) ?: return ttml
        val root = TimelineSection(body, "", "")
        val lines = mutableListOf<TimelineLine>()
        if (!collectTimeline(ttml, root, emptyList(), lines) || lines.isEmpty()) return ttml
        val ordered = lines.sortedBy(TimelineLine::begin)
        val edits = mutableListOf<Edit>()
        if (ordered != lines) {
            edits += Edit(body.contentStart, body.contentEnd, renderTimeline(ttml, root, ordered))
        }

        // Without unique keys a head entry cannot be associated unambiguously.
        val keys = ordered.map { attribute(it.element.attributes, KEY_ATTRIBUTE)?.takeIf(String::isNotBlank) }
        if ((keys.any { it == null } || keys.distinct().size != keys.size) && declaredTracks(ttml).any) return ttml
        if (keys.all { it != null } && keys.distinct().size == keys.size) {
            val metadata = nextElement(ttml, "metadata", 0)
            if (metadata != null) {
                listOf("translations" to "translation", "transliterations" to "transliteration")
                    .forEach { (containerName, itemName) ->
                        val container = nextElement(ttml, containerName, metadata.contentStart)
                            ?.takeIf { it.end <= metadata.contentEnd } ?: return@forEach
                        childElements(ttml, container).filter { isElement(ttml, it.start, itemName) }
                            .forEach { track ->
                                val formatted = formatTrack(ttml, track, ordered, containerName == "translations")
                                    ?: return ttml
                                formatted.edit?.let(edits::add)
                            }
                    }
            }
        }
        return applyEdits(ttml, edits)
    }

    /** Unknown structure or timestamps are left intact instead of guessed. */
    private fun collectTimeline(
        ttml: String,
        section: TimelineSection,
        ancestors: List<TimelineSection>,
        lines: MutableList<TimelineLine>,
    ): Boolean {
        val children = childElements(ttml, section.element)
        if (children.isEmpty()) return false
        var cursor = section.element.contentStart
        var pending = ""
        children.forEach { child ->
            val prefix = pending + ttml.substring(cursor, child.start)
            pending = ""
            when {
                isElement(ttml, child.start, "p") -> {
                    val begin = timelineTime(attribute(child.attributes, BEGIN_ATTRIBUTE_VALUE)) ?: return false
                    lines += TimelineLine(child, begin, ancestors, prefix)
                }
                isElement(ttml, child.start, "div") -> {
                    if (childElements(ttml, child).isEmpty()) {
                        pending = prefix + ttml.substring(child.start, child.end)
                    } else {
                        val nested = TimelineSection(child, prefix, "")
                        if (!collectTimeline(ttml, nested, ancestors + nested, lines)) return false
                    }
                }
                else -> return false
            }
            cursor = child.end
        }
        section.tail = pending + ttml.substring(cursor, section.element.contentEnd)
        return true
    }

    /** Reopen interleaving sections so each line keeps its original section. */
    private fun renderTimeline(ttml: String, root: TimelineSection, lines: List<TimelineLine>): String = buildString {
        val opened = mutableListOf<TimelineSection>()
        val seen = mutableSetOf<TimelineSection>()
        val last = mutableMapOf<TimelineSection, Int>()
        lines.forEachIndexed { index, line -> line.ancestors.forEach { last[it] = index } }
        fun closeTo(depth: Int, index: Int) {
            while (opened.size > depth) {
                val section = opened.removeAt(opened.lastIndex)
                if (last[section] == index) append(section.tail)
                append(ttml, section.element.contentEnd, section.element.end)
            }
        }
        lines.forEachIndexed { index, line ->
            val common = opened.zip(line.ancestors).takeWhile { (a, b) -> a === b }.size
            closeTo(common, index - 1)
            line.ancestors.drop(common).forEach { section ->
                if (seen.add(section)) append(section.prefix)
                var tag = ttml.substring(section.element.start, section.element.contentStart)
                val run = lines.drop(index).takeWhile { section in it.ancestors }
                tag = replaceAttribute(tag, BEGIN_ATTRIBUTE_VALUE, attribute(line.element.attributes, BEGIN_ATTRIBUTE_VALUE))
                val endLine = run.mapNotNull { candidate ->
                    timelineTime(attribute(candidate.element.attributes, END_ATTRIBUTE_VALUE))?.let { it to candidate }
                }.maxByOrNull { it.first }?.second
                tag = replaceAttribute(tag, END_ATTRIBUTE_VALUE, endLine?.let { attribute(it.element.attributes, END_ATTRIBUTE_VALUE) })
                append(tag)
                opened += section
            }
            append(line.prefix)
            append(ttml, line.element.start, line.element.end)
        }
        closeTo(0, lines.lastIndex)
        append(root.tail)
    }

    private fun replaceAttribute(tag: String, pattern: Regex, value: String?): String {
        if (value == null) return tag
        val match = pattern.find(tag) ?: return tag
        val group = match.groups[1] ?: match.groups[2] ?: return tag
        return tag.replaceRange(group.range, value)
    }

    /** Keep an entry per line even after a group's translation is combined. */
    private fun formatTrack(
        ttml: String,
        track: Element,
        lines: List<TimelineLine>,
        merge: Boolean,
    ): TrackFormatting? {
        val entries = childElements(ttml, track)
        if (entries.isEmpty()) return TrackFormatting(null)
        if (entries.any { !isElement(ttml, it.start, "text") }) return null
        val keys = lines.map { requireNotNull(attribute(it.element.attributes, KEY_ATTRIBUTE)) }
        val byKey = entries.associateBy { attribute(it.attributes, FOR_ATTRIBUTE) }
        if (byKey.size != entries.size || byKey.keys.any { it !in keys }) return null
        val texts = keys.map { key ->
            byKey[key]?.let { content(ttml, it) }?.takeIf(String::isNotBlank) ?: ABSENT_TEXT
        }.toMutableList()
        if (merge) {
            lines.indices.groupBy { lines[it].begin }.values.filter { it.size > 1 }.forEach { group ->
                val combined = group.map { texts[it] }.filter(String::isNotBlank).distinct().joinToString("\n")
                texts[group.first()] = combined.ifBlank { ABSENT_TEXT }
                group.drop(1).forEach { texts[it] = ABSENT_TEXT }
            }
        }
        val prefixes = mutableMapOf<Element, String>()
        var cursor = track.contentStart
        entries.forEach { entry ->
            prefixes[entry] = ttml.substring(cursor, entry.start)
            cursor = entry.end
        }
        val formatted = buildString {
            keys.forEachIndexed { index, key ->
                val entry = byKey[key]
                if (entry == null) {
                    append("<text for=").append(quoteRawAttributeValue(key)).append('>')
                    append(texts[index]).append("</text>")
                } else {
                    append(prefixes.getValue(entry))
                    if (entry.selfClosing) {
                        append("<text${entry.attributes}>${texts[index]}</text>")
                    } else {
                        append(ttml, entry.start, entry.contentStart)
                        append(texts[index])
                        append(ttml, entry.contentEnd, entry.end)
                    }
                }
            }
            append(ttml, cursor, track.contentEnd)
        }
        return TrackFormatting(
            if (formatted == content(ttml, track)) null else Edit(track.contentStart, track.contentEnd, formatted),
        )
    }

    /** Exact decimal comparison makes 01:30.100 and 90.1s the same start. */
    private fun timelineTime(value: String?): BigDecimal? {
        val text = value?.trim() ?: return null
        val offset = TIMELINE_OFFSET.matchEntire(text)
        val time = if (offset != null) {
            val multiplier = when (offset.groupValues[2]) {
                "ms" -> BigDecimal("0.001")
                "m" -> BigDecimal(60)
                "h" -> BigDecimal(3600)
                else -> BigDecimal.ONE
            }
            offset.groupValues[1].toBigDecimal().multiply(multiplier)
        } else {
            val parts = text.split(':').takeIf { it.size in 1..3 } ?: return null
            var seconds = BigDecimal.ZERO
            parts.forEach { part ->
                if (!TIMELINE_NUMBER.matches(part)) return null
                seconds = seconds.multiply(BigDecimal(60)).add(part.toBigDecimal())
            }
            seconds
        }
        return time.stripTrailingZeros()
    }

    private class TimelineSection(val element: Element, val prefix: String, var tail: String)
    private data class TrackFormatting(val edit: Edit?)
    private data class TimelineLine(
        val element: Element,
        val begin: BigDecimal,
        val ancestors: List<TimelineSection>,
        val prefix: String,
    )

    private fun declaredTracks(ttml: String) = DeclaredTracks(
        translations = nextElement(ttml, "translations", 0) != null,
        transliterations = nextElement(ttml, "transliterations", 0) != null,
    )

    /**
     * The `<transliteration>` elements of the head track that carry no
     * `xml:lang`, in document order. AMLL's own documents declare the track that
     * way (`<transliterations><transliteration><text for="L1">…`), so the lane is
     * preserved by [migrateAuxiliaryTracks] yet never named — the exact shape the
     * device log's `languages=` (empty) beside a parsed romanization came from.
     * An entry that already names a language is left exactly as it was.
     */
    private fun unnamedTransliterations(ttml: String): List<Element> {
        val block = nextElement(ttml, "transliterations", 0) ?: return emptyList()
        val unnamed = mutableListOf<Element>()
        var cursor = block.contentStart
        while (true) {
            val item = nextElement(ttml, "transliteration", cursor) ?: break
            if (item.end > block.contentEnd) break
            cursor = item.end
            if (attribute(item.attributes, LANGUAGE_ATTRIBUTE).isNullOrBlank()) unnamed += item
        }
        return unnamed
    }

    /** True when the body still carries a kind the head leaves undeclared. */
    private fun hasUndeclaredInlineTrack(ttml: String, declared: DeclaredTracks): Boolean {
        val body = nextElement(ttml, "body", 0) ?: return false
        var index = body.contentStart
        while (index < body.contentEnd) {
            val open = ttml.indexOf('<', index)
            if (open < 0 || open >= body.contentEnd) return false
            val literal = literalEnd(ttml, open)
            if (literal > 0) {
                index = literal
                continue
            }
            val end = tagEnd(ttml, open)
            if (end < 0) return false
            when (attribute(ttml.substring(open, end + 1), ROLE_ATTRIBUTE)) {
                ROLE_TRANSLATION -> if (!declared.translations) return true
                ROLE_ROMAN -> if (!declared.transliterations) return true
            }
            index = end + 1
        }
        return false
    }

    /** Moves inline `x-translation` / `x-roman` spans into a head `<iTunesMetadata>`. */
    private fun migrateAuxiliaryTracks(
        ttml: String,
        declared: DeclaredTracks,
        transliterationLanguage: String,
        unnamedTransliterations: List<Element>,
    ): String {
        // A preserved head track that names no language still has to be named:
        // the tag is the lane's key in the app's own lyric model, so without it
        // the romanization is parsed but no language is ever advertised. An
        // entry that already carries one is not touched.
        val edits = mutableListOf<Edit>()
        unnamedTransliterations.forEach { item ->
            val insertAt = item.start + 1 + TRANSLITERATION_ELEMENT.length
            edits += Edit(insertAt, insertAt, " xml:lang=\"$transliterationLanguage\"")
        }
        val metadata = nextElement(ttml, "metadata", 0) ?: return applyEdits(ttml, edits)

        val translations = mutableListOf<TrackEntry>()
        val transliterations = mutableListOf<TrackEntry>()

        var cursor = metadata.end
        while (true) {
            val line = nextElement(ttml, "p", cursor) ?: break
            cursor = line.end
            val key = attribute(line.attributes, KEY_ATTRIBUTE) ?: continue
            collectLine(ttml, line, key, declared, translations, transliterations, edits)
        }

        // Every keyed line contributes an entry, so a line the source left
        // untranslated still holds its place; whether any entry carries text
        // decides if the track is written at all. The edits are applied either
        // way, because a line may carry a background vocal that needs its body
        // rewrite even when no track follows.
        if (translations.none(TrackEntry::hasText) && transliterations.none(TrackEntry::hasText)) {
            return applyEdits(ttml, edits)
        }

        edits += declareTracks(
            ttml,
            metadata,
            buildTracks(translations, transliterations, transliterationLanguage),
            declared,
        )
        return applyEdits(ttml, edits)
    }

    /**
     * Places the tracks in the head, joining the `<iTunesMetadata>` AMLL already
     * wrote for `<songwriters>` — ahead of what it holds, the order Apple uses —
     * rather than opening a second one beside it.
     */
    private fun declareTracks(
        ttml: String,
        metadata: Element,
        tracks: String,
        declared: DeclaredTracks,
    ): Edit {
        val container = nextElement(ttml, "iTunesMetadata", metadata.start)
            ?.takeIf { it.end <= metadata.contentEnd }
        return when {
            container == null && metadata.selfClosing -> Edit(
                metadata.start,
                metadata.end,
                "<metadata${metadata.attributes}>${wrapTracks(tracks)}</metadata>",
            )
            container == null -> Edit(metadata.contentEnd, metadata.contentEnd, wrapTracks(tracks))
            container.selfClosing -> Edit(
                container.start,
                container.end,
                "<iTunesMetadata${container.attributes}>$tracks</iTunesMetadata>",
            )
            else -> {
                // A newly migrated transliteration belongs after a translations
                // track that was already present. Every other new track starts
                // at the front, where buildTracks keeps Apple's kind order.
                val insertAt = if (declared.translations && !declared.transliterations) {
                    nextElement(ttml, "translations", container.contentStart)
                        ?.takeIf { it.end <= container.contentEnd }
                        ?.end
                        ?: container.contentStart
                } else {
                    container.contentStart
                }
                Edit(insertAt, insertAt, tracks)
            }
        }
    }

    private fun collectLine(
        ttml: String,
        line: Element,
        key: String,
        declared: DeclaredTracks,
        translations: MutableList<TrackEntry>,
        transliterations: MutableList<TrackEntry>,
        edits: MutableList<Edit>,
    ) {
        // Only one track of each kind survives the parser constraint, so the
        // first translation and first romanization of the line are kept. An
        // empty span is no translation, so a later non-empty one still counts.
        // A background romanization is the exception: a background group may
        // split one reading across several `x-roman` spans, so every one of
        // them is kept, concatenated in document order.
        var translation: String? = null
        var roman: String? = null
        var backgroundTranslation: String? = null
        var backgroundRoman: String? = null

        val children = childElements(ttml, line)
        children.forEach { child ->
            when (attribute(child.attributes, ROLE_ATTRIBUTE)) {
                ROLE_TRANSLATION -> if (!declared.translations) {
                    if (translation == null) translation = auxiliaryText(ttml, child)
                    edits += removal(ttml, child, line.contentStart)
                }
                ROLE_ROMAN -> {
                    // The app's native parser knows only `x-bg` and `translation`
                    // role values, so an inline `x-roman` span is never read as a
                    // pronunciation lane: on a Word-timed body
                    // `LyricsLineParser::processSpanElements` treats it as one
                    // more (untimed) lyric span and corrupts the line's word
                    // vector. When the head already declares the track the body
                    // copy is therefore dropped; otherwise it is migrated into
                    // the head track as before.
                    if (!declared.transliterations && roman == null) {
                        roman = auxiliaryText(ttml, child)
                    }
                    edits += removal(ttml, child, line.contentStart)
                }
                ROLE_BACKGROUND -> {
                    val nested = childElements(ttml, child)
                    val removals = mutableListOf<Edit>()
                    nested.forEach { span ->
                        when (attribute(span.attributes, ROLE_ATTRIBUTE)) {
                            ROLE_TRANSLATION -> if (!declared.translations) {
                                if (backgroundTranslation == null) {
                                    backgroundTranslation = auxiliaryText(ttml, span)
                                }
                                removals += removal(ttml, span, child.contentStart)
                            }
                            ROLE_ROMAN -> {
                                // Every `x-roman` span of the group is kept, in
                                // document order, so a reading split across
                                // several of them loses none of its words. A
                                // blank span contributes nothing, exactly as
                                // `auxiliaryText` treats every other extraction.
                                if (!declared.transliterations) {
                                    val text = auxiliaryText(ttml, span)
                                    if (text != null) {
                                        backgroundRoman = backgroundRoman.orEmpty() + text
                                    }
                                }
                                removals += removal(ttml, span, child.contentStart)
                            }
                        }
                    }
                    edits += backgroundEdits(ttml, line, children, child, nested, removals)
                }
            }
        }

        translations += TrackEntry(key, translation, backgroundTranslation)
        transliterations += TrackEntry(key, roman, backgroundRoman)
    }

    /**
     * Rewrites the background vocal that stays in the body: its `begin` / `end`
     * go, because Apple reads the vocal's extent from its syllables, and a vocal
     * starting ahead of the lyrics moves to the front of the line, because Apple
     * places the highlight in document order.
     */
    private fun backgroundEdits(
        ttml: String,
        line: Element,
        siblings: List<Element>,
        background: Element,
        nested: List<Element>,
        auxiliaryRemovals: List<Edit>,
    ): List<Edit> {
        // Without syllables to read the extent from, the attributes are the only
        // timing the vocal has, so they stay and it keeps its place.
        val syllable = firstSyllableBegin(ttml, nested) ?: return auxiliaryRemovals

        val openTag = ttml.substring(background.start, background.contentStart)
        val rewrite = auxiliaryRemovals +
            Edit(background.start, background.contentStart, SPAN_TIMING.replace(openTag, ""))
        val lyric = firstSyllableBegin(ttml, siblings)
        if (siblings.firstOrNull() === background || lyric == null || syllable >= lyric) return rewrite

        return listOf(
            Edit(line.contentStart, line.contentStart, render(ttml, background.start, background.end, rewrite)),
            removal(ttml, background, line.contentStart),
        )
    }

    /** Begin time of the first timed lyric span among [candidates], if any. */
    private fun firstSyllableBegin(ttml: String, candidates: List<Element>): Double? =
        candidates.asSequence()
            .filter { attribute(it.attributes, ROLE_ATTRIBUTE) !in AUXILIARY_ROLES }
            .mapNotNull { clockSeconds(attribute(it.attributes, BEGIN_ATTRIBUTE_VALUE)) }
            .firstOrNull()

    /** Seconds in `3:02.550` or AMLL's offset form `1.5s`; null when unreadable. */
    private fun clockSeconds(value: String?): Double? {
        val clock = value?.let { if (':' in it) it else it.removeSuffix("s") } ?: return null
        val parts = clock.split(':').takeIf { it.size in 1..3 } ?: return null
        var seconds = 0.0
        parts.forEach { part ->
            seconds = seconds * 60 + (part.toDoubleOrNull() ?: return null)
        }
        return seconds
    }

    /**
     * The body of one `<text>`: the line's own text, then its background one
     * wrapped in an `x-bg` span. A line without text of its own still opens
     * with [ABSENT_TEXT], both to keep an entry per line and because Apple
     * reads what precedes the `x-bg` span as the main translation.
     */
    private fun StringBuilder.appendEntryText(entry: TrackEntry) {
        append(entry.main ?: ABSENT_TEXT)
        val background = entry.background ?: return
        append("<span xmlns=\"").append(TTML_NAMESPACE)
        append("\" xmlns:ttm=\"").append(TTML_METADATA_NAMESPACE)
        append("\" ttm:role=\"").append(ROLE_BACKGROUND).append("\">")
        append(parenthesized(background))
        append("</span>")
    }

    /** Apple parenthesizes a background track; AMLL leaves that to the reader. */
    private fun parenthesized(text: String) =
        if (PARENTHESIZED.matches(text)) text else "($text)"

    private fun wrapTracks(tracks: String) =
        "<iTunesMetadata xmlns=\"$ITUNES_NAMESPACE\">$tracks</iTunesMetadata>"

    private fun buildTracks(
        translations: List<TrackEntry>,
        transliterations: List<TrackEntry>,
        transliterationLanguage: String,
    ): String = buildString {
        appendTrack(translations, "translations", "translation", TRANSLATION_LANGUAGE, TRANSLATION_TYPE)
        appendTrack(transliterations, "transliterations", "transliteration", transliterationLanguage)
    }

    private fun StringBuilder.appendTrack(
        entries: List<TrackEntry>,
        container: String,
        item: String,
        language: String,
        type: String? = null,
    ) {
        // Nothing to say for this kind at all, so the track is not opened —
        // rather than opened over a column of placeholders.
        if (entries.none(TrackEntry::hasText)) return
        append('<').append(container).append('>')
        append('<').append(item)
        if (type != null) append(" type=\"").append(type).append('"')
        append(" xml:lang=\"").append(language).append("\">")
        entries.forEach { entry ->
            append("<text for=").append(quoteRawAttributeValue(entry.key)).append('>')
            appendEntryText(entry)
            append("</text>")
        }
        append("</").append(item).append('>')
        append("</").append(container).append('>')
    }

    /** A span's content, or `null` when it holds nothing worth carrying over. */
    private fun auxiliaryText(ttml: String, element: Element): String? =
        content(ttml, element).takeIf(String::isNotBlank)

    private fun content(ttml: String, element: Element): String =
        if (element.selfClosing) "" else ttml.substring(element.contentStart, element.contentEnd)

    /** Deletes the auxiliary span together with the whitespace that preceded it. */
    private fun removal(ttml: String, element: Element, lowerBound: Int): Edit {
        var start = element.start
        while (start > lowerBound && ttml[start - 1].isWhitespace()) start -= 1
        return Edit(start, element.end, "")
    }

    /**
     * Declares the timing mode and lyric language on the root and drops the
     * empty default namespace override from every other element.
     */
    private fun normalizeTags(ttml: String, wordTimed: Boolean, language: String): String {
        val output = StringBuilder(ttml.length)
        var index = 0
        var rootSeen = false
        while (index < ttml.length) {
            val open = ttml.indexOf('<', index)
            if (open < 0) {
                output.append(ttml, index, ttml.length)
                break
            }
            output.append(ttml, index, open)
            val literal = literalEnd(ttml, open)
            if (literal > 0) {
                output.append(ttml, open, literal)
                index = literal
                continue
            }
            val end = tagEnd(ttml, open)
            if (end < 0) {
                output.append(ttml, open, ttml.length)
                break
            }
            val tag = ttml.substring(open, end + 1)
            output.append(
                when {
                    !rootSeen && isElement(ttml, open, "tt") -> {
                        rootSeen = true
                        withRootAttributes(tag, wordTimed, language)
                    }
                    tag.startsWith("</") -> tag
                    else -> EMPTY_DEFAULT_NAMESPACE.replace(tag, "")
                },
            )
            index = end + 1
        }
        return output.toString()
    }

    private fun withRootAttributes(tag: String, wordTimed: Boolean, language: String): String {
        val insertAt = if (tag.endsWith("/>")) tag.length - 2 else tag.length - 1
        // Both attributes are authoritative here, so any inherited value is
        // dropped before the resolved one is appended.
        var head = tag.substring(0, insertAt)
        head = LANGUAGE_ATTRIBUTE.replace(head, "")
        head = TIMING_ATTRIBUTE_VALUE.replace(head, "")
        val additions = buildString {
            if (!ITUNES_DECLARATION.containsMatchIn(head)) {
                append(" xmlns:itunes=\"").append(ITUNES_NAMESPACE).append('"')
            }
            append(" itunes:timing=\"").append(if (wordTimed) "Word" else "Line").append('"')
            append(" xml:lang=\"").append(language).append('"')
        }
        return head.trimEnd() + additions + tag.substring(insertAt)
    }

    private fun hasTimedSpan(text: String, body: Element): Boolean {
        var index = body.contentStart
        while (index < body.contentEnd) {
            val open = text.indexOf('<', index)
            if (open < 0 || open >= body.contentEnd) return false
            val literal = literalEnd(text, open)
            if (literal > 0) {
                index = literal
                continue
            }
            val end = tagEnd(text, open)
            if (end < 0) return false
            if (isElement(text, open, "span") &&
                attribute(text.substring(open, end + 1), BEGIN_ATTRIBUTE_VALUE) != null
            ) {
                return true
            }
            index = end + 1
        }
        return false
    }

    private fun applyEdits(ttml: String, edits: List<Edit>): String =
        if (edits.isEmpty()) ttml else render(ttml, 0, ttml.length, edits)

    /** [ttml] between [from] and [to], with every edit falling inside applied. */
    private fun render(ttml: String, from: Int, to: Int, edits: List<Edit>): String =
        buildString(to - from) {
            var cursor = from
            edits.sortedBy { it.start }.forEach { edit ->
                if (edit.start < cursor || edit.end > to) return@forEach
                append(ttml, cursor, edit.start)
                append(edit.replacement)
                cursor = edit.end
            }
            append(ttml, cursor, to)
        }

    private fun attribute(attributes: String, pattern: Regex): String? {
        val match = pattern.find(attributes) ?: return null
        return if (match.groups[1] != null) match.groupValues[1] else match.groupValues[2]
    }

    // --- Minimal XML element scanning -------------------------------------

    private data class Edit(val start: Int, val end: Int, val replacement: String)

    /** Which auxiliary kinds the head already carries, so they are not moved twice. */
    private data class DeclaredTracks(
        val translations: Boolean,
        val transliterations: Boolean,
    ) {
        val any get() = translations || transliterations
    }

    /**
     * One line's place in a track. Both texts are `null` when the line said
     * nothing of this kind, which still earns it a placeholder entry as long as
     * some other line spoke up.
     */
    private data class TrackEntry(
        val key: String,
        val main: String?,
        val background: String?,
    ) {
        val hasText get() = main != null || background != null
    }

    private data class Element(
        val attributes: String,
        val start: Int,
        val contentStart: Int,
        val contentEnd: Int,
        val end: Int,
        val selfClosing: Boolean,
    )

    /** The next `name` element at or after [from], or `null` when there is none. */
    private fun nextElement(text: String, name: String, from: Int): Element? {
        var index = from
        while (index < text.length) {
            val open = text.indexOf('<', index)
            if (open < 0) return null
            val literal = literalEnd(text, open)
            if (literal > 0) {
                index = literal
                continue
            }
            val end = tagEnd(text, open)
            if (end < 0) return null
            if (isElement(text, open, name)) return parseElement(text, name, open, end)
            index = end + 1
        }
        return null
    }

    private fun parseElement(text: String, name: String, open: Int, tagEnd: Int): Element? {
        val selfClosing = text[tagEnd - 1] == '/'
        val attributesEnd = if (selfClosing) tagEnd - 1 else tagEnd
        val attributes = text.substring(open + 1 + name.length, attributesEnd)
        if (selfClosing) {
            return Element(attributes, open, tagEnd + 1, tagEnd + 1, tagEnd + 1, true)
        }
        var depth = 1
        var index = tagEnd + 1
        while (index < text.length) {
            val open2 = text.indexOf('<', index)
            if (open2 < 0) return null
            val literal = literalEnd(text, open2)
            if (literal > 0) {
                index = literal
                continue
            }
            val end2 = tagEnd(text, open2)
            if (end2 < 0) return null
            if (isCloseTag(text, open2, name)) {
                depth -= 1
                if (depth == 0) {
                    return Element(attributes, open, tagEnd + 1, open2, end2 + 1, false)
                }
            } else if (isElement(text, open2, name) && text[end2 - 1] != '/') {
                depth += 1
            }
            index = end2 + 1
        }
        return null
    }

    /** Direct child elements of [parent], in document order. */
    private fun childElements(text: String, parent: Element): List<Element> {
        val children = mutableListOf<Element>()
        var index = parent.contentStart
        while (index < parent.contentEnd) {
            val open = text.indexOf('<', index)
            if (open < 0 || open >= parent.contentEnd) break
            val literal = literalEnd(text, open)
            if (literal > 0) {
                index = literal
                continue
            }
            val end = tagEnd(text, open)
            if (end < 0) break
            val name = elementName(text, open, end)
            val child = name?.let { parseElement(text, it, open, end) }
            if (child == null) {
                index = end + 1
                continue
            }
            children += child
            index = child.end
        }
        return children
    }

    private fun elementName(text: String, open: Int, tagEnd: Int): String? {
        if (text.startsWith("</", open)) return null
        var index = open + 1
        while (index < tagEnd) {
            val character = text[index]
            if (character.isWhitespace() || character == '/' || character == '>') break
            index += 1
        }
        return text.substring(open + 1, index).takeIf(String::isNotEmpty)
    }

    /** Exclusive end of a comment, CDATA section or processing instruction. */
    private fun literalEnd(text: String, open: Int): Int {
        val terminator = when {
            text.startsWith("<!--", open) -> "-->"
            text.startsWith("<![CDATA[", open) -> "]]>"
            text.startsWith("<?", open) -> "?>"
            else -> return -1
        }
        val close = text.indexOf(terminator, open)
        return if (close < 0) text.length else close + terminator.length
    }

    /** Index of the `>` closing the tag, ignoring `>` inside attribute values. */
    private fun tagEnd(text: String, open: Int): Int {
        var quote: Char? = null
        var index = open + 1
        while (index < text.length) {
            val character = text[index]
            when {
                quote != null -> if (character == quote) quote = null
                character == '"' || character == '\'' -> quote = character
                character == '>' -> return index
            }
            index += 1
        }
        return -1
    }

    private fun isElement(text: String, open: Int, name: String): Boolean {
        if (!text.regionMatches(open + 1, name, 0, name.length, ignoreCase = true)) return false
        val next = text.getOrNull(open + 1 + name.length) ?: return false
        return next == '>' || next == '/' || next.isWhitespace()
    }

    private fun isCloseTag(text: String, open: Int, name: String): Boolean {
        if (!text.startsWith("</", open)) return false
        if (!text.regionMatches(open + 2, name, 0, name.length, ignoreCase = true)) return false
        val next = text.getOrNull(open + 2 + name.length) ?: return false
        return next == '>' || next.isWhitespace()
    }
}
