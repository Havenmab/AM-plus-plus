package dev.amenhancer.module.hook

import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookPoint
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookResolver
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookTarget
import io.github.proify.lyricon.amprovider.xposed.AppleMusicRuntimeMember
import io.github.proify.lyricon.amprovider.xposed.AppleReflection

/**
 * HLE's `PreferencesMonitor` read half, ported onto the fork's profile resolver.
 *
 * HLE reads the app's own "lyrics pronunciation selected" /
 * "lyrics translation selected" preferences instead of assuming the user wants
 * them whenever the module is enabled. On 1606 the old
 * `AppSharedPreferences.isLyricsPronunciationSelected()` static getter no longer
 * exists: `ja.i0` keeps a `Boolean` cache field (`s`), a typed DataStore key
 * (`k`) and a store read (`h` → `d`). [readAppleLyricsPreference] mirrors HLE's
 * order exactly —
 *
 * ```
 * getter → cache field → snapshot → key/store read
 * ```
 *
 * — so the common path never touches the store: the original DataStore read can
 * block, and HLE's comment is explicit that a lyric getter must not pay for it.
 * A value that arrived from the app's own setter hook wins over a cold store
 * read ([snapshot]), which is what makes the preference hook's result visible
 * immediately.
 *
 * Returns null when nothing could be read; the caller decides the fail-open
 * direction. The fork's gates treat null as "unknown, keep working" (`?: true`)
 * rather than HLE's `?: false`, because a profile without the preference members
 * must not silently disable Apple's own pronunciation.
 */
internal object AppleLyricsPreferenceReader {

    private const val PRONUNCIATION = true
    private const val TRANSLATION = false

    /** The current song's pronunciation preference, or null when unreadable. */
    fun isPronunciationSelected(
        resolver: AppleMusicHookResolver,
        snapshot: Boolean?,
    ): Boolean? = runCatching {
        val resolved = resolver.resolveClass(AppleMusicHookPoint.APPLE_SHARED_PREFERENCES_CLASS)
        read(
            clazz = resolved.clazz,
            target = resolved.target,
            pronunciation = PRONUNCIATION,
            snapshot = snapshot,
        )
    }.getOrNull()

    /** The current song's translation preference, or null when unreadable. */
    fun isTranslationSelected(resolver: AppleMusicHookResolver): Boolean? = runCatching {
        val resolved = resolver.resolveClass(AppleMusicHookPoint.APPLE_SHARED_PREFERENCES_CLASS)
        read(
            clazz = resolved.clazz,
            target = resolved.target,
            pronunciation = TRANSLATION,
            snapshot = null,
        )
    }.getOrNull()

    /**
     * HLE's `readAppleLyricsPreference`, verbatim in behaviour. [snapshot] is the
     * last value the app's own pronunciation setter pushed; it is only consulted
     * after the resolver's static getter and the cache field, exactly as HLE's
     * ordering does, and it wins over the store read so a setter callback is
     * never overwritten by a slower cold read.
     */
    fun read(
        clazz: Class<*>,
        target: AppleMusicHookTarget,
        pronunciation: Boolean,
        snapshot: Boolean?,
    ): Boolean? {
        val getter = target.runtimeMemberNameOrNull(
            if (pronunciation) {
                AppleMusicRuntimeMember.LYRICS_PREFERENCES_PRONUNCIATION_GETTER
            } else {
                AppleMusicRuntimeMember.LYRICS_PREFERENCES_TRANSLATION_GETTER
            },
        )
        if (getter != null) {
            return runCatching { AppleReflection.callStatic(clazz, getter) as? Boolean }.getOrNull()
        }
        if (!pronunciation) return null

        val cached = staticField(clazz, target, AppleMusicRuntimeMember.LYRICS_PREFERENCES_PRONUNCIATION_CACHE_FIELD)
            as? Boolean
        if (cached != null) return cached
        if (snapshot != null) return snapshot

        val key = staticField(clazz, target, AppleMusicRuntimeMember.LYRICS_PREFERENCES_PRONUNCIATION_KEY_FIELD)
            ?: return null
        val store = runCatching {
            AppleReflection.callStatic(
                clazz,
                target.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_PREFERENCES_STORE_GETTER),
            )
        }.getOrNull() ?: return null
        return runCatching {
            AppleReflection.call(
                store,
                target.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_PREFERENCES_STORE_READ_METHOD),
                key,
                false,
            ) as? Boolean
        }.getOrNull()
    }

    /** A static field of the profile class, or null when it is not pinned/present. */
    private fun staticField(
        clazz: Class<*>,
        target: AppleMusicHookTarget,
        member: AppleMusicRuntimeMember,
    ): Any? {
        val name = target.runtimeMemberNameOrNull(member) ?: return null
        return runCatching {
            clazz.getDeclaredField(name).apply { isAccessible = true }.get(null)
        }.getOrNull()
    }
}
