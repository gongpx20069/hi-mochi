package com.example.mochi_pet.core.media

import com.example.mochi_pet.core.agent.tool.AgentTool
import com.example.mochi_pet.core.agent.tool.AgentToolJson
import com.example.mochi_pet.core.agent.tool.ToolErrorCode
import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.agent.tool.ToolInputException
import com.example.mochi_pet.core.agent.tool.ToolResultEnvelope
import com.example.mochi_pet.core.agent.tool.functionToolSchema
import com.example.mochi_pet.core.agent.tool.optionalInt
import com.example.mochi_pet.core.agent.tool.requiredEnum
import com.example.mochi_pet.core.agent.tool.requiredString
import com.example.mochi_pet.core.extensions.ExtensionToolScope
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

val MEDIA_TOOL_NAMES = setOf("media_read", "media_play_from_search", "media_control", "media_volume")

enum class MediaAction { PLAY, PAUSE, NEXT, PREVIOUS, SEEK }
enum class MediaVolumeAction { INCREASE, DECREASE, SET, MUTE }
enum class MediaSearchRoute { SESSION, INTENT }
@Serializable
data class MediaSessionInfo(
    val id: String,
    val packageName: String,
    val appName: String?,
    val title: String?,
    val artist: String?,
    @Transient val mediaId: String? = null,
    @Transient val queueItemId: Long? = null,
    val state: String,
    val positionMs: Long?,
    val durationMs: Long?,
    val actions: List<String>,
    val remotePlayback: Boolean,
)

@Serializable
data class MediaVolume(
    val current: Int,
    val minimum: Int,
    val maximum: Int,
    val muted: Boolean,
    val fixed: Boolean,
) {
    val percent: Int get() = if (maximum > 0) (current * 100.0 / maximum).roundToInt() else 0
}

@Serializable
data class MediaPlayerInfo(
    val id: String,
    val packageName: String,
    val appName: String?,
    val canOpen: Boolean,
    val searchModes: List<String>,
)

@Serializable
data class MediaSnapshot(
    val notificationAccess: Boolean,
    val sessions: List<MediaSessionInfo>,
    val volume: MediaVolume,
    val sessionsTruncated: Boolean = false,
    val players: List<MediaPlayerInfo> = emptyList(),
    val playersTruncated: Boolean = false,
)

interface MediaPlatform {
    suspend fun read(): MediaSnapshot
    suspend fun control(id: String, action: MediaAction, positionMs: Long?)
    suspend fun setVolume(index: Int)
    suspend fun playFromSearch(
        playerId: String, sessionId: String?, title: String, artist: String?,
        checkActive: suspend () -> Unit = {},
    ): MediaSearchRoute
}

class MediaFailure(val code: ToolErrorCode, message: String) : Exception(message)

class MediaToolRuntime(private val platform: MediaPlatform, private val mutationMutex: Mutex = Mutex()) {

    fun tools(
        scope: ExtensionToolScope,
        enabled: suspend (String) -> Boolean,
    ): List<AgentTool> =
        if (scope == ExtensionToolScope.SUBAGENT) emptyList()
        else MEDIA_TOOL_NAMES.map { name -> MediaTool(name, scope, enabled) }

