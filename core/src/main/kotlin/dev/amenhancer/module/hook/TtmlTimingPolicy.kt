package dev.amenhancer.module.hook

import dev.amenhancer.module.lyrics.online.AppleLyricTtmlReader
import java.util.LinkedHashMap

/**
 * The native SongInfo surface does not expose Apple's root timing attribute.
 * Keep the raw-TTML check small and deterministic so the parser seam can bind
 * the observed mode to the exact pointer later received by I2.
 */
enum class TtmlTimingMode {
    WORD,
    NON_WORD,
}

/**
 * Metadata observed from the raw TTML before Apple's native parser hides it.
 * Language is intentionally nullable: a missing language declaration must not
 * be guessed from lyric text, but non-Word timing remains eligible regardless
 * of language.
 */
data class TtmlDocumentMetadata(
    val timingMode: TtmlTimingMode,
    val language: String?,
    val hasTranslation: Boolean,
) {
    val isForeign: Boolean
        get() = language?.let(::isForeignLanguage) == true

    /** A Word-timed foreign document without a translation track needs a fallback. */
    val needsTranslationFallback: Boolean
        get() = timingMode == TtmlTimingMode.WORD && isForeign && !hasTranslation

    private companion object {
        fun isForeignLanguage(language: String): Boolean {
            val normalized = language.trim().lowercase().replace('_', '-')
            if (normalized.isBlank()) return false
            return normalized != "zh" &&
                !normalized.startsWith("zh-") &&
                normalized != "cmn" &&
                !normalized.startsWith("cmn-") &&
                normalized != "zho" &&
                !normalized.startsWith("zho-") &&
                normalized != "chi" &&
                !normalized.startsWith("chi-") &&
                normalized != "yue" &&
                !normalized.startsWith("yue-") &&
                normalized != "wuu" &&
                !normalized.startsWith("wuu-") &&
                normalized != "nan" &&
                !normalized.startsWith("nan-") &&
                normalized != "hak" &&
                !normalized.startsWith("hak-") &&
                normalized != "lzh" &&
                !normalized.startsWith("lzh-")
        }
    }
}

object TtmlTimingPolicy {
    private val rootTag = Regex("""(?is)<tt\b[^>]*>""")
    private val timingAttribute = Regex(
        """(?is)(?:[A-Za-z_][\w.-]*:)?timing\s*=\s*(?:"([^"]*)"|'([^']*)')""",
    )
    private val languageAttribute = Regex(
        """(?is)(?:xml:lang|lang)\s*=\s*(?:"([^"]*)"|'([^']*)')""",
    )
    private val translationsBlock = Regex(
        """(?is)<translations\b[^>]*>(.*?)</translations\s*>""",
    )
    private val translationElement = Regex(
        """(?is)<translation\b[^>]*>(.*?)</translation\s*>""",
    )
    private val markup = Regex("""(?is)<[^>]+>""")

    fun metadataOf(ttml: String): TtmlDocumentMetadata = runCatching {
        val root = rootTag.find(ttml)?.value
        val timingMode = timingModeOf(root)
        val language = root?.let { rootTagLanguage(it) }
        val hasTranslation = translationsBlock.find(ttml)?.let { block ->
            translationElement.findAll(block.groupValues[1]).any { translation ->
                markup.replace(translation.groupValues[1], " ").isNotBlank()
            }
        } == true
        TtmlDocumentMetadata(
            timingMode = timingMode,
            language = language,
            hasTranslation = hasTranslation,
        )
    }.getOrElse {
        TtmlDocumentMetadata(
            timingMode = TtmlTimingMode.NON_WORD,
            language = null,
            hasTranslation = false,
        )
    }

    /** Missing, Line, or any value other than Word is intentionally non-word. */
    fun modeOf(ttml: String): TtmlTimingMode = metadataOf(ttml).timingMode

    fun isWord(ttml: String): Boolean = modeOf(ttml) == TtmlTimingMode.WORD

    /**
     * True when Apple's document is actually synchronised with playback: at
     * least one line or timed word starts at a positive begin time.
     *
     * A plain/unsynchronised document — text only, or lines that carry no
     * positive begin anywhere — reports false, so it stays eligible for the
     * third-party search path. Both the translation pass (its `untimed` flag)
     * and the online search chain (which must not replace a timed document)
     * derive their decision from this one predicate, so they cannot drift.
     */
    fun hasTiming(ttml: String): Boolean = AppleLyricTtmlReader.read(ttml).any { line ->
        line.begin > 0L || line.words.any { word -> word.begin > 0L }
    }

    /**
     * The document-facing timing token for diagnostics: `WORD` when Apple
     * declares `itunes:timing="Word"`, otherwise `LINE`. Only meaningful while
     * [hasTiming] is true.
     */
    fun timingKindOf(ttml: String): String = if (isWord(ttml)) "WORD" else "LINE"

    private fun timingModeOf(root: String?): TtmlTimingMode {
        if (root == null) return TtmlTimingMode.NON_WORD
        val value = timingAttribute.find(root)?.let { match ->
            match.groups[1]?.value ?: match.groups[2]?.value
        }
        return if (value?.trim().equals("Word", ignoreCase = true)) {
            TtmlTimingMode.WORD
        } else {
            TtmlTimingMode.NON_WORD
        }
    }

