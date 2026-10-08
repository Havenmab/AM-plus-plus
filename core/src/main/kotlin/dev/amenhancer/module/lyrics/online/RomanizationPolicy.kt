package dev.amenhancer.module.lyrics.online

import java.util.Locale

/**
 * Decides whether a third-party pronunciation may be published as a
 * transliteration lane for Apple's own line.
 *
 * Ported verbatim from HLE's `common.lyric.RomanizationPolicy`: a candidate is
 * accepted only when it is non-empty, is Latin romanization, the original text
 * actually contains non-Latin letters, and the normalized candidate differs from
 * the original. An English line with an English "romanization" therefore gets
 * none, and neither does a Japanese line whose candidate is kana.
 *
 * Pure over `String`/`Locale` and JVM-testable; the enrichment calls it per line
 * before a lane is written, so the policy is the single gate between a
 * provider's pronunciation column and Apple's document.
 */
object RomanizationPolicy {
    private val htmlTag = Regex("<[^>]*>")
    private val htmlEntity = Regex("&(?:#\\d+|#x[0-9a-fA-F]+|[A-Za-z]+);")

    fun sanitize(originalText: String?, pronunciation: String?): String? {
        val candidate = pronunciation?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val visibleCandidate = visibleText(candidate)
        if (visibleCandidate.isEmpty() || !isLatinRomanization(visibleCandidate)) return null

        val visibleOriginal = visibleText(originalText.orEmpty())
        if (!hasNonLatinLetter(visibleOriginal)) return null

        val normalizedOriginal = comparableText(visibleOriginal)
        val normalizedCandidate = comparableText(visibleCandidate)
        if (normalizedCandidate.isEmpty() || normalizedCandidate == normalizedOriginal) return null
        return candidate
    }

    fun isLatinLanguageTag(language: String?): Boolean =
        language
            ?.trim()
            ?.split('-')
            ?.any { it.equals("Latn", ignoreCase = true) } == true

    private fun isLatinRomanization(text: String): Boolean {
        var hasLatinLetter = false
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (Character.isLetter(codePoint)) {
                if (Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.LATIN) {
                    return false
                }
                hasLatinLetter = true
            }
            index += Character.charCount(codePoint)
        }
        return hasLatinLetter
    }

    private fun hasNonLatinLetter(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (
                Character.isLetter(codePoint) &&
                Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.LATIN
            ) {
                return true
            }
            index += Character.charCount(codePoint)
        }
        return false
    }

    private fun visibleText(text: String): String = text
        .replace(htmlTag, " ")
        .replace(htmlEntity, " ")
        .trim()

    private fun comparableText(text: String): String {
        val normalized = text.lowercase(Locale.ROOT)
        return buildString(normalized.length) {
            var index = 0
            while (index < normalized.length) {
                val codePoint = normalized.codePointAt(index)
                if (Character.isLetterOrDigit(codePoint)) appendCodePoint(codePoint)
                index += Character.charCount(codePoint)
            }
        }
    }
}
