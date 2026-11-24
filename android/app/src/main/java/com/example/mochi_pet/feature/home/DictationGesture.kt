package com.example.mochi_pet.feature.home

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

@Composable
internal fun Modifier.holdToDictate(
    enabled: Boolean,
    onStart: () -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
): Modifier {
    val start by rememberUpdatedState(onStart)
    val finish by rememberUpdatedState(onFinish)
    val cancel by rememberUpdatedState(onCancel)
    return pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                var pressed = true
                while (pressed) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    pressed = event.changes.size == 1 && change != null && change.pressed &&
                        (change.position - down.position).getDistance() <= viewConfiguration.touchSlop &&
                        change.position.x in 0f..size.width.toFloat() &&
                        change.position.y in 0f..size.height.toFloat()
                }
                false
            } ?: true
            if (held) {
                var released = false
                try {
                    start()
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (event.changes.size != 1 || change.position.x !in 0f..size.width.toFloat() ||
                            change.position.y !in 0f..size.height.toFloat()
                        ) break
                        if (!change.pressed) {
                            released = true
                            break
                        }
                    }
                } finally {
                    if (released) finish() else cancel()
                }
            }
        }
    }
}
