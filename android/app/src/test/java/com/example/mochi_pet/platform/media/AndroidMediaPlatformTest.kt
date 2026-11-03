package com.example.mochi_pet.platform.media

import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import com.example.mochi_pet.core.media.MediaAction
import com.example.mochi_pet.core.media.MediaFailure
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AndroidMediaPlatformTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager = shadowOf(context.getSystemService(MediaSessionManager::class.java))
    private val notifications = shadowOf(context.getSystemService(NotificationManager::class.java))
    private val platform = AndroidMediaPlatform(context)
    private val sessions = mutableListOf<MediaSession>()

    @After fun close() { sessions.forEach { it.release() } }

    @Test fun `notification access gates discovery and own wake session is excluded`() = runBlocking {
        val other = player("fixture.music")
        player(context.packageName)
        assertFalse(platform.read().notificationAccess)
        assertTrue(platform.read().sessions.isEmpty())
        grant(true)
        val snapshot = platform.read()
        assertTrue(snapshot.notificationAccess)
        assertEquals("fixture.music", snapshot.sessions.single().packageName)
        assertEquals("Fixture track", snapshot.sessions.single().title)
        assertEquals(listOf("play", "seek"), snapshot.sessions.single().actions)
        assertEquals(snapshot.sessions.single().id, platform.read().sessions.single().id)
        grant(false)
        assertThrows(MediaFailure::class.java) {
            runBlocking { platform.control(snapshot.sessions.single().id, MediaAction.PLAY, null) }
        }
        assertTrue(platform.read().sessions.isEmpty())
        grant(true)
        manager.clearControllers()
        manager.addController(other)
        assertTrue(platform.read().sessions.single().id != snapshot.sessions.single().id)
    }

    @Test fun `volume changes only music stream and sends no global media keys`() = runBlocking {
        val audio = context.getSystemService(AudioManager::class.java)
        val ring = audio.getStreamVolume(AudioManager.STREAM_RING)
        val alarm = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        val notification = audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
        platform.setVolume(3)
        assertEquals(3, platform.read().volume.current)
        assertEquals(ring, audio.getStreamVolume(AudioManager.STREAM_RING))
        assertEquals(alarm, audio.getStreamVolume(AudioManager.STREAM_ALARM))
        assertEquals(notification, audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION))
        assertTrue(shadowOf(audio).dispatchedMediaKeyEvents.isEmpty())
    }

    @Test
    @Config(sdk = [26])
    fun `Android 8 authorization matches the exact listener component`() {
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners", "fixture.other/.Listener")
        assertFalse(mediaNotificationAccessGranted(context))
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners",
            ComponentName(context, MediaSessionAccessService::class.java).flattenToString())
        assertTrue(mediaNotificationAccessGranted(context))
    }

    @Test fun `removed session cannot silently redirect to another player`() = runBlocking {
        grant(true)
        player("fixture.first")
        val first = platform.read().sessions.single().id
        manager.clearControllers()
        player("fixture.second")
        assertThrows(MediaFailure::class.java) {
            runBlocking { platform.control(first, MediaAction.PLAY, null) }
        }
        assertEquals("fixture.second", platform.read().sessions.single().packageName)
    }

    @Test fun `native control targets the selected player without touching Mochi or global keys`() = runBlocking {
        grant(true)
        val own = player(context.packageName)
        val other = player("fixture.music")
        val id = platform.read().sessions.single().id
        platform.control(id, MediaAction.PLAY, null)
        assertEquals(PlaybackState.ACTION_PLAY, shadowOf(other.transportControls).lastPerformedAction)
        assertEquals(0L, shadowOf(own.transportControls).lastPerformedAction)
        platform.control(id, MediaAction.SEEK, 30_000)
        assertEquals(30_000L, shadowOf(other.transportControls).seekToPositionMs)
        assertTrue(shadowOf(context.getSystemService(AudioManager::class.java)).dispatchedMediaKeyEvents.isEmpty())
    }

    private fun grant(value: Boolean) {
        notifications.setNotificationListenerAccessGranted(ComponentName(context, MediaSessionAccessService::class.java), value)
    }

    private fun player(packageName: String): MediaController {
        val session = MediaSession(context, "fixture-${sessions.size}").also { sessions += it }
        val controller = session.controller
        shadowOf(controller).apply {
            setPackageName(packageName)
            setPlaybackState(PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_SEEK_TO)
                .setState(PlaybackState.STATE_PAUSED, 1_000, 0f)
                .build())
            setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Fixture track")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, 120_000).build())
        }
        manager.addController(controller)
        return controller
    }
}
