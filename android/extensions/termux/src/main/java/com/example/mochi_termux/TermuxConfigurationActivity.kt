package com.example.mochi_termux

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mochi_extension.MochiExtensionProtocol
import com.example.mochi_ui.ExtensionSetupScreen
import com.example.mochi_ui.MochiTheme
import com.example.mochi_ui.extensionUiContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch

internal enum class TermuxSetupStep { INSTALL, PERMISSION, CHECK, EXTERNAL, CHECKING, READY, ERROR }

internal fun termuxSetupStep(installed: Boolean, permitted: Boolean): TermuxSetupStep = when {
    !installed -> TermuxSetupStep.INSTALL
    !permitted -> TermuxSetupStep.PERMISSION
    else -> TermuxSetupStep.CHECK
}

internal class TermuxSetupViewModel(application: Application) : AndroidViewModel(application) {
    private val bridge = TermuxBridge(application)
    var step by mutableStateOf(termuxSetupStep(bridge.installed(), bridge.available()))
        private set
    var error by mutableStateOf<Int?>(null)
        private set
    private var job: Job? = null
    var awaitingTerminal = false
    var awaitingSettings = false

    fun resume() {
        if (awaitingTerminal) {
            awaitingTerminal = false
            check()
        } else if (awaitingSettings) {
            awaitingSettings = false
            permissionResult(bridge.available())
        } else if (step == TermuxSetupStep.INSTALL && bridge.installed()) {
            step = termuxSetupStep(true, bridge.available())
        }
        if (step == TermuxSetupStep.CHECK) check()
    }

    fun permissionResult(granted: Boolean) {
        if (granted) check() else {
            step = TermuxSetupStep.PERMISSION
            error = R.string.permission_denied
        }
    }

    fun showExternal() {
        error = null
        step = TermuxSetupStep.EXTERNAL
    }

    fun launchFailed() {
        awaitingTerminal = false
        awaitingSettings = false
        error = R.string.open_failed
    }

