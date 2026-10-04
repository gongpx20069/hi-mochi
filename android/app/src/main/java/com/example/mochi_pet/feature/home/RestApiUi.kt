package com.example.mochi_pet.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.example.mochi_pet.core.rest.RestApiChange
import com.example.mochi_pet.core.rest.RestApiException
import com.example.mochi_pet.core.rest.RestAuth
import com.example.mochi_pet.core.rest.RestConnection
import com.example.mochi_pet.core.rest.RestConnectionInput
import com.example.mochi_pet.core.rest.RestConnectionSummary
import com.example.mochi_pet.core.rest.RestMethod
import com.example.mochi_pet.core.rest.RestOutputField
import com.example.mochi_pet.core.rest.RestParameter
import com.example.mochi_pet.core.rest.RestParameterLocation
import com.example.mochi_pet.core.rest.RestTestResult
import com.example.mochi_pet.core.rest.RestToolDefinition
import com.example.mochi_pet.core.rest.RestValueType
import com.example.mochi_pet.core.rest.prepareRestRequest
import com.example.mochi_pet.core.rest.validateRestConnection
import com.example.mochi_pet.core.rest.validateRestTool
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class RestApiActions(
    val change: suspend (RestApiChange) -> Unit,
    val test: suspend (RestConnectionInput, RestToolDefinition, JsonObject) -> RestTestResult,
)