    private fun rootTagLanguage(root: String): String? = languageAttribute.find(root)?.let { match ->
        match.groups[1]?.value?.trim()?.takeIf(String::isNotBlank)
            ?: match.groups[2]?.value?.trim()?.takeIf(String::isNotBlank)
    }
}

/**
 * Bounded identity observations from the parser seam. Weak references avoid
 * retaining JavaCPP pointer wrappers (and their native addresses) after Apple
 * releases a lyric document.
 *
 * A capture is deliberately kept even while the returned pointer has no Adam ID
 * yet: Apple parses the displayed document before its identity is bound, so
 * dropping an unbound parse would drop every ordinary document. The observation
 * stays keyed by pointer identity, which is the same contract the timing gate
 * already relies on when it reads back `metadataOf(original)` for the pointer
 * Apple later passes to I2, and [associate] copies it onto the track once that
 * identity is known.
 */
class TtmlTimingObservationRegistry(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private class Observation(
        val pointer: java.lang.ref.WeakReference<Any>,
        val metadata: TtmlDocumentMetadata,
        val rawTtml: String?,
    )

    private val observations = ArrayDeque<Observation>()
    private val idObservations = object : LinkedHashMap<Long, TtmlDocumentMetadata>(
        maxEntries.coerceAtLeast(1),
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Long, TtmlDocumentMetadata>?,
        ): Boolean = size > maxEntries.coerceAtLeast(1)
    }

    /**
     * Raw TTML for the same verified song IDs, bounded like the metadata map.
     * The translation pass reads the displayed document back so an online lane
     * can be merged into Apple's own lines.
     */
    private val rawTtmlById = object : LinkedHashMap<Long, String>(
        maxEntries.coerceAtLeast(1),
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Long, String>?,
        ): Boolean = size > maxEntries.coerceAtLeast(1)
    }

    fun record(
        pointer: Any?,
        metadata: TtmlDocumentMetadata,
        appleMusicId: Long? = null,
        rawTtml: String? = null,
    ) {
        if (pointer == null) return
        synchronized(observations) {
            sweepCleared()
            val iterator = observations.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().pointer.get() === pointer) iterator.remove()
            }
            while (observations.size >= maxEntries.coerceAtLeast(1)) observations.removeFirst()
            appleMusicId?.takeIf { it > 0L }?.let { id ->
                idObservations[id] = metadata
                rawTtml?.let { raw -> rawTtmlById[id] = raw }
            }
            observations.addLast(
                Observation(java.lang.ref.WeakReference(pointer), metadata, rawTtml),
            )
        }
    }

    fun record(
        pointer: Any?,
        mode: TtmlTimingMode,
        rawTtml: String? = null,
    ) = record(
        pointer = pointer,
        metadata = TtmlDocumentMetadata(
            timingMode = mode,
            language = null,
            hasTranslation = false,
        ),
        rawTtml = rawTtml,
    )

    fun metadataOf(pointer: Any?): TtmlDocumentMetadata? {
        if (pointer == null) return null
        synchronized(observations) {
            sweepCleared()
            return observations.firstOrNull { it.pointer.get() === pointer }?.metadata
        }
    }

    /** The raw Apple document captured for one pointer, or null. */
    fun rawTtmlOf(pointer: Any?): String? {
        if (pointer == null) return null
        synchronized(observations) {
            sweepCleared()
            return observations.firstOrNull { it.pointer.get() === pointer }?.rawTtml
        }
    }

    /**
     * Binds a parse observation to the track whose identity became known at the
     * I2 seam. The pointer identity is the key because Apple hands the exact
     * wrapper it received from the parser back to I2 — the same contract the
     * timing gate already relies on when it reads `metadataOf(original)`. Only
     * the sampled observation for that exact pointer is ever copied, so a
     * document can never leak onto another track.
     *
     * Returns true when a captured observation existed for that pointer; false
     * when nothing was observed, which is the "still not captured" case the
     * device log has to distinguish from a rejected candidate.
     */
    fun associate(pointer: Any?, appleMusicId: Long): Boolean {
        if (pointer == null || appleMusicId <= 0L) return false
        synchronized(observations) {
            sweepCleared()
            val observation = observations.firstOrNull { it.pointer.get() === pointer }
                ?: return false
            idObservations[appleMusicId] = observation.metadata
            observation.rawTtml?.let { raw -> rawTtmlById[appleMusicId] = raw }
            return true
        }
    }

    /** Returns the latest observed native TTML metadata for a verified song ID. */
    fun metadataOfAppleMusicId(appleMusicId: Long): TtmlDocumentMetadata? {
        if (appleMusicId <= 0L) return null
        synchronized(observations) { return idObservations[appleMusicId] }
    }

    /** Returns the raw TTML observed for a verified song ID, or null. */
    fun rawTtmlOfAppleMusicId(appleMusicId: Long): String? {
        if (appleMusicId <= 0L) return null
        synchronized(observations) { return rawTtmlById[appleMusicId] }
    }

    fun modeOf(pointer: Any?): TtmlTimingMode? {
        return metadataOf(pointer)?.timingMode
    }

    private fun sweepCleared() {
        val iterator = observations.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().pointer.get() == null) iterator.remove()
        }
    }

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 256
    }
}
