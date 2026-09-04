package com.example.mochi_extension

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

object MochiExtensionProtocol {
    const val VERSION = 1
    const val BIND_PERMISSION =
        "com.example.mochi_pet.permission.BIND_EXTENSION"
    const val MIJIA_EXTENSION_ID = "mijia"
    const val MIJIA_PACKAGE =
        "com.example.mochi_pet.extension.mijia"
    const val MIJIA_SERVICE =
        "com.example.mochi_mijia.MijiaExtensionService"
    const val MIJIA_CONFIGURATION_ACTIVITY =
        "com.example.mochi_mijia.MijiaConfigurationActivity"
    const val EXTRA_UI_LANGUAGE_TAG =
        "com.example.mochi_extension.extra.UI_LANGUAGE_TAG"
}

object ExtensionConnectionStatus {
    const val DISCONNECTED = "disconnected"
    const val CONNECTING = "connecting"
    const val CONNECTED = "connected"
    const val AUTHORIZATION_EXPIRED = "authorization_expired"
    const val ERROR = "error"

    val ALL = setOf(
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        AUTHORIZATION_EXPIRED,
        ERROR,
    )
}

object ExtensionExecutionContext {
    const val FOREGROUND_MAIN = "foreground_main"
    const val SCHEDULED = "scheduled"
    const val SUBAGENT = "subagent"

    val ALL = setOf(FOREGROUND_MAIN, SCHEDULED, SUBAGENT)
}

object ExtensionRiskLevel {
    const val READ = "read"
    const val WRITE = "write"
    const val SENSITIVE = "sensitive"

    val ALL = setOf(READ, WRITE, SENSITIVE)
}

object ExtensionAttachmentPresentation {
    const val CAMERA_SNAPSHOT = "camera_snapshot"

    val ALL = setOf(CAMERA_SNAPSHOT)
}

@Parcelize
data class ExtensionMetadata(
    val protocolVersion: Int,
    val minimumHostVersionCode: Int,
    val extensionId: String,
    val displayName: String,
    val packageName: String,
    val serviceClassName: String,
    val configurationActivityClassName: String,
    val versionName: String,
    val versionCode: Long,
    val capabilities: List<String>,
) : Parcelable

@Parcelize
data class ExtensionConnectionState(
    val status: String,
    val detail: String?,
    val accountLabel: String?,
    val selectedHomeCount: Int,
    val selectedDeviceCount: Int,
) : Parcelable

@Parcelize
data class ExtensionToolDefinition(
    val name: String,
    val description: String,
    val inputSchemaJson: String,
    val riskLevel: String,
    val defaultEnabled: Boolean,
) : Parcelable

@Parcelize
data class ExtensionToolRequest(
    val requestId: String,
    val toolName: String,
    val argumentsJson: String,
    val timeoutMillis: Long,
    val executionContext: String,
) : Parcelable

@Parcelize
data class ExtensionAttachmentDescriptor(
    val attachmentId: String,
    val mimeType: String,
    val byteCount: Long,
    val widthPixels: Int,
    val heightPixels: Int,
    val expiresAtEpochMillis: Long,
    val presentation: String,
    val metadataJson: String,
) : Parcelable

@Parcelize
data class ExtensionToolResult(
    val requestId: String,
    val success: Boolean,
    val contentJson: String?,
    val errorCode: String?,
    val errorMessage: String?,
    val attachments: List<ExtensionAttachmentDescriptor>,
) : Parcelable
