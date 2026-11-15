package dev.amenhancer.module.lyrics.online

import dev.amenhancer.module.config.CustomLyricsFilePolicy

/**
 * Turns a lyric document into the request [PronunciationBoundary] answers.
 *
 * The tokens come from the document's own timed spans, not from the view tree, so a plan can be
 * requested the moment the document is published — long before any row is inflated. Two facts the
 * view tree has and a document does not are left at their neutral values on purpose: [lineTop] is
 * always 0 (a document has no wrapping, so nothing may be locked for it) and [wholeWordReading] is
 * always false (that is a width measurement of a laid-out row); both only ever make a boundary
 * *looser*, never stricter.
 */
object PronunciationBoundaryRequests {

    /** The request for [ttml], or null when the document carries no lines to plan. */
    fun fromTtml(
        songId: String,
        ttml: String,
        title: String? = null,
        artist: String? = null,
        language: String? = null,
        maxLines: Int = PronunciationBoundary.MAX_LINES_PER_REQUEST,
    ): PronunciationBoundary.BoundaryRequest? {
        if (songId.isBlank() || ttml.isBlank()) return null
        val document = runCatching { AppleLyricTtmlReader.read(ttml) }.getOrNull() ?: return null
        if (document.isEmpty()) return null
        val kept = document.take(maxLines.coerceAtLeast(1))
        val lines = kept.mapIndexed { index, line ->
            val tokens = line.words.mapIndexed { tokenIndex, word ->
                val core = PronunciationBoundary.coreText(word.text)
                PronunciationBoundary.BoundaryToken(
                    index = tokenIndex,
                    kana = core,
                    kanaCount = core.length,
                    reading = line.romanizationSpans.getOrNull(tokenIndex)
                        ?.text
                        ?.let(PronunciationBoundary::coreText)
                        ?.ifEmpty { null },
                    trailingBlank = word.text.lastOrNull()?.isWhitespace() == true,
                )
            }
            PronunciationBoundary.BoundaryRequestLine(
                index = index,
                lineKey = lineKey(tokens),
                line = line.text,
                tokens = tokens,
                previousLine = kept.getOrNull(index - 1)?.text,
                nextLine = kept.getOrNull(index + 1)?.text,
            )
        }.filter { it.tokens.isNotEmpty() }
        if (lines.isEmpty()) return null
        return PronunciationBoundary.BoundaryRequest(
            songId = songId,
            lines = lines,
            title = title,
            artist = artist,
            language = language,
        )
    }

    /** The line's identity, the same key the view tree builds and the store is asked for. */
    fun lineKey(tokens: List<PronunciationBoundary.BoundaryToken>): String =
        tokens.fold("") { text, token -> text + token.kana }

    /** The identity hash of a request: its lines' kana joins, in order. */
    fun kanaHash(request: PronunciationBoundary.BoundaryRequest): String = CustomLyricsFilePolicy.sha256(
        request.lines.joinToString("\n") { it.lineKey }.toByteArray(Charsets.UTF_8),
    )

    /** The per-line token counts a cached answer has to match. */
    fun tokenCounts(request: PronunciationBoundary.BoundaryRequest): List<Int> =
        request.lines.map { it.tokens.size }
}
