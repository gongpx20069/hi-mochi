package com.example.mochi_extension

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

object ExtensionApiLimits {
    const val MAX_CAPABILITIES = 32
    const val MAX_TOOLS = 32
    const val MAX_TOOL_DESCRIPTION_BYTES = 1_024
    const val MAX_SCHEMA_BYTES = 64 * 1_024
    const val MAX_ARGUMENT_BYTES = 64 * 1_024
    const val MAX_RESULT_BYTES = 256 * 1_024
    const val MAX_ATTACHMENT_METADATA_BYTES = 16 * 1_024
    const val MAX_ATTACHMENTS = 1
    const val MAX_ATTACHMENT_BYTES = 5L * 1_024 * 1_024
    const val MAX_IMAGE_DIMENSION = 8_192
    const val MAX_TIMEOUT_MILLIS = 120_000L
}

object ExtensionApiValidator {
    private val json = Json { ignoreUnknownKeys = false }
    private val identifier = Regex("[a-z][a-z0-9_]{2,63}")
    private val requestId = Regex("[A-Za-z0-9._:-]{8,96}")
    private val packageName =
        Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")
    private val imageMimeTypes = setOf("image/jpeg", "image/png")

    fun metadataError(metadata: ExtensionMetadata): String? {
        if (metadata.protocolVersion <= 0) return "Protocol version must be positive."
        if (metadata.minimumHostVersionCode <= 0) {
            return "Minimum host version code must be positive."
        }
        if (!identifier.matches(metadata.extensionId)) {
            return "Extension ID is invalid."
        }
        if (metadata.displayName.isBlank() || metadata.displayName.utf8Size() > 128) {
            return "Display name is invalid."
        }
        if (!packageName.matches(metadata.packageName)) return "Package name is invalid."
        if (!packageName.matches(metadata.serviceClassName)) {
            return "Service class name is invalid."
        }
        if (!packageName.matches(metadata.configurationActivityClassName)) {
            return "Configuration activity class name is invalid."
        }
        if (metadata.versionName.isBlank() || metadata.versionName.utf8Size() > 64) {
            return "Version name is invalid."
        }
        if (metadata.versionCode <= 0) return "Version code must be positive."
        if (metadata.capabilities.size > ExtensionApiLimits.MAX_CAPABILITIES) {
            return "Too many extension capabilities."
        }
        if (
            metadata.capabilities.any { !identifier.matches(it) } ||
            metadata.capabilities.distinct().size != metadata.capabilities.size
        ) {
            return "Extension capabilities are invalid."
        }
        return null
    }

    fun connectionStateError(state: ExtensionConnectionState): String? {
        if (state.status !in ExtensionConnectionStatus.ALL) {
            return "Connection status is invalid."
        }
        if ((state.detail?.utf8Size() ?: 0) > 1_024) {
            return "Connection detail is too large."
        }
        if ((state.accountLabel?.utf8Size() ?: 0) > 256) {
            return "Account label is too large."
        }
        if (state.selectedHomeCount < 0 || state.selectedDeviceCount < 0) {
            return "Selected counts cannot be negative."
        }
        return null
    }

    fun toolDefinitionsError(
        definitions: List<ExtensionToolDefinition>,
    ): String? {
        if (definitions.size > ExtensionApiLimits.MAX_TOOLS) {
            return "Too many Tool definitions."
        }
        if (definitions.map { it.name }.distinct().size != definitions.size) {
            return "Tool names must be unique."
        }
        definitions.forEach { definition ->
            if (!identifier.matches(definition.name)) return "Tool name is invalid."
            if (
                definition.description.isBlank() ||
                definition.description.utf8Size() >
                ExtensionApiLimits.MAX_TOOL_DESCRIPTION_BYTES
            ) {
                return "Tool description is invalid."
            }
            if (definition.riskLevel !in ExtensionRiskLevel.ALL) {
                return "Tool risk level is invalid."
            }
            if (
                definition.inputSchemaJson.utf8Size() >
                ExtensionApiLimits.MAX_SCHEMA_BYTES
            ) {
                return "Tool schema is too large."
            }
            val schema = parseObject(definition.inputSchemaJson)
                ?: return "Tool schema must be a JSON object."
            if (schema["type"]?.toString() != "\"object\"") {
                return "Tool schema root type must be object."
            }
        }
        return null
    }

