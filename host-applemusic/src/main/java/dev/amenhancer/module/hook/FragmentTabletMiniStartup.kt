package dev.amenhancer.module.hook

import android.view.View
import android.view.ViewGroup
import org.json.JSONObject

/** Re-evaluates the native startup predicate after the restored queue/account becomes ready. */
internal class FragmentTabletMiniStartup(
    private val owner: Any,
    private val root: ViewGroup,
    private val material: View,
    loader: ClassLoader,
    content: Class<*>,
    names: JSONObject,
) : Runnable, AutoCloseable {
    private val browser = resolveFragmentNativeBrowserGetter(content)
    private val item = FragmentChromeContract.method(browser.returnType, names.getString("currentItemMethod"))
    private val loggedIn = FragmentChromeContract.method(loader.loadClass(names.getString("accountClass")), names.getString("accountReadyMethod"))
    private val show = FragmentChromeContract.method(content, names.getString("visibilityMethod"), java.lang.Boolean.TYPE)
    private val view = FragmentChromeContract.method(content, "getView")
    private var attempts = 0
    private var closed = false

    fun start() { root.post(this) }
    override fun run() {
        if (closed || view.invoke(owner) !== root || !root.isAttachedToWindow) return
        if (material.visibility == View.VISIBLE) return
        runCatching {
            val readyAccount = loggedIn.invoke(null) == true
            val currentItem = browser.invoke(owner)?.let { item.invoke(it) != null } == true
            if (readyAccount && currentItem) {
                show.invoke(owner, true)
                ModernXposedRuntime.log("7.0 tablet mini startup: restored native visibility after account/queue ready")
                return
            }
            if (++attempts < 40) root.postDelayed(this, 250)
            else ModernXposedRuntime.log("7.0 tablet mini startup remains native: account=$readyAccount, item=$currentItem")
        }.onFailure { ModernXposedRuntime.log("7.0 tablet mini startup refresh failed", it) }
    }
    override fun close() { closed = true; root.removeCallbacks(this) }
}