    private inner class MediaTool(
        override val name: String,
        private val scope: ExtensionToolScope,
        private val enabled: suspend (String) -> Boolean,
    ) : AgentTool {
        override val schema = mediaSchema(name)

        private suspend fun checkEnabled() {
            currentCoroutineContext().ensureActive()
            if (!enabled(name)) throw MediaFailure(
                ToolErrorCode.PERMISSION_DENIED,
                "Enable this Tool and Media Control in Tools. An already sent command may still complete.",
            )
        }

        override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolResultEnvelope =
            try {
                checkEnabled()
                val data = when (name) {
                    "media_read" -> {
                        arguments.onlyFields(setOf("player_id", "title", "artist"))
                        if (arguments.isNotEmpty()) {
                            val id = arguments.boundedString("player_id", 100)
                            val title = arguments.boundedString("title", 200)
                            val artist = if ("artist" in arguments) arguments.boundedString("artist", 200) else null
                            val player = requirePlayer(platform.read(), id)
                            observeSong(player, null, title, artist)
                        } else {
                            val snapshot = platform.read()
                            buildJsonObject {
                                put("snapshot", AgentToolJson.format.encodeToJsonElement(snapshot))
                                put("volumePercent", snapshot.volume.percent)
                                put("volumeScope", "Local device media stream, shared with music, video and Mochi speech; not cast volume.")
                                if (!snapshot.notificationAccess) {
                                    put("notice", "Active sessions and verified song requests require player access in Capabilities > Media Control. App Control and local volume do not require it.")
                                }
                            }
                        }
                    }
                    else -> mutationMutex.withLock {
                        checkEnabled()
                        when (name) {
                            "media_play_from_search" -> playFromSearch(arguments)
                            "media_control" -> control(arguments)
                            else -> volume(arguments)
                        }
                    }
                }
                checkEnabled()
                ToolResultEnvelope.success(data)
            } catch (error: MediaFailure) {
                ToolResultEnvelope.error(error.code, error.message ?: "Media operation failed.")
            } catch (_: SecurityException) {
                ToolResultEnvelope.error(
                    ToolErrorCode.PERMISSION_DENIED,
                    "Android denied media access. Check notification access and device audio restrictions. Do not retry an uncertain command automatically.",
                )
            }

        private fun requireForegroundScope() {
            if (scope != ExtensionToolScope.FOREGROUND_MAIN) {
                throw MediaFailure(ToolErrorCode.CONFLICT,
                    "Opening a player requires a foreground conversation. Open Mochi and request it again; scheduled tasks may only search through an existing supported session.")
            }
        }

        private suspend fun playFromSearch(arguments: JsonObject): JsonObject {
            arguments.onlyFields(setOf("player_id", "title", "artist"))
            val id = arguments.boundedString("player_id", 100)
            val title = arguments.boundedString("title", 200)
            val artist = if ("artist" in arguments) arguments.boundedString("artist", 200) else null
            val before = platform.read()
            val player = requirePlayer(before, id)
            if (!before.notificationAccess) {
                throw MediaFailure(ToolErrorCode.PERMISSION_DENIED,
                    "Grant notification access in Capabilities > Media Control before requesting a song, so playback can be verified.")
            }
            val sessions = before.sessions.filter { it.packageName == player.packageName && "play_from_search" in it.actions }
            if (sessions.size > 1) {
                throw MediaFailure(ToolErrorCode.CONFLICT, "This player has several searchable sessions. Close the unused sessions before requesting a song.")
            }
            val session = sessions.singleOrNull()
            if (session == null) {
                if ("intent" !in player.searchModes) {
                    throw MediaFailure(ToolErrorCode.CONFLICT,
                        "No standard song-search route is available. Use the separate App Control tools to discover, open and operate the selected app, then media_read to verify playback.")
                }
                requireForegroundScope()
            }
            checkEnabled()
            platform.playFromSearch(id, session?.id, title, artist, ::checkEnabled)
            return observeSong(player, session?.id, title, artist)
        }

        private suspend fun observeSong(player: MediaPlayerInfo, sessionId: String?, title: String, artist: String?): JsonObject {
            repeat(40) {
                checkEnabled()
                val after = platform.read()
                requirePlayer(after, player.id)
                if (!after.notificationAccess) {
                    throw MediaFailure(ToolErrorCode.PERMISSION_DENIED,
                        "Player access is required to verify playback; playback is unconfirmed. Do not repeat song selection automatically.")
                }
                val matches = after.sessions.filter {
                    it.packageName == player.packageName && (sessionId == null || it.id == sessionId) &&
                        it.state == "playing" && sameMediaText(it.title, title) &&
                        (artist == null || sameMediaText(it.artist, artist))
                }
                if (matches.size == 1) {
                    checkEnabled()
                    return buildJsonObject {
                        put("verified", true)
                        put("session", AgentToolJson.format.encodeToJsonElement(matches.single()))
                        put("notice", "Playback is verified. If you opened this app for the request, use app_navigate return to restore Mochi's existing screen and Focus mode; do not restart playback or change its UI surface.")
                    }
                }
                delay(200)
            }
            throw MediaFailure(ToolErrorCode.TIMEOUT,
                "Matching title, requested artist and playing state were not confirmed. Login, subscription or manual selection may be needed. Do not automatically repeat song selection.")
        }

        private suspend fun control(arguments: JsonObject): JsonObject {
            arguments.onlyFields(setOf("session_id", "action", "position_ms"))
            val id = arguments.requiredString("session_id")
            if (id.length > 100) throw ToolInputException("session_id is too long")
            val action = arguments.requiredEnum<MediaAction>("action")
            val position = arguments.optionalInt("position_ms")?.toLong()
            if ((action == MediaAction.SEEK) != (position != null) || (position != null && position < 0)) {
                throw ToolInputException("Only seek requires a nonnegative position_ms")
            }
            val before = requireSession(platform.read(), id)
            if (action.name.lowercase() !in before.actions) {
                throw MediaFailure(ToolErrorCode.CONFLICT, "This player does not support ${action.name.lowercase()}.")
            }
            if (position != null && before.durationMs != null && position > before.durationMs) {
                throw ToolInputException("position_ms exceeds the current track duration")
            }
            checkEnabled()
            platform.control(id, action, position)
            repeat(16) {
                checkEnabled()
                val after = requireSession(platform.read(), id)
                if (controlObserved(before, after, action, position)) {
                    return buildJsonObject {
                        put("verified", true)
                        put("session", AgentToolJson.format.encodeToJsonElement(after))
                    }
                }
                delay(200)
            }
            throw MediaFailure(ToolErrorCode.TIMEOUT,
                "Command sent, but the player's resulting state was not confirmed. Do not automatically repeat it; read state again.")
        }

        private fun JsonObject.boundedString(key: String, maximum: Int): String =
            requiredString(key).also {
                if (it.length > maximum || it.any(Char::isISOControl)) throw ToolInputException("$key is invalid or too long")
            }

        private fun requirePlayer(snapshot: MediaSnapshot, id: String): MediaPlayerInfo =
            snapshot.players.firstOrNull { it.id == id }
                ?: throw MediaFailure(ToolErrorCode.NOT_FOUND, "Player unavailable. Use media_read again; do not guess or substitute another app.")

        private fun sameMediaText(actual: String?, requested: String): Boolean {
            fun normalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).trim().lowercase(Locale.ROOT)
            return actual != null && normalized(actual) == normalized(requested)
        }

