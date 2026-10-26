package dev.amenhancer.module.ui.source

import android.app.Activity
import dev.amenhancer.module.hook.LyricsSourceSheetEntry
import dev.amenhancer.module.ui.EmbeddedSettingsHost
import java.lang.ref.WeakReference

/**
 * The lyrics-source sheet, as the Apple Music menu sees it.
 *
 * The menu hook lives in the host-applemusic module, which cannot depend on
 * this module, so the host publishes a lambda through [LyricsSourceSheetEntry]
 * and [LyricsSourceSheetEntryBridge] installs it from
 * [EmbeddedSettingsHost.install]. Everything below the entry point stays
 * module-internal: the host only knows "open the sheet for this activity".
 */
internal object LyricsSourceSheet {

    private var hostReference: WeakReference<EmbeddedSettingsHost>? = null
    private var activeDialog: WeakReference<LyricsSourceSheetDialog>? = null

    /** Remembers which host generation owns the entry point. */
    fun attach(host: EmbeddedSettingsHost) {
        hostReference = WeakReference(host)
    }

    /** Drops the host and closes the sheet when the module is torn down. */
    fun detach() {
        hostReference = null
        activeDialog?.get()?.let { dialog ->
            runCatching { dialog.dismiss() }
        }
        activeDialog = null
    }

    /**
     * Opens the sheet for [activity]. The menu hook already checked
     * [LyricsSourceSheetEntry.available]; the remaining guards keep a stale
     * window (activity finishing, or a sheet already open) from double-opening.
     */
    fun show(activity: Activity) {
        val host = hostReference?.get() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val current = activeDialog?.get()
        if (current != null && current.isShowing) {
            current.window?.decorView?.requestFocus()
            return
        }
        val dialog = LyricsSourceSheetDialog(host, activity)
        activeDialog = WeakReference(dialog)
        runCatching { dialog.show() }
    }
}

/**
 * Installs / removes the host-side entry lambda.
 *
 * The installed lambda is kept so [detach] only clears the entry point it
 * installed itself: a newer host generation must survive an older one's
 * teardown.
 */
internal object LyricsSourceSheetEntryBridge {

    private var installed: ((Activity) -> Unit)? = null

    fun attach(host: EmbeddedSettingsHost) {
        LyricsSourceSheet.attach(host)
        val entry: (Activity) -> Unit = { activity -> LyricsSourceSheet.show(activity) }
        installed = entry
        LyricsSourceSheetEntry.show = entry
    }

    fun detach() {
        val entry = installed ?: return
        LyricsSourceSheet.detach()
        // The host keeps the same "only clear the value I installed" rule.
        LyricsSourceSheetEntry.uninstall(entry)
        installed = null
    }
}
