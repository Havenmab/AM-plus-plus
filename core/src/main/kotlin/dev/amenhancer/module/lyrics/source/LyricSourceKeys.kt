package dev.amenhancer.module.lyrics.source

import dev.amenhancer.module.lyrics.online.Source
import dev.amenhancer.module.model.CustomLyricsSources
import java.util.Locale

/**
 * The provider ids the sheet and the persisted selection use. They are a small,
 * closed vocabulary on purpose: the option key carries one, so an id change
 * would invalidate stored selections.
 *
 * The ids are the short form the spec froze ("apple" / "qm" / "ne" / "kuwo" /
 * "kugou" / "lb" / "amll" / "file"); they are deliberately *not* the module's
 * internal source names ([CustomLyricsSources] uses "qq", "netease",
 * "amll-ttml-db", ...), and [fromCustomLyricsSource] is the only translation
 * between the two.
 */
object LyricSourceProviders {
    /** Apple's own document. */
    const val APPLE = "apple"

    /** QQ Music. */
    const val QQ = "qm"

    /** NetEase Cloud Music. */
    const val NETEASE = "ne"

    /** Kuwo Music. */
    const val KUWO = "kuwo"

    /** Kugou Music. */
    const val KUGOU = "kugou"

    /** Lunabeat TTML hub. */
    const val LUNABEAT = "lb"

    /** AMLL TTML DB. */
    const val AMLL = "amll"

    /** The AM-Lyrics repository. */
    const val AM_LYRICS = "am-lyrics"

    /** A document in the module's custom-lyrics index with no provider origin. */
    const val FILE = "file"

    /** The automatic-completion cache. */
    const val CACHE = "cache"

    /** The overlay store itself (content recorded without a known provider). */
    const val OVERLAY = "overlay"

    /** Every searchable provider, in the module's default chain order. */
    val ONLINE_ORDER: List<String> = listOf(QQ, NETEASE, KUWO, KUGOU, LUNABEAT, AMLL, AM_LYRICS)

    /** Maps a search-provider enum value onto its option id. */
    fun fromSource(source: Source): String = when (source) {
        Source.QM -> QQ
        Source.NE -> NETEASE
        Source.KUWO -> KUWO
        Source.KUGOU -> KUGOU
        Source.LB -> LUNABEAT
    }

    /**
     * Maps a [CustomLyricsSources] value onto its option id. Unknown or removed
     * providers degrade to [FILE] rather than disappearing, matching
     * [dev.amenhancer.module.config.CustomLyricsManifestPolicy]'s rule for old
     * index entries.
     */
    fun fromCustomLyricsSource(source: String?): String = when (source?.trim()?.lowercase(Locale.ROOT)) {
        CustomLyricsSources.MANUAL -> FILE
        CustomLyricsSources.AUTO_CACHE -> CACHE
        CustomLyricsSources.AMLL -> AMLL
        CustomLyricsSources.AM_LYRICS -> AM_LYRICS
        CustomLyricsSources.LUNABEAT -> LUNABEAT
        CustomLyricsSources.QQ -> QQ
        CustomLyricsSources.NETEASE -> NETEASE
        CustomLyricsSources.KUWO -> KUWO
        CustomLyricsSources.KUGOU -> KUGOU
        else -> FILE
    }

    /** True when the provider is one of the searchable online providers. */
    fun isOnline(providerId: String): Boolean = providerId in ONLINE_ORDER

    /** The chain position of a provider; unknown ids sort last. */
    fun onlineRank(providerId: String): Int =
        ONLINE_ORDER.indexOf(providerId).let { if (it < 0) ONLINE_ORDER.size else it }

    /**
     * The provider ids whose brand can identify a saved document by name.
     *
     * The custom-lyrics index only stores a provider tag for the providers
     * [dev.amenhancer.module.config.CustomLyricsManifestPolicy] accepts; a document
     * saved from a search provider is downgraded to "manual" when the index is
     * sanitized, so the provider tag is lost while the entry keeps the provider
     * name the sheet wrote into it. This list is what [providerIdForLabel] matches.
     */
    val LABELLED_PROVIDERS: List<String> = listOf(
        QQ,
        NETEASE,
        KUWO,
        KUGOU,
        LUNABEAT,
        AMLL,
        AM_LYRICS,
    )

    /**
     * Recovers a provider id from a saved document name, or null when the name is
     * not a provider brand. Both language variants are matched (the sheet names a
     * record with [LyricSourceText.providerLabel]), spaces and case are ignored, so
     * "QQ 音乐", "qq音乐" and "QQ Music" all identify [QQ].
     */
    fun providerIdForLabel(displayName: String): String? {
        val normalized = normalizeLabel(displayName)
        if (normalized.isEmpty()) return null
        return LABELLED_PROVIDERS.firstOrNull { providerId ->
            normalizeLabel(LyricSourceText.providerLabel(providerId, Locale.SIMPLIFIED_CHINESE)) == normalized ||
                normalizeLabel(LyricSourceText.providerLabel(providerId, Locale.US)) == normalized
        }
    }

