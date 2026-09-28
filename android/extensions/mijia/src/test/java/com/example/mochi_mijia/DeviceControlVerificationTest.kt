package com.example.mochi_mijia

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceControlVerificationTest {
    private val power = MiotProperty(2, 1, "on", "bool", true, true, null, emptySet(), "television")

    @Test fun `spec parser retains service identity before reducing duplicate actions`() {
        val specification = MiotSpecClient().parseSpecification("fixture", Json.parseToJsonElement(
            """{"services":[
              {"iid":2,"type":"urn:miot-spec-v2:service:television:0000781B:test:1",
               "actions":[{"iid":1,"type":"urn:miot-spec-v2:action:turn-off:00002809:test:1","in":[]}]},
              {"iid":7,"type":"urn:xiaomi-spec:service:remote-control:00007801:test:1",
               "actions":[{"iid":1,"type":"urn:xiaomi-spec:action:turn-off:00002801:test:1","in":[]}]}
            ]}""",
        ).jsonObject)
        val reduced = SemanticCapabilityReducer.reduce(MijiaDeviceCategory.TELEVISION, specification)
        assertEquals(MiotActionReference(2, 1), reduced.actions.getValue("turn_off").reference)
    }

    @Test fun `direct television shutdown cannot be overwritten by remote power menu`() {
        val direct = MiotAction(2, 1, "turn-off", emptyList(), "television")
        val menu = MiotAction(7, 1, "turn-off", emptyList(), "remote-control")
        val ble = MiotAction(6, 1, "turn-on", emptyList(), "ble-control")
        for (actions in listOf(listOf(direct, menu, ble), listOf(ble, menu, direct))) {
            val capabilities = SemanticCapabilityReducer.reduce(
                MijiaDeviceCategory.TELEVISION,
                MiotSpecification("test", emptyList(), actions),
            )
            assertEquals(direct, capabilities.actions["turn_off"])
            assertFalse(capabilities.actions.containsKey("turn_on"))
            assertEquals(listOf("power"), capabilities.operationNames)
            assertTrue(capabilities.stateProperties.isEmpty())
        }
    }

    @Test fun `remote-only power action is not exposed as direct shutdown`() {
        val capabilities = SemanticCapabilityReducer.reduce(
            MijiaDeviceCategory.TELEVISION,
            MiotSpecification("test", listOf(power.copy(serviceName = "remote-control")), listOf(
                MiotAction(7, 1, "turn-off", emptyList(), "remote-control"),
            )),
        )
        assertTrue(capabilities.operationNames.isEmpty())
        assertFalse(capabilities.stateProperties.containsKey("power"))
    }

    @Test fun `readback confirms only after observed value changes`() = runBlocking {
        var calls = 0
        val result = verifyDeviceControl(power, JsonPrimitive(false), read = {
            calls++
            MiotPropertyResult(0, JsonPrimitive(calls < 2))
        }, pause = {})
        assertEquals("confirmed", result.status)
        assertEquals(2, result.attempts)
        assertEquals(JsonPrimitive(false), result.observed)
    }

    @Test fun `mismatch is bounded and never a success`() = runBlocking {
        val result = verifyDeviceControl(power, JsonPrimitive(false), read = {
            MiotPropertyResult(0, JsonPrimitive(true))
        }, pause = {})
        assertEquals("not_confirmed", result.status)
        assertEquals(3, result.attempts)
    }

    @Test fun `unsupported state does not poll unrelated properties`() = runBlocking {
        val result = verifyDeviceControl(null, JsonPrimitive(false), read = { error("Unexpected read") })
        assertEquals("unavailable", result.status)
        assertEquals("no_readable_result_property", result.reason)
        assertEquals(0, result.attempts)
    }

    @Test fun `offline property and missing value never confirm power off`() = runBlocking {
        for (value in listOf(MiotPropertyResult(-704042011, null), MiotPropertyResult(0, null))) {
            val result = verifyDeviceControl(power, JsonPrimitive(false), read = { value }, pause = {})
            assertEquals("unavailable", result.status)
            assertEquals(3, result.attempts)
        }
    }

    @Test fun `readback timeout is explicit but parent cancellation propagates`() = runBlocking<Unit> {
        val result = verifyDeviceControl(power, JsonPrimitive(false), read = { awaitCancellation() }, timeoutMillis = 20)
        assertEquals("state_read_timeout", result.reason)
        assertThrows(CancellationException::class.java) {
            runBlocking {
                verifyDeviceControl(power, JsonPrimitive(false), read = { throw CancellationException("Stop") })
            }
        }
    }

    @Test fun `provider failure after accepted write stays an explicit verification failure`() = runBlocking {
        val result = verifyDeviceControl(power, JsonPrimitive(false), read = {
            throw MijiaProviderException("Unavailable")
        })
        assertEquals("unavailable", result.status)
        assertEquals("state_read_failed", result.reason)
    }

    @Test fun `property responses are matched by identity not position and preserve errors`() {
        val other = MiotPropertyReference(4, 1)
        val results = Json.parseToJsonElement(
            """[{"did":"tv","siid":4,"piid":1,"code":-1},{"did":"tv","siid":2,"piid":1,"code":0,"value":false}]""",
        ).jsonArray
        val parsed = parsePropertyResults("tv", listOf(power.reference, other), results)
        assertEquals(JsonPrimitive(false), parsed.getValue(power.reference).value)
        assertEquals(-1, parsed.getValue(other).code)
        assertThrows(MijiaProviderException::class.java) {
            parsePropertyResults("different-device", listOf(power.reference), results)
        }
        assertThrows(MijiaProviderException::class.java) {
            parsePropertyResults("tv", listOf(power.reference), JsonArray(results + results))
        }
    }
}
