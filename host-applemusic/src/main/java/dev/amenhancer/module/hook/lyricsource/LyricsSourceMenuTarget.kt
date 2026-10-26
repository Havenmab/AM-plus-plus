package dev.amenhancer.module.hook.lyricsource

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import dev.amenhancer.host.applemusic.AppleMusicHostProfile
import dev.amenhancer.module.hook.LyricsSourceSheetEntry
import dev.amenhancer.module.hook.LyricsSourceTarget
import dev.amenhancer.module.hook.ModernMethodHook
import dev.amenhancer.module.hook.ModernXposedRuntime
import dev.amenhancer.module.hook.TargetCapabilityInstall

/**
 * The 「歌词源…」 row inside Apple Music's own lyrics "more" popup.
 *
 * What the popup is: on 6.5.x/7.0 the lyrics page builds a plain [PopupWindow]
 * whose content view is a vertical [LinearLayout] holding one [TextView] per
 * native row — the rows the user sees today are 隐藏发音 / 隐藏翻译. Evidence:
 * the HLE reference (AppleOnlineSourceMenuHooks + AppleOnlineSourceMenuDialog,
 * which append their own row the same way), and the host profile's
 * LYRICS_SOURCE_MENU_CLICK_LISTENER contract — the click listener that opens the
 * popup carries the PlayerLyricsViewFragment back reference, and that fragment
 * owns a PopupWindow field (1609: l1).
 *
 * Two install paths, one insertion:
 *
 * 1. Exact (preferred): the profile pins the click-listener class for the build,
 *    so this target hooks its onClick(View) and reads the popup from the
 *    fragment field the listener points at. Same path HLE uses; the fragment
 *    identity proves which page the popup belongs to.
 * 2. Generic (no symbols): android.widget.PopupWindow.showAsDropDown is a
 *    framework method, so hooking it needs no per-build symbol. It only acts
 *    when the anchor is the lyrics "more" button (list_left_icon / its
 *    more_button content description, resolved by resource *name*), the anchor
 *    sits inside the lyrics sheet header (current_player_item), and the content
 *    view already holds 2..8 text rows. Anything else is left alone.
 *
 * Both paths funnel into [insertItem], which removes any previously inserted row
 * before appending one, so a popup can never grow a second copy — including two
 * popups in the same process, and including the exact hook firing after the
 * generic one for the same popup.
 *
 * Failure policy: nothing here may damage the native rows. Every reflective step
 * is fail-open — an unresolved contract, a missing class, an unexpected popup
 * shape or a blank native row all end the attempt with a log line and no view
 * change. [install] reports [TargetCapabilityInstall.Degraded] when neither path
 * installed; the sheet being unregistered is itself a degraded reason, because a
 * row that opens nothing is worse than no row.
 */
