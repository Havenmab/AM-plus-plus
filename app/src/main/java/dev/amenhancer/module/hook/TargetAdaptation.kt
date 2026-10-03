package dev.amenhancer.module.hook

import android.app.Application
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
    override fun install(): TargetCapabilityInstall {
        val resolution = symbols.resolve(AppleMusicSymbols.EditorialVideoUrlSelector)
        val selector = resolution.valueOrNull()
            ?: return TargetCapabilityInstall.Degraded(resolution.summary)
        val installed = runCatching {
            ModernXposedRuntime.hookMethod(selector, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!TabletModeQualifier.isEligible(application)) return
                    val flavors = param.args.getOrNull(2) as? Array<*> ?: return
                    EditorialVideoFlavorPolicy.squareFirst(flavors)?.let { param.args[2] = it }
                }
            })
        }.getOrDefault(false)
        return if (installed) {
            TargetCapabilityInstall.Active(
                "Editorial Video square flavor enabled for tablet playback; ${resolution.summary}",
            )
        } else {
            TargetCapabilityInstall.Degraded(
                "Editorial Video square flavor hook failed; ${resolution.summary}",
            )
        }
    }
}

internal object EditorialVideoFlavorPolicy {
    fun squareFirst(flavors: Array<*>): Array<*>? {
        val square = flavors.indexOfFirst { (it as? Enum<*>)?.name == "DETAIL_SQUARE" }
        val tall = flavors.indexOfFirst { (it as? Enum<*>)?.name == "DETAIL_TALL" }
        if (square < 0 || tall < 0 || square == 0) return null
        return flavors.copyOf().also {
            val value = it[square]
            it[square] = it[tall]
            it[tall] = value
        }
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
