package com.example.mochi_pet.feature.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun ConversationEntryLayout(
    quickEntry: Boolean,
    dictating: Boolean,
    onShowHistory: () -> Unit,
    modifier: Modifier = Modifier,
    history: @Composable () -> Unit,
    composer: @Composable () -> Unit,
) {
    val reveal by rememberUpdatedState(onShowHistory)
    val animatedProgress by animateFloatAsState(
        if (quickEntry) 0f else 1f,
        animationSpec = if (quickEntry) snap() else tween(280),
        label = "conversation-history",
    )
    val progress = if (quickEntry) 0f else animatedProgress
    Layout(
        modifier = modifier.clipToBounds().testTag("conversation-layout")
            .pointerInput(quickEntry, dictating) {
                if (!quickEntry || dictating) return@pointerInput
                var distance = 0f
                detectVerticalDragGestures(
                    onDragStart = { distance = 0f },
                    onVerticalDrag = { change, amount -> change.consume(); distance += amount },
                    onDragCancel = { distance = 0f },
                    onDragEnd = { if (distance >= 64.dp.toPx()) reveal() },
                )
            },
        content = {
            Box(Modifier.graphicsLayer {
                alpha = progress
                translationY = (1f - progress) * 12.dp.toPx()
            }) {
                if (!quickEntry) history()
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { composer() }
            Box(Modifier.graphicsLayer { alpha = 1f - progress }) {
                if (quickEntry || progress < 1f) {
                    MochiEntryMark("Mochi Chat", Modifier.testTag("conversation-entry-logo"))
                }
            }
        },
    ) { children, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val controls = children[1].measure(constraints.copy(minWidth = width, minHeight = 0))
        val logo = children[2].measure(Constraints(maxWidth = width, maxHeight = height - controls.height))
        val logoSpace = if (logo.height > 0) (logo.height + 20.dp.roundToPx()).coerceAtMost(height - controls.height) else 0
        val centered = ((height - controls.height - logoSpace) / 2 + logoSpace).coerceAtLeast(0)
        val bottom = height - controls.height
        val top = (centered + (bottom - centered) * progress).roundToInt()
        val historyContent = children[0].measure(Constraints.fixed(width, (top - 8.dp.roundToPx()).coerceAtLeast(0)))
        layout(width, height) {
            historyContent.placeRelative(0, 0)
            // Keep one composer node while moving it, preserving text focus and the held gesture.
            controls.placeRelative(0, top)
            logo.placeRelative((width - logo.width) / 2, centered - logoSpace)
        }
    }
}
