package dev.amenhancer.module.ui.source

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import dev.amenhancer.module.lyrics.source.LyricLane
import dev.amenhancer.module.lyrics.source.LyricLaneState
import dev.amenhancer.module.lyrics.source.LyricSourceKind
import dev.amenhancer.module.lyrics.source.LyricSourceOption
import dev.amenhancer.module.lyrics.source.LyricSourceText
import dev.amenhancer.module.lyrics.source.LyricsSourceSnapshot
import dev.amenhancer.module.ui.EmbeddedG3Drawable
import dev.amenhancer.module.ui.EmbeddedGlyphDrawable
import dev.amenhancer.module.ui.EmbeddedGlyphKind
import dev.amenhancer.module.ui.EmbeddedSettingsHost
import dev.amenhancer.module.ui.bindEmbeddedClickFeedback
import dev.amenhancer.module.ui.bindEmbeddedDialogActionTheme
import dev.amenhancer.module.ui.bindEmbeddedTheme
import dev.amenhancer.module.ui.dp
import dev.amenhancer.module.ui.embeddedBadge
import dev.amenhancer.module.ui.embeddedCard
import dev.amenhancer.module.ui.embeddedLyricsEditorButton
import dev.amenhancer.module.ui.embeddedMusicIcon
import dev.amenhancer.module.ui.embeddedPalette
import dev.amenhancer.module.ui.embeddedSettingRow
import dev.amenhancer.module.ui.embeddedSpacer
import dev.amenhancer.module.ui.embeddedTextSize
import dev.amenhancer.module.ui.fromEmbeddedSettingsView
import dev.amenhancer.module.ui.isEmbeddedPhone
import dev.amenhancer.module.ui.matchWidthWrapContent
import dev.amenhancer.module.ui.setEmbeddedBackgroundColor
import dev.amenhancer.module.ui.setEmbeddedImageDrawable
import dev.amenhancer.module.ui.setEmbeddedTextColor
import java.util.Locale

/**
 * The sheet's cards: the current song, the three lanes and their candidate
 * lists.
 *
 * Every builder is a host extension so it reuses the settings page's spacing,
 * text scale, palette and G3 surfaces instead of duplicating a second design
 * system. Blocks are rebuilt from [LyricsSourceSheetController] state on each
 * render; none of them owns user-editable text, so a rebuild cannot lose input
 * focus (the search card is built once and only updates its dynamic regions).
 */
internal fun EmbeddedSettingsHost.renderLyricsSourceSongCard(
    activity: Activity,
    parent: LinearLayout,
    controller: LyricsSourceSheetController,
) {
    parent.removeAllViews()
    val locale = LyricsSourceSheetText.locale(activity)
    when (controller.phase) {
        LyricsSourceSheetPhase.UNAVAILABLE -> parent.addView(
            statusCard(
                activity = activity,
                controller = controller,
                message = LyricsSourceSheetText.unavailable(locale),
                hint = LyricsSourceSheetText.unavailableHint(locale),
                retryLabel = null,
            ),
        )
        LyricsSourceSheetPhase.ERROR -> parent.addView(
            statusCard(
                activity = activity,
                controller = controller,
                message = LyricsSourceSheetText.loadFailed(locale, controller.failureMessage.orEmpty()),
                hint = null,
                retryLabel = LyricsSourceSheetText.retry(locale),
            ),
        )
        LyricsSourceSheetPhase.NO_SONG, LyricsSourceSheetPhase.READY -> {
            val snapshot = controller.snapshot
            if (snapshot == null) {
                parent.addView(noSongCard(activity, controller))
            } else {
                parent.addView(songCard(activity, snapshot))
            }
        }
    }
}

/** "恢复 Apple 原生" hint text under the footer action. */
internal fun EmbeddedSettingsHost.lyricsSourceRestoreHint(activity: Activity, snapshot: LyricsSourceSnapshot?): String {
    val locale = LyricsSourceSheetText.locale(activity)
    return if (snapshot?.hasOverrides == true) {
        LyricsSourceSheetText.restoreHint(locale)
    } else {
        LyricsSourceSheetText.restoreAllNative(locale)
    }
}

