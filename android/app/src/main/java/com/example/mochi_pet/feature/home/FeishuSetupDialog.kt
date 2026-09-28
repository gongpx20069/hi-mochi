package com.example.mochi_pet.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.example.mochi_pet.core.tools.FEISHU_REDIRECT_URI
import com.example.mochi_pet.core.tools.FEISHU_SCOPES

sealed interface FeishuUiAction {
    class Connect(val appId: String, val appSecret: String) : FeishuUiAction
    data object Cancel : FeishuUiAction
    data object Disconnect : FeishuUiAction
    data object OpenConsole : FeishuUiAction
    data object OpenGuide : FeishuUiAction
}

@Composable
internal fun FeishuSetupDialog(
    onAction: (FeishuUiAction) -> Unit,
    onDismiss: () -> Unit,
) {
    var appId by remember { mutableStateOf("") }
    var appSecret by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        onDismissRequest = onDismiss,
        title = { Text("Connect Feishu") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Use your own Feishu enterprise app. No server deployment is required. An administrator may need to approve and publish it.")
                Text("1. Create an enterprise self-built app in the Feishu developer console. Copy App ID and App Secret from Credentials & Basic Info.")
                TextButton(onClick = { onAction(FeishuUiAction.OpenConsole) }) {
                    Text("Open Feishu console")
                }
                Text("2. Under Permissions, enable all the following user-identity permissions, including offline_access for automatic renewal.")
                SelectionContainer { Text(FEISHU_SCOPES.joinToString("\n")) }
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(FEISHU_SCOPES.joinToString("\n")))
                }) { Text("Copy permission list") }
                Text("The hosted document service also requires task, chat, media, and whiteboard permissions to read embedded content and write documents. Mochi exposes only five document tools, not messaging or task tools.")
                Text("3. In Security Settings, add this exact redirect URL. If a refresh-token security switch is shown, enable it.")
                SelectionContainer { Text(FEISHU_REDIRECT_URI) }
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(FEISHU_REDIRECT_URI))
                }) { Text("Copy redirect URL") }
                Text("4. Publish the app and ensure your Feishu account is in its availability. Permission changes need a new published version.")
                TextButton(onClick = { onAction(FeishuUiAction.OpenGuide) }) {
                    Text("Official Feishu setup guide")
                }
                Text("5. Enter credentials below, then authorize with your Feishu account in the browser on this phone. Return to Mochi afterwards. Keep Mochi running; authorization expires after five minutes.")
                OutlinedTextField(
                    value = appId, onValueChange = { appId = it },
                    label = { Text("App ID") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = appSecret, onValueChange = { appSecret = it },
                    label = { Text("App Secret") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Text("Credentials are encrypted on this device and excluded from sharing, backups, and Agent prompts. Never paste them into chat. Disconnect removes Mochi's local credentials; revoke the app in Feishu to withdraw authorization there.")
                Text("Default tools: search, browse wiki folders, read, create, and update cloud documents. Whole-document deletion, spreadsheets, Bitable, and PPT editing are not supported. Enable Feishu Knowledge separately in Skills.")
            }
        },
        confirmButton = {
            Button(
                enabled = appId.isNotBlank() && appSecret.isNotBlank(),
                onClick = {
                    val action = FeishuUiAction.Connect(appId.trim(), appSecret.trim())
                    appSecret = ""
                    onDismiss()
                    onAction(action)
                },
            ) { Text("Authorize Feishu") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