@Composable
internal fun AddToolDialog(onMcp: () -> Unit, onRest: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add tool") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onMcp, modifier = Modifier.fillMaxWidth()) { Text("Connect MCP service") }
                Text("Discover tools from an existing MCP service.")
                OutlinedButton(onClick = onRest, modifier = Modifier.fillMaxWidth()) { Text("Connect REST API") }
                Text("Use your own HTTPS API and Token. No code required.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun RestApiConnectionCard(summary: RestConnectionSummary, actions: RestApiActions) {
    val connection = summary.connection
    var expanded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var toolEditor by remember { mutableStateOf<RestToolDefinition?>(null) }
    var deletion by remember { mutableStateOf<RestApiChange.Delete?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun change(action: RestApiChange) {
        scope.launch {
            busy = true
            error = restUiOperation { actions.change(action) }
            busy = false
        }
    }
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row {
                Column(Modifier.weight(1f)) {
                    Text(connection.name, style = MaterialTheme.typography.titleMedium)
                    Text("REST API", style = MaterialTheme.typography.labelMedium)
                    Text(connection.baseUrl, style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = connection.enabled,
                    onCheckedChange = { change(RestApiChange.SetEnabled(connection.id, null, it)) },
                    enabled = !busy,
                )
            }
            Text("Foreground conversations only. Not included in sharing or scheduled Agents.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide tools" else "Show tools") }
                TextButton(onClick = { editing = true }, enabled = !busy) { Text("Edit") }
                TextButton(onClick = { deletion = RestApiChange.Delete(connection.id) }, enabled = !busy) { Text("Delete") }
            }
            if (expanded) {
                connection.tools.forEach { tool ->
                    Column {
                        Row {
                            Column(Modifier.weight(1f)) {
                                Text(tool.name)
                                Text("${tool.method} ${tool.path}", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(
                                checked = tool.enabled,
                                onCheckedChange = { change(RestApiChange.SetEnabled(connection.id, tool.id, it)) },
                                enabled = !busy,
                            )
                        }
                        Row {
                            TextButton(onClick = { toolEditor = tool }, enabled = !busy) { Text("Edit / test") }
                            TextButton(onClick = { deletion = RestApiChange.Delete(connection.id, tool.id) }, enabled = !busy) { Text("Delete") }
                        }
                    }
                }
                OutlinedButton(onClick = { toolEditor = RestToolDefinition() }, enabled = !busy) { Text("Add API tool") }
            }
            if (connection.tools.isEmpty() && !expanded) {
                OutlinedButton(onClick = { toolEditor = RestToolDefinition() }, enabled = !busy) { Text("Add API tool") }
            }
        }
    }
    if (editing) RestConnectionDialog(summary, actions) { editing = false }
    toolEditor?.let { tool ->
        RestToolDialog(connection, tool, actions) { toolEditor = null }
    }
    deletion?.let { action ->
        AlertDialog(
            onDismissRequest = { deletion = null },
            title = { Text("Delete REST configuration?") },
            text = { Text("This removes local configuration, not remote data. Already submitted requests may still finish.") },
            confirmButton = {
                TextButton(onClick = { deletion = null; change(action) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deletion = null }) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun RestConnectionDialog(
    existing: RestConnectionSummary?,
    actions: RestApiActions,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(existing?.connection ?: RestConnection()) }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    RestDialog("REST API connection", busy, onDismiss, {
        Button(enabled = !busy, onClick = {
            scope.launch {
                busy = true
                error = restUiOperation {
                    validateRestConnection(draft)
                    actions.change(RestApiChange.SaveConnection(RestConnectionInput(draft, token)))
                    token = ""
                    onDismiss()
                }
                busy = false
            }
        }) { Text("Save connection") }
    }) {
        Text("Save the service and Token once, then add its API tools. Connections start disabled.")
        RestTextField("Connection name", draft.name, !busy) { draft = draft.copy(name = it) }
        RestTextField("Service address (https://api.example.com)", draft.baseUrl, !busy) { draft = draft.copy(baseUrl = it) }
        RestChoice("Authentication", draft.auth, RestAuth.entries, !busy, ::restAuthLabel) { draft = draft.copy(auth = it) }
        if (draft.auth == RestAuth.HEADER) {
            RestTextField("API key header", draft.headerName, !busy) { draft = draft.copy(headerName = it) }
        }
        if (draft.auth != RestAuth.NONE) {
            OutlinedTextField(
                value = token, onValueChange = { token = it }, modifier = Modifier.fillMaxWidth(),
                enabled = !busy, singleLine = true, label = { Text("Token") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Text(if (existing?.hasSecret == true) "Leave blank to keep the saved Token. Changing address or authentication requires a new Token."
                else "Token stays encrypted on this device and is not sent to the model.")
        }
        Text("Public HTTPS on port 443 only. Put the path and query parameters in each tool; local networks are not supported.")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
internal fun RestToolDialog(
    connection: RestConnection,
    original: RestToolDefinition,
    actions: RestApiActions,
    onDismiss: () -> Unit,
) {
    val editorConnection = remember { connection }
    var draft by remember { mutableStateOf(original) }
    var sampleValues by remember { mutableStateOf(emptyMap<String, String>()) }
    var bodyText by remember { mutableStateOf(original.body.toString()) }
    var successValueText by remember { mutableStateOf(original.successValue?.toString().orEmpty()) }
    var test by remember { mutableStateOf<RestTestResult?>(null) }
    var testedDraft by remember { mutableStateOf<RestToolDefinition?>(null) }
    var testSamples by remember { mutableStateOf<Map<String, String>?>(null) }
    var preview by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmTest by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun prepared(): RestToolDefinition {
        val body = try { Json.parseToJsonElement(bodyText) as? JsonObject } catch (_: SerializationException) { null }
        require(body != null) { "JSON body must be an object." }
        val success = if (draft.successPointer != null) Json.parseToJsonElement(successValueText) else null
        return draft.copy(body = body, successValue = success)
    }
    fun arguments(tool: RestToolDefinition): JsonObject = JsonObject(tool.parameters.mapNotNull { parameter ->
        val value = sampleValues[parameter.name].orEmpty()
        if (value.isEmpty() && (!parameter.required || parameter.defaultValue != null)) null else parameter.name to if (parameter.type == RestValueType.STRING) {
            JsonPrimitive(value)
        } else Json.parseToJsonElement(value)
    }.toMap())
    fun runTest() {
        scope.launch {
            busy = true
            test = null
            error = restUiOperation {
                val tool = prepared()
                val result = actions.test(RestConnectionInput(editorConnection), tool.copy(outputs = emptyList()), arguments(tool))
                test = result
                testedDraft = tool.copy(outputs = emptyList(), enabled = false)
                testSamples = sampleValues
                bodyText = tool.body.toString()
                successValueText = tool.successValue?.toString().orEmpty()
                draft = draft.copy(outputs = draft.outputs.filter { output ->
                    result.fields.any { it.pointer == output.pointer && it.type == output.type }
                })
                if (result.fields.isEmpty()) throw IllegalArgumentException("No selectable scalar fields were returned.")
            }
            busy = false
        }
    }
    val currentTest = test?.takeIf {
        testedDraft == draft.copy(body = testedDraft?.body ?: draft.body, successValue = testedDraft?.successValue, outputs = emptyList(), enabled = false) &&
            bodyText == testedDraft?.body?.toString() &&
            successValueText == testedDraft?.successValue?.toString().orEmpty() && testSamples == sampleValues
    }
    RestDialog("REST API tool", busy, onDismiss, {
        Button(enabled = !busy, onClick = {
            scope.launch {
                busy = true
                error = restUiOperation {
                    val tool = prepared()
                    validateRestTool(tool)
                    require(currentTest != null) { "Test this configuration before saving the tool." }
                    currentTest?.let { result ->
                        require(tool.outputs.all { output -> result.fields.any { it.pointer == output.pointer && it.type == output.type } }) {
                            "Selected fields do not match the latest test."
                        }
                    }
                    actions.change(RestApiChange.SaveTool(editorConnection.id, editorConnection.revision, tool))
                    onDismiss()
                }
                busy = false
            }
        }) { Text("Save tool") }
    }) {
        Text(editorConnection.name)
        RestTextField("Tool name", draft.name, !busy) { draft = draft.copy(name = it) }
        RestTextField("When should Mochi use this tool?", draft.description, !busy, singleLine = false) { draft = draft.copy(description = it) }
        RestChoice("Method", draft.method, RestMethod.entries, !busy, { it.name }) {
            draft = draft.copy(method = it, requiresConfirmation = true)
        }
        RestTextField("API path (for example /v1/temperature)", draft.path, !busy) { draft = draft.copy(path = it) }
        Row {
            Checkbox(
                checked = draft.requiresConfirmation,
                onCheckedChange = { draft = draft.copy(requiresConfirmation = it) },
                enabled = !busy && draft.method == RestMethod.GET,
            )
            Text("Ask the user before each action. Turn off only for a trusted read-only GET.")
        }
        Text("Parameters", style = MaterialTheme.typography.titleSmall)
        draft.parameters.forEachIndexed { index, parameter ->
            fun update(value: RestParameter) {
                draft = draft.copy(parameters = draft.parameters.mapIndexed { i, old -> if (i == index) value else old })
            }
            OutlinedCard {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    RestTextField("Parameter name", parameter.name, !busy) { update(parameter.copy(name = it)) }
                    RestTextField("Parameter meaning", parameter.description, !busy) { update(parameter.copy(description = it)) }
                    RestChoice("Location", parameter.location, RestParameterLocation.entries, !busy, ::restLocationLabel) { update(parameter.copy(location = it, required = if (it == RestParameterLocation.PATH) true else parameter.required)) }
                    RestChoice("Type", parameter.type, RestValueType.entries, !busy, ::restTypeLabel) { update(parameter.copy(type = it, defaultValue = null)) }
                    Row {
                        Checkbox(parameter.required, { update(parameter.copy(required = it)) }, enabled = !busy && parameter.location != RestParameterLocation.PATH)
                        Text("Required")
                    }
                    var defaultText by remember(parameter.name, parameter.type) {
                        mutableStateOf(parameter.defaultValue?.let { if (it is JsonPrimitive && it.isString) it.content else it.toString() }.orEmpty())
                    }
                    RestTextField("Default value (optional)", defaultText, !busy) { value ->
                        defaultText = value
                        val parsed = if (value.isEmpty()) null else if (parameter.type == RestValueType.STRING) JsonPrimitive(value)
                        else try { Json.parseToJsonElement(value) } catch (_: SerializationException) { JsonPrimitive(value) }
                        update(parameter.copy(defaultValue = parsed))
                    }
                    RestTextField("Test value", sampleValues[parameter.name].orEmpty(), !busy) { sampleValues = sampleValues + (parameter.name to it) }
                    TextButton(enabled = !busy, onClick = { draft = draft.copy(parameters = draft.parameters.filterIndexed { i, _ -> i != index }) }) { Text("Remove parameter") }
                }
            }
        }
        OutlinedButton(enabled = !busy && draft.parameters.size < 20, onClick = {
            draft = draft.copy(parameters = draft.parameters + RestParameter("parameter${draft.parameters.size + 1}"))
        }) { Text("Add parameter") }
        TextButton(onClick = { advanced = !advanced }) { Text("Advanced request settings") }
        if (advanced) {
            if (draft.method != RestMethod.GET) RestTextField("Fixed JSON body (no credentials)", bodyText, !busy, false) { bodyText = it }
            RestTextField("Success field pointer (optional, e.g. /code)", draft.successPointer.orEmpty(), !busy) {
                draft = draft.copy(successPointer = it.takeIf(String::isNotBlank))
            }
            if (draft.successPointer != null) RestTextField("Expected success value (JSON, e.g. 0)", successValueText, !busy) { successValueText = it }
        }
        Text("Without a success field, only HTTP status and selected output types are checked.")
        Row {
            OutlinedButton(enabled = !busy, onClick = {
                scope.launch {
                    error = restUiOperation {
                        val request = prepareRestRequest(editorConnection, prepared(), arguments(prepared()))
                        preview = "${request.method} ${request.url}\n${request.body ?: ""}\n${restAuthLabel(editorConnection.auth)}: [hidden]"
                    }
                }
            }) { Text("Preview") }
            TextButton(enabled = !busy, onClick = { confirmTest = true }) { Text("Test API") }
        }
        preview?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (busy) Text("Working...")
        if (test != null && currentTest == null) Text("Configuration changed. Test again.")
        currentTest?.let { result ->
            Text("Select the fields Mochi may read. The preview is limited to 128 scalar fields; array indexes select only that item.")
            result.fields.forEachIndexed { index, field ->
                val selected = draft.outputs.firstOrNull { it.pointer == field.pointer }
                Column {
                    Row(
                        Modifier.toggleable(
                            value = selected != null,
                            role = Role.Checkbox,
                            enabled = !busy && (selected != null || draft.outputs.size < 32),
                            onValueChange = { checked ->
                                draft = draft.copy(outputs = if (checked) {
                                    draft.outputs + RestOutputField(field.pointer, "field${index + 1}", type = field.type)
                                } else draft.outputs.filterNot { it.pointer == field.pointer })
                            },
                        ),
                    ) {
                        Checkbox(checked = selected != null, onCheckedChange = null)
                        Text("${field.pointer.ifEmpty { "/" }} = ${field.value.toString().take(160)}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (selected != null) {
                        RestTextField("Output name", selected.name, !busy) { value ->
                            draft = draft.copy(outputs = draft.outputs.map { if (it.pointer == field.pointer) it.copy(name = value) else it })
                        }
                        RestTextField("Meaning and units", selected.description, !busy) { value ->
                            draft = draft.copy(outputs = draft.outputs.map { if (it.pointer == field.pointer) it.copy(description = value) else it })
                        }
                    }
                }
            }
        }
        if (currentTest == null && draft.outputs.isNotEmpty()) {
            Text("Saved output fields")
            draft.outputs.forEach { Text("${it.name}: ${it.pointer}") }
        }
        Row {
            Checkbox(draft.enabled, { draft = draft.copy(enabled = it) }, enabled = !busy && (draft.enabled || currentTest != null && draft.outputs.isNotEmpty()))
            Text("Enable this tool after saving")
        }
        Text("The connection switch must also be on. Outputs go to your configured model and may enter conversation history.")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (confirmTest) AlertDialog(
        onDismissRequest = { confirmTest = false },
        title = { Text("Send a real API request?") },
        text = { Text("Testing sends the configured request, may consume quota, and may change remote data. It is not a simulation.") },
        confirmButton = { TextButton(onClick = { confirmTest = false; runTest() }) { Text("Send request") } },
        dismissButton = { TextButton(onClick = { confirmTest = false }) { Text("Cancel") } },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
    )
}

@Composable
private fun RestDialog(
    title: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    confirm: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) { content() }
        },
        confirmButton = confirm,
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (busy) "Cancel" else "Close") } },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
    )
}

@Composable
private fun RestTextField(label: String, value: String, enabled: Boolean, singleLine: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, enabled = enabled, label = { Text(label) },
        modifier = Modifier.fillMaxWidth(), singleLine = singleLine)
}

@Composable
private fun <T> RestChoice(label: String, selected: T, values: List<T>, enabled: Boolean, name: (T) -> String, onChange: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        OutlinedButton(onClick = { open = true }, enabled = enabled) { Text(name(selected)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            values.forEach { value ->
                DropdownMenuItem(text = { Text(name(value)) }, onClick = { open = false; onChange(value) })
            }
        }
    }
}

private fun restAuthLabel(auth: RestAuth): String = when (auth) {
    RestAuth.NONE -> "No authentication"
    RestAuth.BEARER -> "Bearer Token"
    RestAuth.HEADER -> "API key header"
    RestAuth.AUTHORIZATION -> "Raw Authorization"
}

private fun restLocationLabel(location: RestParameterLocation) = when (location) {
    RestParameterLocation.QUERY -> "Query parameter"
    RestParameterLocation.PATH -> "Path parameter"
    RestParameterLocation.BODY -> "JSON body field"
}

private fun restTypeLabel(type: RestValueType) = when (type) {
    RestValueType.STRING -> "Text"
    RestValueType.NUMBER -> "Number"
    RestValueType.BOOLEAN -> "Boolean (true / false)"
}

private suspend fun restUiOperation(operation: suspend () -> Unit): String? = try {
    operation()
    null
} catch (error: CancellationException) {
    throw error
} catch (error: RestApiException) {
    error.message ?: "REST API request failed."
} catch (error: SerializationException) {
    "Enter valid JSON values."
} catch (error: IllegalArgumentException) {
    error.message ?: "Invalid REST configuration."
} catch (error: IllegalStateException) {
    error.message ?: "Could not update REST configuration."
} catch (error: IOException) {
    "Could not read or save REST configuration."
}