        private suspend fun volume(arguments: JsonObject): JsonObject {
            arguments.onlyFields(setOf("action", "percent"))
            val action = arguments.requiredEnum<MediaVolumeAction>("action")
            val percent = arguments.optionalInt("percent")
            if ((action == MediaVolumeAction.SET) != (percent != null) || (percent != null && percent !in 0..100)) {
                throw ToolInputException("Only set requires percent, an integer from 0 to 100")
            }
            val before = platform.read().volume
            if (before.fixed || before.maximum <= 0) {
                throw MediaFailure(ToolErrorCode.CONFLICT, "Local media volume is fixed on this device.")
            }
            val target = when (action) {
                MediaVolumeAction.INCREASE -> before.current + 1
                MediaVolumeAction.DECREASE -> before.current - 1
                MediaVolumeAction.SET -> (before.maximum * percent!! / 100.0).roundToInt()
                MediaVolumeAction.MUTE -> 0
            }.coerceIn(before.minimum, before.maximum)
            if (action == MediaVolumeAction.MUTE && target != 0) {
                throw MediaFailure(ToolErrorCode.CONFLICT, "This device does not allow zero media volume.")
            }
            checkEnabled()
            platform.setVolume(target)
            repeat(6) {
                checkEnabled()
                val after = platform.read().volume
                if (after.current == target && (target == 0 || !after.muted)) {
                    return buildJsonObject {
                        put("verified", true)
                        put("volume", AgentToolJson.format.encodeToJsonElement(after))
                        put("volumePercent", after.percent)
                        put("volumeScope", "Local device media stream, including Mochi speech; not per-app or cast volume.")
                    }
                }
                delay(100)
            }
            throw MediaFailure(ToolErrorCode.CONFLICT,
                "Android did not apply the requested media volume. It may be limited by device policy or safe-volume protection; no bypass was attempted.")
        }
    }
}

