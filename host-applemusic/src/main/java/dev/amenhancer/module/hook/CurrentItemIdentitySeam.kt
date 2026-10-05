package dev.amenhancer.module.hook

import dev.amenhancer.module.CurrentSongDetails
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Reviewed no-argument duration accessors across the current-item hierarchy.
 * `getPlaybackDuration` is the 7.0 name and reports **seconds**; the others are
 * the pre-7.0 millisecond surface. Shared with the target-symbol fallback so the
 * seam and the profile resolver can never disagree about which accessor is
 * acceptable.
 */
internal val CURRENT_ITEM_DURATION_GETTER_NAMES = setOf(
    "getDuration",
    "getDurationMs",
    "getDurationInMillis",
    "getPlaybackDuration",
)

/**
 * Duration accessors whose raw value is seconds, not milliseconds.
 *
 * Established from the supplied 7.0.0-beta (1606) package: the
 * `BasePlaybackItem#getPlaybackDuration()J` body is exactly
 * `iget-object BaseContentItem.offer; iget-wide Offer.duration; return-wide`, and
 * `com.apple.android.music.typeadapter.OffersTypeAdapter.read` stores the
 * store-platform `assets[].duration` long verbatim, while `ShowsViewModel`
 * formats the same value with two `div-long 60` steps (seconds → minutes →
 * hours/minutes). The concrete hierarchy exposes no millisecond-valued
 * accessor, so this one is kept and normalised in [durationMillisFrom]. The
 * device log pins the scale directly: a 3:14 track was scored with
 * `localDurationMs=194` against a `candidateDurationMs=194000`, i.e. the same
 * length in two different units.
 */
internal val SECOND_VALUED_DURATION_GETTER_NAMES = setOf("getPlaybackDuration")

/** Unit labels for [durationUnitOf]; also the diagnostic's `localDurationUnit`. */
internal const val DURATION_UNIT_SECONDS = "seconds"
internal const val DURATION_UNIT_MILLISECONDS = "milliseconds"

/**
 * Converts the raw value of [accessorName] into the milliseconds the match
 * scorer and the TTML writer expect. A non-positive value stays zero — the
 * scorer's neutral "no length" value — so an unavailable or partially
 * initialised accessor can never fabricate a duration.
 */
internal fun durationMillisFrom(accessorName: String, rawValue: Long): Long = when {
    rawValue <= 0L -> 0L
    accessorName in SECOND_VALUED_DURATION_GETTER_NAMES -> rawValue * 1_000L
    else -> rawValue
}

/** The unit [durationMillisFrom] reads [accessorName] in, or null when unresolved. */
internal fun durationUnitOf(accessorName: String?): String? = when {
    accessorName.isNullOrEmpty() -> null
    accessorName in SECOND_VALUED_DURATION_GETTER_NAMES -> DURATION_UNIT_SECONDS
    else -> DURATION_UNIT_MILLISECONDS
}

/**
 * The verified current lyrics item identity seam: the I2 fragment's current
 * item field (`com.apple.android.music.player.fragment.m#c` of type
 * `com.apple.android.music.model.BaseContentItem`) read through `getId()` and
 * parsed with [parseCurrentItemAdamId]. The same item optionally supplies
 * `getTitle()` and `getArtistName()` for embedded current-song editing, and a
 * numeric duration accessor, normalised to milliseconds once here for the
 * online-lyric match score.
 *
 * Lyric replacement and current-song identity capability share this exact
 * contract; neither consumer may reinterpret the identity as a title or
 * metadata match. Both resolve the seam through the same
 * [AppleMusicSymbols.LyricsCurrentItemField] symbol so a version change is
 * reported once as missing or ambiguous instead of being guessed.
 *
 * The duration accessor is version-exact: 7.0's `BaseContentItem` declares none
 * and the concrete `BasePlaybackItem` hierarchy uses `getPlaybackDuration()J`,
 * pinned as [AppleMusicSymbols.CurrentItemDurationMethod]. The declared field
 * type can never expose a subclass accessor through `Class#getMethod`, so the
 * pinned symbol is consulted for the concrete runtime item first and the
 * reviewed name scan stays as the fallback.
 */