    fun check() {
        if (job?.isActive == true) return
        val prerequisite = termuxSetupStep(bridge.installed(), bridge.available())
        if (prerequisite != TermuxSetupStep.CHECK) {
            step = prerequisite
            error = null
            return
        }
        step = TermuxSetupStep.CHECKING
        error = null
        job = viewModelScope.launch {
            try {
                bridge.connect()
                step = TermuxSetupStep.READY
            } catch (_: TimeoutCancellationException) {
                error = R.string.check_timeout
                step = TermuxSetupStep.ERROR
            } catch (failure: TermuxException) {
                error = when (failure.reason) {
                    TermuxFailureReason.COMMAND_ACCESS -> R.string.permission_denied
                    TermuxFailureReason.BACKGROUND_START -> R.string.background_blocked
                    TermuxFailureReason.SERVICE_UNAVAILABLE -> R.string.service_unavailable
                    TermuxFailureReason.CHECK_FAILED -> R.string.check_failed
                }
                step = TermuxSetupStep.ERROR
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
        }
    }
}

class TermuxConfigurationActivity : ComponentActivity() {
    private val model: TermuxSetupViewModel by viewModels()
    private lateinit var localized: Context
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        model.permissionResult(it)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        localized = extensionUiContext(intent.getStringExtra(MochiExtensionProtocol.EXTRA_UI_LANGUAGE_TAG))
        setContent {
            MochiTheme {
                val step = model.step
                var showCommand by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(step) {
                    if (step == TermuxSetupStep.READY) {
                        setResult(Activity.RESULT_OK)
                        finish()
                    }
                }
                val primary = when (step) {
                    TermuxSetupStep.INSTALL -> R.string.install_termux
                    TermuxSetupStep.PERMISSION -> R.string.grant
                    TermuxSetupStep.EXTERNAL -> R.string.bootstrap
                    TermuxSetupStep.ERROR -> R.string.retry_check
                    TermuxSetupStep.CHECKING -> R.string.testing
                    TermuxSetupStep.READY -> R.string.done
                    TermuxSetupStep.CHECK -> R.string.test
                }
                ExtensionSetupScreen(
                    title = text(R.string.setup_title),
                    steps = listOf(text(R.string.step_prepare), text(R.string.step_authorize), text(R.string.step_verify)),
                    step = when (step) {
                        TermuxSetupStep.INSTALL -> 0
                        TermuxSetupStep.PERMISSION, TermuxSetupStep.EXTERNAL -> 1
                        else -> 2
                    },
                    backLabel = text(R.string.back),
                    onBack = { finish() },
                    primaryLabel = text(primary),
                    onPrimary = {
                        when (step) {
                            TermuxSetupStep.INSTALL -> launchExternal(
                                Intent(Intent.ACTION_VIEW, "https://github.com/termux/termux-app#installation".toUri()),
                            )
                            TermuxSetupStep.PERMISSION -> permission.launch(TERMUX_PERMISSION)
                            TermuxSetupStep.EXTERNAL -> {
                                getSystemService(ClipboardManager::class.java)
                                    .setPrimaryClip(ClipData.newPlainText("Termux setup", SETUP_COMMAND))
                                openTerminal()
                            }
                            else -> model.check()
                        }
                    },
                    busy = step == TermuxSetupStep.CHECKING,
                ) {
                    Text(text(when (step) {
                        TermuxSetupStep.INSTALL -> R.string.prepare_title
                        TermuxSetupStep.PERMISSION -> R.string.permission_title
                        TermuxSetupStep.EXTERNAL -> R.string.external_title
                        TermuxSetupStep.ERROR -> R.string.error_title
                        else -> R.string.verify_title
                    }), style = MaterialTheme.typography.titleLarge)
                    Text(text(when (step) {
                        TermuxSetupStep.INSTALL -> R.string.prepare_help
                        TermuxSetupStep.PERMISSION -> R.string.permission_help
                        TermuxSetupStep.EXTERNAL -> R.string.paste_help
                        TermuxSetupStep.CHECKING -> R.string.verify_progress
                        TermuxSetupStep.ERROR -> R.string.recovery_help
                        else -> R.string.verify_help
                    }), style = MaterialTheme.typography.bodyLarge)
                    model.error?.let { Text(text(it), color = MaterialTheme.colorScheme.error) }
                    if (step == TermuxSetupStep.EXTERNAL) {
                        Text(text(R.string.bootstrap_scope), style = MaterialTheme.typography.bodyMedium)
                        TextButton({ showCommand = !showCommand }) {
                            Text(text(if (showCommand) R.string.hide_command else R.string.show_command))
                        }
                        if (showCommand) SelectionContainer {
                            Text(SETUP_COMMAND, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                        }
                        TextButton({ model.check() }) { Text(text(R.string.already_configured)) }
                    }
                    if (step == TermuxSetupStep.ERROR || step == TermuxSetupStep.CHECK) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton({ model.showExternal() }) { Text(text(R.string.first_setup)) }
                            if (step == TermuxSetupStep.ERROR) {
                                TextButton({ openTerminal() }) { Text(text(R.string.open_termux_retry)) }
                            }
                        }
                    }
                    if (step == TermuxSetupStep.PERMISSION && model.error != null) {
                        TextButton({
                            model.awaitingSettings = true
                            launchExternal(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
                        }) { Text(text(R.string.open_settings)) }
                    }
                    Text(text(R.string.setup_help), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.resume()
    }

    private fun text(id: Int) = localized.getString(id)

    private fun openTerminal() {
        val launch = packageManager.getLaunchIntentForPackage("com.termux")
        if (launch == null) {
            model.launchFailed()
        } else {
            model.awaitingTerminal = true
            launchExternal(launch)
        }
    }

    private fun launchExternal(target: Intent) {
        try {
            startActivity(target)
        } catch (_: android.content.ActivityNotFoundException) {
            model.launchFailed()
        } catch (_: SecurityException) {
            model.launchFailed()
        }
    }
}
