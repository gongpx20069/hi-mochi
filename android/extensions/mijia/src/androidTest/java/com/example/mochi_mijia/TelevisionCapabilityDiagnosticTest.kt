package com.example.mochi_mijia

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TelevisionCapabilityDiagnosticTest {
    @Test
    fun inspectSelectedTelevisionCapabilities() = runBlocking {
        assumeTrue(
            InstrumentationRegistry.getArguments()
                .getString("mochiTvDiagnostic") == "true",
        )
        val graph = MijiaGraph.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val devices = graph.repository.selectedDevices()
            .filter { it.category == MijiaDeviceCategory.TELEVISION }
        check(devices.isNotEmpty()) { "No selected television." }
        devices.forEach { device ->
            val type = checkNotNull(device.specificationType)
            val spec = graph.specificationClient.get(type)
            Log.i("MochiTvDiagnostic", "model=${device.model} specification=$type")
            spec.properties.forEach {
                Log.i("MochiTvDiagnostic", "property=${it.serviceId}:${it.propertyId} name=${it.name} format=${it.format} read=${it.readable} write=${it.writable}")
            }
            spec.actions.forEach {
                Log.i("MochiTvDiagnostic", "action=${it.serviceId}:${it.actionId} name=${it.name} inputs=${it.inputPropertyIds}")
            }
            val supported = SemanticCapabilityReducer.reduce(device.category, spec)
            val powerOff = supported.actions["turn_off"]
            Log.i("MochiTvDiagnostic", "selected_power_off=${powerOff?.serviceId}:${powerOff?.actionId}")
            if (supported.stateProperties.isNotEmpty()) {
                val states = graph.repository.getProperties(
                    device, supported.stateProperties.values.map { it.reference },
                )
                Log.i("MochiTvDiagnostic", "readable_properties=${states.size} read_errors=${states.values.count { it.code != 0 }}")
            }
            if (InstrumentationRegistry.getArguments().getString("mochiTvPowerOff") == device.model) {
                check(devices.size == 1) { "Power-off diagnostic requires exactly one selected television." }
                val result = graph.toolExecutor.execute(MijiaToolExecutor.CONTROL_TELEVISION, buildJsonObject {
                    put("device_id", device.id)
                    put("operation", "power")
                    put("value", false)
                }).content
                Log.i("MochiTvDiagnostic", "power_off_accepted=${result["command_accepted"]} verification=${result["verification"]}")
            }
        }
    }
}
