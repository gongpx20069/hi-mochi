package com.example.mochi_webdav

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.mochi_extension.MochiExtensionProtocol
import com.example.mochi_ui.ExtensionSetupScreen
import com.example.mochi_ui.MochiTheme
import com.example.mochi_ui.extensionUiContext
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal class WebdavSetupViewModel(application: Application) : AndroidViewModel(application) {
    val repository = (application as WebdavApplication).repository
    var endpoint by mutableStateOf("https://dav.jianguoyun.com/dav/")
    var root by mutableStateOf("/")
    var username by mutableStateOf("")
    var replacement by mutableStateOf("")
    var allowLan by mutableStateOf(false)
    var busy by mutableStateOf(false)
        private set
    var loaded by mutableStateOf(false)
        private set
    var error by mutableStateOf<WebdavError?>(null)
    var fileSaved by mutableStateOf(false)
        private set
    var exportingId: String? = null
    private var initial: WebdavConfig? = null

    init {
        viewModelScope.launch {
            val state = repository.state.first { !it.loading }
            initial = state.config
            state.config?.let {
                endpoint = it.endpoint; root = it.root; username = it.username; allowLan = it.allowLan
            }
            error = state.error
            loaded = true
        }
    }

    fun connect(done: () -> Unit) = operate {
        val previous = initial
        val retained = previous?.takeIf { it.endpoint == endpoint.trim() && it.username == username.trim() }?.password
        val password = replacement.ifEmpty { retained ?: throw WebdavException(WebdavError.INVALID_ARGUMENT) }
        val draft = WebdavConfig(endpoint.trim(), root.trim(), username.trim(), password, allowLan, previous?.revision.orEmpty())
        val config = if (draft == previous) draft else draft.copy(revision = UUID.randomUUID().toString())
        requireWebdav(!config.allowLan || repository.lanPermissionGranted(), WebdavError.PERMISSION_DENIED)
        withContext(Dispatchers.IO) {
            requireWebdav(WebdavClient(config).stat("").directory, WebdavError.INVALID_ARGUMENT)
            repository.save(config, previous?.revision)
        }
        replacement = ""
        done()
    }

    fun importFile(uri: Uri) = operate {
        val resolver = getApplication<Application>().contentResolver
        withContext(Dispatchers.IO) {
            val config = repository.config()
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "upload.bin"
            repository.createTransfer(name, "import", config.revision) { file, maximum ->
                val input = resolver.openInputStream(uri) ?: throw WebdavException(WebdavError.STORAGE)
                input.use { source ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1_024)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = source.read(buffer)
                            if (count < 0) break
                            total += count
                            requireWebdav(total <= maximum, WebdavError.LIMIT_EXCEEDED)
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
        }
    }

    fun exportFile(id: String, uri: Uri) = operate {
        withContext(Dispatchers.IO) {
            repository.withTransfer(id) { _, source ->
                val output = getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                    ?: throw WebdavException(WebdavError.STORAGE)
                output.use { destination ->
                    source.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1_024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            destination.write(buffer, 0, count)
                        }
                    }
                }
            }
        }
        fileSaved = true
    }

    fun forget(id: String) = operate { withContext(Dispatchers.IO) { repository.forgetTransfer(id) } }

    private fun operate(action: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null; fileSaved = false
        viewModelScope.launch {
            try {
                withTimeout(60_000) { action() }
            } catch (_: TimeoutCancellationException) {
                error = WebdavError.TIMEOUT
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: WebdavException) {
                error = failure.code
            } catch (_: IOException) {
                error = WebdavError.STORAGE
            } catch (_: SecurityException) {
                error = WebdavError.PERMISSION_DENIED
            } finally {
                busy = false
            }
        }
    }
}

