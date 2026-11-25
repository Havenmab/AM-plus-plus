package dev.amenhancer.module.hook

import dev.amenhancer.host.applemusic.AppleMusicHostProfile
import org.json.JSONObject

/**
 * The exact Apple Music 7.0.3 lyric line-change timing contract.
 *
 * The host computes a move duration from the gap between two lyric lines inside its native line
 * callback and stores it in the lyrics item animator's move-duration field; the animator reads that
 * field once per move batch. This contract resolves the three members involved — the callback, the
 * field, and the animator that owns it — plus the offset callback the host calls in the same branch
 * (the "the host wrote the duration in this very call" signal).
 *
 * Everything is declared in the version profile and resolved once, before any hook is registered;
 * a missing or shape-shifted member fails the whole contract instead of half-installing it.
 */
internal class LyricLineMotionContract(
    profile: AppleMusicHostProfile,
    loader: ClassLoader,
    names: JSONObject,
) {
    private val members = FragmentProfileMembers(
        loader, names, "lyricLineMotion",
        FragmentMemberRequirements(
            methods = setOf("lineCallback", "lineOffset"),
            fields = setOf("callbackFragment", "animator", "duration"),
        ),
    )

    /** The native line event: `(begin, LyricsLineVector, end) -> Unit`, erased as Objects. */
    val lineCallback = members.method("lineCallback")

    /** The host's own "suggest a line offset" bridge, called with the negated duration. */
    val lineOffset = members.method("lineOffset")

    /** The callback's captured fragment (a synthetic final field of the callback object). */
    val callbackFragment = members.field("callbackFragment")

    /** The lyrics list's animator instance on that fragment. */
    val animator = members.field("animator")

    /** The animator's move duration, in milliseconds (declared on ItemAnimator itself). */
    val duration = members.field("duration")

    init {
        check(profile.productionEnabled && names.getInt("contractVersion") == 1) { "line motion contract version" }
        val animatorClass = members.type(
            checkNotNull(names.optJSONObject("classes")?.optString("animator")?.takeIf(String::isNotBlank)) {
                "lyricLineMotion.classes.animator: undeclared role"
            },
        )
        // The lyrics animator must be the host's DefaultItemAnimator variant: that is the class
        // whose move batch reads this exact duration field. A plain ItemAnimator would resolve the
        // same field and animate nothing.
        check(animatorClass.isAssignableFrom(animator.type)) {
            "lyricLineMotion: ${animator.type.name} does not extend ${animatorClass.name}"
        }
        // The field has to live on an ancestor of the animator, never on an unrelated class.
        check(duration.declaringClass.isAssignableFrom(animator.type)) {
            "lyricLineMotion: ${duration.declaringClass.name} does not own ${animator.type.name}"
        }
        check(callbackFragment.declaringClass == lineCallback.declaringClass) {
            "lyricLineMotion: callback and captured fragment disagree"
        }
        check(lineCallback.returnType == Any::class.java &&
            lineCallback.parameterTypes.contentEquals(arrayOf(Any::class.java, Any::class.java, Any::class.java))) {
            "lyricLineMotion: unexpected line callback shape ${lineCallback.parameterTypes.toList()}"
        }
        check(lineOffset.declaringClass == callbackFragment.type &&
            lineOffset.returnType == Void.TYPE &&
            lineOffset.parameterTypes.contentEquals(arrayOf<Class<*>>(callbackFragment.type, Integer.TYPE))) {
            "lyricLineMotion: unexpected line offset bridge shape"
        }
        check(duration.type == java.lang.Long.TYPE) { "lyricLineMotion: duration field is not a long" }
    }

    fun fragmentOf(callback: Any): Any? = callbackFragment.get(callback)

    fun animatorOf(fragment: Any): Any? = animator.get(fragment)

    fun durationOf(animator: Any): Long = duration.getLong(animator)

    fun writeDuration(animator: Any, durationMs: Long) = duration.setLong(animator, durationMs)
}
