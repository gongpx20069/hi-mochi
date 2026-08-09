package com.example.mochi_pet.feature.home

internal data class FocusStandbyOffset(
    val xDp: Int,
    val yDp: Int,
)

internal fun isFocusStandbyEligible(
    focusMode: Boolean,
    homePresentation: Boolean,
    enabled: Boolean,
    pipelineActive: Boolean,
    voiceListening: Boolean,
    browserActive: Boolean,
): Boolean =
    focusMode &&
        homePresentation &&
        enabled &&
        !pipelineActive &&
        !voiceListening &&
        !browserActive

internal fun focusStandbyOffset(epochMinute: Long): FocusStandbyOffset {
    val offsets = listOf(
        FocusStandbyOffset(-8, -6),
        FocusStandbyOffset(7, -4),
        FocusStandbyOffset(9, 6),
        FocusStandbyOffset(-6, 8),
    )
    return offsets[Math.floorMod(epochMinute, offsets.size.toLong()).toInt()]
}
