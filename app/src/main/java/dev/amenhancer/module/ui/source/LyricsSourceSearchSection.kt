package dev.amenhancer.module.ui.source

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.amenhancer.module.lyrics.source.LyricLane
import dev.amenhancer.module.lyrics.source.LyricSourceOption
import dev.amenhancer.module.lyrics.source.LyricSourceText
import dev.amenhancer.module.ui.EmbeddedG3Drawable
import dev.amenhancer.module.ui.EmbeddedSettingsColors
import dev.amenhancer.module.ui.EmbeddedSettingsHost
import dev.amenhancer.module.ui.bindEmbeddedClickFeedback
import dev.amenhancer.module.ui.bindEmbeddedInputTheme
import dev.amenhancer.module.ui.bindEmbeddedTheme
import dev.amenhancer.module.ui.dp
import dev.amenhancer.module.ui.embeddedBadge
import dev.amenhancer.module.ui.embeddedCard
import dev.amenhancer.module.ui.embeddedPalette
import dev.amenhancer.module.ui.embeddedSearchFieldHeight
import dev.amenhancer.module.ui.embeddedSectionLabel
import dev.amenhancer.module.ui.embeddedTextSize
import dev.amenhancer.module.ui.fromEmbeddedSettingsView
import dev.amenhancer.module.ui.isEmbeddedPhone
import dev.amenhancer.module.ui.matchWidthWrapContent
import dev.amenhancer.module.ui.setEmbeddedHintColor
import dev.amenhancer.module.ui.setEmbeddedTextColor
import java.util.Locale

/**
 * The "搜索其他来源" card.
 *
 * It is built once and only its dynamic regions (lane chips, status line, result
 * list) are rebuilt, because the two query fields must keep their text, cursor
 * and IME state across every controller render.
 *
 * Search is debounced by [LyricsSourceSheetController]; this section only sends
 * text edits and taps, and renders whatever state the controller reports.
 */