/** The three lane cards, in [LyricLane.ALL] order. */
internal fun EmbeddedSettingsHost.renderLyricsSourceLaneCards(
    activity: Activity,
    parent: LinearLayout,
    controller: LyricsSourceSheetController,
) {
    parent.removeAllViews()
    val snapshot = controller.snapshot
    LyricLane.ALL.forEachIndexed { index, lane ->
        parent.addView(laneCard(activity, controller, lane, snapshot?.lane(lane)))
        if (index != LyricLane.ALL.lastIndex) {
            parent.addView(embeddedSpacer(activity, if (isEmbeddedPhone(activity)) 10 else 12))
        }
    }
}

// --- song card ---------------------------------------------------------------

private fun EmbeddedSettingsHost.songCard(activity: Activity, snapshot: LyricsSourceSnapshot): View =
    embeddedCard(activity, null) {
        val locale = LyricsSourceSheetText.locale(activity)
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val artwork = artworkView(activity)
            artwork.contentDescription = snapshot.title.ifBlank { LyricsSourceSheetText.currentSong(locale) }
            LyricsSourceArtwork.load(artwork, snapshot.song.artworkUrl)
            addView(artwork, LinearLayout.LayoutParams(
                dp(activity, if (isEmbeddedPhone(activity)) 52 else 44),
                dp(activity, if (isEmbeddedPhone(activity)) 52 else 44),
            ))
            val details = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply {
                    text = snapshot.title.ifBlank { LyricsSourceSheetText.currentSong(locale) }
                    textSize = embeddedTextSize(activity, 16f, 16f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setEmbeddedTextColor(activity) { colors -> colors.onSurface }
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }, matchWidthWrapContent())
                addView(TextView(activity).apply {
                    text = snapshot.artist.ifBlank { "—" }
                    textSize = embeddedTextSize(activity, 13f, 12f)
                    setEmbeddedTextColor(activity) { colors -> colors.secondary }
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(activity, 2), 0, 0)
                }, matchWidthWrapContent())
                val album = snapshot.album.trim()
                if (album.isNotEmpty()) {
                    addView(TextView(activity).apply {
                        text = album
                        textSize = embeddedTextSize(activity, 12f, 11f)
                        setEmbeddedTextColor(activity) { colors -> colors.mutedText }
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, dp(activity, 1), 0, 0)
                    }, matchWidthWrapContent())
                }
            }
            addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(activity, if (isEmbeddedPhone(activity)) 14 else 12)
            })
            addView(badgeColumn(activity, snapshot, locale), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(activity, 8) })
        }, matchWidthWrapContent())
    }

private fun EmbeddedSettingsHost.badgeColumn(
    activity: Activity,
    snapshot: LyricsSourceSnapshot,
    locale: Locale,
): View = LinearLayout(activity).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.END
    val original = snapshot.lane(LyricLane.ORIGINAL)?.selected
    val provider = original?.let { LyricSourceText.providerLabel(it.providerId, locale) }
        ?: LyricSourceText.noOptionsLabel(locale)
    addView(embeddedBadge(activity, LyricsSourceSheetText.sourceBadge(locale, provider)).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    })
    if (snapshot.hasOverrides) {
        addView(embeddedBadge(activity, LyricsSourceSheetText.customisedBadge(locale)).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(activity, 4) }
        })
    }
}

private fun EmbeddedSettingsHost.artworkView(activity: Activity): ImageView = ImageView(activity).apply {
    scaleType = ImageView.ScaleType.CENTER_CROP
    val placeholder = embeddedMusicIcon(activity) as? ImageView
    setEmbeddedImageDrawable(activity, placeholder?.drawable)
    clipToOutline = true
    val surface = EmbeddedG3Drawable().apply { cornerRadius = dp(activity, 10).toFloat() }
    background = surface
    outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
    bindEmbeddedTheme(activity) { colors -> surface.setColor(colors.raised) }
}

