package dev.amenhancer.module.hook

import android.app.Application
import android.util.Size
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.model.CustomLyricsEntry

/**
 * The complete target-specific seam used by feature hooks.
 *
 * Each feature receives only its own capability, while symbol discovery and
 * reflective hook installation remain private to the Apple Music adapters.
 */
internal data class TargetAdaptation(
    val identity: String,
    val currentSong: CurrentSongIdentityCache = CurrentSongIdentityCache(),
    val dualPane: DualPaneTarget,
    val editorialVideo: EditorialVideoTarget,
    val bidirectionalLyricBlur: BidirectionalLyricBlurTarget,
    val cjkKaraokeAnimation: CjkKaraokeAnimationTarget = CjkKaraokeAnimationTarget {
        TargetCapabilityInstall.Degraded("CJK karaoke animation target was not configured")
    },
    val lyricsTypeface: LyricsTypefaceTarget = LyricsTypefaceTarget {
        TargetCapabilityInstall.Degraded("Lyrics typeface target was not configured")
    },
    val customLyrics: CustomLyricsTarget = CustomLyricsTarget {
        TargetCapabilityInstall.Degraded("Custom lyrics target was not configured")
    },
    val currentSongIdentity: CurrentSongIdentityTarget = CurrentSongIdentityTarget {
        TargetCapabilityInstall.Degraded("Current song identity target was not configured")
    },
    /** Retained only for compatibility; the former global target is never installed. */
    val catalogLanguage: CatalogLanguageTarget = CatalogLanguageTarget {
        TargetCapabilityInstall.Degraded("Catalog language target is intentionally disabled")
    },
    val hleMetadata: HleMetadataTarget = HleMetadataTarget {
        TargetCapabilityInstall.Degraded("HLE metadata target was not configured")
    },
    /**
     * Tablet iPad-style chrome adapter. Null until the adapter factory supplies it; the feature
     * reports DEGRADED when the capability is absent instead of installing anything.
     */
    val tabletChrome: TabletChromeTarget? = null,
) {
    companion object {
        fun appleMusic(
            config: TargetConfigClient,
            application: Application,
            classLoader: ClassLoader,
            lyricsTypefaceSession: LyricsTypefaceSession,
            currentSong: CurrentSongIdentityCache = CurrentSongIdentityCache(),
        ): TargetAdaptation {
            val build = targetBuild(application)
            val resolver = IndexedTargetSymbolResolver(
                build = build,
                source = ApkTargetClassSource(application, classLoader),
            )
            val settings = config.settings()
            val autoLyricsRuntime = (settings.customLyricsEnabled && settings.automaticLyricsEnabled)
                .takeIf { it }
                ?.let {
                    val suppressedAutoIds = runCatching {
                        config.customLyricsManifest().entries
                            .filterNot { entry -> entry.enabled }
                            .mapTo(mutableSetOf(), CustomLyricsEntry::appleMusicId)
                    }.getOrDefault(emptySet())
                    createAutoLyricsRuntime(application, suppressedAutoIds)
                }
            return TargetAdaptation(
                identity = build.displayName,
                currentSong = currentSong,
                dualPane = AppleMusicDualPaneTarget(resolver, build),
                editorialVideo = AppleMusicEditorialVideoTarget(application, resolver),
                bidirectionalLyricBlur = AppleMusicBidirectionalLyricBlurTarget(resolver),
                cjkKaraokeAnimation = AppleMusicCjkKaraokeAnimationTarget(resolver),
                lyricsTypeface = AppleMusicLyricsTypefaceTarget(
                    symbols = resolver,
                    session = lyricsTypefaceSession,
                ),
                customLyrics = AppleMusicCustomLyricsTarget(
                    config = config,
                    symbols = resolver,
                    currentSong = currentSong,
                    autoLyricsRuntime = autoLyricsRuntime,
                ),
                currentSongIdentity = AppleMusicCurrentSongIdentityTarget(
                    resolver,
                    currentSong,
                ),
                catalogLanguage = AppleMusicCatalogLanguageTarget(
                    symbols = resolver,
                    rawTargetLanguage = settings.titleCorrectionMode.catalogLanguage.orEmpty(),
                ),
                hleMetadata = HleMetadataTarget {
                    val activeModule = ModernXposedRuntime.activeModule()
                        ?: return@HleMetadataTarget TargetCapabilityInstall.Degraded(
                            "Modern Xposed module was not attached",
                        )
                    runCatching {
                        HleMetadataRuntime(
                            module = activeModule,
                            application = application,
                            classLoader = classLoader,
                            mode = settings.titleCorrectionMode,
                            restoreCjkOriginalMetadata = settings.restoreCjkOriginalMetadata,
                            localizedMetadataCache = settings.localizedMetadataCache,
                        ).install()
                    }.getOrElse { error ->
                        ModernXposedRuntime.log("HLE metadata runtime install failed", error)
                        TargetCapabilityInstall.Degraded(
                            "HLE metadata runtime failed: ${error.message ?: error.javaClass.simpleName}",
                        )
                    }
                },
                tabletChrome = AppleMusicTabletChromeTarget(resolver, build),
            )
        }
    }
}
internal fun interface DualPaneTarget {
    fun install(): TargetCapabilityInstall
}

internal fun interface EditorialVideoTarget {
    fun install(): TargetCapabilityInstall
}

internal fun interface BidirectionalLyricBlurTarget {
    fun install(): TargetCapabilityInstall
}

internal fun interface CjkKaraokeAnimationTarget {
    fun install(): TargetCapabilityInstall
}

internal fun interface LyricsTypefaceTarget {
    fun install(): TargetCapabilityInstall
}

