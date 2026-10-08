package dev.amenhancer.module.lyrics.online

/**
 * Timing-keyed overlay of the online translation/pronunciation lanes for the
 * current track. This is the fork's port of HLE's
 * `AppleNativeOnlineTranslationStore`, reduced to the subset the native
 * lyric-model delivery reads.
 *
 * HLE does not deliver pronunciation by editing TTML: it writes the online
 * lanes into Apple's own lyric model and advertises the languages. The
 * document lane in this fork already injects a `<transliterations>` head track,
 * but Apple Music 7.0.0-beta (1606) does not render it (device-confirmed:
 * `pronunciationLines=34` and `published=true`, yet the screen shows no
 * romanization). The native-model hooks therefore ask this store for the line
 * content keyed by the line's timing, exactly as HLE does through
 * `AppleNativeOnlineTranslationStore.translation/pronunciation`.
 *
 * One overlay is held at a time (the current track); every lookup is gated on
 * the caller's song id, so a stale track can never leak into the next one.
 * Content is sanitized on write: a pronunciation is only stored when
 * [RomanizationPolicy.sanitize] accepts it against Apple's own line text, and a
 * translation only when [OnlineTranslationContentPolicy] considers it real.
 * Fails open everywhere — a missing overlay, a blank id or a timing miss all
 * return null, so the caller keeps Apple's own value.
 */
class NativeLyricOverlayStore {

    private data class TimingKey(val begin: Long, val end: Long)

    private data class LineKey(val timing: TimingKey, val text: String)

    /** The lanes stored for one line; either side may be absent. */
    data class Content(val translation: String?, val pronunciation: String?)

    private data class Entry(val text: String, val content: Content)

    private data class Overlay(
        val songId: String,
        val exactContent: Map<LineKey, Content>,
        val contentByTiming: Map<TimingKey, List<Entry>>,
        val hasTranslation: Boolean,
        val hasPronunciation: Boolean,
        val translationSource: String?,
        val pronunciationSource: String?,
    )

    @Volatile
    private var overlay: Overlay? = null

    @Volatile
    private var revision: Long = 0L

    /**
     * Replaces the overlay with [lines] for [songId]. Returns true when the
     * stored content changed. A blank id or a body with no lane at all is
     * rejected and leaves the previous overlay in place, mirroring HLE's
     * "Apple source first, online only when it has something" rule.
     */
    @Synchronized
    fun update(
        songId: String?,
        lines: List<NativeLyricLine>,
        translationSource: String? = null,
        pronunciationSource: String? = null,
    ): Boolean {
        val id = songId?.takeIf(String::isNotBlank) ?: return false
        val entries = lines.mapNotNull { line ->
            val content = Content(
                translation = OnlineTranslationContentPolicy.sanitize(line.translation),
                pronunciation = RomanizationPolicy.sanitize(line.text, line.roma),
            )
            if (content.translation == null && content.pronunciation == null) {
                return@mapNotNull null
            }
            LineKey(TimingKey(line.begin, line.end), normalizeText(line.text)) to content
        }
        if (entries.isEmpty()) return false
        val next = Overlay(
            songId = id,
            exactContent = entries.toMap(),
            contentByTiming = entries.groupBy(
                keySelector = { it.first.timing },
                valueTransform = { Entry(it.first.text, it.second) },
            ),
            hasTranslation = entries.any { it.second.translation != null },
            hasPronunciation = entries.any { it.second.pronunciation != null },
            translationSource = translationSource,
            pronunciationSource = pronunciationSource,
        )
        if (overlay == next) return false
        overlay = next
        revision += 1
        return true
    }

    /** Drops the overlay; a non-null [songId] only clears when it still matches. */
    @Synchronized
    fun clear(songId: String? = null): Boolean {
        val current = overlay ?: return false
        if (!songId.isNullOrBlank() && current.songId != songId) return false
        overlay = null
        revision += 1
        return true
    }

    fun revision(): Long = revision

    /** The track the overlay currently belongs to, or null when empty. */
    fun currentSongId(): String? = overlay?.songId

    fun hasTranslation(songId: String?): Boolean =
        !songId.isNullOrBlank() &&
            overlay?.let { it.songId == songId && it.hasTranslation } == true

    fun hasPronunciation(songId: String?): Boolean =
        !songId.isNullOrBlank() &&
            overlay?.let { it.songId == songId && it.hasPronunciation } == true

    fun translationSource(songId: String?): String? =
        overlay?.takeIf { it.songId == songId && it.hasTranslation }?.translationSource

