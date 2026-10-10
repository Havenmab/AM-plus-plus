package dev.amenhancer.module.lyrics.online

/**
 * Injects the translation enrichment lane into Apple's own TTML head without
 * touching the document body.
 *
 * The translation pass used to rebuild the whole document through
 * [AppleLyricTtmlReader] and [AppleLyricTtmlWriter]; that round trip dropped the
 * raw whitespace between Apple's word spans and flattened `ttm:role="x-bg"`
 * background vocals. Here only the `<iTunesMetadata>` lane text is edited, so
 * paragraphs, word spans, the whitespace between them, `x-bg` markup, agents,
 * namespaces and attributes are copied through byte for byte.
 *
 * An existing `translations` block is rewritten in place (its lines are carried
 * through the merge, so Apple's own entries survive); when the rebuilt track is
 * empty the original block is kept verbatim. Otherwise the new track is inserted
 * before `</iTunesMetadata>` (converting a self-closing element), or inside
 * `<metadata>` when Apple's document carries no `iTunesMetadata` at all.
 *
 * A `transliterations` block is **never** written: the third-party romanization
 * lane has been removed from the product, and a block Apple's own document
 * already carries is preserved untouched because the edit is confined to the
 * `translations` range.
 *
 * Lane keys mirror [AppleLyricTtmlReader]: each `<p>`'s `itunes:key`, or its
 * document position `L<n>` when Apple omitted it. Returns null — leaving the
 * caller's document untouched — when there is nothing to write or no place to
 * put it.
 */
object AppleLyricTtmlLaneInjector {

    private const val ITUNES_NAMESPACE = "http://music.apple.com/lyric-ttml-internal"

    private val translationsBlock =
        Regex("""(?is)<translations\b[^>]*>.*?</translations\s*>""")
    /** Matches [AppleLyricTtmlReader]'s paragraph rule so the keys line up 1:1. */
    private val paragraph = Regex("""(?is)<p\b([^>]*)>(.*?)</p\s*>""")
    private val keyAttribute =
        Regex("""(?is)\bitunes:key\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    private val iTunesMetadataSelfClosing = Regex("""(?is)<iTunesMetadata\b[^>]*?/\s*>""")
    private val iTunesMetadataOpen = Regex("""(?is)<iTunesMetadata\b[^>]*>""")
    private val iTunesMetadataClose = Regex("""(?is)</iTunesMetadata\s*>""")
    private val metadataClose = Regex("""(?is)</metadata\s*>""")

    /**
     * The original [ttml] with the translation lane built from [lines] injected,
     * or null when no lane can be placed. An existing `transliterations` block is
     * copied through untouched.
     */
    fun inject(ttml: String, lines: List<AppleTtmlLine>): String? = runCatching {
        val keys = documentKeys(ttml)
        val translations = AppleLyricTtmlWriter.translationsTrack(lines, keys)
        val existing = translationsBlock.find(ttml)
        if (existing != null) {
            // Apple's own block is rebuilt from the merged lines, which carry its
            // entries; if nothing was built, keep the block exactly as it was
            // rather than deleting it.
            return ttml.replaceRange(existing.range, translations ?: existing.value)
        }
        if (translations == null) return null
        placeInsideMetadata(ttml, translations)
    }.getOrNull()

    private fun placeInsideMetadata(ttml: String, lanes: String): String? {
        iTunesMetadataSelfClosing.find(ttml)?.let { selfClosing ->
            val open = selfClosing.value.trimEnd()
                .removeSuffix(">")
                .trimEnd()
                .removeSuffix("/")
                .trimEnd()
            return ttml.replaceRange(selfClosing.range, "$open>$lanes</iTunesMetadata>")
        }
        iTunesMetadataOpen.find(ttml)?.let { open ->
            val close = iTunesMetadataClose.find(ttml, open.range.last + 1) ?: return null
            return ttml.substring(0, close.range.first) +
                lanes +
                ttml.substring(close.range.first)
        }
        val close = metadataClose.find(ttml) ?: return null
        val block = "<iTunesMetadata xmlns=\"$ITUNES_NAMESPACE\">$lanes</iTunesMetadata>"
        return ttml.substring(0, close.range.first) + block + ttml.substring(close.range.first)
    }

    private fun documentKeys(ttml: String): List<String> =
        paragraph.findAll(ttml).mapIndexed { index, match ->
            val key = keyAttribute.find(match.groupValues[1])?.let { attribute ->
                attribute.groups[1]?.value ?: attribute.groups[2]?.value
            }
            key?.takeIf(String::isNotBlank) ?: "L${index + 1}"
        }.toList()
}