internal class CurrentItemIdentitySeam(
    private val symbols: TargetSymbolResolver,
) {
    private lateinit var currentItemField: Field
    private lateinit var currentItemGetId: Method
    private var currentItemGetTitle: Method? = null
    private var currentItemGetArtistName: Method? = null
    private var currentItemGetDuration: Method? = null
    private var pinnedDurationGetter: Method? = null
    private val durationGetters = ConcurrentHashMap<Class<*>, Method>()

    /** The verified current item field resolution summary, or null before resolve. */
    var fieldSummary: String? = null
        private set

    /** Optional metadata contracts used by embedded current-song editing. */
    var metadataSummary: String? = null
        private set

    /** The verified duration accessor identity, or null while none resolved. */
    var durationSummary: String? = null
        private set

    /**
     * Resolves the seam against the given I2 install entry point. Returns a
     * diagnostic when the contract cannot be established, or null once the
     * seam is ready to read identities.
     */
    fun resolve(installMethod: Method): String? {
        val currentItemResolution = symbols.resolve(AppleMusicSymbols.LyricsCurrentItemField)
        val currentItemFieldValue = currentItemResolution.valueOrNull()
            ?: return currentItemResolution.summary
        val getId = resolveCurrentItemGetId(currentItemFieldValue.type)
            ?: return "BaseContentItem#getId() was unavailable; ${currentItemResolution.summary}"
        if (!currentItemFieldValue.declaringClass.isAssignableFrom(installMethod.declaringClass)) {
            return "Current lyrics item field is not in the I2 fragment hierarchy; " +
                currentItemResolution.summary
        }
        currentItemField = currentItemFieldValue.apply { isAccessible = true }
        currentItemGetId = getId
        currentItemGetTitle = resolveStringGetter(currentItemFieldValue.type, "getTitle")
        currentItemGetArtistName = resolveStringGetter(currentItemFieldValue.type, "getArtistName")
        currentItemGetDuration = resolveNumericGetter(currentItemFieldValue.type)
        pinnedDurationGetter = symbols.resolve(AppleMusicSymbols.CurrentItemDurationMethod)
            .valueOrNull()
            ?.apply { isAccessible = true }
        fieldSummary = currentItemResolution.summary
        durationSummary = (pinnedDurationGetter ?: currentItemGetDuration)?.let { method ->
            "current-item-duration-method ${method.declaringClass.name}#${method.name}"
        }
        metadataSummary = buildList {
            if (currentItemGetTitle == null) add("current-item-title-method unavailable")
            if (currentItemGetArtistName == null) add("current-item-artist-method unavailable")
            if (currentItemGetDuration == null && pinnedDurationGetter == null) {
                add("current-item-duration-method unavailable")
            }
        }.takeIf { it.isNotEmpty() }?.joinToString("; ")
        return null
    }

    /** The current lyrics item Adam ID of an I2 fragment, or null when unavailable. */
    fun currentItemAdamIdOf(fragment: Any?): Long? {
        if (fragment == null) return null
        return runCatching {
            val item = currentItemField.get(fragment) ?: return@runCatching null
            parseCurrentItemAdamId(currentItemGetId.invoke(item))
        }.getOrNull()
    }

    /** Rebinds the verified lyrics fragment field to an exact player item. */
    fun bindCurrentItemOf(fragment: Any?, item: Any?): Boolean {
        if (fragment == null || item == null) return false
        return runCatching {
            if (!currentItemField.type.isInstance(item)) return@runCatching false
            currentItemField.set(fragment, item)
            currentItemField.get(fragment) === item
        }.getOrDefault(false)
    }

    /** Reads identity directly from a verified player item argument. */
    fun detailsOfItem(item: Any?): CurrentSongDetails? {
        if (item == null) return null
        return runCatching {
            val appleMusicId = parseCurrentItemAdamId(currentItemGetId.invoke(item))
                ?: return@runCatching null
            val durationGetter = durationGetterFor(item.javaClass) ?: currentItemGetDuration
            val rawDuration = invokeLongGetter(durationGetter, item)
            CurrentSongDetails(
                appleMusicId = appleMusicId,
                title = invokeStringGetter(currentItemGetTitle, item),
                artist = invokeStringGetter(currentItemGetArtistName, item),
                // The single conversion point: the scorer and the TTML writer
                // both work in milliseconds, while the 7.0 accessor is seconds.
                durationMs = durationMillisFrom(durationGetter?.name.orEmpty(), rawDuration),
                durationRaw = rawDuration,
                durationUnit = durationUnitOf(durationGetter?.name).takeIf { rawDuration > 0L },
            )
        }.getOrNull()
    }

    private fun resolveCurrentItemGetId(itemType: Class<*>): Method? = resolveStringGetter(itemType, "getId")

    private fun resolveStringGetter(itemType: Class<*>, name: String): Method? = runCatching {
        itemType.getMethod(name)
            .takeIf { method -> method.returnType == String::class.java }
            ?.apply { isAccessible = true }
    }.getOrNull()

    /**
     * Optional track length. The declared BaseContentItem type is checked first
     * at resolve time, but the concrete PlaybackItem may be the one that
     * declares it, so `detailsOfItem` re-resolves against the runtime class.
     * The version-profile accessor is preferred whenever the concrete item is an
     * instance of its declaring type.
     */
    private fun resolveNumericGetter(itemType: Class<*>): Method? = runCatching {
        itemType.methods.firstOrNull { method ->
            method.parameterCount == 0 &&
                method.name in CURRENT_ITEM_DURATION_GETTER_NAMES &&
                (method.returnType == Long::class.javaPrimitiveType ||
                    method.returnType == Int::class.javaPrimitiveType)
        }?.apply { isAccessible = true }
    }.getOrNull()

    private fun durationGetterFor(itemType: Class<*>): Method? =
        durationGetters[itemType] ?: (
            pinnedDurationGetter?.takeIf { it.declaringClass.isAssignableFrom(itemType) }
                ?: resolveNumericGetter(itemType)
            )?.also { method -> durationGetters[itemType] = method }

    private fun invokeStringGetter(method: Method?, receiver: Any): String? = method
        ?.let { runCatching { it.invoke(receiver) as? String }.getOrNull() }
        ?.trim()
        ?.takeIf(String::isNotEmpty)

    private fun invokeLongGetter(method: Method?, receiver: Any): Long = method
        ?.let { runCatching { (it.invoke(receiver) as? Number)?.toLong() }.getOrNull() }
        ?.coerceAtLeast(0L)
        ?: 0L
}

/** Parses Apple's current item identity; only a positive Adam ID is accepted. */
internal fun parseCurrentItemAdamId(value: Any?): Long? = when (value) {
    is String -> value.toLongOrNull()
    is Number -> value.toLong()
    else -> null
}?.takeIf { it > 0L }
