package dev.amenhancer.module.hook

import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import org.json.JSONObject

/** Reaches Apple's static-mode compositor after an Editorial listener has been cleared. */
internal class FragmentTabletVideoHandoff(loader: ClassLoader, names: JSONObject) {
    private val song = loader.loadClass(names.getString("songClass"))
    private val base = loader.loadClass(names.getString("baseClass"))
    private val listener = loader.loadClass(names.getString("listenerClass"))
    private val owner = FragmentChromeContract.field(listener, names.getString("listenerOwner"), song)
    private val metadata = FragmentChromeContract.method(listener, "onMediaMetadataChanged", loader.loadClass("z3.w"))
    private val updateSize = FragmentChromeContract.method(base, names.getString("videoSizeMethod"),
        java.lang.Float.TYPE, java.lang.Integer.TYPE, java.lang.Integer.TYPE)
    private val surfaceGetter = FragmentChromeContract.method(song, names.getString("surfaceGetter"))
        .also { check(it.returnType == TextureView::class.java) }
    private val videoMode = FragmentChromeContract.field(base, names.getString("videoModeField"), java.lang.Boolean.TYPE)
    private val getView = FragmentChromeContract.method(song, "getView")
    private val getParent = FragmentChromeContract.method(song, "getParentFragment")
    private val getBrowser = resolveFragmentNativeBrowserGetter(song)
    private val getVideoSize = FragmentChromeContract.method(getBrowser.returnType, "O")
    private val videoWidth = FragmentChromeContract.field(getVideoSize.returnType, "a", java.lang.Integer.TYPE)
    private val videoHeight = FragmentChromeContract.field(getVideoSize.returnType, "b", java.lang.Integer.TYPE)
    private val surfaceName = names.getString("surfaceResource")
    private val imageName = names.getString("imageResource")
    private val calls = FragmentTabletVideoCallScope()

    private fun active(fragment: Any): Boolean = song.isInstance(fragment) &&
        getParent.invoke(fragment)?.let(FragmentTabletDualPaneCoordinator::activeController) == true

    private fun surface(view: View): TextureView? = view.resources.getIdentifier(
        surfaceName, "id", view.context.packageName).takeIf { it != 0 }?.let { view.findViewById(it) }

    fun install(scope: HookRegistrationScope) {
        fun hook(method: java.lang.reflect.Method, callback: ModernMethodHook) {
            check(ModernXposedRuntime.hookMethod(method, callback, scope)) { "Native video handoff hook failed: $method" }
        }
        hook(updateSize, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val fragment = param.thisObject ?: return
                calls.enter(fragment, ((param.args[1] as Int) <= 0 || (param.args[2] as Int) <= 0) && active(fragment))
            }
            override fun afterHookedMethod(param: MethodHookParam) { param.thisObject?.let(calls::leave) }
        })
        hook(surfaceGetter, object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val fragment = param.thisObject ?: return
                if (param.throwable != null || param.result != null || !calls.allows(fragment)) return
                val view = getView.invoke(fragment) as? View ?: return
                surface(view)?.takeIf { it.isAttachedToWindow && it.width > 0 && it.height > 0 }?.let { param.result = it }
            }
        })
        hook(metadata, object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.throwable != null) return
                val fragment = owner.get(param.thisObject) ?: return
                if (!active(fragment)) return
                val view = getView.invoke(fragment) as? ViewGroup ?: return
                view.post {
                    if (getView.invoke(fragment) !== view || !active(fragment)) return@post
                    runCatching {
                        val texture = surface(view) ?: return@runCatching
                        val imageId = view.resources.getIdentifier(imageName, "id", view.context.packageName)
                        val image = view.findViewById<View>(imageId) ?: return@runCatching
                        if (texture.surfaceTextureListener != null || !texture.isAvailable ||
                            texture.alpha < .99f || image.alpha > .01f) return@runCatching
                        val browser = getBrowser.invoke(fragment) ?: return@runCatching
                        val size = getVideoSize.invoke(browser) ?: return@runCatching
                        if (videoWidth.getInt(size) > 0 && videoHeight.getInt(size) > 0) return@runCatching
                        val mode = videoMode.getBoolean(fragment)
                        ModernXposedRuntime.log("7.0 tablet video handoff: stale surface, nativeVideoMode=$mode")
                        if (mode) {
                            updateSize.invoke(fragment, 1f, 0, 0)
                            ModernXposedRuntime.log("7.0 tablet video handoff: native static mode refreshed")
                        }
                    }.onFailure { ModernXposedRuntime.log("7.0 tablet video handoff failed", it) }
                }
            }
        })
    }
}
