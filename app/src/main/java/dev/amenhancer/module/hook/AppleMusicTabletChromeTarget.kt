package dev.amenhancer.module.hook

import android.app.Activity
import android.os.Bundle
import android.view.View
import dev.amenhancer.module.ModuleConstants
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Host adapter for the iPad-style tablet chrome.
 *
 * The chrome never owns a player. The host's live `MediaPlayerController` belongs to
 * `MediaPlaybackService`, so this adapter installs a single `onCreate` hook on that service and
 * reads the controller out of it (`service.N` -> holder -> `holder.h`). The controller is held
 * only as a `@Volatile` reference: a missing capture leaves [available] false and every command
 * a guarded no-op, never an exception in the Compose render path.
 *
 * Every seam is resolved independently through [TargetSymbolResolver]; the verified 6.5.3 (1599)
 * owners are profile-pinned with a disabled structural fallback, so 6.5.0/6.5.1/6.5.2 and
 * unknown builds report the missing seam instead of binding a guessed class. [resolutionSummary]
 * names exactly which seams resolved and which did not so the parent reports `DEGRADED` from
 * evidence rather than from a generic failure string.
 */
internal class AppleMusicTabletChromeTarget(
    private val symbols: TargetSymbolResolver,
    private val build: TargetBuild,
) : TabletChromeTarget {

    /** Alias so a caller can hold the adapter through its installed-target interface. */
    val commands: TabletChromeTarget get() = this

    private val installStarted = AtomicBoolean(false)

    @Volatile
    private var installResult: TargetCapabilityInstall? = null

    @Volatile
    private var controller: Any? = null

    @Volatile
    private var seams: Seams? = null

    @Volatile
    private var seamSummary: String = "tablet chrome seams unresolved"

    /**
     * `FeatureHealth`-friendly summary: which seams resolved, which are missing, and whether a
     * live controller has actually been captured yet.
     */
    override val resolutionSummary: String
        get() {
            val capture = if (controller != null) "captured" else "pending"
            val state = when (installResult) {
                is TargetCapabilityInstall.Active -> "active"
                is TargetCapabilityInstall.Degraded -> "degraded"
                null -> "not-installed"
            }
            return "$seamSummary; controller=$capture; state=$state"
        }

    override val available: Boolean
        get() = controller != null && seams?.coreResolved == true

    override val shuffleAvailable: Boolean
        get() = seams?.shuffleResolved == true

    override val repeatAvailable: Boolean
        get() = seams?.repeatResolved == true

    override val moreAvailable: Boolean
        get() = seams?.moreResolved == true

    /**
     * Resolves every seam and installs the controller capture hook. Idempotent: a repeated call
     * returns the first outcome instead of double-registering the hook.
     */
    override fun install(): TargetCapabilityInstall {
        installResult?.let { return it }
        if (!installStarted.compareAndSet(false, true)) {
            return installResult
                ?: TargetCapabilityInstall.Degraded("Tablet chrome installation is already in progress")
        }
        val result = runCatching { installSeams() }.getOrElse { error ->
            debug("tablet chrome install failed: $error")
            TargetCapabilityInstall.Degraded(
                "Tablet chrome install failed: " + (error.message ?: error.javaClass.simpleName),
            )
        }
        installResult = result
        return result
    }

    private fun installSeams(): TargetCapabilityInstall {
        val serviceResolution = symbols.resolve(AppleMusicSymbols.TabletMediaPlaybackService)
        val onCreateResolution = symbols.resolve(AppleMusicSymbols.TabletMediaPlaybackServiceOnCreate)
        val controllerFieldResolution =
            symbols.resolve(AppleMusicSymbols.TabletMediaPlaybackServiceControllerField)
        val holderResolution =
            symbols.resolve(AppleMusicSymbols.TabletMediaPlaybackServiceControllerHolder)
        val holderFieldResolution =
            symbols.resolve(AppleMusicSymbols.TabletMediaPlaybackServiceControllerHolderField)
        val controllerTypeResolution = symbols.resolve(AppleMusicSymbols.TabletMediaPlayerController)
        val playResolution = symbols.resolve(AppleMusicSymbols.TabletMediaPlayerControllerPlay)
        val pauseResolution = symbols.resolve(AppleMusicSymbols.TabletMediaPlayerControllerPause)
        val skipNextResolution =
            symbols.resolve(AppleMusicSymbols.TabletMediaPlayerControllerSkipToNextItem)
        val skipPreviousResolution =
            symbols.resolve(AppleMusicSymbols.TabletMediaPlayerControllerSkipToPreviousItem)
        val canSkipNextResolution =
            symbols.resolve(AppleMusicSymbols.TabletMediaPlayerControllerCanSkipToNextItem)
        val canSkipPreviousResolution =
            symbols.resolve(AppleMusicSymbols.TabletMediaPlayerControllerCanSkipToPreviousItem)
        val getShuffleResolution =
            symbols.resolve(AppleMusicSymbols.TabletMediaPlayerControllerGetShuffleMode)
        val setShuffleResolution =
            symbols.resolve(AppleMusicSymbols.TabletMediaPlayerControllerSetShuffleMode)
        val canSetShuffleResolution =
            symbols.resolve(AppleMusicSymbols.TabletMediaPlayerControllerCanSetShuffleMode)
        val getRepeatResolution =
            symbols.resolve(AppleMusicSymbols.TabletMediaPlayerControllerGetRepeatMode)
        val setRepeatResolution =
            symbols.resolve(AppleMusicSymbols.TabletMediaPlayerControllerSetRepeatMode)
        val canSetRepeatResolution =
            symbols.resolve(AppleMusicSymbols.TabletMediaPlayerControllerCanSetRepeatMode)
        val getPlaybackStateResolution =
            symbols.resolve(AppleMusicSymbols.TabletMediaPlayerControllerGetPlaybackState)
        val getCurrentItemResolution =
            symbols.resolve(AppleMusicSymbols.TabletMediaPlayerControllerGetCurrentItem)
        val addListenerResolution =
            symbols.resolve(AppleMusicSymbols.TabletMediaPlayerControllerAddListener)
        val removeListenerResolution =
            symbols.resolve(AppleMusicSymbols.TabletMediaPlayerControllerRemoveListener)
        val queueItemResolution = symbols.resolve(AppleMusicSymbols.TabletPlayerQueueItemGetItem)
        val titleResolution = symbols.resolve(AppleMusicSymbols.TabletPlayerMediaItemGetTitle)
        val artistResolution = symbols.resolve(AppleMusicSymbols.TabletPlayerMediaItemGetArtistName)
        val expandResolution = symbols.resolve(AppleMusicSymbols.TabletPlayerActivityExpandPlayer)
        val fragmentResolution = symbols.resolve(AppleMusicSymbols.TabletPlayerActivityPlayerFragment)
        val currentFragmentResolution = symbols.resolve(AppleMusicSymbols.TabletPlayerControllerCurrentFragment)
        val selectPaneResolution = symbols.resolve(AppleMusicSymbols.PlayerControllerSelectPane)
        val queueKeyOwnerResolution = symbols.resolve(AppleMusicSymbols.TabletQueueBundleArgKeyOwner)
        val queueKeyFieldResolution = symbols.resolve(AppleMusicSymbols.TabletQueueBundleArgKeyField)

        val checks = listOf<Pair<String, TargetResolution<*>>>(
            "service" to serviceResolution,
            "onCreate" to onCreateResolution,
            "controllerField" to controllerFieldResolution,
            "holderClass" to holderResolution,
            "holderField" to holderFieldResolution,
            "controllerType" to controllerTypeResolution,
            "play" to playResolution,
            "pause" to pauseResolution,
            "skipNext" to skipNextResolution,
            "skipPrevious" to skipPreviousResolution,
            "canSkipNext" to canSkipNextResolution,
            "canSkipPrevious" to canSkipPreviousResolution,
            "getShuffle" to getShuffleResolution,
            "setShuffle" to setShuffleResolution,
            "canSetShuffle" to canSetShuffleResolution,
            "getRepeat" to getRepeatResolution,
            "setRepeat" to setRepeatResolution,
            "canSetRepeat" to canSetRepeatResolution,
            "getPlaybackState" to getPlaybackStateResolution,
            "getCurrentItem" to getCurrentItemResolution,
            "addListener" to addListenerResolution,
            "removeListener" to removeListenerResolution,
            "queueItemGetItem" to queueItemResolution,
            "mediaItemGetTitle" to titleResolution,
            "mediaItemGetArtist" to artistResolution,
            "expandPlayer" to expandResolution,
            "playerFragment" to fragmentResolution,
            "currentFragment" to currentFragmentResolution,
            "selectPane" to selectPaneResolution,
            "queueArgKeyOwner" to queueKeyOwnerResolution,
            "queueArgKeyField" to queueKeyFieldResolution,
        )
        val summary = buildString {
            append("tablet chrome [")
            append(build.displayName)
            append("] seams resolved=[")
            append(
                checks.filter { it.second is TargetResolution.Found<*> }
                    .joinToString(",") { it.first },
            )
            append("] missing=[")
            append(
                checks.filterNot { it.second is TargetResolution.Found<*> }
                    .joinToString(",") { it.first },
            )
            append(']')
        }
        seamSummary = summary

        val resolved = Seams(
            service = serviceResolution.valueOrNull(),
            onCreate = onCreateResolution.valueOrNull(),
            controllerType = controllerTypeResolution.valueOrNull(),
            controllerField = controllerFieldResolution.valueOrNull(),
            holderField = holderFieldResolution.valueOrNull(),
            play = playResolution.valueOrNull(),
            pause = pauseResolution.valueOrNull(),
            skipNext = skipNextResolution.valueOrNull(),
            skipPrevious = skipPreviousResolution.valueOrNull(),
            canSkipNext = canSkipNextResolution.valueOrNull(),
            canSkipPrevious = canSkipPreviousResolution.valueOrNull(),
            getShuffle = getShuffleResolution.valueOrNull(),
            setShuffle = setShuffleResolution.valueOrNull(),
            canSetShuffle = canSetShuffleResolution.valueOrNull(),
            getRepeat = getRepeatResolution.valueOrNull(),
            setRepeat = setRepeatResolution.valueOrNull(),
            canSetRepeat = canSetRepeatResolution.valueOrNull(),
            getPlaybackState = getPlaybackStateResolution.valueOrNull(),
            getCurrentItem = getCurrentItemResolution.valueOrNull(),
            addListener = addListenerResolution.valueOrNull(),
            removeListener = removeListenerResolution.valueOrNull(),
            queueItemGetItem = queueItemResolution.valueOrNull(),
            mediaItemGetTitle = titleResolution.valueOrNull(),
            mediaItemGetArtist = artistResolution.valueOrNull(),
            expandPlayer = expandResolution.valueOrNull(),
            playerFragment = fragmentResolution.valueOrNull(),
            currentFragment = currentFragmentResolution.valueOrNull(),
            selectPane = selectPaneResolution.valueOrNull(),
            queueArgKey = queueKeyFieldResolution.valueOrNull(),
            summary = summary,
        )
        resolved.prepare()
        seams = resolved

        val onCreate = resolved.onCreate
        if (!resolved.captureReady || onCreate == null) {
            return TargetCapabilityInstall.Degraded(resolved.summary)
        }
        if (!installCaptureHook(onCreate)) {
            return TargetCapabilityInstall.Degraded(
                resolved.summary + "; capture hook registration failed",
            )
        }
        if (!resolved.coreResolved) {
            return TargetCapabilityInstall.Degraded(
                resolved.summary + "; controller command seams missing",
            )
        }
        return TargetCapabilityInstall.Active(resolved.summary)
    }

    /**
     * Hooks `MediaPlaybackService#onCreate`. The hook re-runs whenever the host recreates the
     * service, so the adapter always points at the newest live controller.
     */
    private fun installCaptureHook(onCreate: Method): Boolean = runCatching {
        ModernXposedRuntime.hookMethod(onCreate, object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                captureController(param.thisObject)
            }
        })
        true
    }.onFailure {
        debug("tablet chrome capture hook registration failed: $it")
    }.getOrDefault(false)

    private fun captureController(service: Any?) {
        val resolved = seams ?: return
        val source = service ?: return
        val controllerField = resolved.controllerField ?: return
        val holderField = resolved.holderField ?: return
        val captured = runCatching {
            val holder = controllerField.get(source) ?: return@runCatching null
            holderField.get(holder)
        }.onFailure {
            debug("tablet chrome controller capture failed: $it")
        }.getOrNull()
        if (captured == null) {
            debug("tablet chrome controller capture produced no controller")
            return
        }
        controller = captured
        debug("tablet chrome controller captured=" + captured.javaClass.name)
    }

    override fun isPlaying(): Boolean = runCatching {
        val target = controller ?: return@runCatching false
        val method = seams?.getPlaybackState ?: return@runCatching false
        (method.invoke(target) as? Int) == PLAYBACK_STATE_PLAYING
    }.getOrDefault(false)

    override fun shuffleEnabled(): Boolean = runCatching {
        val target = controller ?: return@runCatching false
        val method = seams?.getShuffle ?: return@runCatching false
        (method.invoke(target) as? Int) == SHUFFLE_MODE_SONGS
    }.getOrDefault(false)

    override fun repeatMode(): Int = runCatching {
        val target = controller ?: return@runCatching REPEAT_MODE_OFF
        val method = seams?.getRepeat ?: return@runCatching REPEAT_MODE_OFF
        (method.invoke(target) as? Int) ?: REPEAT_MODE_OFF
    }.getOrDefault(REPEAT_MODE_OFF)

    override fun setShuffleEnabled(enabled: Boolean) {
        runCatching {
            val target = controller ?: return@runCatching
            val resolved = seams ?: return@runCatching
            val setter = resolved.setShuffle ?: return@runCatching
            if (resolved.canSetShuffle?.invoke(target) == false) return@runCatching
            setter.invoke(target, if (enabled) SHUFFLE_MODE_SONGS else SHUFFLE_MODE_OFF)
        }.onFailure { debug("tablet chrome setShuffleEnabled failed: $it") }
    }

    override fun cycleRepeatMode() {
        runCatching {
            val target = controller ?: return@runCatching
            val resolved = seams ?: return@runCatching
            val setter = resolved.setRepeat ?: return@runCatching
            if (resolved.canSetRepeat?.invoke(target) == false) return@runCatching
            val current = (resolved.getRepeat?.invoke(target) as? Int) ?: REPEAT_MODE_OFF
            setter.invoke(target, nextTabletRepeatMode(current))
        }.onFailure { debug("tablet chrome cycleRepeatMode failed: $it") }
    }

    override fun skipToPrevious() {
        runCommand("skipToPrevious") { target, resolved ->
            if (resolved.canSkipPrevious?.invoke(target) == false) return@runCommand
            resolved.skipPrevious?.invoke(target)
        }
    }

    override fun skipToNext() {
        runCommand("skipToNext") { target, resolved ->
            if (resolved.canSkipNext?.invoke(target) == false) return@runCommand
            resolved.skipNext?.invoke(target)
        }
    }

    override fun play() {
        runCommand("play") { target, resolved -> resolved.play?.invoke(target) }
    }

    override fun pause() {
        runCommand("pause") { target, resolved -> resolved.pause?.invoke(target) }
    }

    override fun currentTitle(): String? = currentText("title") { it.mediaItemGetTitle }

    override fun currentArtist(): String? = currentText("artist") { it.mediaItemGetArtist }

    override fun expandPlayer(activity: Activity) {
        runCatching {
            val resolved = seams ?: return@runCatching
            val method = resolved.expandPlayer ?: return@runCatching
            if (!method.declaringClass.isInstance(activity)) return@runCatching
            val enumType = method.parameterTypes.firstOrNull() ?: return@runCatching
            val constant = enumConstant(enumType, EXPAND_PLAYER) ?: return@runCatching
            method.invoke(activity, constant)
            debug("tablet chrome expandPlayer invoked")
        }.onFailure { debug("tablet chrome expandPlayer failed: $it") }
    }

    override fun openLyrics(activity: Activity) {
        openPane(activity, LYRICS_PANE, null, "openLyrics")
    }

    override fun openQueue(activity: Activity) {
        // The pane enum carries the intent: the host's own queue button additionally puts a boolean
        // under `utils.E.o` so the pane opens on its "up next" tab, but an unresolved key must not
        // stop the pane from opening at all — the lyrics pane proves the selector works with no
        // extras. Fall back to an empty bundle instead of silently doing nothing.
        val key = queueArgKey()
        if (key == null) {
            debug("tablet chrome openQueue: queue bundle arg key unavailable, opening the pane bare")
        }
        val extras = key?.let { resolved ->
            runCatching { Bundle().apply { putBoolean(resolved, true) } }.getOrNull()
        }
        openPane(activity, QUEUE_PANE, extras, "openQueue")
    }

    override fun openSongMenu(activity: Activity) {
        if (!available || !moreAvailable || activity.isFinishing || activity.isDestroyed) return
        runCatching {
            val resolved = seams ?: return@runCatching
            val accessor = resolved.playerFragment ?: return@runCatching
            if (!accessor.declaringClass.isInstance(activity)) return@runCatching
            val player = checkNotNull(accessor.invoke(activity)) { "player fragment unavailable" }
            val pane = checkNotNull(resolved.currentFragment?.invoke(player)) { "current player pane unavailable" }
            val root = checkNotNull(resolved.fragmentView?.invoke(pane) as? View) { "current player view unavailable" }
            if (!root.isAttachedToWindow) return@runCatching
            val id = activity.resources.getIdentifier("list_left_icon", "id", ModuleConstants.TARGET_PACKAGE)
            check(id != 0) { "native song menu resource unavailable" }
            val button = checkNotNull(root.findViewById<View>(id)) { "native song menu button unavailable" }
            if (!button.isEnabled || !button.hasOnClickListeners()) {
                debug("tablet chrome openSongMenu: native menu callback unavailable")
                return@runCatching
            }
            val invoked = button.callOnClick()
            debug("tablet chrome openSongMenu invoked=$invoked")
        }.onFailure { debug("tablet chrome openSongMenu failed: $it") }
    }

    override fun addListener(listener: () -> Unit): AutoCloseable {
        val target = controller
        val resolved = seams
        val add = resolved?.addListener
        val remove = resolved?.removeListener
        val listenerType = add?.parameterTypes?.firstOrNull()
        if (target == null || add == null || remove == null || listenerType == null) {
            return AutoCloseable { }
        }
        val proxy = runCatching {
            Proxy.newProxyInstance(
                listenerType.classLoader,
                arrayOf(listenerType),
                InvocationHandler { proxyRef, method, args ->
                    when (method.name) {
                        "onPlaybackStateChanged",
                        "onCurrentItemChanged",
                        "onRepeatModeChanged",
                        "onShuffleModeChanged",
                        -> {
                            runCatching { listener() }
                            null
                        }
                        "toString" -> "AppleMusicTabletChromeListener"
                        "hashCode" -> System.identityHashCode(proxyRef)
                        "equals" -> proxyRef === args?.firstOrNull()
                        else -> defaultListenerReturn(method.returnType)
                    }
                },
            )
        }.onFailure {
            debug("tablet chrome listener proxy creation failed: $it")
        }.getOrNull() ?: return AutoCloseable { }
        runCatching { add.invoke(target, proxy) }
            .onFailure { debug("tablet chrome addListener failed: $it") }
        return AutoCloseable {
            runCatching { remove.invoke(target, proxy) }
                .onFailure { debug("tablet chrome removeListener failed: $it") }
        }
    }

    private fun runCommand(label: String, command: (Any, Seams) -> Unit) {
        runCatching {
            val target = controller ?: return@runCatching
            val resolved = seams ?: return@runCatching
            command(target, resolved)
        }.onFailure { debug("tablet chrome $label failed: $it") }
    }

    private fun currentText(label: String, selector: (Seams) -> Method?): String? = runCatching {
        val target = controller ?: return@runCatching null
        val resolved = seams ?: return@runCatching null
        val getCurrentItem = resolved.getCurrentItem ?: return@runCatching null
        val getItem = resolved.queueItemGetItem ?: return@runCatching null
        val getText = selector(resolved) ?: return@runCatching null
        val queueItem = getCurrentItem.invoke(target) ?: return@runCatching null
        val mediaItem = getItem.invoke(queueItem) ?: return@runCatching null
        (getText.invoke(mediaItem) as? String)?.takeIf { it.isNotBlank() }
    }.onFailure { debug("tablet chrome current $label failed: $it") }.getOrNull()

    private fun openPane(activity: Activity, paneConstant: String, extras: Bundle?, label: String) {
        runCatching {
            val resolved = seams ?: return@runCatching
            val accessor = resolved.playerFragment ?: return@runCatching
            val selectPane = resolved.selectPane ?: return@runCatching
            if (!accessor.declaringClass.isInstance(activity)) return@runCatching
            val fragment = accessor.invoke(activity) ?: return@runCatching
            val enumType = selectPane.parameterTypes.firstOrNull() ?: return@runCatching
            val constant = enumConstant(enumType, paneConstant) ?: run {
                debug("tablet chrome $label missing enum constant $paneConstant")
                return@runCatching
            }
            selectPane.invoke(fragment, constant, extras)
            debug("tablet chrome $label invoked")
        }.onFailure { debug("tablet chrome $label failed: $it") }
    }

    /**
     * The Bundle key the host's own queue button uses.
     *
     * `utils.E` composes its keys in `<clinit>` as `E.class.getName() + ".KEY_..."`; `o` is
     * `… + ".KEY_IS_USER_PRESS_QUEUE_BUTTON"` (verified in the 6.5.3 bytecode, next to `n` =
     * `…KEY_IS_USER_PRESS_LYRICS_BUTTON`). The reflective read is preferred because it survives a
     * renamed class, but it can come back empty if the holder has not been initialised yet, so the
     * bytecode-derived literal is used as the fallback rather than dropping the extras entirely.
     */
    private fun queueArgKey(): String? = runCatching {
        val field = seams?.queueArgKey ?: return@runCatching QUEUE_ARG_KEY_FALLBACK
        (field.get(null) as? String)?.takeIf { it.isNotBlank() } ?: QUEUE_ARG_KEY_FALLBACK
    }.getOrElse { QUEUE_ARG_KEY_FALLBACK }

    private fun enumConstant(enumType: Class<*>, name: String): Any? = runCatching {
        enumType.enumConstants.orEmpty().firstOrNull { (it as? Enum<*>)?.name == name }
    }.getOrNull()

    /** All resolved host members, plus the summary the parent reports. */
    private class Seams(
        val service: Class<*>?,
        val onCreate: Method?,
        val controllerType: Class<*>?,
        val controllerField: Field?,
        val holderField: Field?,
        val play: Method?,
        val pause: Method?,
        val skipNext: Method?,
        val skipPrevious: Method?,
        val canSkipNext: Method?,
        val canSkipPrevious: Method?,
        val getShuffle: Method?,
        val setShuffle: Method?,
        val canSetShuffle: Method?,
        val getRepeat: Method?,
        val setRepeat: Method?,
        val canSetRepeat: Method?,
        val getPlaybackState: Method?,
        val getCurrentItem: Method?,
        val addListener: Method?,
        val removeListener: Method?,
        val queueItemGetItem: Method?,
        val mediaItemGetTitle: Method?,
        val mediaItemGetArtist: Method?,
        val expandPlayer: Method?,
        val playerFragment: Method?,
        val currentFragment: Method?,
        val selectPane: Method?,
        val queueArgKey: Field?,
        val summary: String,
    ) {
        /** The capture path needs the service, its onCreate hook and both holder fields. */
        val captureReady: Boolean =
            service != null && onCreate != null && controllerField != null && holderField != null

        /** Controls the chrome cannot render meaningfully without. */
        val coreResolved: Boolean =
            controllerType != null &&
                play != null &&
                pause != null &&
                skipNext != null &&
                skipPrevious != null &&
                getPlaybackState != null &&
                getShuffle != null &&
                setShuffle != null &&
                getRepeat != null &&
                setRepeat != null &&
                addListener != null &&
                removeListener != null

        val shuffleResolved: Boolean = getShuffle != null && setShuffle != null

        val repeatResolved: Boolean = getRepeat != null && setRepeat != null

        val fragmentView: Method? = runCatching {
            currentFragment?.returnType?.getMethod("getView")
        }.getOrNull()

        val moreResolved: Boolean = playerFragment != null && currentFragment != null && fragmentView != null

        fun prepare() {
            listOfNotNull(
                play,
                pause,
                skipNext,
                skipPrevious,
                canSkipNext,
                canSkipPrevious,
                getShuffle,
                setShuffle,
                canSetShuffle,
                getRepeat,
                setRepeat,
                canSetRepeat,
                getPlaybackState,
                getCurrentItem,
                addListener,
                removeListener,
                queueItemGetItem,
                mediaItemGetTitle,
                mediaItemGetArtist,
                expandPlayer,
                playerFragment,
                currentFragment,
                fragmentView,
                selectPane,
            ).forEach { method -> runCatching { method.isAccessible = true } }
            listOfNotNull(controllerField, holderField, queueArgKey).forEach { field ->
                runCatching { field.isAccessible = true }
            }
        }
    }
}

