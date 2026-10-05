package dev.amenhancer.plugin.runtime

import dev.amenhancer.plugin.api.AmppPlugin
import java.lang.reflect.Modifier

internal object PluginEntries {
    fun instantiate(loader: ClassLoader, entry: String): AmppPlugin {
        val type = loader.loadClass(entry)
        require(type.classLoader === loader && AmppPlugin::class.java.isAssignableFrom(type) &&
            Modifier.isPublic(type.modifiers) && !Modifier.isAbstract(type.modifiers)) { "无效入口或重复 SDK" }
        return type.getConstructor().newInstance() as AmppPlugin
    }
}