    fun requestError(request: ExtensionToolRequest): String? {
        if (!requestId.matches(request.requestId)) return "Request ID is invalid."
        if (!identifier.matches(request.toolName)) return "Tool name is invalid."
        if (
            request.argumentsJson.utf8Size() >
            ExtensionApiLimits.MAX_ARGUMENT_BYTES ||
            parseObject(request.argumentsJson) == null
        ) {
            return "Tool arguments must be a bounded JSON object."
        }
        if (request.timeoutMillis !in 1..ExtensionApiLimits.MAX_TIMEOUT_MILLIS) {
            return "Tool timeout is invalid."
        }
        if (request.executionContext !in ExtensionExecutionContext.ALL) {
            return "Execution context is invalid."
        }
        return null
    }

    fun resultError(
        result: ExtensionToolResult,
        expectedRequestId: String? = null,
    ): String? {
        if (!requestId.matches(result.requestId)) return "Result request ID is invalid."
        if (expectedRequestId != null && result.requestId != expectedRequestId) {
            return "Result request ID does not match."
        }
        if (result.attachments.size > ExtensionApiLimits.MAX_ATTACHMENTS) {
            return "Too many result attachments."
        }
        result.attachments.forEach { descriptor ->
            attachmentError(descriptor)?.let { return it }
        }
        if (result.success) {
            if (result.errorCode != null || result.errorMessage != null) {
                return "Successful result cannot contain an error."
            }
            val content = result.contentJson
                ?: return "Successful result requires content."
            if (
                content.utf8Size() > ExtensionApiLimits.MAX_RESULT_BYTES ||
                parseObject(content) == null
            ) {
                return "Result content must be a bounded JSON object."
            }
        } else {
            if (result.contentJson != null || result.attachments.isNotEmpty()) {
                return "Failed result cannot contain content or attachments."
            }
            if (
                result.errorCode.isNullOrBlank() ||
                result.errorMessage.isNullOrBlank() ||
                result.errorCode.utf8Size() > 64 ||
                result.errorMessage.utf8Size() > 1_024
            ) {
                return "Failed result requires a bounded error."
            }
        }
        return null
    }

    fun attachmentError(
        descriptor: ExtensionAttachmentDescriptor,
    ): String? {
        if (!requestId.matches(descriptor.attachmentId)) {
            return "Attachment ID is invalid."
        }
        if (descriptor.mimeType !in imageMimeTypes) {
            return "Attachment MIME type is invalid."
        }
        if (descriptor.byteCount !in 1..ExtensionApiLimits.MAX_ATTACHMENT_BYTES) {
            return "Attachment byte count is invalid."
        }
        if (
            descriptor.widthPixels !in 1..ExtensionApiLimits.MAX_IMAGE_DIMENSION ||
            descriptor.heightPixels !in 1..ExtensionApiLimits.MAX_IMAGE_DIMENSION
        ) {
            return "Attachment dimensions are invalid."
        }
        if (descriptor.expiresAtEpochMillis <= 0) {
            return "Attachment expiry is invalid."
        }
        if (descriptor.presentation !in ExtensionAttachmentPresentation.ALL) {
            return "Attachment presentation is invalid."
        }
        if (
            descriptor.metadataJson.utf8Size() >
            ExtensionApiLimits.MAX_ATTACHMENT_METADATA_BYTES ||
            parseObject(descriptor.metadataJson) == null
        ) {
            return "Attachment metadata must be a bounded JSON object."
        }
        return null
    }

    private fun parseObject(value: String): JsonObject? =
        runCatching { json.parseToJsonElement(value) as? JsonObject }.getOrNull()

    private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size
}
