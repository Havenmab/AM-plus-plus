package dev.amenhancer.module.hook

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.widget.ImageView
import dev.amenhancer.module.ModuleConstants
import java.lang.reflect.Method
import java.util.WeakHashMap

/** Native detachment recycles the blur bitmap while leaving shaders bound to that bitmap. */
internal class FragmentPlayerBackgroundRecovery(
    private val contract: FragmentPlayerRecoveryContract,
    private val scope: HookRegistrationScope,
) {
    private val pending = WeakHashMap<View, Boolean>()
    private var reportedFailure = false

    private fun clear(view: View) {
        contract.backgroundShader.set(view, null)
        contract.backgroundPreviousShader.set(view, null)
        contract.backgroundDerived.set(view, null)
        (contract.backgroundPaint.get(view) as Paint).shader = null
        (contract.backgroundPreviousPaint.get(view) as Paint).shader = null
    }

    private fun restore(view: View, root: View?) {
        if (!scope.isActive || !view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return
        val image = root?.findViewById<ImageView>(root.resources.getIdentifier(
            contract.names.getString("imageId"), "id", ModuleConstants.TARGET_PACKAGE,
        ))
        fun usable(bitmap: Bitmap?): Bitmap? = bitmap?.takeUnless { it.isRecycled }
        fun picture(bitmap: Bitmap?): Bitmap? = usable(bitmap)?.takeIf { it.width > 1 && it.height > 1 }
        val current = usable(contract.backgroundSource.get(view) as? Bitmap)
        val foreground = (image?.drawable as? BitmapDrawable)?.bitmap
        val bitmap = picture(contract.backgroundQueued.get(view) as? Bitmap)
            ?: picture(current) ?: picture(foreground) ?: return
        val invalidSource = current == null || current.width <= 1 || current.height <= 1
        val recycled = (contract.backgroundDerived.get(view) as? Bitmap)?.isRecycled == true
        if (pending[view] != true && !invalidSource && !recycled) return
        (contract.backgroundFade.get(view) as ValueAnimator).cancel()
        clear(view)
        contract.backgroundArtwork.invoke(view, bitmap)
        pending.remove(view)
    }

    private fun safely(action: () -> Unit) {
        if (!scope.isActive) return
        runCatching(action).onFailure { error ->
            if (!reportedFailure) {
                reportedFailure = true
                ModernXposedRuntime.log("Native player background recovery skipped: ${error.javaClass.simpleName}")
            }
        }
    }

    private fun observe(method: Method, after: (ModernMethodHook.MethodHookParam) -> Unit) {
        check(ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.throwable == null) safely { after(param) }
            }
        }, scope))
    }

    fun install() {
        observe(contract.backgroundDetach) { param ->
            val view = param.thisObject as? View ?: return@observe
            clear(view)
            pending[view] = true
        }
        observe(View::class.java.getDeclaredMethod("onAttachedToWindow").apply { isAccessible = true }) { param ->
            val view = param.thisObject as? View ?: return@observe
            if (contract.backgroundDetach.declaringClass.isInstance(view) && pending[view] == true) {
                view.post { safely { restore(view, view.rootView) } }
            }
        }
        observe(contract.resume) { param ->
            val root = contract.getView.invoke(param.thisObject) as? View ?: return@observe
            val view = root.findViewById<View>(root.resources.getIdentifier(
                contract.names.getString("backgroundId"), "id", ModuleConstants.TARGET_PACKAGE,
            )) ?: return@observe
            if (contract.backgroundDetach.declaringClass.isInstance(view)) {
                root.post { safely { restore(view, root) } }
            }
        }
        observe(ImageView::class.java.getDeclaredMethod("setImageDrawable", android.graphics.drawable.Drawable::class.java)) { param ->
            val image = param.thisObject as? ImageView ?: return@observe
            if (image.id != image.resources.getIdentifier(contract.names.getString("imageId"), "id", ModuleConstants.TARGET_PACKAGE)) return@observe
            val root = image.rootView
            val view = root.findViewById<View>(root.resources.getIdentifier(
                contract.names.getString("backgroundId"), "id", ModuleConstants.TARGET_PACKAGE,
            )) ?: return@observe
            if (contract.backgroundDetach.declaringClass.isInstance(view)) {
                image.post { safely { restore(view, root) } }
            }
        }
        scope.onClose { pending.clear() }
    }
}
