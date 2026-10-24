package dev.amenhancer.module.hook

/**
 * Native timing and visual grouping metadata shared by the single-character
 * gate and the profile-enabled short-span gate. A rejected or malformed binding
 * keeps Apple's original (non-overridden) classifier result.
 */
data class CjkKaraokeWordTiming(
    val text: CharSequence,
    val nativeDurationMs: Int,
    val cumulativeDurationMs: Int,
    val cumulativeTextLength: Int,
    val splitBindingCount: Int,
    val isBackground: Boolean,
)

/**
 * Does not impose Apple's duration/length trigger. Those conditions remain in
 * z.a0; this gate only blocks merged or multi-character CJK chunks.
 */
fun isSingleUnmergedCjkWord(timing: CjkKaraokeWordTiming): Boolean {
    if (timing.isBackground) return false
    if (timing.splitBindingCount < 0 || timing.splitBindingCount > 1) return false

    val normalized = timing.text.toString().trim()
    if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length) != 1) {
        return false
    }
    if (!containsCjkKaraokeScript(normalized)) return false

    // g0 writes f/g as the visual chunk's accumulated duration/text length.
    // Equality with the current native word's values is the non-merged case.
    return timing.cumulativeDurationMs == timing.nativeDurationMs &&
        timing.cumulativeTextLength == 1
}

/**
 * Allows a short unmerged foreground CJK span whose native duration averages
 * at least 1000 ms per character. The host still owns the animation gates.
 */
fun isUnmergedCjkSpan(timing: CjkKaraokeWordTiming, maxCharacters: Int): Boolean {
    if (timing.isBackground || maxCharacters < 2) return false

    val normalized = timing.text.toString().trim()
    val characterCount = normalized.codePointCount(0, normalized.length)
    if (characterCount !in 2..minOf(maxCharacters, 3)) return false

    var offset = 0
    repeat(characterCount) {
        val next = normalized.offsetByCodePoints(offset, 1)
        if (!containsCjkKaraokeScript(normalized.substring(offset, next))) return false
        offset = next
    }

    if (timing.splitBindingCount < 0 || timing.splitBindingCount > characterCount) return false
    return timing.nativeDurationMs >= characterCount * 1_000 &&
        timing.cumulativeDurationMs == timing.nativeDurationMs &&
        timing.cumulativeTextLength == characterCount
}
