package com.example.mochi_pet.core.media

import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.agent.tool.ToolRegistry
import com.example.mochi_pet.core.extensions.ExtensionToolScope
import com.example.mochi_pet.core.model.MochiSurface
import java.time.LocalDate
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaToolsTest {
    private val context = ToolExecutionContext(LocalDate.of(2026, 11, 3), MochiSurface.Face)
    private val player = MediaSessionInfo(
        "player-1", "fixture.music", "Fixture Music", "Track A", "Artist", "track-a", 1,
        "paused", 0, 100_000, listOf("play", "pause", "next", "previous", "seek"), false,
    )
    private val platform = FakeMediaPlatform(MediaSnapshot(true, listOf(player), MediaVolume(5, 0, 10, false, false)))
    private var enabled = true
    private val runtime = MediaToolRuntime(platform)
    private val registry = ToolRegistry(runtime.tools(ExtensionToolScope.FOREGROUND_MAIN) { enabled })

    @Test fun `main and scheduled agents get three tools but subagents get none`() {
        assertEquals(MEDIA_TOOL_NAMES, registry.names)
        assertEquals(MEDIA_TOOL_NAMES,
            runtime.tools(ExtensionToolScope.SCHEDULED) { true }.map { it.name }.toSet())
        assertTrue(runtime.tools(ExtensionToolScope.SUBAGENT) { true }.isEmpty())
    }

    @Test fun `missing notification permission is explicit without blocking volume`() = runTest {
        platform.snapshot = platform.snapshot.copy(notificationAccess = false, sessions = emptyList())
        val result = registry.execute("media_read", "{}", context)
        assertEquals("ok", result.status)
        assertTrue(result.data!!.jsonObject.containsKey("notice"))
        assertEquals("PERMISSION_DENIED",
            registry.execute("media_control", """{"session_id":"player-1","action":"play"}""", context).code)
        val volume = registry.execute("media_volume", """{"action":"set","percent":70}""", context)
        assertEquals("ok", volume.status)
        assertEquals(7, platform.snapshot.volume.current)
        assertTrue(platform.controls.isEmpty())
    }

    @Test fun `explicit target supported action and observed playback are required`() = runTest {
        assertEquals("NOT_FOUND",
            registry.execute("media_control", """{"session_id":"wrong","action":"play"}""", context).code)
        platform.snapshot = platform.snapshot.copy(sessions = listOf(player.copy(actions = listOf("pause"))))
        assertEquals("CONFLICT",
            registry.execute("media_control", """{"session_id":"player-1","action":"play"}""", context).code)
        assertTrue(platform.controls.isEmpty())
        platform.snapshot = platform.snapshot.copy(sessions = listOf(player))
        platform.onControl = { platform.snapshot = platform.snapshot.copy(sessions = listOf(player.copy(state = "playing"))) }
        assertEquals("ok",
            registry.execute("media_control", """{"session_id":"player-1","action":"play"}""", context).status)
        assertEquals(listOf(MediaAction.PLAY), platform.controls)
    }

    @Test fun `unobserved skip is uncertain and is never resent`() = runTest {
        val result = registry.execute("media_control", """{"session_id":"player-1","action":"next"}""", context)
        assertEquals("TIMEOUT", result.code)
        assertEquals(listOf(MediaAction.NEXT), platform.controls)
        platform.onControl = { platform.snapshot = platform.snapshot.copy(sessions = listOf(player.copy(mediaId = "track-b"))) }
        assertEquals("ok",
            registry.execute("media_control", """{"session_id":"player-1","action":"next"}""", context).status)
    }

    @Test fun `metadata arriving late cannot falsely confirm a skip and internal identifiers are not exposed`() = runTest {
        platform.snapshot = platform.snapshot.copy(sessions = listOf(player.copy(title = null, mediaId = null, queueItemId = null)))
        platform.onControl = { platform.snapshot = platform.snapshot.copy(sessions = listOf(player)) }
        assertEquals("TIMEOUT",
            registry.execute("media_control", """{"session_id":"player-1","action":"next"}""", context).code)
        val result = registry.execute("media_read", "{}", context)
        assertFalse(result.data.toString().contains("track-a"))
        assertFalse(result.data.toString().contains("queueItemId"))
    }

    @Test fun `invalid volume and seek arguments cannot cause side effects`() = runTest {
        for (args in listOf(
            """{"action":"set","percent":101}""", """{"action":"mute","percent":50}""",
            """{"action":"set"}""", """{"action":"set","percent":-1}""",
            """{"action":"set","percent":"30"}""", """{"action":true}""",
            """{"action":"increase","stream":"alarm"}""",
        )) assertEquals("INVALID_ARGS", registry.execute("media_volume", args, context).code)
        for (args in listOf(
            """{"session_id":"player-1","action":"seek","position_ms":-1}""",
            """{"session_id":"player-1","action":"seek","position_ms":100001}""",
            """{"session_id":"player-1","action":"play","position_ms":100}""",
        )) assertEquals("INVALID_ARGS", registry.execute("media_control", args, context).code)
        assertEquals(0, platform.volumeWrites)
        assertTrue(platform.controls.isEmpty())
    }

    @Test fun `volume reports actual hardware rounding and honors fixed or restricted volume`() = runTest {
        val rounded = registry.execute("media_volume", """{"action":"set","percent":44}""", context)
        assertEquals("40", rounded.data!!.jsonObject.getValue("volumePercent").jsonPrimitive.content)
        assertEquals("ok", registry.execute("media_volume", """{"action":"mute"}""", context).status)
        assertEquals(0, platform.snapshot.volume.current)
        platform.snapshot = platform.snapshot.copy(volume = platform.snapshot.volume.copy(fixed = true))
        assertEquals("CONFLICT", registry.execute("media_volume", """{"action":"increase"}""", context).code)
        assertEquals(2, platform.volumeWrites)
        platform.snapshot = platform.snapshot.copy(volume = platform.snapshot.volume.copy(fixed = false))
        platform.applyVolume = false
        assertEquals("CONFLICT", registry.execute("media_volume", """{"action":"set","percent":80}""", context).code)
        assertEquals(3, platform.volumeWrites)
    }

    @Test fun `revocation blocks stale tool objects and interruption does not resend`() = runTest {
        enabled = false
        assertEquals("PERMISSION_DENIED", registry.execute("media_volume", """{"action":"increase"}""", context).code)
        assertEquals(0, platform.volumeWrites)
        enabled = true
        platform.onControl = { enabled = false }
        assertEquals("PERMISSION_DENIED",
            registry.execute("media_control", """{"session_id":"player-1","action":"next"}""", context).code)
        enabled = true
        platform.onControl = {}
        val pending = async {
            registry.execute("media_control", """{"session_id":"player-1","action":"previous"}""", context)
        }
        runCurrent()
        assertFalse(pending.isCompleted)
        pending.cancel()
        pending.join()
        assertTrue(pending.isCancelled)
        assertEquals(listOf(MediaAction.NEXT, MediaAction.PREVIOUS), platform.controls)
    }

    private class FakeMediaPlatform(var snapshot: MediaSnapshot) : MediaPlatform {
        val controls = mutableListOf<MediaAction>()
        var volumeWrites = 0
        var applyVolume = true
        var onControl: () -> Unit = {}
        override suspend fun read() = snapshot
        override suspend fun control(id: String, action: MediaAction, positionMs: Long?) {
            controls += action
            onControl()
        }
        override suspend fun setVolume(index: Int) {
            volumeWrites++
            if (applyVolume) snapshot = snapshot.copy(volume = snapshot.volume.copy(current = index, muted = index == 0))
        }
    }
}
