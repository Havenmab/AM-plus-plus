/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

internal data class PendingDataBindingRefresh(
    val mediaId: String,
    val alias: AppliedMetadataAlias,
    val bindGeneration: Long,
)

internal data class PendingVisibleDataBindingResolution(
    val mediaId: String,
    val bindGeneration: Long,
    val originalResolutionMode: InAppOriginalResolutionMode,
)

internal enum class DataBindingRefreshStrategy {
    VARIABLES_ONLY,

    /**
     * The row already shows the requested title/subtitle, so a full
     * `invalidateAll` (the expensive whole-row rebind) is skipped and only the
     * pending bindings are executed. Added by HLE v8.2.1 (`1a665f69`) after it
     * measured 61% redundant rebinds; existing equality checks keep their
     * meaning because neither of them treats this as `FULL_INVALIDATE`.
     */
    ALREADY_RENDERED,
    FULL_INVALIDATE,
}

internal data class DataBindingAliasValues(
    val title: String?,
    val subtitle: String?,
)

internal data class DataBindingVariableApplyResult(
    val titleApplied: Boolean,
    val subtitleApplied: Boolean,
)

internal enum class VisibleTextField {
    TITLE,
    ARTIST,
    ALBUM,
}

internal data class InAppActionSheetBinding(
    val mediaId: String,
    val field: VisibleTextField,
)