internal class LyricsSourceSearchSection(
    private val host: EmbeddedSettingsHost,
    private val activity: Activity,
    private val controller: LyricsSourceSheetController,
) {

    private val locale: Locale get() = LyricsSourceSheetText.locale(activity)
    private val chips = LinkedHashMap<LyricLane, TextView>()
    private val chipSurfaces = LinkedHashMap<LyricLane, EmbeddedG3Drawable>()
    private val artistInput: EditText
    private val titleInput: EditText
    private val statusContainer: LinearLayout
    private val resultsContainer: LinearLayout
    private var syncingInputs = false
    private var lastRenderedSearchState: LyricsSourceSearchState? = null
    private var lastRenderedPreviewState: LyricsSourcePreviewState? = null

    val root: LinearLayout

    init {
        artistInput = searchInput(
            hintText = LyricsSourceSheetText.artistHint(locale),
            initial = controller.artistQuery,
            onEdited = controller::onArtistEdited,
        )
        titleInput = searchInput(
            hintText = LyricsSourceSheetText.songTitleHint(locale),
            initial = controller.titleQuery,
            onEdited = controller::onTitleEdited,
        )
        statusContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        resultsContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val chipsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val inputsRow = LinearLayout(activity).apply {
            orientation = if (host.isEmbeddedPhone(activity)) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            setPadding(0, host.dp(activity, 10), 0, 0)
        }
        val phone = host.isEmbeddedPhone(activity)
        LyricLane.ALL.forEach { lane ->
            val chip = createChip(lane)
            chips[lane] = chip
            chipsRow.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                host.dp(activity, 34),
            ).apply { marginEnd = host.dp(activity, 8) })
        }
        addInput(inputsRow, host.inputContainer(activity, artistInput), phone, first = true)
        addInput(inputsRow, host.inputContainer(activity, titleInput), phone, first = false)
        root = host.embeddedCard(activity, null) {
            addView(
                host.embeddedSectionLabel(activity, LyricsSourceSheetText.searchTitle(locale)),
                host.matchWidthWrapContent(),
            )
            addView(TextView(activity).apply {
                text = LyricsSourceSheetText.searchSummary(locale)
                textSize = host.embeddedTextSize(activity, 12f, 12f)
                setEmbeddedTextColor(activity) { colors -> colors.secondary }
                setSingleLine(false)
                maxLines = 2
                setPadding(0, 0, 0, host.dp(activity, 10))
            }, host.matchWidthWrapContent())
            addView(chipsRow, host.matchWidthWrapContent())
            addView(inputsRow, host.matchWidthWrapContent())
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, host.dp(activity, 10), 0, 0)
                addView(
                    host.primaryActionButton(activity, LyricsSourceSheetText.search(locale)) {
                        controller.searchNow()
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        host.dp(activity, 42),
                    ),
                )
                addView(
                    host.textActionButton(activity, LyricsSourceSheetText.resetQuery(locale)) {
                        controller.resetQueryToSong()
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        host.dp(activity, 42),
                    ).apply { marginStart = host.dp(activity, 4) },
                )
            }, host.matchWidthWrapContent())
            addView(statusContainer, host.matchWidthWrapContent())
            addView(resultsContainer, host.matchWidthWrapContent())
        }
    }

    /** Updates chips, fields, status and result rows from the controller state. */
    fun render() {
        renderChips()
        syncInputs()
        renderStatus()
        renderResults()
    }

    /** Called by "搜索在线来源…" from a lane card. */
    fun focusArtist() {
        artistInput.requestFocus()
        val manager = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        runCatching { manager?.showSoftInput(artistInput, InputMethodManager.SHOW_IMPLICIT) }
    }

    private fun createChip(lane: LyricLane): TextView {
        val surface = EmbeddedG3Drawable().apply { cornerRadius = host.dp(activity, 18).toFloat() }
        chipSurfaces[lane] = surface
        return TextView(activity).apply {
            text = LyricSourceText.laneLabel(lane, locale)
            textSize = host.embeddedTextSize(activity, 13f, 13f)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            minimumHeight = host.dp(activity, 34)
            setPadding(host.dp(activity, 14), 0, host.dp(activity, 14), 0)
            background = surface
            bindEmbeddedClickFeedback(activity, cornerRadiusDp = 18)
            setOnClickListener { fromEmbeddedSettingsView(this) { controller.selectSearchLane(lane) } }
            bindEmbeddedTheme(activity) { colors -> styleChip(lane, this, colors) }
        }
    }

    private fun styleChip(lane: LyricLane, chip: TextView, colors: EmbeddedSettingsColors) {
        val selected = controller.searchLane == lane
        chipSurfaces[lane]?.let { surface ->
            surface.setColor(if (selected) colors.selected else android.graphics.Color.TRANSPARENT)
            surface.setStroke(host.dp(activity, 1), if (selected) colors.selected else colors.outline)
        }
        chip.setTextColor(if (selected) colors.primary else colors.secondary)
    }

    private fun renderChips() {
        val colors = embeddedPalette(activity)
        chips.forEach { (lane, chip) -> styleChip(lane, chip, colors) }
    }

    private fun syncInputs() {
        syncingInputs = true
        try {
            if (!artistInput.hasFocus() && artistInput.text.toString() != controller.artistQuery) {
                artistInput.setText(controller.artistQuery)
                artistInput.setSelection(artistInput.text.length)
            }
            if (!titleInput.hasFocus() && titleInput.text.toString() != controller.titleQuery) {
                titleInput.setText(controller.titleQuery)
                titleInput.setSelection(titleInput.text.length)
            }
        } finally {
            syncingInputs = false
        }
    }

    private fun renderStatus() {
        statusContainer.removeAllViews()
        when (val state = controller.searchState) {
            LyricsSourceSearchState.Idle ->
                if (LyricsSourceSearchPolicy.isSearchable(controller.titleQuery, controller.artistQuery)) {
                    statusContainer.addView(host.messageRow(activity, LyricsSourceSheetText.searchIdle(locale)))
                }
            LyricsSourceSearchState.Loading ->
                statusContainer.addView(host.busyRow(activity, LyricsSourceSheetText.searching(locale)))
            is LyricsSourceSearchState.Failed -> {
                statusContainer.addView(host.messageRow(activity, state.message, emphasized = true))
                statusContainer.addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(
                        host.textActionButton(activity, LyricsSourceSheetText.retry(locale)) {
                            controller.searchNow()
                        },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                }, host.matchWidthWrapContent())
            }
            is LyricsSourceSearchState.Loaded -> if (state.options.isEmpty()) {
                statusContainer.addView(
                    host.emptyHint(
                        activity,
                        LyricsSourceSheetText.noResults(locale),
                        LyricsSourceSheetText.noResultsHint(locale),
                    ),
                )
            }
        }
    }

    private fun renderResults() {
        // Only a new search result or a preview change can alter this list; an
        // unrelated render (a lane switch, a failed write) leaves it untouched so
        // the list keeps its scroll position.
        if (lastRenderedSearchState === controller.searchState &&
            lastRenderedPreviewState === controller.previewState &&
            resultsContainer.childCount > 0
        ) {
            return
        }
        lastRenderedSearchState = controller.searchState
        lastRenderedPreviewState = controller.previewState
        resultsContainer.removeAllViews()
        val options = (controller.searchState as? LyricsSourceSearchState.Loaded)?.options.orEmpty()
        if (options.isEmpty()) return
        resultsContainer.addView(TextView(activity).apply {
            text = LyricsSourceSheetText.resultCount(locale, options.size)
            textSize = host.embeddedTextSize(activity, 12f, 12f)
            setEmbeddedTextColor(activity) { colors -> colors.secondary }
            setPadding(0, host.dp(activity, 4), 0, host.dp(activity, 4))
        }, host.matchWidthWrapContent())
        options.forEachIndexed { index, option ->
            resultsContainer.addView(resultRow(option))
            if (index != options.lastIndex) resultsContainer.addView(host.divider(activity))
        }
    }

    private fun resultRow(option: LyricSourceOption): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(host.dp(activity, 4), host.dp(activity, 8), host.dp(activity, 4), host.dp(activity, 8))
            bindEmbeddedClickFeedback(activity, cornerRadiusDp = 10)
            setOnClickListener { fromEmbeddedSettingsView(this) { controller.togglePreview(option) } }
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(activity).apply {
                    text = option.label.ifBlank { LyricSourceText.kindLabel(option.kind, locale) }
                    textSize = host.embeddedTextSize(activity, 15f, 15f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setEmbeddedTextColor(activity) { colors -> colors.onSurface }
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (option.matchScore > 0) {
                    addView(host.embeddedBadge(activity, LyricsSourceSheetText.matchScore(locale, option.matchScore)))
                }
            }, host.matchWidthWrapContent())
            if (option.detail.isNotBlank()) {
                addView(TextView(activity).apply {
                    text = option.detail
                    textSize = host.embeddedTextSize(activity, 12f, 12f)
                    setEmbeddedTextColor(activity) { colors -> colors.secondary }
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, host.dp(activity, 2), 0, 0)
                }, host.matchWidthWrapContent())
            }
            addView(host.optionBadges(activity, option, locale), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = host.dp(activity, 4) })
            if (controller.previewState.optionKeyOrNull == option.key) {
                addView(previewBlock(option), host.matchWidthWrapContent())
            }
        }

    private fun previewBlock(option: LyricSourceOption): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, host.dp(activity, 8), 0, host.dp(activity, 2))
            when (val state = controller.previewState) {
                is LyricsSourcePreviewState.Loading ->
                    addView(host.busyRow(activity, LyricsSourceSheetText.previewLoading(locale)))
                is LyricsSourcePreviewState.Failed ->
                    addView(host.messageRow(activity, state.message, emphasized = true))
                is LyricsSourcePreviewState.Loaded -> {
                    val claim = precisionClaim(state.precisionKnown, state.hasWords)
                    val title = LyricsSourceSheetText.previewLines(locale, state.lines.size)
                    addView(TextView(activity).apply {
                        text = when (claim) {
                            null -> title
                            else -> title + " · " + LyricsSourceSheetText.previewPrecision(
                                locale,
                                claim == LyricsSourcePrecisionClaim.WORDS,
                            )
                        }
                        textSize = host.embeddedTextSize(activity, 12f, 12f)
                        setEmbeddedTextColor(activity) { colors -> colors.secondary }
                        setPadding(0, 0, 0, host.dp(activity, 4))
                    }, host.matchWidthWrapContent())
                    state.lines.forEach { line ->
                        addView(TextView(activity).apply {
                            text = line.original
                            textSize = host.embeddedTextSize(activity, 14f, 14f)
                            setEmbeddedTextColor(activity) { colors -> colors.onSurface }
                            setSingleLine(false)
                            setPadding(0, host.dp(activity, 2), 0, 0)
                        }, host.matchWidthWrapContent())
                        line.translation?.takeIf { it.isNotBlank() }?.let { translation ->
                            addView(TextView(activity).apply {
                                text = translation
                                textSize = host.embeddedTextSize(activity, 12.5f, 12.5f)
                                setEmbeddedTextColor(activity) { colors -> colors.secondary }
                                setSingleLine(false)
                                setPadding(0, host.dp(activity, 1), 0, 0)
                            }, host.matchWidthWrapContent())
                        }
                        line.romanization?.takeIf { it.isNotBlank() }?.let { romanization ->
                            addView(TextView(activity).apply {
                                text = romanization
                                textSize = host.embeddedTextSize(activity, 12f, 12f)
                                setEmbeddedTextColor(activity) { colors -> colors.mutedText }
                                setSingleLine(false)
                                setPadding(0, host.dp(activity, 1), 0, 0)
                            }, host.matchWidthWrapContent())
                        }
                    }
                }
                LyricsSourcePreviewState.None -> Unit
            }
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, host.dp(activity, 8), 0, 0)
                addView(
                    host.primaryActionButton(activity, LyricsSourceSheetText.use(locale), enabled = !controller.isBusy) {
                        controller.applyOption(option, controller.searchLane)
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        host.dp(activity, 42),
                    ),
                )
                addView(
                    host.textActionButton(activity, LyricsSourceSheetText.collapse(locale)) {
                        controller.togglePreview(option)
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        host.dp(activity, 42),
                    ).apply { marginStart = host.dp(activity, 4) },
                )
            }, host.matchWidthWrapContent())
        }

    private fun searchInput(hintText: String, initial: String, onEdited: (String) -> Unit): EditText =
        EditText(activity).apply {
            bindEmbeddedInputTheme(activity)
            background = null
            hint = hintText
            textSize = host.embeddedTextSize(activity, 14f, 14f)
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            showSoftInputOnFocus = true
            includeFontPadding = false
            setPadding(host.dp(activity, 12), 0, host.dp(activity, 12), 0)
            setText(initial)
            setSelection(text.length)
            setEmbeddedTextColor(activity) { colors -> colors.onSurface }
            setEmbeddedHintColor(activity) { colors -> colors.muted }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (syncingInputs) return
                    onEdited(s?.toString().orEmpty())
                }
            })
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    controller.searchNow()
                    true
                } else {
                    false
                }
            }
        }

    private fun addInput(
        row: LinearLayout,
        container: FrameLayout,
        phone: Boolean,
        first: Boolean,
    ) {
        val height = host.embeddedSearchFieldHeight(activity)
        val params = if (phone) {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply {
                if (!first) topMargin = host.dp(activity, 8)
            }
        } else {
            LinearLayout.LayoutParams(0, height, 1f).apply {
                if (!first) marginStart = host.dp(activity, 8)
            }
        }
        row.addView(container, params)
    }
}

/** A rounded field shell matching the custom-lyrics search field. */
internal fun EmbeddedSettingsHost.inputContainer(activity: Activity, input: EditText): FrameLayout =
    FrameLayout(activity).apply {
        bindEmbeddedTheme(activity) { colors ->
            background = EmbeddedG3Drawable().apply {
                setColor(colors.raised)
                setStroke(dp(activity, 1), colors.outline)
                cornerRadius = dp(activity, 12).toFloat()
            }
        }
        addView(input, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
    }
