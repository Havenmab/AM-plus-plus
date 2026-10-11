package dev.amenhancer.module.hook

import android.view.TextureView
import org.json.JSONObject

/** Includes helper/listener/browser dependencies, not only the three hooked methods. */
internal class FragmentTabletVideoContract(names: JSONObject, members: FragmentProfileMembers) {
    val song = members.type(names.getString("songClass"))
    val owner = members.field("owner")
    val metadata = members.method("metadata")
    val updateSize = members.method("updateSize")
    val surfaceGetter = members.method("surface")
    val videoMode = members.field("videoMode")
    val sizeAnimation = members.field("sizeAnimation")
    val getView = members.method("getView")
    val getParent = members.method("parent")
    val getBrowser = members.method("browser")
    val getVideoSize = members.method("videoSize")
    val videoWidth = members.field("videoWidth")
    val videoHeight = members.field("videoHeight")
    val surfaceName = names.getString("surfaceResource")
    val imageName = names.getString("imageResource")

    init {
        check(owner.type == song && owner.declaringClass == metadata.declaringClass)
        check(metadata.parameterTypes.contentEquals(arrayOf(members.type(names.getString("metadataClass")))))
        check(surfaceGetter.returnType == TextureView::class.java)
        check(android.animation.Animator::class.java.isAssignableFrom(sizeAnimation.type) &&
            sizeAnimation.declaringClass.isAssignableFrom(song))
        check(getVideoSize.declaringClass.isAssignableFrom(getBrowser.returnType))
        check(videoWidth.declaringClass == getVideoSize.returnType && videoHeight.declaringClass == getVideoSize.returnType)
    }
}
