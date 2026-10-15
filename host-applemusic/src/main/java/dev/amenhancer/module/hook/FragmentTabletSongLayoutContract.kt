package dev.amenhancer.module.hook

import android.view.ViewGroup
import org.json.JSONObject

/** Immutable native member/resource contract; no hooks, views or mutable session state. */
internal class FragmentTabletSongLayoutContract(names: JSONObject, members: FragmentProfileMembers) {
    private val classes = names.getJSONObject("classes")
    val containerType = members.type(classes.getString("container"))
    val paramsType = members.type(classes.getString("parameters"))
    val barrierType = members.type(classes.getString("barrier"))
    val guideline = members.constructor("guideline")
    val newParams = members.constructor("parameters")
    val guideBegin = members.field("guideBegin")
    val guideEnd = members.field("guideEnd")
    val guidePercent = members.field("guidePercent")
    val orientation = members.field("orientation")
    val startToStart = members.field("startToStart")
    val endToEnd = members.field("endToEnd")
    val getMargin = members.method("barrierGetMargin")
    val setMargin = members.method("barrierSetMargin")
    val getReferences = members.method("barrierGetReferences")
    val setReferences = members.method("barrierSetReferences")
    val bindingForView = members.method("bindingForView")
    val bindingRoot = members.field("bindingRoot")
    val seekbarPadding = members.field("seekbarPadding")
    val setSeekbarPadding = members.method("setSeekbarPadding")
    val resources: Map<String, String> = names.getJSONObject("resources").let { values ->
        values.keys().asSequence().associateWith(values::getString)
    }
    val dimensions: Map<String, String> = names.getJSONObject("dimensions").let { values ->
        values.keys().asSequence().associateWith(values::getString)
    }
    val containerResource: String = resources.getValue("container")

    init {
        listOf("container", "artworkStart", "artworkEnd", "metadataStart", "metadataEnd", "title", "subtitle",
            "collaborator", "more", "controls", "barrier").forEach { check(!resources[it].isNullOrBlank()) { "songLayout.resources.$it missing" } }
        listOf("artworkInset", "metadataInset", "progressInset").forEach { check(!dimensions[it].isNullOrBlank()) { "songLayout.dimensions.$it missing" } }
        check(ViewGroup::class.java.isAssignableFrom(containerType))
        check(ViewGroup.MarginLayoutParams::class.java.isAssignableFrom(paramsType))
        check(newParams.declaringClass == paramsType)
        listOf(guideBegin, guideEnd, orientation, startToStart, endToEnd).forEach {
            check(it.declaringClass == paramsType && it.type == Int::class.javaPrimitiveType)
        }
        check(guidePercent.declaringClass == paramsType && guidePercent.type == Float::class.javaPrimitiveType)
        check(seekbarPadding.declaringClass.isAssignableFrom(setSeekbarPadding.declaringClass))
    }
}