internal fun interface CustomLyricsTarget {
    fun install(): TargetCapabilityInstall
}

internal fun interface CurrentSongIdentityTarget {
    fun install(): TargetCapabilityInstall
}

internal fun interface HleMetadataTarget {
    fun install(): TargetCapabilityInstall
}

/**
 * Host adapter behind the tablet iPad-style chrome. [install] captures the host's live playback
 * controller and installs the command seams; the adapter itself is the command surface the chrome
 * session drives, so a partially resolved adapter degrades per command instead of failing whole.
 */
internal interface TabletChromeTarget : TabletChromeCommands {
    fun install(): TargetCapabilityInstall

    /** Which seams resolved, so the health message can name what is missing. */
    val resolutionSummary: String
}

internal class AppleMusicEditorialVideoTarget(
    private val application: Application,
    private val symbols: TargetSymbolResolver,
) : EditorialVideoTarget {
    private val metadataRefreshDepth = ThreadLocal.withInitial { 0 }

    override fun install(): TargetCapabilityInstall {
        val listenerResolution = symbols.resolve(
            AppleMusicSymbols.EditorialVideoPlayerMetadataListener,
        )
        val listener = listenerResolution.valueOrNull()
            ?: return TargetCapabilityInstall.Degraded(listenerResolution.summary)
        val resolution = symbols.resolve(AppleMusicSymbols.EditorialVideoUrlSelector)
        val selector = resolution.valueOrNull()
            ?: return TargetCapabilityInstall.Degraded(resolution.summary)
        val motionResolution = symbols.resolve(AppleMusicSymbols.EditorialVideoMotionSetup)
        val motionSetup = motionResolution.valueOrNull()
            ?: return TargetCapabilityInstall.Degraded(motionResolution.summary)

        val listenerInstalled = runCatching {
            ModernXposedRuntime.hookMethod(listener, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!TabletModeQualifier.isEligible(application)) return
                    metadataRefreshDepth.set(metadataRefreshDepth.get() + 1)
                    param.extras[SCOPED_REFRESH] = true
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.extras[SCOPED_REFRESH] != true) return
                    val depth = metadataRefreshDepth.get()
                    if (depth <= 1) {
                        metadataRefreshDepth.remove()
                    } else {
                        metadataRefreshDepth.set(depth - 1)
                    }
                }
            })
        }.getOrDefault(false)
        val selectorInstalled = runCatching {
            ModernXposedRuntime.hookMethod(selector, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (metadataRefreshDepth.get() == 0) return
                    val flavors = param.args.getOrNull(2) as? Array<*> ?: return
                    EditorialVideoFlavorPolicy.squareFirst(flavors)?.let { param.args[2] = it }
                }
            })
        }.getOrDefault(false)
        val motionInstalled = runCatching {
            ModernXposedRuntime.hookMethod(motionSetup, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (metadataRefreshDepth.get() == 0) return
                    val size = param.args.getOrNull(4) as? Size ?: return
                    if (size.width > 0 && size.height > 0 && size.width != size.height) {
                        param.args[4] = Size(size.width, size.width)
                    }
                }
            })
        }.getOrDefault(false)

        return if (listenerInstalled && selectorInstalled && motionInstalled) {
            TargetCapabilityInstall.Active(
                "Editorial Video square flavor enabled for tablet playback; " +
                    "motion size normalized; ${listenerResolution.summary}; " +
                    "${resolution.summary}; ${motionResolution.summary}",
            )
        } else {
            TargetCapabilityInstall.Degraded(
                "Editorial Video tablet hook incomplete; ${listenerResolution.summary}; " +
                    "${resolution.summary}; ${motionResolution.summary}",
            )
        }
    }

    private companion object {
        const val SCOPED_REFRESH = "ampp.editorial-video.scoped-refresh"
    }
}

internal object EditorialVideoFlavorPolicy {
    fun squareFirst(flavors: Array<*>): Array<*>? {
        val square = flavors.indexOfFirst { (it as? Enum<*>)?.name == "DETAIL_SQUARE" }
        val tall = flavors.indexOfFirst { (it as? Enum<*>)?.name == "DETAIL_TALL" }
        if (square >= 0) {
            if (square == 0) return null
            return flavors.copyOf().also {
                @Suppress("UNCHECKED_CAST")
                val reordered = it as Array<Any?>
                val value = reordered[0]
                reordered[0] = reordered[square]
                reordered[square] = value
            }
        }
        if (tall < 0) return null

        val tallValue = flavors[tall] as? Enum<*> ?: return null
        val squareValue = tallValue.javaClass.enumConstants
            ?.firstOrNull { (it as? Enum<*>)?.name == "DETAIL_SQUARE" }
            ?: return null
        @Suppress("UNCHECKED_CAST")
        val reordered = java.lang.reflect.Array.newInstance(
            flavors.javaClass.componentType,
            flavors.size + 1,
        ) as Array<Any?>
        reordered[0] = squareValue
        flavors.forEachIndexed { index, value -> reordered[index + 1] = value }
        return reordered
    }
}

internal sealed interface TargetCapabilityInstall {
    val message: String

    data class Active(override val message: String) : TargetCapabilityInstall {
        init {
            require(message.isNotBlank()) { "Target capability diagnostic must not be blank" }
        }
    }

    data class Degraded(override val message: String) : TargetCapabilityInstall {
        init {
            require(message.isNotBlank()) { "Target capability diagnostic must not be blank" }
        }
    }
}

internal fun TargetCapabilityInstall.toFeatureInstallResult(): FeatureInstallResult = when (this) {
    is TargetCapabilityInstall.Active -> FeatureInstallResult.active(message)
    is TargetCapabilityInstall.Degraded -> FeatureInstallResult.degraded(message)
}