/**
 * off(0) -> all(2) -> one(1) -> off(0), the reference three-state button order. An unknown
 * current mode is treated as off so the first press always progresses to `all`.
 */
internal fun nextTabletRepeatMode(current: Int): Int = when (current) {
    REPEAT_MODE_OFF -> REPEAT_MODE_ALL
    REPEAT_MODE_ALL -> REPEAT_MODE_ONE
    REPEAT_MODE_ONE -> REPEAT_MODE_OFF
    else -> REPEAT_MODE_ALL
}

/** Verified `PlaybackRepeatMode` constants (6.5.3 / 1599). */
private const val REPEAT_MODE_OFF = 0
private const val REPEAT_MODE_ONE = 1
private const val REPEAT_MODE_ALL = 2

/** Verified `PlaybackShuffleMode` constants (6.5.3 / 1599). */
private const val SHUFFLE_MODE_OFF = 0
private const val SHUFFLE_MODE_SONGS = 1

/** Verified `PlaybackState` constant (6.5.3 / 1599); everything else is "not playing". */
private const val PLAYBACK_STATE_PLAYING = 1

/** Verified `PlayerActivity$p` enum constants (6.5.3 / 1599). */
private const val EXPAND_PLAYER = "EXPAND_PLAYER"

/** Verified `v0$n` pane enum constants (6.5.3 / 1599). */
private const val LYRICS_PANE = "LYRICS"
private const val QUEUE_PANE = "QUEUE"

/**
 * `com.apple.android.music.utils.E.o` as the host builds it: `E.class.getName() +
 * ".KEY_IS_USER_PRESS_QUEUE_BUTTON"`. Only used when the reflective read yields nothing.
 */
private const val QUEUE_ARG_KEY_FALLBACK =
    "com.apple.android.music.utils.E.KEY_IS_USER_PRESS_QUEUE_BUTTON"

private const val DEBUG_PREFIX = "[AMENH-3]"

private fun defaultListenerReturn(returnType: Class<*>): Any? = when (returnType) {
    Void.TYPE -> null
    Boolean::class.javaPrimitiveType -> false
    Int::class.javaPrimitiveType -> 0
    Long::class.javaPrimitiveType -> 0L
    Float::class.javaPrimitiveType -> 0f
    Double::class.javaPrimitiveType -> 0.0
    Short::class.javaPrimitiveType -> 0.toShort()
    Byte::class.javaPrimitiveType -> 0.toByte()
    Character::class.javaPrimitiveType -> '\u0000'
    else -> null
}

private fun debug(message: String) {
    val line = DEBUG_PREFIX + " " + message
    // Never let host-attached logging (or a JVM unit test without an Android runtime) escape.
    runCatching { ModernXposedRuntime.log(line) }
}