internal class LyricsSourceMenuTarget(
    private val application: android.app.Application,
    private val classLoader: ClassLoader,
    private val profile: AppleMusicHostProfile,
    /** False while the feature is off; the hooks stay installed but insert nothing. */
    private val enabled: () -> Boolean,
    private val onFailure: (String) -> Unit,
    private val logger: (String) -> Unit,
) : LyricsSourceTarget {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var installed = false

    @Volatile
    private var installedDetail: String? = null

    @Volatile
    private var insertLogged = false

    /** Resource ids resolved by *name*, so a numeric id shift between builds never matters. */
    private val moreButtonId: Int by lazy {
        runCatching {
            application.resources.getIdentifier(MORE_BUTTON_ID_NAME, "id", application.packageName)
        }.getOrDefault(0)
    }

    private val moreButtonDescription: String? by lazy {
        runCatching {
            val stringId = application.resources.getIdentifier(
                MORE_BUTTON_DESCRIPTION_NAME,
                "string",
                application.packageName,
            )
            if (stringId == 0) null else application.resources.getString(stringId)
        }.getOrNull()
    }

    private val lyricsSheetHeaderId: Int by lazy {
        runCatching {
            application.resources.getIdentifier(LYRICS_SHEET_HEADER_ID_NAME, "id", application.packageName)
        }.getOrDefault(0)
    }

    @Synchronized
    override fun install(): TargetCapabilityInstall {
        installedDetail?.let { return TargetCapabilityInstall.Active("Lyrics source menu already active: " + it) }
        if (!enabled()) {
            return TargetCapabilityInstall.Degraded("歌词源面板已关闭，不添加菜单项")
        }
        // The app-side sheet registers through EmbeddedSettingsHost.install(), which runs
        // *after* FeatureInstallation.installEmbedded() (this target's install). Gating the
        // install on LyricsSourceSheetEntry.available therefore failed forever on a cold
        // start: the hook was never installed, so the item could never appear even after the
        // sheet was ready. Install the hook unconditionally and check availability when a
        // popup is actually populated (injectRow), which runs on every menu open.
        if (!LyricsSourceSheetEntry.available) {
            logger("lyrics-source menu: sheet surface not registered yet; item appears once it is")
        }
        // The always-present entry comes first and does not depend on any menu: the
        // lyrics popup only exists when Apple's document has the translation /
        // romanization rows, and the player "..." is a track action menu this feature
        // deliberately leaves alone. A build without the popup seam still gets a
        // working entry through this path.
        LyricsSourceFloatingEntry.install(
            enabled = enabled,
            onOpen = { activity -> LyricsSourceSheetEntry.open(activity) },
            logger = logger,
        )
        val contract = LyricsSourceMenuContract.from(profile.document)
        val detail = buildList {
            installExactListener(contract)?.let { add(it) }
            installGenericPopupHook()?.let { add(it) }
        }
        if (detail.isEmpty()) {
            val reason = "Apple Music " + profile.versionCode + " 的歌词更多弹层没有可用契约（浮动入口已安装）"
            onFailure(reason)
            return TargetCapabilityInstall.Degraded(reason)
        }
        installed = true
        installedDetail = detail.joinToString(" + ")
        logger("lyrics-source menu: installed " + installedDetail + " (profile=" + profile.id + ")")
        return TargetCapabilityInstall.Active("Lyrics source menu: " + installedDetail)
    }

    // ---------------------------------------------------------------- exact path

    private fun installExactListener(contract: LyricsSourceMenuContract?): String? {
        if (contract == null) {
            logger("lyrics-source menu: profile has no LYRICS_SOURCE_MENU_CLICK_LISTENER contract (generic path only)")
            return null
        }
        val listenerClass = runCatching {
            Class.forName(contract.listenerClassName, false, classLoader)
        }.getOrNull() ?: run {
            logger("lyrics-source menu: listener " + contract.listenerClassName + " not loadable")
            return null
        }
        val method = listenerClass.declaredMethods.firstOrNull { candidate ->
            candidate.name == contract.methodName &&
                candidate.parameterCount == 1 &&
                candidate.parameterTypes[0] == View::class.java
        } ?: run {
            logger("lyrics-source menu: " + contract.listenerClassName + "#" + contract.methodName + "(View) is gone")
            return null
        }
        val hooked = runCatching {
            ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!enabled()) return
                    val anchor = param.args.getOrNull(0) as? View ?: return
                    val fragment = runCatching { fragmentOf(param.thisObject, contract) }.getOrNull()
                    val popup = runCatching { findPopup(param.thisObject, contract) }.getOrNull()
                    if (popup != null) offerMenu(popup, anchor, EXACT_PATH)
                    // The fragment reference is the only place where the lyrics page's
                    // view tree is reachable, so the always-on entry attaches from here.
                    LyricsSourceFloatingEntry.attachFromFragment(
                        fragment = fragment,
                        enabled = enabled,
                        onOpen = { activity -> LyricsSourceSheetEntry.open(activity) },
                        logger = logger,
                    )
                }
            })
        }.getOrDefault(false)
        if (!hooked) {
            logger("lyrics-source menu: hooking " + contract.listenerClassName + "#" + contract.methodName + " failed")
            return null
        }
        return "exact " + contract.listenerClassName + "#" + contract.methodName
    }

    /**
     * Walks the click listener's back reference to the lyrics fragment and then
     * that fragment's PopupWindow field (1609: l1). Both are resolved
     * structurally, because the field names are obfuscated per build and the
     * profile only pins the stable ones.
     */
    private fun findPopup(listener: Any?, contract: LyricsSourceMenuContract): PopupWindow? {
        val fragment = fragmentOf(listener, contract) ?: return null
        var type: Class<*>? = fragment.javaClass
        while (type != null) {
            for (field in type.declaredFields) {
                if (!PopupWindow::class.java.isAssignableFrom(field.type)) continue
                val value = runCatching {
                    field.isAccessible = true
                    field.get(fragment)
                }.getOrNull() as? PopupWindow ?: continue
                if (value.contentView != null) return value
            }
            type = type.superclass
        }
        return null
    }

    private fun fragmentOf(listener: Any?, contract: LyricsSourceMenuContract): Any? {
        if (listener == null) return null
        val pinnedField = contract.fragmentFieldName
        if (pinnedField != null) {
            val value = runCatching {
                val field = listener.javaClass.getDeclaredField(pinnedField)
                field.isAccessible = true
                field.get(listener)
            }.getOrNull()
            if (value != null) return value
        }
        val fragmentClassName = contract.fragmentClassName ?: return null
        var type: Class<*>? = listener.javaClass
        while (type != null) {
            for (field in type.declaredFields) {
                if (field.type.name != fragmentClassName) continue
                val value = runCatching {
                    field.isAccessible = true
                    field.get(listener)
                }.getOrNull() ?: continue
                return value
            }
            type = type.superclass
        }
        return null
    }

    // -------------------------------------------------------------- generic path

    private fun installGenericPopupHook(): String? {
        val popupClass = runCatching { Class.forName("android.widget.PopupWindow") }.getOrNull() ?: run {
            logger("lyrics-source menu: android.widget.PopupWindow is not loadable")
            return null
        }
        val hooked = buildList {
            popupClass.declaredMethods
                .filter { method ->
                    method.name == SHOW_AS_DROP_DOWN &&
                        method.parameterCount in 1..4 &&
                        method.parameterTypes.firstOrNull() == View::class.java
                }
                .forEach { method ->
                    val ok = runCatching {
                        ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                if (!enabled()) return
                                val popup = param.thisObject as? PopupWindow ?: return
                                val anchor = param.args.getOrNull(0) as? View ?: return
                                offerMenu(popup, anchor, GENERIC_PATH)
                            }
                        })
                    }.getOrDefault(false)
                    if (ok) add(method.name + "/" + method.parameterCount)
                }
        }
        if (hooked.isEmpty()) return null
        return "popup " + hooked.joinToString(",")
    }

    // ---------------------------------------------------------------- insertion

    private fun offerMenu(popup: PopupWindow, anchor: View, source: String) {
        // Post instead of inserting inline: showAsDropDown's caller has not laid
        // the popup out yet, and the click-listener hook runs before the popup has
        // finished positioning itself.
        mainHandler.post {
            runCatching { insertItem(popup, anchor, source) }
                .onFailure { error ->
                    logger("lyrics-source menu: insert failed: " + (error.message ?: error.javaClass.simpleName))
                }
        }
    }

    private fun insertItem(popup: PopupWindow, anchor: View, source: String) {
        if (!LyricsSourceSheetEntry.available) return
        if (!popup.isShowing) return
        // The exact path already proved the page through the click listener's
        // PlayerLyricsViewFragment reference. The generic path has no such reference,
        // so it proves the entry point from the anchor instead: the lyrics "more"
        // button, any descendant of the lyrics sheet header, or the player page's own
        // overflow button. The sheet must stay reachable on a song whose popup only
        // carries one row, so the anchor — not the row count — is what identifies us.
        val anchorKind = anchorKind(anchor)
        if (source == GENERIC_PATH && anchorKind == null && !belongsToLyricsSheet(anchor)) return
        val menu = popup.contentView as? LinearLayout ?: run {
            // An adapter-owned menu (RecyclerView) cannot be appended to without
            // mutating Apple's adapter, which this feature never does; the long-press
            // entry covers those screens instead.
            val container = popup.contentView
            logger(
                "lyrics-source menu: popup content is " + container?.javaClass?.name +
                    " (not appendable), relying on the long-press entry",
            )
            return
        }
        // Our own row from an earlier popup (or from the other install path) is
        // removed first, so the popup can only ever hold one copy.
        for (index in menu.childCount - 1 downTo 0) {
            if (menu.getChildAt(index).tag == ITEM_TAG) menu.removeViewAt(index)
        }
        val nativeRows = (0 until menu.childCount)
            .map { menu.getChildAt(it) }
            .filter { it.tag != ITEM_TAG }
        // At least one clickable text row: the lyrics popup has two, but other
        // popups AM shows from the same buttons may carry a single action.
        if (nativeRows.size !in MIN_NATIVE_ROWS..MAX_NATIVE_ROWS) return
        if (nativeRows.any { it !is TextView || (it as TextView).text.isNullOrBlank() }) return
        if (nativeRows.none { it.isClickable }) return
        // The popup builder adds the pronunciation row first and the translation
        // row second; remembering them lets the sheet's 不显示 switch perform the
        // same tap the user would have performed in this menu.
        LyricsSourceNativeMenu.remember(
            pronunciation = nativeRows.getOrNull(0),
            translation = nativeRows.getOrNull(1),
        )
        val item = createMenuItem(popup, menu, nativeRows.first() as TextView)
        menu.addView(item, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        resize(popup, menu, anchor)
        if (!insertLogged) {
            insertLogged = true
            logger(
                "lyrics-source menu: row added source=" + source +
                    " anchor=" + describeAnchor(anchor) +
                    " nativeRows=" + nativeRows.size +
                    " total=" + menu.childCount,
            )
        }
    }

    private fun createMenuItem(popup: PopupWindow, menu: LinearLayout, reference: TextView): TextView {
        val item = TextView(menu.context).apply {
            tag = ITEM_TAG
            text = MENU_ITEM_TEXT
            isSingleLine = true
            maxLines = 1
            ellipsize = null
            setHorizontallyScrolling(true)
            gravity = (reference.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) or Gravity.CENTER_VERTICAL
            setPadding(reference.paddingLeft, reference.paddingTop, reference.paddingRight, reference.paddingBottom)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, reference.textSize)
            setTextColor(reference.currentTextColor)
            typeface = reference.typeface
            minimumWidth = reference.minimumWidth
            minimumHeight = reference.minimumHeight
            background = runCatching {
                reference.background?.constantState?.newDrawable(menu.resources)
            }.getOrNull()
            isClickable = true
            isFocusable = true
        }
        item.setOnClickListener { view ->
            dismissPopup(popup)
            openSheet(view)
        }
        return item
    }

    private fun openSheet(view: View) {
        val activity = view.context.findActivity()
        if (activity == null) {
            logger("lyrics-source menu: tap without an Activity context, sheet not opened")
            return
        }
        if (!LyricsSourceSheetEntry.open(activity)) {
            logger("lyrics-source menu: sheet entry refused to open")
        }
    }

    private fun dismissPopup(popup: PopupWindow) {
        // AM's popup normally closes itself on a native row tap; we close it
        // first so the sheet is never opened behind a still-visible popup.
        runCatching { popup.dismiss() }
    }

    /**
     * The popup was shown with the anchor, so growing it needs the same anchor;
     * the native rows already decide the minimum width.
     */
    private fun resize(popup: PopupWindow, menu: LinearLayout, anchor: View) {
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        var nativeWidth = 0
        for (index in 0 until menu.childCount) {
            val child = menu.getChildAt(index)
            child.measure(unspecified, unspecified)
            nativeWidth = maxOf(nativeWidth, child.measuredWidth, child.minimumWidth)
        }
        val desiredWidth = maxOf(nativeWidth + menu.paddingLeft + menu.paddingRight, menu.width, 1)
        menu.measure(
            View.MeasureSpec.makeMeasureSpec(desiredWidth, View.MeasureSpec.EXACTLY),
            unspecified,
        )
        val desiredHeight = menu.measuredHeight.coerceAtLeast(1)
        runCatching { popup.update(anchor, desiredWidth, desiredHeight) }
        menu.requestLayout()
        menu.invalidate()
    }

    // --------------------------------------------------------------- validation

    private fun isLyricsMoreAnchor(view: View): Boolean = anchorKind(view) != null

    /**
     * Which known "more" button [view] is, or null. Both are resolved by resource
     * *name* so a numeric id shift never matters, and the two names are what tells
     * the lyrics popup apart from the player page's own overflow menu.
     */
    private fun anchorKind(view: View): String? {
        if (view !is ImageView) return null
        val nameMatches = (moreButtonId != 0 && view.id == moreButtonId) ||
            (moreButtonDescription != null && view.contentDescription?.toString() == moreButtonDescription)
        if (!nameMatches) return null
        // Only the lyrics page's own "more" button hosts our row. The player page's
        // "..." opens a *track* action menu (pin / remove / share), so inserting a
        // lyrics entry there would describe the wrong thing.
        return if (belongsToLyricsSheet(view)) ANCHOR_LYRICS_MORE else null
    }

    /**
     * The generic path has no fragment reference, so it proves the page from the
     * view tree instead: Apple's lyrics sheet header (current_player_item) is the
     * "more" button's ancestor. A player-page or mini-player "more" button never
     * satisfies this, and their popups are not ours.
     */
    private fun belongsToLyricsSheet(anchor: View): Boolean {
        val headerId = lyricsSheetHeaderId
        if (headerId == 0) return false
        var current: Any? = anchor.parent
        var depth = 0
        while (current is View && depth < MAX_ANCESTOR_DEPTH) {
            if (current.id == headerId) return true
            current = current.parent
            depth += 1
        }
        return false
    }

    private fun describeAnchor(anchor: View): String = runCatching {
        val id = if (anchor.id == 0) "0" else application.resources.getResourceEntryName(anchor.id)
        anchor.javaClass.simpleName + "#" + id
    }.getOrDefault(anchor.javaClass.simpleName)

    private fun Context.findActivity(): Activity? {
        var context: Context? = this
        var depth = 0
        while (context is ContextWrapper && depth < MAX_ANCESTOR_DEPTH) {
            if (context is Activity) return context
            context = context.baseContext
            depth += 1
        }
        return context as? Activity
    }

    private companion object {
        const val ITEM_TAG = "ampp_lyrics_source_menu_item"
        const val MENU_ITEM_TEXT = "歌词源…"
        const val EXACT_PATH = "exact"
        const val GENERIC_PATH = "popup"
        const val SHOW_AS_DROP_DOWN = "showAsDropDown"
        const val MORE_BUTTON_ID_NAME = "list_left_icon"
        const val MORE_BUTTON_DESCRIPTION_NAME = "more_button"
        const val LYRICS_SHEET_HEADER_ID_NAME = "current_player_item"
        const val MIN_NATIVE_ROWS = 1
        const val ANCHOR_LYRICS_MORE = "lyrics-more"
        const val MAX_NATIVE_ROWS = 8
        const val MAX_ANCESTOR_DEPTH = 8
    }
}

