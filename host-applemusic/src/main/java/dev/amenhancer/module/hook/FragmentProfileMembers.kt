package dev.amenhancer.module.hook

import org.json.JSONObject
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Resolve the complete declared inventory once, before any hook is registered. */
internal class FragmentProfileMembers(
    private val loader: ClassLoader,
    private val names: JSONObject,
    private val section: String,
    required: FragmentMemberRequirements,
) {
    private val types = HashMap<String, Class<*>>()
    private val methods = LinkedHashMap<String, Method>()
    private val fields = LinkedHashMap<String, Field>()
    private val constructors = LinkedHashMap<String, Constructor<*>>()

    init {
        val errors = ArrayList<String>()
        fun resolve(group: String, roles: Set<String>, action: (String, JSONObject) -> Unit) {
            val declared = names.optJSONObject(group)
            roles.filter { declared?.has(it) != true }.forEach { errors += "$section.$group.$it: missing required role" }
            declared?.keys()?.forEach { role ->
                runCatching { action(role, declared.getJSONObject(role)) }.onFailure {
                    errors += "$section.$group.$role: ${it.message ?: it.javaClass.simpleName}"
                }
            }
        }
        resolve("methods", required.methods) { role, c ->
            val parameters = c.getJSONArray("parameters")
            methods[role] = type(c.getString("owner")).getDeclaredMethod(c.getString("name"),
                *Array(parameters.length()) { type(parameters.getString(it)) }).apply {
                check(returnType == type(c.getString("returns")) &&
                    Modifier.isStatic(modifiers) == c.getBoolean("static") && !isBridge && !isSynthetic &&
                    (c.optBoolean("invocationOnly") || !Modifier.isAbstract(modifiers))) { "descriptor mismatch: $this" }
                isAccessible = true
            }
        }
        resolve("fields", required.fields) { role, c ->
            fields[role] = type(c.getString("owner")).getDeclaredField(c.getString("name")).apply {
                check(type == type(c.getString("type")) && !Modifier.isStatic(modifiers)) { "descriptor mismatch: $this" }
                isAccessible = true
            }
        }
        resolve("constructors", required.constructors) { role, c ->
            val parameters = c.getJSONArray("parameters")
            constructors[role] = type(c.getString("owner")).getDeclaredConstructor(
                *Array(parameters.length()) { type(parameters.getString(it)) }).apply { isAccessible = true }
        }
        check(errors.isEmpty()) { errors.joinToString("\n") }
    }

    fun type(name: String): Class<*> = types.getOrPut(name) {
        when (name) {
            "void" -> Void.TYPE
            "boolean" -> java.lang.Boolean.TYPE
            "int" -> Integer.TYPE
            "float" -> java.lang.Float.TYPE
            "long" -> java.lang.Long.TYPE
            else -> if (name.startsWith("[")) Class.forName(name, false, loader) else loader.loadClass(name)
        }
    }
    fun method(role: String): Method = checkNotNull(methods[role]) { "$section.methods.$role: undeclared role" }
    fun field(role: String): Field = checkNotNull(fields[role]) { "$section.fields.$role: undeclared role" }
    fun constructor(role: String): Constructor<*> = checkNotNull(constructors[role]) { "$section.constructors.$role: undeclared role" }
}

internal data class FragmentMemberRequirements(
    val methods: Set<String> = emptySet(),
    val fields: Set<String> = emptySet(),
    val constructors: Set<String> = emptySet(),
)

/** Roles are code-owned; a new profile cannot omit a dependency by omitting its JSON entry. */
internal object FragmentDualPaneInventory {
    val main = FragmentMemberRequirements(
        methods = setOf("initialize", "createView", "selectPane", "resume", "destroyView", "paneViewCreated",
            "context", "parent", "fragmentId", "childManager", "fragmentAccessor", "tagAccessor", "stateSaved",
            "findFragment", "replace", "show", "remove", "onCommit", "commit", "refresh", "lyricsVisibility",
            "setPane", "coverFrame", "resetCover", "nativeCover", "expandedSheetTop", "supportsLyrics", "online", "offlineLyrics", "executeBinding"),
        fields = setOf("callbackOwner", "pane", "entering", "transitioning", "paneLiveData", "callback", "behavior", "behaviorState", "currentItem", "bindingRoot"),
        constructors = setOf("transaction"),
    )
    val lyrics = FragmentMemberRequirements(
        methods = setOf("createView", "resume", "destroyView", "getView", "fragmentId", "registerListener", "listenerAttach",
            "listenerDetach", "refresh", "metrics", "chrome", "controls"),
        fields = setOf("binding", "container", "recycler", "gradients", "bindingContainer", "bindingControls",
            "listenerList", "syncFirst", "syncSecond", "metricOffset", "metricStart", "metricEnd"),
    )
    val karaoke = FragmentMemberRequirements(methods = setOf("bind"),
        fields = setOf("adapter", "width", "binding", "item", "flexbox"))
    val video = FragmentMemberRequirements(
        methods = setOf("metadata", "updateSize", "surface", "getView", "parent", "browser", "videoSize"),
        fields = setOf("owner", "videoMode", "sizeAnimation", "videoWidth", "videoHeight"),
    )
    val song = FragmentMemberRequirements(
        methods = setOf("barrierGetMargin", "barrierSetMargin", "barrierGetReferences", "barrierSetReferences", "bindingForView", "setSeekbarPadding"),
        fields = setOf("guideBegin", "guideEnd", "guidePercent", "orientation", "startToStart", "endToEnd", "bindingRoot", "seekbarPadding"),
        constructors = setOf("guideline", "parameters"),
    )
}
