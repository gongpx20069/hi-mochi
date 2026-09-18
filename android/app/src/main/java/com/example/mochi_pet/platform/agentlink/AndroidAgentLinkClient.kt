package com.example.mochi_pet.platform.agentlink

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.example.mochi_pet.core.agent.tool.ToolErrorCode
import com.example.mochi_pet.core.agent.tool.ToolResultEnvelope
import com.example.mochi_pet.core.agentlink.AGENTLINK_TOOLS
import com.example.mochi_pet.core.agentlink.AgentLinkActivityRequest
import com.example.mochi_pet.core.agentlink.AgentLinkAuthorization
import com.example.mochi_pet.core.agentlink.AgentLinkChatLink
import com.example.mochi_pet.core.agentlink.AgentLinkClient
import com.example.mochi_pet.core.agentlink.AgentLinkRequest
import com.example.mochi_pet.core.agentlink.AgentLinkState
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

class AndroidAgentLinkClient(
    private val context: Context,
    private val store: DataStore<Preferences>,
) : AgentLinkClient {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val authorization = AgentLinkAuthorization()

    override suspend fun refresh(): AgentLinkState = withContext(Dispatchers.IO) {
        val prefs = store.data.first()
        val base = AgentLinkState(
            enabled = prefs[ENABLED] ?: false,
            enabledTools = (prefs[TOOLS] ?: AGENTLINK_TOOLS).intersect(AGENTLINK_TOOLS),
            links = prefs[LINKS]?.let {
                runCatching { json.decodeFromString<List<AgentLinkChatLink>>(it) }.getOrNull()
            }.orEmpty(),
        )
        val identity = runCatching { identity() }.getOrNull()
            ?: return@withContext base
        if (prefs[SIGNER] != identity) {
            return@withContext base.copy(installed = true, status = "authorization_required")
        }
        val response = rpc("agentlink_control", """{"action":"status"}""")
        val data = response.data as? JsonObject
        val authorized = data?.get("authorized")?.jsonPrimitive?.booleanOrNull == true
        val connected = data?.get("connected")?.jsonPrimitive?.booleanOrNull == true
        val compatible = data?.get("protocolVersion")?.jsonPrimitive?.intOrNull == 1
        base.copy(
            installed = true,
            authorized = authorized && compatible && response.status == "ok",
            connected = authorized && connected && compatible && response.status == "ok",
            status = when {
                response.status != "ok" -> "disconnected"
                !compatible -> "incompatible"
                !authorized -> "authorization_required"
                !connected -> "disconnected"
                else -> "connected"
            },
        )
    }

    override suspend fun setEnabled(enabled: Boolean) {
        store.edit { it[ENABLED] = enabled }
    }

    override suspend fun setToolEnabled(name: String, enabled: Boolean) {
        require(name in AGENTLINK_TOOLS)
        store.edit {
            val current = it[TOOLS] ?: AGENTLINK_TOOLS
            it[TOOLS] = if (enabled) current + name else current - name
        }
    }

    override suspend fun beginAuthorization(): AgentLinkActivityRequest = withContext(Dispatchers.IO) {
        val signer = identity()
        authorization.begin(signer)
    }

    override suspend fun completeAuthorization(requestId: String?, version: Int, accepted: Boolean) {
        val currentSigner = if (accepted) withContext(Dispatchers.IO) { identity() } else null
        val signer = authorization.complete(requestId, version, accepted, currentSigner) ?: return
        store.edit { it[SIGNER] = signer }
    }

    override suspend fun revoke() {
        var confirmed = false
        try {
            confirmed = rpc("agentlink_control", """{"action":"revoke"}""").status == "ok"
        } finally {
            store.edit {
                if (confirmed) it.remove(SIGNER)
                it[ENABLED] = false
                it.remove(LINKS)
            }
        }
        check(confirmed) { "Mochi access disabled locally; remote revoke unconfirmed. Open AgentLink Manage access." }
    }

    override suspend fun remember(link: AgentLinkChatLink) {
        store.edit {
            val links = it[LINKS]?.let { raw ->
                runCatching { json.decodeFromString<List<AgentLinkChatLink>>(raw) }.getOrNull()
            }.orEmpty()
            val previous = links.firstOrNull { old ->
                old.machineId == link.machineId && old.chatId == link.chatId
            }
            val merged = link.copy(
                title = if (link.title == link.chatId) previous?.title ?: link.title else link.title,
                taskId = link.taskId ?: previous?.taskId,
                cursor = link.cursor ?: previous?.cursor,
                operationId = link.operationId ?: previous?.operationId,
            )
            it[LINKS] = json.encodeToString((listOf(merged) + links.filterNot { old ->
                old.machineId == link.machineId && old.chatId == link.chatId
            }).take(20))
        }
    }

    override suspend fun execute(tool: String, request: AgentLinkRequest): ToolResultEnvelope {
        require(tool in AGENTLINK_TOOLS)
        val state = refresh()
        if (!state.connected || !state.enabled || tool !in state.enabledTools) {
            return failure(ToolErrorCode.PERMISSION_DENIED, "Connect AgentLink in Tools; native authorization required")
        }
        val wireRequest = if (tool == "agentlink_workspace" &&
            request.action == "list" && request.machineId == null
        ) request.copy(action = "machines") else request
        // AgentLink itself fixes provenance to mochi; it rejects caller-supplied source fields.
        return rpc(tool, json.encodeToString(wireRequest.copy(source = null)))
    }

    /** Only UI events create this explicit intent; no model-supplied component or URI is accepted. */
    suspend fun activityIntent(request: AgentLinkActivityRequest): Intent = withContext(Dispatchers.IO) {
        val signer = identity()
        if (!request.authorize) {
            require(store.data.first()[SIGNER] == signer) { "Connect AgentLink first" }
        } else {
            require(authorization.isPending(request.requestId, signer)) { "Authorization request expired" }
        }
        val action = when {
            request.authorize -> "$PACKAGE.AUTHORIZE"
            request.chat != null -> "$PACKAGE.OPEN_CHAT"
            else -> "$PACKAGE.MANAGE_ACCESS"
        }
        Intent(action).setComponent(ComponentName(PACKAGE, AUTH_ACTIVITY))
            .putExtra("requestId", request.requestId)
            .apply {
                request.chat?.let { chat ->
                    val links = store.data.first()[LINKS]?.let {
                        json.decodeFromString<List<AgentLinkChatLink>>(it)
                    }.orEmpty()
                    require(chat in links) { "Unknown linked chat" }
                    putExtra("machineId", chat.machineId)
                    putExtra("chatId", chat.chatId)
                }
            }
    }

    @Suppress("DEPRECATION")
    private fun identity(): String {
        val pm = context.packageManager
        val service = pm.getServiceInfo(ComponentName(PACKAGE, SERVICE), PackageManager.GET_META_DATA)
        val activity = pm.getActivityInfo(ComponentName(PACKAGE, AUTH_ACTIVITY), PackageManager.GET_META_DATA)
        require(service.packageName == PACKAGE && service.name == SERVICE &&
            activity.packageName == PACKAGE && activity.name == AUTH_ACTIVITY &&
            service.exported && service.enabled && activity.exported && activity.enabled) {
            "AgentLink components unavailable"
        }
        require(service.metaData?.getInt(PROTOCOL_METADATA) == 1 &&
            activity.metaData?.getInt(PROTOCOL_METADATA) == 1) { "Unsupported AgentLink protocol" }
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES
        val info = pm.getPackageInfo(PACKAGE, flags)
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners
        else info.signatures
        require(!signatures.isNullOrEmpty()) { "AgentLink signer unavailable" }
        return signatures.map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
        }.sorted().joinToString(":")
    }

    private suspend fun rpc(method: String, arguments: String): ToolResultEnvelope =
        withContext(Dispatchers.IO) {
            if (!fitsPayload(arguments, 128 * 1024)) {
                return@withContext failure(ToolErrorCode.INVALID_ARGS, "AgentLink request too large")
            }
            val thread = HandlerThread("AgentLink-reply").apply { start() }
            val response = CompletableDeferred<ToolResultEnvelope>()
            val connected = CompletableDeferred<IBinder>()
            val requestId = UUID.randomUUID().toString()
            val reply = Messenger(Handler(thread.looper) { message ->
                if (message.what == 2 && message.data.getString("requestId") == requestId) {
                    val raw = message.data.getString("result")
                    val result = if (raw == null || !fitsPayload(raw, 256 * 1024)) {
                        failure(ToolErrorCode.PROVIDER_ERROR, "AgentLink response too large or missing")
                    } else {
                        runCatching {
                            json.decodeFromString<ToolResultEnvelope>(raw).also {
                                require(it.status == "ok" || (
                                    it.status == "error" && it.code in ToolErrorCode.entries.map { code -> code.name }
                                ))
                            }
                        }.getOrElse {
                            failure(ToolErrorCode.PROVIDER_ERROR, "Invalid AgentLink response")
                        }
                    }
                    response.complete(result)
                }
                true
            })
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    if (name == ComponentName(PACKAGE, SERVICE)) connected.complete(binder)
                    else connected.completeExceptionally(SecurityException("Unexpected AgentLink component"))
                }
                override fun onServiceDisconnected(name: ComponentName) {
                    connected.completeExceptionally(IllegalStateException("AgentLink disconnected"))
                    response.complete(failure(ToolErrorCode.PROVIDER_ERROR, "AgentLink disconnected; write outcome unknown"))
                }
                override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
                override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
            }
            var bound = false
            var binder: IBinder? = null
            val death = IBinder.DeathRecipient {
                response.complete(failure(ToolErrorCode.PROVIDER_ERROR, "AgentLink stopped; write outcome unknown"))
            }
            try {
                val pinned = store.data.first()[SIGNER]
                require(pinned != null && identity() == pinned) { "AgentLink authorization required" }
                withTimeout(30_000) {
                    bound = context.bindService(
                        Intent().setComponent(ComponentName(PACKAGE, SERVICE)),
                        connection, Context.BIND_AUTO_CREATE,
                    )
                    check(bound) { "AgentLink unavailable" }
                    val remote = connected.await()
                    binder = remote
                    remote.linkToDeath(death, 0)
                    Messenger(remote).send(Message.obtain(null, 1).apply {
                        data = Bundle().apply {
                            putString("requestId", requestId)
                            putString("method", method)
                            putString("arguments", arguments)
                        }
                        replyTo = reply
                    })
                    response.await()
                }
            } catch (_: TimeoutCancellationException) {
                failure(ToolErrorCode.TIMEOUT, "AgentLink timed out; do not resend writes; read state after reconnecting")
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failure(ToolErrorCode.PROVIDER_ERROR, "AgentLink disconnected or identity changed; refresh authorization")
            } finally {
                response.cancel()
                connected.cancel()
                binder?.let { runCatching { it.unlinkToDeath(death, 0) } }
                if (bound) runCatching { context.unbindService(connection) }
                thread.quitSafely()
            }
        }

    private fun failure(code: ToolErrorCode, message: String) = ToolResultEnvelope.error(code, message)

    private fun fitsPayload(text: String, limit: Int): Boolean =
        text.length.toLong() * 2 <= limit && text.toByteArray().size <= limit

    companion object {
        const val PACKAGE = "com.gongpx.androidacpclient"
        const val SERVICE = "$PACKAGE.integration.AgentLinkControlService"
        const val AUTH_ACTIVITY = "$PACKAGE.integration.AgentLinkAuthorizationActivity"
        const val PROTOCOL_METADATA = "$PACKAGE.PROTOCOL_VERSION"
        private val SIGNER = stringPreferencesKey("agentlink_confirmed_signer")
        private val ENABLED = booleanPreferencesKey("agentlink_enabled")
        private val TOOLS = stringSetPreferencesKey("agentlink_tools")
        private val LINKS = stringPreferencesKey("agentlink_links")
    }
}
