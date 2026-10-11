package dev.amenhancer.module.hook

import android.view.TextureView
import org.json.JSONObject
import kotlin.math.roundToInt

/** Includes helper/listener/browser dependencies, not only the three hooked methods. */
internal class FragmentTabletVideoContract(names: JSONObject, members: FragmentProfileMembers) {
    val song = members.type(names.getString("songClass"))
    val owner = members.field("owner")
    val metadata = members.method("metadata")
    val updateSize = members.method("updateSize")
    val resize = members.method("resize")
    val surfaceGetter = members.method("surface")
    val videoMode = members.field("videoMode")
    val sizeAnimation = members.field("sizeAnimation")
    val staticBaseline = members.field("staticBaseline")
    val targetSize = members.field("targetSize")
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
        check(staticBaseline.type == android.util.Size::class.java && staticBaseline.declaringClass.isAssignableFrom(song))
        check(targetSize.type == android.util.Size::class.java && targetSize.declaringClass.isAssignableFrom(song))
        check(resize.declaringClass.isAssignableFrom(song) &&
            resize.parameterTypes.contentEquals(arrayOf(java.lang.Boolean.TYPE, android.util.Size::class.java)))
        check(getVideoSize.declaringClass.isAssignableFrom(getBrowser.returnType))
        check(videoWidth.declaringClass == getVideoSize.returnType && videoHeight.declaringClass == getVideoSize.returnType)
    }
}

/** Native video aspect is fitted inside the same resting square as the static image. */
internal fun tabletVideoSize(base: android.util.Size, video: android.util.Size): android.util.Size {
    if (video.width <= 0 || video.height <= 0) return video
    val edge = minOf(base.width, base.height)
    if (maxOf(video.width, video.height) == edge) return video
    val aspect = video.width.toDouble() / video.height
    return if (aspect > 1.0) android.util.Size(edge, (edge / aspect).roundToInt().coerceAtLeast(1))
        else android.util.Size((edge * aspect).roundToInt().coerceAtLeast(1), edge)
}