private fun EmbeddedSettingsHost.noSongCard(activity: Activity, controller: LyricsSourceSheetController): View =
    embeddedCard(activity, null) {
        val locale = LyricsSourceSheetText.locale(activity)
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(embeddedMusicIcon(activity), LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 44)))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply {
                    text = LyricsSourceSheetText.noSong(locale)
                    textSize = embeddedTextSize(activity, 15f, 15f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setEmbeddedTextColor(activity) { colors -> colors.onSurface }
                    setSingleLine(false)
                }, matchWidthWrapContent())
                addView(TextView(activity).apply {
                    text = LyricsSourceSheetText.noSongHint(locale)
                    textSize = embeddedTextSize(activity, 12f, 12f)
                    setEmbeddedTextColor(activity) { colors -> colors.secondary }
                    setSingleLine(false)
                    setPadding(0, dp(activity, 3), 0, 0)
                }, matchWidthWrapContent())
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(activity, 12)
            })
            addView(
                embeddedLyricsEditorButton(activity, LyricsSourceSheetText.refresh(locale)) { controller.refreshSnapshot() },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }, matchWidthWrapContent())
    }

private fun EmbeddedSettingsHost.statusCard(
    activity: Activity,
    controller: LyricsSourceSheetController,
    message: String,
    hint: String?,
    retryLabel: String?,
): View = embeddedCard(activity, null) {
    addView(TextView(activity).apply {
        text = message
        textSize = embeddedTextSize(activity, 15f, 15f)
        setEmbeddedTextColor(activity) { colors -> colors.onSurface }
        setSingleLine(false)
    }, matchWidthWrapContent())
    if (!hint.isNullOrBlank()) {
        addView(TextView(activity).apply {
            text = hint
            textSize = embeddedTextSize(activity, 12f, 12f)
            setEmbeddedTextColor(activity) { colors -> colors.secondary }
            setSingleLine(false)
            setPadding(0, dp(activity, 4), 0, 0)
        }, matchWidthWrapContent())
    }
    if (retryLabel != null) {
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(activity, 10), 0, 0)
            addView(textActionButton(activity, retryLabel) { controller.refreshSnapshot() })
        }, matchWidthWrapContent())
    }
}

// --- lane cards --------------------------------------------------------------

private fun EmbeddedSettingsHost.laneCard(
    activity: Activity,
    controller: LyricsSourceSheetController,
    lane: LyricLane,
    state: LyricLaneState?,
): View = embeddedCard(activity, null) {
    val locale = LyricsSourceSheetText.locale(activity)
    val busy = controller.isLaneBusy(lane)
    val selected = state?.selected
    val appleAvailable = state?.appleAvailable == true
    val sourceText = when {
        selected != null -> selected.label
        state == null -> LyricSourceText.noOptionsLabel(locale)
        lane.isSupplement -> LyricSourceText.hiddenLabel(locale)
        else -> LyricSourceText.noOptionsLabel(locale)
    }
    addView(LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(activity).apply {
            text = LyricSourceText.laneLabel(lane, locale)
            textSize = embeddedTextSize(activity, 14f, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setEmbeddedTextColor(activity) { colors -> colors.onSurface }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (state?.overridden == true) {
            addView(embeddedBadge(activity, LyricsSourceSheetText.customisedBadge(locale)))
        }
        if (!state?.options.isNullOrEmpty()) {
            addView(
                embeddedLyricsEditorButton(activity, LyricsSourceSheetText.change(locale)) {
                    controller.toggleLaneCandidates(lane)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(activity, 6) },
            )
        }
    }, matchWidthWrapContent())
    addView(LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(activity, 6), 0, 0)
        addView(TextView(activity).apply {
            text = sourceText
            textSize = embeddedTextSize(activity, 15f, 15f)
            setEmbeddedTextColor(activity) { colors ->
                if (selected == null) colors.onSurfaceVariant else colors.onSurface
            }
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (busy) {
            addView(progressIndicator(activity), LinearLayout.LayoutParams(dp(activity, 18), dp(activity, 18)))
        }
        selected?.let { option ->
            addView(embeddedBadge(activity, precisionLabel(option, locale)))
            addView(embeddedBadge(activity, LyricSourceText.providerLabel(option.providerId, locale)))
        }
    }, matchWidthWrapContent())
    selected?.detail?.takeIf { it.isNotBlank() }?.let { detail ->
        addView(TextView(activity).apply {
            text = detail
            textSize = embeddedTextSize(activity, 12f, 12f)
            setEmbeddedTextColor(activity) { colors -> colors.secondary }
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(activity, 3), 0, 0)
        }, matchWidthWrapContent())
    }
    if (lane.isSupplement && state != null) {
        addView(embeddedSpacer(activity, 6))
        addView(
            embeddedSettingRow(
                activity = activity,
                title = LyricsSourceSheetText.hideLaneTitle(locale),
                summary = LyricsSourceSheetText.hideLaneSummary(locale),
                checked = state.hidden,
                enabled = !busy,
                compactWidePadding = true,
            ) { hidden -> controller.setLaneHidden(lane, hidden) },
            matchWidthWrapContent(),
        )
    }
    if (state != null && appleAvailable && selected?.kind != LyricSourceKind.APPLE) {
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(activity, 2), 0, 0)
            addView(
                textActionButton(activity, LyricsSourceSheetText.useAppleLane(locale)) {
                    controller.clearLane(lane)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }, matchWidthWrapContent())
    }
    if (controller.expandedLane == lane) {
        addView(divider(activity), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(activity, 1),
        ).apply { topMargin = dp(activity, 8); bottomMargin = dp(activity, 4) })
        addView(laneCandidates(activity, controller, lane, state), matchWidthWrapContent())
    }
}

