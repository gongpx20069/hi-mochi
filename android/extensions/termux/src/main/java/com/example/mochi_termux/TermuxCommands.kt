package com.example.mochi_termux

import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

internal const val TERMUX_PERMISSION = "com.termux.permission.RUN_COMMAND"
// These are upstream Termux's fixed runtime paths, not the extension's private data directory.
@android.annotation.SuppressLint("SdCardPath")
internal const val TERMUX_HOME = "/data/data/com.termux/files/home"
@android.annotation.SuppressLint("SdCardPath")
internal const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
internal const val HELPER = "$TERMUX_HOME/.local/state/mochi-termux/runner-v1"

internal val SETUP_COMMAND = """
    mkdir -p ~/.termux &&
    touch ~/.termux/termux.properties &&
    sed -i '/^[[:space:]]*allow-external-apps[[:space:]]*=/d' ~/.termux/termux.properties &&
    printf '\nallow-external-apps=true\n' >> ~/.termux/termux.properties &&
    termux-reload-settings &&
    printf '\nMOCHI_SETUP_SAVED\n'
""".trimIndent().replace("\n", " ")

internal fun termuxConnectionArguments(script: String): Array<String> {
    val install = """
        set -eu
        umask 077
        mkdir -p "${'$'}HOME/.local/state/mochi-termux"
        printf '%s' "${'$'}1" | base64 -d > "${'$'}HOME/.local/state/mochi-termux/runner-v1.tmp"
        chmod 700 "${'$'}HOME/.local/state/mochi-termux/runner-v1.tmp"
        mv "${'$'}HOME/.local/state/mochi-termux/runner-v1.tmp" "${'$'}HOME/.local/state/mochi-termux/runner-v1"
        bash "${'$'}HOME/.local/state/mochi-termux/runner-v1" probe
    """.trimIndent()
    // Normalize the bundled shell source, never user-supplied command text.
    return arrayOf("-c", install, "mochi-setup", encoded(script.replace("\r\n", "\n")))
}

internal data class ShellCommand(
    val command: String,
    val workdir: String,
    val timeoutSeconds: Int,
) {
    companion object {
        fun parse(arguments: JsonObject): ShellCommand {
            require(arguments.keys.all { it in setOf("command", "workdir", "timeout_seconds") }) {
                "Unknown execution argument."
            }
            val command = arguments.string("command")
            val workdir = arguments.optionalString("workdir") ?: TERMUX_HOME
            val timeout = arguments["timeout_seconds"]?.let {
                require(it is JsonPrimitive && !it.isString) { "Timeout must be an integer." }
                requireNotNull(it.intOrNull) { "Timeout must be an integer." }
            } ?: 120
            require(command.isNotBlank() && command.toByteArray().size <= 16_384 && '\u0000' !in command) {
                "Command must contain 1 to 16384 UTF-8 bytes without NUL."
            }
            require(workdir.startsWith("/") && workdir.length <= 1024 && workdir.none { it.isISOControl() }) {
                "Working directory must be an absolute path without control characters."
            }
            require(timeout in 1..1800) { "Timeout must be between 1 and 1800 seconds." }
            return ShellCommand(command, workdir, timeout)
        }
    }
}

internal fun JsonObject.string(name: String): String =
    requireNotNull(optionalString(name)) { "Missing $name." }

internal fun JsonObject.optionalString(name: String): String? =
    this[name]?.let {
        require(it is JsonPrimitive && it.isString) { "$name must be a string." }
        it.content
    }

internal fun validTaskId(value: String): Boolean =
    Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(value)

internal fun encoded(value: String): String =
    Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

internal data class TaskOutput(
    val state: String,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val truncated: Boolean,
)

internal fun parseTaskOutput(output: String): TaskOutput {
    val lines = output.trimEnd('\n').split('\n')
    require(lines.size == 6 && lines[0] == "MOCHI_TASK_V1") { "Invalid task response." }
    require(lines[1] in setOf("running", "succeeded", "failed", "stopping", "stopped", "timed_out", "unknown")) {
        "Invalid task state."
    }
    require(lines[2] == "-" || lines[2].toIntOrNull() in 0..255) { "Invalid exit code." }
    require(lines[5] in setOf("0", "1")) { "Invalid output limit flag." }
    fun decode(value: String): String {
        require(value.length <= 24_000) { "Task output exceeds limit." }
        return Base64.getDecoder().decode(value).toString(Charsets.UTF_8)
    }
    return TaskOutput(lines[1], lines[2].toIntOrNull(), decode(lines[3]), decode(lines[4]), lines[5] == "1")
}
