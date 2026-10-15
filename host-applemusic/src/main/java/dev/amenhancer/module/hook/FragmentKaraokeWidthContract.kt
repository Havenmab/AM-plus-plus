package dev.amenhancer.module.hook

import android.view.View
import org.json.JSONObject

/** All members are resolved once, before registration; the row callback performs no discovery. */
internal class FragmentKaraokeWidthContract(lyrics: Class<*>, names: JSONObject, members: FragmentProfileMembers) {
    val adapter = members.type(names.getString("adapterClass"))
    private val holder = members.type(names.getString("holderClass"))
    private val binding = members.type(names.getString("bindingClass"))
    private val holderBase = members.type(names.getString("holderBindingClass"))
    val bind = members.method("bind")
    private val adapterField = members.field("adapter")
    private val widthField = members.field("width")
    private val bindingField = members.field("binding")
    private val itemField = members.field("item")
    private val flexbox = members.field("flexbox")

    init {
        check(adapterField.declaringClass == lyrics && adapterField.type == adapter)
        check(bind.declaringClass == adapter && bind.parameterTypes.contentEquals(arrayOf(holder, Int::class.javaPrimitiveType)))
        check(bindingField.declaringClass == holderBase && bindingField.type == binding.superclass)
        check(itemField.declaringClass == holder && itemField.type == View::class.java)
    }

    fun owns(fragment: Any, candidate: Any) = adapterField.get(fragment) === candidate
    fun initialized(candidate: Any) = widthField.getInt(candidate) > 0
    fun initialize(candidate: Any, row: Any, viewportWidth: Int): Int? {
        if (initialized(candidate)) return null
        val rowBinding = bindingField.get(row)?.takeIf(binding::isInstance) ?: return null
        val item = itemField.get(row) as? View ?: return null
        val leaf = flexbox.get(rowBinding) as? View ?: return null
        val insets = ArrayList<Int>()
        var view: View? = leaf
        var reachedItem = false
        while (view != null) {
            (view.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
                insets += it.marginStart; insets += it.marginEnd
            }
            insets += view.paddingStart; insets += view.paddingEnd
            if (view === item) { reachedItem = true; break }
            view = view.parent as? View
        }
        // Native creation sees a detached item. Cached items are attached: stop at itemView,
        // otherwise the pane/window offsets would be mistaken for internal row padding.
        if (!reachedItem) return null
        return FragmentKaraokeWidthPolicy.initialize(widthField.getInt(candidate), viewportWidth, insets)?.also {
            widthField.setInt(candidate, it)
        }
    }
}