private fun EmbeddedSettingsHost.laneCandidates(
    activity: Activity,
    controller: LyricsSourceSheetController,
    lane: LyricLane,
    state: LyricLaneState?,
): View {
    val locale = LyricsSourceSheetText.locale(activity)
    val container = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    val options = state?.options.orEmpty()
    if (options.isEmpty()) {
        container.addView(emptyHint(activity, LyricsSourceSheetText.noCandidates(locale), LyricsSourceSheetText.noCandidatesHint(locale)))
    } else {
        options.forEachIndexed { index, option ->
            container.addView(candidateRow(activity, controller, lane, option, state?.selected?.key == option.key))
            if (index != options.lastIndex) container.addView(divider(activity))
        }
    }
    container.addView(LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(activity, 4), 0, 0)
        addView(
            embeddedLyricsEditorButton(activity, LyricsSourceSheetText.searchFromLane(locale)) {
                controller.beginSearchFor(lane)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }, matchWidthWrapContent())
    return container
}

private fun EmbeddedSettingsHost.candidateRow(
    activity: Activity,
    controller: LyricsSourceSheetController,
    lane: LyricLane,
    option: LyricSourceOption,
    current: Boolean,
): View {
    val locale = LyricsSourceSheetText.locale(activity)
    return LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true
        isFocusable = true
        setPadding(dp(activity, 4), dp(activity, 8), dp(activity, 4), dp(activity, 8))
        bindEmbeddedClickFeedback(activity, cornerRadiusDp = 10)
        setOnClickListener { fromEmbeddedSettingsView(this) { controller.applyOption(option, lane) } }
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(activity).apply {
                text = option.label.ifBlank { LyricSourceText.kindLabel(option.kind, locale) }
                textSize = embeddedTextSize(activity, 14f, 14f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setEmbeddedTextColor(activity) { colors -> if (current) colors.primary else colors.onSurface }
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (current) addView(embeddedBadge(activity, LyricsSourceSheetText.currentBadge(locale)))
        }, matchWidthWrapContent())
        if (option.detail.isNotBlank()) {
            addView(TextView(activity).apply {
                text = option.detail
                textSize = embeddedTextSize(activity, 12f, 12f)
                setEmbeddedTextColor(activity) { colors -> colors.secondary }
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(activity, 2), 0, 0)
            }, matchWidthWrapContent())
        }
        addView(optionBadges(activity, option, locale), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(activity, 4) })
    }
}

// --- shared pieces -----------------------------------------------------------

internal fun EmbeddedSettingsHost.actionButton(
    activity: Activity,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
): Button = Button(activity).apply {
    bindEmbeddedDialogActionTheme(activity)
    text = label
    isEnabled = enabled
    alpha = if (enabled) 1f else 0.55f
    setOnClickListener { if (isEnabled) fromEmbeddedSettingsView(this) { onClick() } }
}