class WebdavConfigurationActivity : ComponentActivity() {
    private val model: WebdavSetupViewModel by viewModels()
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        model.error = if (it) null else WebdavError.PERMISSION_DENIED
    }
    private val importer = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::importFile)
    }
    private val exporter = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val id = model.exportingId
        model.exportingId = null
        if (uri != null) {
            if (id != null) model.exportFile(id, uri) else model.error = WebdavError.NOT_FOUND
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val localized = extensionUiContext(intent.getStringExtra(MochiExtensionProtocol.EXTRA_UI_LANGUAGE_TAG))
        fun text(id: Int) = localized.getString(id)
        setContent {
            val stored by model.repository.state.collectAsStateWithLifecycle()
            MochiTheme {
                ExtensionSetupScreen(
                    title = text(R.string.app_name),
                    steps = listOf(text(R.string.connection), text(R.string.scope), text(R.string.ready)),
                    step = if (stored.config == null) 0 else 2,
                    backLabel = text(R.string.back),
                    onBack = { finish() },
                    primaryLabel = text(R.string.save_connect),
                    onPrimary = { model.connect { setResult(Activity.RESULT_OK); finish() } },
                    busy = model.busy || !model.loaded,
                ) {
                    Text(text(R.string.setup_help), style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { model.endpoint = "https://dav.jianguoyun.com/dav/" }, enabled = !model.busy) {
                        Text(text(R.string.nutstore_preset))
                    }
                    OutlinedTextField(model.endpoint, { model.endpoint = it }, label = { Text(text(R.string.endpoint)) },
                        modifier = Modifier.fillMaxWidth(), enabled = !model.busy, singleLine = true)
                    OutlinedTextField(model.root, { model.root = it }, label = { Text(text(R.string.root)) },
                        modifier = Modifier.fillMaxWidth(), enabled = !model.busy, singleLine = true)
                    Text(text(R.string.root_help))
                    OutlinedTextField(model.username, { model.username = it }, label = { Text(text(R.string.username)) },
                        modifier = Modifier.fillMaxWidth(), enabled = !model.busy, singleLine = true)
                    OutlinedTextField(model.replacement, { model.replacement = it }, label = { Text(text(R.string.password)) },
                        supportingText = { Text(text(R.string.password_help)) }, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(), enabled = !model.busy, singleLine = true)
                    Row {
                        Checkbox(model.allowLan, { allow ->
                            model.allowLan = allow
                            if (allow && Build.VERSION.SDK_INT >= 37 && !model.repository.lanPermissionGranted()) {
                                permission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                            }
                        }, enabled = !model.busy)
                        Text(text(R.string.allow_lan), Modifier.weight(1f))
                    }
                    Text(text(R.string.lan_help))
                    if (model.allowLan) TextButton(onClick = {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
                    }) { Text(text(R.string.app_permissions)) }
                    model.error?.let {
                        Text(text(errorString(it)), color = MaterialTheme.colorScheme.error)
                        Text(text(R.string.failure_help))
                    }
                    if (model.fileSaved) Text(text(R.string.file_saved))
                    if (stored.config != null) {
                        Text(text(R.string.transfers), style = MaterialTheme.typography.titleLarge)
                        Text(text(R.string.transfer_help))
                        TextButton(onClick = { importer.launch(arrayOf("*/*")) }, enabled = !model.busy) {
                            Text(text(R.string.import_file))
                        }
                        stored.transfers.forEach { file ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${file.name} · ${file.bytes} B")
                                if (file.direction == "download") TextButton(onClick = {
                                    model.exportingId = file.id
                                    exporter.launch(file.name)
                                }, enabled = !model.busy) { Text(text(R.string.export_file)) }
                                TextButton(onClick = { model.forget(file.id) }, enabled = !model.busy) {
                                    Text(text(R.string.forget_file))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun errorString(error: WebdavError): Int = when (error) {
    WebdavError.INVALID_ARGUMENT -> R.string.error_arguments
    WebdavError.PERMISSION_DENIED -> R.string.error_permission
    WebdavError.AUTHORIZATION -> R.string.error_auth
    WebdavError.NOT_FOUND -> R.string.error_missing
    WebdavError.CONFLICT -> R.string.error_conflict
    WebdavError.LIMIT_EXCEEDED -> R.string.error_limit
    WebdavError.UNSUPPORTED -> R.string.error_unsupported
    WebdavError.NETWORK -> R.string.error_network
    WebdavError.TIMEOUT -> R.string.error_timeout
    WebdavError.CANCELLED -> R.string.error_cancelled
    WebdavError.STORAGE -> R.string.error_storage
    WebdavError.SERVER_RESPONSE -> R.string.error_response
}