    private fun normalizeLabel(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace(" ", "")
}

/**
 * Builds and validates the stable option keys that get persisted per song and
 * lane.
 *
 * The shape is deliberately readable in a device log and in the JSON file:
 *
 * - "apple" for Apple's own document,
 * - "overlay:<lane>" for content this module already published,
 * - "file:<fileId>" and "cache:<fileId>" for the custom-lyrics index,
 * - "online:<provider>:<remoteId>" for a provider search hit,
 * - "none" for an explicitly hidden supplement lane (see
 *   [LyricsSourceController.setLaneHidden]).
 *
 * Keys are never built by callers: the catalog produces them and the store only
 * accepts what [sanitize] approves, so a hand-edited or truncated JSON file can
 * never inject a key that no option can match.
 */
object LyricSourceKeys {
    /** Apple's own document. */
    const val APPLE: String = "apple"

    /**
     * The persisted marker for "this supplement lane is hidden on purpose".
     *
     * It is a reserved key, not an option: no [LyricSourceOption] ever carries it,
     * so [LyricSourceCatalog] resolves it to a null selection with
     * [LyricLaneState.overridden] set, which is exactly the "不显示" state the
     * sheet's toggle writes.
     */
    const val HIDDEN: String = "none"

    private const val OVERLAY_PREFIX = "overlay"
    private const val FILE_PREFIX = "file"
    private const val CACHE_PREFIX = "cache"
    private const val ONLINE_PREFIX = "online"
    private const val SEPARATOR = ":"
    private const val SEPARATOR_CHAR = ':'

    /** Upper bound for a persisted key; kept short so a JSON file cannot bloat. */
    const val MAX_KEY_LENGTH = 128

    /** Upper bound for one path/free-form segment of a key. */
    const val MAX_SEGMENT_LENGTH = 64

    /** The content this module already published for one lane. */
    fun overlay(lane: LyricLane): String = OVERLAY_PREFIX + ":" + lane.key

    /** A custom-lyrics index record (user-imported or downloaded). */
    fun customFile(fileId: String): String = FILE_PREFIX + ":" + segment(fileId)

    /** An automatically cached document. */
    fun cache(fileId: String): String = CACHE_PREFIX + ":" + segment(fileId)

    /** One provider search hit. */
    fun online(providerId: String, remoteId: String): String =
        ONLINE_PREFIX + ":" + segment(providerId) + ":" + segment(remoteId)

    /**
     * Accepts a persisted key or returns null. This is the store's only gate: a
     * blank, over-long or control-character key is dropped instead of restored.
     */
    fun sanitize(raw: String?): String? {
        val value = raw?.trim() ?: return null
        if (value.isEmpty() || value.length > MAX_KEY_LENGTH) return null
        if (value.any { it.isISOControl() || it == ' ' }) return null
        if (!KEY_PATTERN.matches(value)) return null
        return value
    }

    /** True when the key is the reserved "lane hidden" marker. */
    fun isHidden(key: String?): Boolean = key == HIDDEN

    /**
     * The provider id inside an "online:<provider>:<remoteId>" key, or null.
     *
     * Parsing exists so a stored selection can be turned back into a label and a
     * placeholder option when the provider search that produced it is no longer
     * in the snapshot (see [LyricSourceCatalog]): the persisted key is the only
     * evidence left of "the user picked this QQ 歌曲".
     */
    fun onlineProvider(key: String?): String? = splitOnline(key)?.first

    /** The provider-side id inside an "online:<provider>:<remoteId>" key, or null. */
    fun onlineRemoteId(key: String?): String? = splitOnline(key)?.second

    /** The index file id inside a "file:<fileId>" or "cache:<fileId>" key, or null. */
    fun customFileId(key: String?): String? {
        val value = key?.trim() ?: return null
        val prefix = when {
            value.startsWith(FILE_PREFIX + SEPARATOR) -> FILE_PREFIX + SEPARATOR
            value.startsWith(CACHE_PREFIX + SEPARATOR) -> CACHE_PREFIX + SEPARATOR
            else -> return null
        }
        return value.removePrefix(prefix).takeIf { it.isNotEmpty() }
    }

    private fun splitOnline(key: String?): Pair<String, String>? {
        val value = key?.trim() ?: return null
        val prefix = ONLINE_PREFIX + SEPARATOR
        if (!value.startsWith(prefix)) return null
        val rest = value.removePrefix(prefix)
        val separator = rest.indexOf(SEPARATOR_CHAR)
        if (separator <= 0 || separator == rest.length - 1) return null
        return rest.substring(0, separator) to rest.substring(separator + 1)
    }

    /**
     * One key segment: trimmed, control characters and the ':' separator removed,
     * and bounded. Losing a character of a provider's id can only happen for an id
     * that was never usable in a key to begin with; the catalog then simply lists
     * that candidate under a shorter key, and the host still resolves it by
     * [LyricSourceOption.key].
     */
    private fun segment(raw: String): String {
        val cleaned = raw.trim()
            .filterNot { it.isISOControl() || it == ':' || it == ' ' }
            .take(MAX_SEGMENT_LENGTH)
        return cleaned.ifEmpty { "unknown" }
    }

    private val KEY_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]*")
}
