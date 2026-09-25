package com.example.mochi_mijia

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mochi_extension.MochiExtensionProtocol
import com.example.mochi_ui.ExtensionSetupScreen
import com.example.mochi_ui.MochiTheme
import com.example.mochi_ui.extensionUiContext
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal enum class MijiaSetupStep { PREPARE, AUTHORIZE, SELECT }

internal fun visibleMijiaDevices(devices: List<MijiaDevice>, query: String): List<MijiaDevice> =
    devices.filter { device ->
        device.category in SUPPORTED_MIJIA_CATEGORIES &&
            listOf(device.name, device.homeName, device.roomName.orEmpty()).any {
                it.contains(query.trim(), ignoreCase = true)
            }
    }.sortedWith(compareBy(MijiaDevice::homeName, { it.roomName.orEmpty() }, MijiaDevice::name))

internal class MijiaSetupViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = MijiaGraph.get(application)
    var step by mutableStateOf(MijiaSetupStep.PREPARE)
        private set
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf(R.string.prepare_help)
        private set
    var error by mutableStateOf<Int?>(null)
        private set
    var qr by mutableStateOf<Bitmap?>(null)
        private set
    var remaining by mutableStateOf(0)
        private set
    var devices by mutableStateOf<List<MijiaDevice>>(emptyList())
        private set
    var selected by mutableStateOf<Set<String>>(emptySet())
        private set
    private var saved = emptySet<String>()
    var finished by mutableStateOf(false)
        private set
    var devicesLoaded by mutableStateOf(false)
        private set
    val dirty get() = selected != saved
    private var operation: Job? = null

    init {
        runOperation(R.string.load_devices_failed) {
            if (withContext(Dispatchers.IO) { graph.sessionStore.load() } != null) loadDevices()
        }
    }

    private fun runOperation(failureText: Int, block: suspend () -> Unit) {
        if (operation?.isActive == true) return
        busy = true
        error = null
        operation = viewModelScope.launch {
            try {
                block()
            } catch (_: TimeoutCancellationException) {
                error = R.string.qr_expired
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: MijiaAuthorizationException) {
                step = MijiaSetupStep.PREPARE
                error = R.string.authorization_failed
            } catch (_: MijiaProviderException) {
                error = failureText
            } catch (_: IOException) {
                error = R.string.network_failed
            } catch (_: IllegalArgumentException) {
                error = failureText
            } finally {
                busy = false
                qr = null
            }
        }
    }

    fun connect() = runOperation(R.string.connection_failed) {
        devicesLoaded = false
        devices = emptyList()
        selected = emptySet()
        saved = emptySet()
        step = MijiaSetupStep.AUTHORIZE
        status = R.string.requesting_qr
        val challenge = graph.passportQrClient.begin()
        qr = withContext(Dispatchers.Default) { qrBitmap(challenge.loginUrl) }
        val duration = challenge.timeoutSeconds.coerceIn(1, 600)
        val deadline = SystemClock.elapsedRealtime() + duration * 1_000
        status = R.string.scan_instructions
        coroutineScope {
            val countdown = launch {
                while (true) {
                    remaining = ((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0) / 1_000).toInt()
                    delay(1_000)
                }
            }
            try {
                withTimeout(duration * 1_000) { graph.passportQrClient.complete(challenge) }
            } finally {
                countdown.cancel()
                qr = null
            }
        }
        loadDevices()
    }

    private suspend fun loadDevices() {
        step = MijiaSetupStep.SELECT
        status = R.string.loading_devices
        devices = visibleMijiaDevices(withContext(Dispatchers.IO) {
            graph.repository.homesAndDevices().second
        }, "")
        saved = withContext(Dispatchers.IO) { graph.sessionStore.load() }?.selectedDeviceIds.orEmpty()
            .intersect(devices.map { it.id }.toSet())
        selected = saved
        devicesLoaded = true
        status = if (devices.isEmpty()) R.string.empty_devices else R.string.choose_devices
    }

    fun retryDevices() = runOperation(R.string.load_devices_failed) { loadDevices() }

    fun select(ids: Set<String>, checked: Boolean) {
        if (busy) return
        val supported = ids.intersect(devices.map { it.id }.toSet())
        selected = if (checked) selected + supported else selected - supported
    }

    fun save() = runOperation(R.string.save_devices_failed) {
        withContext(Dispatchers.IO) { graph.repository.saveSelectedDevices(selected) }
        saved = selected
        finished = true
    }

    fun cancel() {
        operation?.cancel()
        qr = null
    }
}