private fun requireSession(snapshot: MediaSnapshot, id: String): MediaSessionInfo {
    if (!snapshot.notificationAccess) {
        throw MediaFailure(ToolErrorCode.PERMISSION_DENIED, "Grant notification access in Capabilities > Media Control.")
    }
    return snapshot.sessions.firstOrNull { it.id == id }
        ?: throw MediaFailure(ToolErrorCode.NOT_FOUND,
            "The media session is no longer available. Open the player and use media_read again; do not guess another target.")
}

private fun controlObserved(
    before: MediaSessionInfo,
    after: MediaSessionInfo,
    action: MediaAction,
    positionMs: Long?,
): Boolean = when (action) {
    MediaAction.PLAY -> after.state == "playing"
    MediaAction.PAUSE -> after.state == "paused"
    MediaAction.SEEK -> after.positionMs?.let { abs(it - requireNotNull(positionMs)) <= 1_500 } == true
    MediaAction.NEXT, MediaAction.PREVIOUS ->
        (before.mediaId != null && after.mediaId != null && after.mediaId != before.mediaId) ||
            (before.queueItemId != null && after.queueItemId != null && after.queueItemId != before.queueItemId) ||
            (before.title != null && after.title != null && after.title != before.title)
}

private fun JsonObject.onlyFields(allowed: Set<String>) {
    if (keys.any { it !in allowed }) throw ToolInputException("Unexpected arguments: ${(keys - allowed).joinToString()}")
    if (values.any { it !is JsonPrimitive }) throw ToolInputException("Arguments must be scalar values")
    for ((key, value) in this) {
        val primitive = value as JsonPrimitive
        if (primitive.isString != (key in setOf("action", "session_id", "player_id", "title", "artist", "ref", "text"))) {
            throw ToolInputException("$key has an invalid value type")
        }
    }
}

private fun mediaSchema(name: String): JsonObject = functionToolSchema(
    name = name,
    description = when (name) {
        "media_read" -> "Discover available Android music apps, player IDs, search modes, active sessions, tracks and local media volume. " +
            "Use returned player/session IDs, never guess. If the intended app is ambiguous, ask the user. " +
            "Only user-selected apps are exposed. With player_id, title and optional artist, wait for exact playing-song verification without sending a playback or navigation command."
        "media_play_from_search" -> "Request a song by title and optional artist in a player discovered by media_read. " +
            "Requires notification access and a supported standard search route. Scheduled tasks can use existing searchable sessions only. " +
            "Success requires matching track metadata and playing state. Never repeat an unconfirmed request automatically or substitute another app."
        "media_control" -> "Control an existing player discovered by media_read. No song search, app launching, " +
            "account actions or UI automation. Use only reported supported actions and the user's intended player. " +
            "Success requires observed player state; never repeat a next/previous command after an uncertain outcome."
        else -> "Change only the phone's shared media volume (also affects Mochi speech), never ringtone, " +
            "alarm or cast-device volume. Increase/decrease by one hardware step; set percent 0-100; mute sets zero. " +
            "Use only for an explicit user volume request or an authorized scheduled task. Report actual applied volume."
    },
    properties = buildJsonObject {
        if (name == "media_control") {
            put("session_id", buildJsonObject { put("type", "string") })
            put("action", enumProperty(MediaAction.entries.map { it.name.lowercase() }))
            put("position_ms", buildJsonObject { put("type", "integer"); put("minimum", 0); put("maximum", Int.MAX_VALUE) })
        }
        if (name == "media_volume") {
            put("action", enumProperty(MediaVolumeAction.entries.map { it.name.lowercase() }))
            put("percent", buildJsonObject { put("type", "integer"); put("minimum", 0); put("maximum", 100) })
        }
        if (name == "media_read" || name == "media_play_from_search") {
            put("player_id", buildJsonObject { put("type", "string"); put("maxLength", 100) })
        }
        if (name == "media_play_from_search" || name == "media_read") {
            put("title", buildJsonObject { put("type", "string"); put("maxLength", 200) })
            put("artist", buildJsonObject { put("type", "string"); put("maxLength", 200) })
        }
    },
    required = when (name) {
        "media_control" -> listOf("session_id", "action")
        "media_volume" -> listOf("action")
        "media_play_from_search" -> listOf("player_id", "title")
        else -> emptyList()
    },
)

private fun enumProperty(values: List<String>) = buildJsonObject {
    put("type", "string")
    put("enum", JsonArray(values.map(::JsonPrimitive)))
}
