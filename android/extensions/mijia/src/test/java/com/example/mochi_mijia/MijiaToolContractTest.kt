package com.example.mochi_mijia

import com.example.mochi_extension.ExtensionApiValidator
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MijiaToolContractTest {
    @Test
    fun allToolDefinitionsSatisfyExtensionContract() {
        assertNull(
            ExtensionApiValidator.toolDefinitionsError(
                MijiaToolExecutor.DEFINITIONS,
            ),
        )
        assertEquals(
            MijiaToolExecutor.DEFINITIONS.size,
            MijiaToolExecutor.DEFINITIONS.map { it.name }.distinct().size,
        )
        assertTrue(MijiaToolExecutor.DEFINITIONS.all { it.defaultEnabled })
    }

    @Test
    fun reducerOnlyExposesDeclaredSafeCapabilities() {
        val capabilities = SemanticCapabilityReducer.reduce(
            MijiaDeviceCategory.LIGHT,
            MiotSpecification(
                type = "urn:miot-spec-v2:device:light:0000A001:test:1",
                properties = listOf(
                    property("on", readable = true, writable = true),
                    property("brightness", readable = true, writable = false),
                    property("target-temperature", readable = true, writable = true),
                    property("serial-number", readable = true, writable = false),
                ),
                actions = listOf(
                    MiotAction(2, 1, "turn-on", emptyList()),
                ),
            ),
        )

        assertEquals(
            setOf("power", "brightness", "target_temperature"),
            capabilities.stateProperties.keys,
        )
        assertEquals(setOf("power"), capabilities.writableProperties.keys)
        assertTrue(capabilities.actions.isEmpty())
    }

    @Test
    fun scaleNeverExposesBodyMeasurements() {
        val capabilities = SemanticCapabilityReducer.reduce(
            MijiaDeviceCategory.SCALE,
            MiotSpecification(
                type = "urn:miot-spec-v2:device:scale:0000A035:test:1",
                properties = listOf(
                    property("weight", readable = true, writable = false),
                    property("body-fat-percentage", readable = true, writable = false),
                    property("heart-rate", readable = true, writable = false),
                    property("battery-level", readable = true, writable = false),
                    property("fault", readable = true, writable = false),
                ),
                actions = emptyList(),
            ),
        )

        assertEquals(
            setOf("battery_level", "fault"),
            capabilities.stateProperties.keys,
        )
        assertTrue(capabilities.writableProperties.isEmpty())
        assertTrue(capabilities.actions.isEmpty())
    }

    @Test
    fun propertyValidationEnforcesRangeStepAndEnums() {
        val stepped = property(
            name = "brightness",
            readable = true,
            writable = true,
            range = listOf(0.0, 100.0, 5.0),
        )
        assertEquals(JsonPrimitive(25), stepped.validateValue(JsonPrimitive(25)))
        assertThrows(IllegalArgumentException::class.java) {
            stepped.validateValue(JsonPrimitive(26))
        }

        val enumerated = property(
            name = "mode",
            readable = true,
            writable = true,
            format = "string",
            allowedValues = setOf("auto", "silent"),
        )
        assertEquals(
            JsonPrimitive("auto"),
            enumerated.validateValue(JsonPrimitive("auto")),
        )
        assertThrows(IllegalArgumentException::class.java) {
            enumerated.validateValue(JsonPrimitive("turbo"))
        }
        assertFalse(enumerated.allowedValues.contains("turbo"))
    }

    private fun property(
        name: String,
        readable: Boolean,
        writable: Boolean,
        format: String = "int32",
        range: List<Double>? = null,
        allowedValues: Set<String> = emptySet(),
    ) = MiotProperty(
        serviceId = 2,
        propertyId = name.hashCode().and(Int.MAX_VALUE),
        name = name,
        format = format,
        readable = readable,
        writable = writable,
        range = range,
        allowedValues = allowedValues,
    )
}