/**
 * The one profile entry this feature needs: which class Apple uses to open the
 * lyrics popup, and how to reach the fragment (hence the popup) from it.
 *
 * Every field comes from the host profile's hookTargets block, so registering a
 * new build never touches code. A missing or half-filled entry simply means the
 * exact path is unavailable and the generic popup path takes over.
 */
internal data class LyricsSourceMenuContract(
    val listenerClassName: String,
    val methodName: String,
    val fragmentClassName: String?,
    val fragmentFieldName: String?,
) {
    companion object {
        const val CONTRACT_ID = "LYRICS_SOURCE_MENU_CLICK_LISTENER"
        const val FRAGMENT_CLASS_MEMBER = "LYRICS_SOURCE_MENU_FRAGMENT_CLASS"
        const val FRAGMENT_FIELD_MEMBER = "LYRICS_SOURCE_MENU_FRAGMENT_FIELD"

        fun from(document: org.json.JSONObject): LyricsSourceMenuContract? = runCatching {
            val targets = document.optJSONObject("hookTargets") ?: return null
            val candidates = targets.optJSONArray(CONTRACT_ID) ?: return null
            for (index in 0 until candidates.length()) {
                val entry = candidates.optJSONObject(index) ?: continue
                val className = entry.optString("className").takeIf(String::isNotBlank) ?: continue
                val methodName = entry.optString("methodName").takeIf(String::isNotBlank) ?: continue
                val members = entry.optJSONObject("runtimeMemberNames")
                return LyricsSourceMenuContract(
                    listenerClassName = className,
                    methodName = methodName,
                    fragmentClassName = members?.optString(FRAGMENT_CLASS_MEMBER)
                        ?.takeIf(String::isNotBlank),
                    fragmentFieldName = members?.optString(FRAGMENT_FIELD_MEMBER)
                        ?.takeIf(String::isNotBlank),
                )
            }
            null
        }.getOrNull()
    }
}
