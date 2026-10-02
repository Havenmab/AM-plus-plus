package dev.amenhancer.module.hook

import android.view.View
import android.widget.TextView

internal class TabletChromeSmallTitle(private val view: TextView) {
    private var hidden = false

    fun update(selectedRootTitle: String?) {
        if (!TabletChromeHeaderPolicy.hidesSmallTitle(view.text?.toString().orEmpty(), selectedRootTitle)) {
            restore()
        } else if (view.visibility == View.VISIBLE) {
            view.visibility = View.INVISIBLE
            hidden = true
        }
    }

    fun restore() {
        if (hidden && view.visibility == View.INVISIBLE) view.visibility = View.VISIBLE
        hidden = false
    }
}