    fun pronunciationSource(songId: String?): String? =
        overlay?.takeIf { it.songId == songId && it.hasPronunciation }?.pronunciationSource

    fun translation(songId: String?, begin: Long, end: Long, text: String?): String? =
        content(songId, begin, end, text)?.translation

    fun pronunciation(songId: String?, begin: Long, end: Long, text: String?): String? =
        content(songId, begin, end, text)?.pronunciation

    private fun content(songId: String?, begin: Long, end: Long, text: String?): Content? {
        val current = overlay ?: return null
        if (songId.isNullOrBlank() || current.songId != songId) return null
        val timing = TimingKey(begin, end)
        // The exact (timing, text) entry wins; a timing that carries exactly one
        // entry still answers when Apple re-renders the same span with a slightly
        // different whitespace normalization. Two entries on one timing stay
        // ambiguous and return null rather than guessing, as HLE does.
        current.exactContent[LineKey(timing, normalizeText(text))]?.let { return it }
        return current.contentByTiming[timing]?.singleOrNull()?.content
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")

        fun normalizeText(text: String?): String = text.orEmpty().replace(WHITESPACE, " ").trim()
    }
}

/**
 * Pure decisions of HLE's native-model pronunciation delivery, kept free of
 * Android and reflection so the device-facing rule is covered by JVM tests.
 *
 * These are the non-reflection halves of `AppleSupplementTextHooks` /
 * `ApplePronunciationPolicy`; the host hooks only resolve the app's members and
 * call into here.
 */
object NativeLyricModelPolicy {

    /** HLE's third-party pronunciation tag when the system language is not Latin. */
    const val THIRD_PARTY_PRONUNCIATION_LANGUAGE = "und-Latn"

    /**
     * HLE's `thirdPartyPronunciationFallbackLanguage`: the language to advertise
     * for a third-party pronunciation. Null when the Mandarin rule hides it,
     * when the feature is off, or when there is no online pronunciation at all;
     * otherwise the system lyrics language when it is already a Latin script
     * tag, else the script-neutral [THIRD_PARTY_PRONUNCIATION_LANGUAGE].
     */
    fun thirdPartyPronunciationFallbackLanguage(
        systemLanguage: String?,
        enabled: Boolean,
        hasOnlinePronunciation: Boolean,
        hideMandarinPinyin: Boolean,
        pronunciationLanguages: Collection<String> = listOfNotNull(systemLanguage),
        genre: String? = null,
    ): String? {
        if (
            ApplePronunciationVisibilityPolicy.shouldHide(
                genre = genre,
                pronunciationLanguages = pronunciationLanguages,
                hideMandarinPinyin = hideMandarinPinyin,
            )
        ) {
            return null
        }
        if (!enabled) return null
        if (!hasOnlinePronunciation) return null
        return systemLanguage.latinLanguageOrNull() ?: THIRD_PARTY_PRONUNCIATION_LANGUAGE
    }

    /**
     * HLE's `ApplePronunciationPolicy.selectLanguage`: keep the system match
     * when it is a Latin tag, else the first Apple language that is, else the
     * online fallback when it is Latin. Everything else is null, so Apple's own
     * match survives untouched.
     */
    fun selectLanguage(
        systemMatch: String?,
        appleLanguages: List<String>,
        onlineFallbackLanguage: String?,
    ): String? = systemMatch.latinLanguageOrNull()
        ?: appleLanguages.firstNotNullOfOrNull { it.latinLanguageOrNull() }
        ?: onlineFallbackLanguage.latinLanguageOrNull()

    /** HLE's `hasTranslation` / `setTranslation` availability resolution. */
    fun hasTranslationAvailability(
        original: Boolean,
        enabled: Boolean,
        hasOnlineTranslation: Boolean,
    ): Boolean = original || (enabled && hasOnlineTranslation)

    /**
     * HLE's `hasPronunciation` / `setPronunciation` availability resolution: a
     * Mandarin song is hidden outright, otherwise the online romanization is
     * advertised and Apple's own valid romanization is never withdrawn.
     */
    fun hasPronunciationAvailability(
        original: Boolean,
        enabled: Boolean,
        hasOnlinePronunciation: Boolean,
        hasValidOfficialPronunciation: Boolean,
        mandarinHidden: Boolean,
    ): Boolean {
        if (mandarinHidden) return false
        return (enabled && hasOnlinePronunciation) ||
            (original && hasValidOfficialPronunciation)
    }

    private fun String?.latinLanguageOrNull(): String? = this
        ?.trim()
        ?.takeIf { it.isNotEmpty() && RomanizationPolicy.isLatinLanguageTag(it) }
}