internal fun EmbeddedSettingsHost.primaryActionButton(
    activity: Activity,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
): Button = Button(activity).apply {
    bindEmbeddedDialogActionTheme(activity, emphasized = true)
    text = label
    isEnabled = enabled
    alpha = if (enabled) 1f else 0.55f
    setOnClickListener { if (isEnabled) fromEmbeddedSettingsView(this) { onClick() } }
}

internal fun EmbeddedSettingsHost.textActionButton(
    activity: Activity,
    label: String,
    onClick: () -> Unit,
): TextView = embeddedLyricsEditorButton(activity, label, onClick)

internal fun EmbeddedSettingsHost.divider(activity: Activity): View = View(activity).apply {
    setEmbeddedBackgroundColor(activity) { colors -> colors.outline }
    alpha = 0.35f
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 1))
}

internal fun EmbeddedSettingsHost.progressIndicator(activity: Activity): ProgressBar = ProgressBar(activity).apply {
    isIndeterminate = true
    bindEmbeddedTheme(activity) { colors ->
        indeterminateTintList = ColorStateList.valueOf(colors.primary)
    }
}

internal fun EmbeddedSettingsHost.emptyHint(activity: Activity, title: String, hint: String): View =
    LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(activity, 16), dp(activity, 22), dp(activity, 16), dp(activity, 18))
        addView(ImageView(activity).apply {
            setEmbeddedImageDrawable(
                activity,
                EmbeddedGlyphDrawable(
                    EmbeddedGlyphKind.DocumentSearch,
                    embeddedPalette(activity).disabledText,
                ),
            )
            setPadding(dp(activity, 6), dp(activity, 6), dp(activity, 6), dp(activity, 6))
        }, LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)))
        addView(TextView(activity).apply {
            text = title
            textSize = embeddedTextSize(activity, 14f, 14f)
            gravity = Gravity.CENTER
            setEmbeddedTextColor(activity) { colors -> colors.onSurfaceVariant }
            setSingleLine(false)
            setPadding(0, dp(activity, 6), 0, 0)
        }, matchWidthWrapContent())
        addView(TextView(activity).apply {
            text = hint
            textSize = embeddedTextSize(activity, 12f, 12f)
            gravity = Gravity.CENTER
            setEmbeddedTextColor(activity) { colors -> colors.mutedText }
            setSingleLine(false)
            setPadding(0, dp(activity, 4), 0, 0)
        }, matchWidthWrapContent())
    }

internal fun EmbeddedSettingsHost.busyRow(activity: Activity, label: String): View =
    LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(activity, 10), 0, dp(activity, 6))
        addView(progressIndicator(activity), LinearLayout.LayoutParams(dp(activity, 18), dp(activity, 18)))
        addView(TextView(activity).apply {
            text = label
            textSize = embeddedTextSize(activity, 13f, 13f)
            setEmbeddedTextColor(activity) { colors -> colors.secondary }
            setPadding(dp(activity, 8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

internal fun EmbeddedSettingsHost.messageRow(activity: Activity, message: String, emphasized: Boolean = false): View =
    TextView(activity).apply {
        text = message
        textSize = embeddedTextSize(activity, 13f, 13f)
        setEmbeddedTextColor(activity) { colors ->
            if (emphasized) colors.primary else colors.secondary
        }
        setSingleLine(false)
        setPadding(0, dp(activity, 10), 0, dp(activity, 4))
    }

internal fun EmbeddedSettingsHost.optionBadges(
    activity: Activity,
    option: LyricSourceOption,
    locale: Locale,
): View = LinearLayout(activity).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    addView(embeddedBadge(activity, LyricSourceText.providerLabel(option.providerId, locale)).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    })
    addView(embeddedBadge(activity, precisionLabel(option, locale)).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(activity, 6) }
    })
    if (option.hasTranslation) {
        addView(embeddedBadge(activity, LyricsSourceSheetText.hasTranslation(locale)).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(activity, 6) }
        })
    }
    if (option.hasRomanization) {
        addView(embeddedBadge(activity, LyricsSourceSheetText.hasRomanization(locale)).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(activity, 6) }
        })
    }
}

internal fun precisionLabel(option: LyricSourceOption, locale: Locale): String =
    if (option.hasWords) {
        LyricsSourceSheetText.wordPrecision(locale)
    } else {
        LyricsSourceSheetText.linePrecision(locale)
    }