class MijiaConfigurationActivity : ComponentActivity() {
    private val model: MijiaSetupViewModel by viewModels()
    private lateinit var localized: Context

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(Color.TRANSPARENT),
        )
        localized = extensionUiContext(intent.getStringExtra(MochiExtensionProtocol.EXTRA_UI_LANGUAGE_TAG))
        setContent {
            MochiTheme {
                var query by rememberSaveable { mutableStateOf("") }
                var confirmLeave by rememberSaveable { mutableStateOf(false) }
                fun leave() {
                    if (model.dirty) confirmLeave = true else {
                        model.cancel()
                        finish()
                    }
                }
                BackHandler { leave() }
                LaunchedEffect(model.finished) {
                    if (model.finished) {
                        setResult(Activity.RESULT_OK)
                        finish()
                    }
                }
                ExtensionSetupScreen(
                    title = text(R.string.setup_title),
                    steps = listOf(text(R.string.step_prepare), text(R.string.step_authorize), text(R.string.step_select)),
                    step = model.step.ordinal,
                    backLabel = text(R.string.back),
                    onBack = { leave() },
                    primaryLabel = text(when {
                        model.step == MijiaSetupStep.SELECT && (!model.devicesLoaded || model.devices.isEmpty()) -> R.string.retry_devices
                        model.step == MijiaSetupStep.SELECT -> R.string.save_selected_devices
                        model.qr != null -> R.string.waiting_scan
                        else -> R.string.generate_qr
                    }),
                    onPrimary = {
                        when {
                            model.step == MijiaSetupStep.SELECT && (!model.devicesLoaded || model.devices.isEmpty()) -> model.retryDevices()
                            model.step == MijiaSetupStep.SELECT -> model.save()
                            else -> model.connect()
                        }
                    },
                    busy = model.busy,
                    footer = if (model.step == MijiaSetupStep.SELECT) {
                        localized.resources.getQuantityString(R.plurals.selected_count, model.selected.size, model.selected.size)
                    } else null,
                ) {
                    Text(text(when (model.step) {
                        MijiaSetupStep.PREPARE -> R.string.prepare_title
                        MijiaSetupStep.AUTHORIZE -> R.string.authorize_title
                        MijiaSetupStep.SELECT -> R.string.selection_title
                    }), style = MaterialTheme.typography.titleLarge)
                    Text(text(model.status), style = MaterialTheme.typography.bodyLarge)
                    model.error?.let { Text(text(it), color = MaterialTheme.colorScheme.error) }
                    model.qr?.let { image ->
                        Image(
                            image.asImageBitmap(), text(R.string.qr_content_description),
                            Modifier.align(Alignment.CenterHorizontally).widthIn(max = 280.dp)
                                .fillMaxWidth().aspectRatio(1f).background(androidx.compose.ui.graphics.Color.White).padding(12.dp),
                        )
                        Text(localized.resources.getQuantityString(R.plurals.qr_expires, model.remaining, model.remaining))
                    }
                    if (model.step == MijiaSetupStep.SELECT && model.devices.isNotEmpty()) {
                        OutlinedTextField(
                            value = query, onValueChange = { query = it },
                            label = { Text(text(R.string.search_devices)) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                        )
                        val visible = visibleMijiaDevices(model.devices, query)
                        if (visible.isEmpty()) Text(text(R.string.no_search_results))
                        visible.groupBy { it.homeId }.values.forEach { homeDevices ->
                            val ids = homeDevices.map { it.id }.toSet()
                            val allSelected = model.selected.containsAll(ids)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(homeDevices.first().homeName, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                TextButton({ model.select(ids, !allSelected) }, enabled = !model.busy) {
                                    Text(text(if (allSelected) R.string.clear_visible else R.string.select_visible))
                                }
                            }
                            homeDevices.forEach { device ->
                                val checked = device.id in model.selected
                                Surface(
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp).toggleable(
                                        checked, enabled = !model.busy, role = Role.Checkbox,
                                        onValueChange = { model.select(setOf(device.id), it) },
                                    ),
                                    shape = RoundedCornerShape(20.dp),
                                    color = if (checked) MaterialTheme.colorScheme.secondaryContainer
                                        else MaterialTheme.colorScheme.surface,
                                ) {
                                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = checked, onCheckedChange = null)
                                        Column(Modifier.weight(1f).padding(start = 8.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(device.name, style = MaterialTheme.typography.titleMedium)
                                            Text(device.roomName ?: text(R.string.no_room),
                                                style = MaterialTheme.typography.bodyMedium)
                                            Text(text(categoryLabel(device.category)),
                                                color = MaterialTheme.colorScheme.secondary,
                                                style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Text(text(R.string.extension_description), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (confirmLeave) AlertDialog(
                    onDismissRequest = { confirmLeave = false },
                    title = { Text(text(R.string.discard_title)) },
                    text = { Text(text(R.string.discard_help)) },
                    confirmButton = { TextButton({
                        model.cancel()
                        finish()
                    }) { Text(text(R.string.discard)) } },
                    dismissButton = { TextButton({ confirmLeave = false }) { Text(text(R.string.keep_editing)) } },
                )
            }
        }
    }

    private fun text(id: Int) = localized.getString(id)
}

private fun categoryLabel(category: MijiaDeviceCategory): Int = when (category) {
    MijiaDeviceCategory.LIGHT -> R.string.category_light
    MijiaDeviceCategory.SWITCH -> R.string.category_switch
    MijiaDeviceCategory.PLUG -> R.string.category_plug
    MijiaDeviceCategory.FAN -> R.string.category_fan
    MijiaDeviceCategory.AIR_CONDITIONER -> R.string.category_air_conditioner
    MijiaDeviceCategory.AIR_PURIFIER -> R.string.category_air_purifier
    MijiaDeviceCategory.HUMIDIFIER -> R.string.category_humidifier
    MijiaDeviceCategory.CURTAIN -> R.string.category_curtain
    MijiaDeviceCategory.SENSOR -> R.string.category_sensor
    MijiaDeviceCategory.TELEVISION -> R.string.category_television
    MijiaDeviceCategory.CAMERA -> R.string.category_camera
    MijiaDeviceCategory.SCALE -> R.string.category_scale
    MijiaDeviceCategory.UNKNOWN -> R.string.category_unknown
}

private fun qrBitmap(value: String): Bitmap {
    val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 720, 720)
    return createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            this[x, y] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
    }
}
