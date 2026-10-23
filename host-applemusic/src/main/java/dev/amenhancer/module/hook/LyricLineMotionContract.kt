package dev.amenhancer.module.hook

import android.view.View
import android.view.ViewGroup
import dev.amenhancer.host.applemusic.AppleMusicHostProfile
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * The minimal Apple Music contract the iOS-style lyric line motion needs, resolved once
 * before any callback is activated:
 *
 *  - `createView` + the `binding`/`recycler` fields give the exact lyric `RecyclerView`
 *    (the same two fields the dual-pane and lyric-blur contracts verify), so the session
 *    never has to guess which recycler in the process is the lyrics list;
 *  - `vmLine` is `PlayerLyricsViewModel.setCurrentHighlightedLine(int)`, the host's own
 *    "the highlighted line changed" signal, which carries the new line index.
 *
 * Everything is selected by the exact production build; a stale profile degrades the
 * feature instead of guessing.
 */
internal class LyricLineMotionContract(profile: AppleMusicHostProfile, val loader: ClassLoader) {
    private val names = profile.document.getJSONObject("lyricBlur")
    private val members = FragmentProfileMembers(loader, names, "lyricBlur", FragmentMemberRequirements(
        methods = setOf("createView", "vmLine"),
        fields = setOf("binding", "recycler"),
    ))

    val createView: Method = members.method("createView")
    val vmLine: Method = members.method("vmLine")
    val binding: Field = members.field("binding")
    val recycler: Field = members.field("recycler")

    init {
        check(profile.productionEnabled && names.getInt("contractVersion") == 1)
        check(View::class.java.isAssignableFrom(createView.returnType))
        check(vmLine.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType)))
        check(vmLine.returnType == Void.TYPE && !Modifier.isStatic(vmLine.modifiers))
        check(binding.declaringClass == createView.declaringClass)
        check(recycler.declaringClass == binding.type)
        check(ViewGroup::class.java.isAssignableFrom(recycler.type))
    }

    /** The lyric recycler owned by [fragment], or null while the fragment is not created. */
    fun recyclerOf(fragment: Any): ViewGroup? {
        val bindingValue = binding.get(fragment) ?: return null
        return recycler.get(bindingValue) as? ViewGroup
    }
}
