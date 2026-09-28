package com.example.mochi_mijia

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

data class DeviceControlVerification(
    val status: String,
    val reason: String,
    val attempts: Int = 0,
    val observed: JsonElement? = null,
    val errorCode: Int? = null,
)

internal suspend fun verifyDeviceControl(
    property: MiotProperty?,
    expected: JsonElement?,
    read: suspend (MiotPropertyReference) -> MiotPropertyResult,
    pause: suspend () -> Unit = { delay(500) },
    timeoutMillis: Long = 6_000,
): DeviceControlVerification {
    if (property == null || expected == null) {
        return DeviceControlVerification("unavailable", "no_readable_result_property")
    }
    var attempts = 0
    return withTimeoutOrNull(timeoutMillis) {
        var last = DeviceControlVerification("unavailable", "no_observation")
        repeat(3) { index ->
            if (index > 0) pause()
            attempts++
            val result = try {
                read(property.reference)
            } catch (error: MijiaAuthorizationException) {
                return@withTimeoutOrNull DeviceControlVerification("unavailable", "authorization_failed", attempts)
            } catch (error: MijiaProviderException) {
                return@withTimeoutOrNull DeviceControlVerification("unavailable", "state_read_failed", attempts)
            }
            last = when {
                result.code != 0 -> DeviceControlVerification(
                    "unavailable", "property_read_failed", attempts, errorCode = result.code,
                )
                result.value == null -> DeviceControlVerification("unavailable", "missing_value", attempts)
                samePropertyValue(property, expected, result.value) -> DeviceControlVerification(
                    "confirmed", "state_matches", attempts, result.value,
                )
                else -> DeviceControlVerification("not_confirmed", "state_differs", attempts, result.value)
            }
            if (last.status == "confirmed") return@withTimeoutOrNull last
        }
        last
    } ?: DeviceControlVerification("unavailable", "state_read_timeout", attempts)
}

private fun samePropertyValue(property: MiotProperty, expected: JsonElement, actual: JsonElement): Boolean {
    if (property.format in setOf("float", "int8", "int16", "int32", "int64", "uint8", "uint16", "uint32", "uint64")) {
        val left = (expected as? JsonPrimitive)?.doubleOrNull
        val right = (actual as? JsonPrimitive)?.doubleOrNull
        return left != null && right != null && left.isFinite() && left == right
    }
    return expected == actual
}
