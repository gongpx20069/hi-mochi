package com.example.mochi_extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExtensionApiValidatorTest {
    @Test
    fun acceptsValidContractValues() {
        val metadata = validMetadata()
        val definition = validDefinition()
        val request = validRequest()
        val result = validResult()

        assertNull(ExtensionApiValidator.metadataError(metadata))
        assertNull(
            ExtensionApiValidator.connectionStateError(
                ExtensionConnectionState(
                    status = ExtensionConnectionStatus.CONNECTED,
                    detail = null,
                    accountLabel = "Mi Home",
                    selectedHomeCount = 1,
                    selectedDeviceCount = 4,
                ),
            ),
        )
        assertNull(ExtensionApiValidator.toolDefinitionsError(listOf(definition)))
        assertNull(ExtensionApiValidator.requestError(request))
        assertNull(
            ExtensionApiValidator.resultError(
                result = result,
                expectedRequestId = request.requestId,
            ),
        )
    }

    @Test
    fun rejectsDuplicateToolNames() {
        val definition = validDefinition()

        assertEquals(
            "Tool names must be unique.",
            ExtensionApiValidator.toolDefinitionsError(
                listOf(definition, definition),
            ),
        )
    }

    @Test
    fun rejectsNonObjectSchema() {
        assertEquals(
            "Tool schema must be a JSON object.",
            ExtensionApiValidator.toolDefinitionsError(
                listOf(validDefinition().copy(inputSchemaJson = "[]")),
            ),
        )
    }

    @Test
    fun rejectsMismatchedResultRequestId() {
        assertEquals(
            "Result request ID does not match.",
            ExtensionApiValidator.resultError(
                result = validResult(),
                expectedRequestId = "request-other-0002",
            ),
        )
    }

    @Test
    fun rejectsOversizedAttachment() {
        val result = validResult().copy(
            attachments = listOf(
                validAttachment().copy(
                    byteCount = ExtensionApiLimits.MAX_ATTACHMENT_BYTES + 1,
                ),
            ),
        )

        assertEquals(
            "Attachment byte count is invalid.",
            ExtensionApiValidator.resultError(result),
        )
    }

    @Test
    fun failedResultCannotCarryAttachment() {
        val result = ExtensionToolResult(
            requestId = "request-mijia-0001",
            success = false,
            contentJson = null,
            errorCode = "PROVIDER",
            errorMessage = "Provider unavailable.",
            attachments = listOf(validAttachment()),
        )

        assertEquals(
            "Failed result cannot contain content or attachments.",
            ExtensionApiValidator.resultError(result),
        )
    }

    private fun validMetadata() = ExtensionMetadata(
        protocolVersion = MochiExtensionProtocol.VERSION,
        minimumHostVersionCode = 10_001,
        extensionId = MochiExtensionProtocol.MIJIA_EXTENSION_ID,
        displayName = "Mochi Mi Home Extension",
        packageName = MochiExtensionProtocol.MIJIA_PACKAGE,
        serviceClassName = MochiExtensionProtocol.MIJIA_SERVICE,
        configurationActivityClassName =
        MochiExtensionProtocol.MIJIA_CONFIGURATION_ACTIVITY,
        versionName = "1.0.7",
        versionCode = 10_007,
        capabilities = listOf("typed_tools", "attachments"),
    )

    private fun validDefinition() = ExtensionToolDefinition(
        name = "mijia_list_devices",
        description = "List selected supported Mi Home devices.",
        inputSchemaJson = """{"type":"object","properties":{}}""",
        riskLevel = ExtensionRiskLevel.READ,
        defaultEnabled = false,
    )

    private fun validRequest() = ExtensionToolRequest(
        requestId = "request-mijia-0001",
        toolName = "mijia_list_devices",
        argumentsJson = "{}",
        timeoutMillis = 30_000,
        executionContext = ExtensionExecutionContext.FOREGROUND_MAIN,
    )

    private fun validResult() = ExtensionToolResult(
        requestId = "request-mijia-0001",
        success = true,
        contentJson = """{"devices":[]}""",
        errorCode = null,
        errorMessage = null,
        attachments = emptyList(),
    )

    private fun validAttachment() = ExtensionAttachmentDescriptor(
        attachmentId = "attachment-camera-0001",
        mimeType = "image/jpeg",
        byteCount = 1_024,
        widthPixels = 640,
        heightPixels = 360,
        expiresAtEpochMillis = 1_900_000_000_000,
        presentation = ExtensionAttachmentPresentation.CAMERA_SNAPSHOT,
        metadataJson = """{"camera_name":"Front door"}""",
    )
}
