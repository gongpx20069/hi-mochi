package com.example.mochi_pet.platform.media

import android.app.Application
import android.app.Activity
import android.app.KeyguardManager
import android.app.SearchManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.provider.MediaStore
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
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AndroidMediaPlatformTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager = shadowOf(context.getSystemService(MediaSessionManager::class.java))
    private val notifications = shadowOf(context.getSystemService(NotificationManager::class.java))
    private var allowed = DEFAULT_MUSIC_APPS + setOf("fixture.music", "fixture.first", "fixture.second")
    private val platform = AndroidMediaPlatform(context) { allowed }
    private val sessions = mutableListOf<MediaSession>()

    @After fun close() { sessions.forEach { it.release() } }

    @Test fun `generic app discovery includes selected non music apps without player access`(): Unit = runBlocking {
        installPlayer("fixture.reader")
        installPlayer("fixture.hidden")
        allowed = setOf("fixture.reader")
        assertFalse(mediaNotificationAccessGranted(context))
        val selected = platform.apps().apps.single()
        assertEquals("fixture.reader", selected.packageName)
        assertEquals(selected.id, platform.apps().apps.single().id)
        allowed = emptySet()
        assertTrue(platform.apps().apps.isEmpty())
        assertThrows(MediaFailure::class.java) { runBlocking { platform.open(selected.id) } }
    }

    @Test fun `removing an app hides its metadata and revokes previously discovered sessions`(): Unit = runBlocking {
        grant(true)
        player("fixture.music")
        val id = platform.read().sessions.single().id
        allowed = emptySet()
        assertTrue(platform.read().players.isEmpty())
        assertTrue(platform.read().sessions.isEmpty())
        assertThrows(MediaFailure::class.java) { runBlocking { platform.control(id, MediaAction.PLAY, null) } }
    }

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

    @Test fun `installed player opening requires a resumed unlocked activity and never requires notification access`(): Unit = runBlocking {
        installPlayer("com.tencent.qqmusic")
        val player = platform.read().players.single()
        assertTrue(player.canOpen)
        assertTrue(player.searchModes.isEmpty())
        assertFalse(platform.read().notificationAccess)
        assertEquals(player.id, platform.read().players.single().id)
        assertThrows(MediaFailure::class.java) { runBlocking { platform.open(player.id) } }
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            platform.onActivityResumed(activity.get())
            platform.open(player.id)
            assertEquals("com.tencent.qqmusic", shadowOf(activity.get()).nextStartedActivity.component?.packageName)
            platform.onActivityPaused(activity.get())
            assertThrows(MediaFailure::class.java) { runBlocking { platform.open(player.id) } }
            platform.onActivityResumed(activity.get())
            shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
            assertThrows(MediaFailure::class.java) { runBlocking { platform.open(player.id) } }
        } finally {
            platform.onActivityPaused(activity.get())
            activity.pause().stop().destroy()
        }
    }

    @Test fun `cold song request sends only the selected app a structured standard intent`() = runBlocking {
        installPlayer("com.netease.cloudmusic", search = true)
        val player = platform.read().players.single()
        assertEquals(listOf("intent"), player.searchModes)
        assertThrows(MediaFailure::class.java) { runBlocking { platform.playFromSearch(player.id, null, "Track A", "Artist") } }
        grant(true)
        assertThrows(MediaFailure::class.java) { runBlocking { platform.playFromSearch(player.id, null, "Track A", "Artist") } }
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            platform.onActivityResumed(activity.get())
            platform.playFromSearch(player.id, null, "Track A", "Artist")
            val intent = shadowOf(activity.get()).nextStartedActivity
            assertEquals("com.netease.cloudmusic", intent.`package`)
            assertEquals(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH, intent.action)
            assertEquals("vnd.android.cursor.item/audio", intent.getStringExtra(MediaStore.EXTRA_MEDIA_FOCUS))
            assertEquals("Track A", intent.getStringExtra(MediaStore.EXTRA_MEDIA_TITLE))
            assertEquals("Artist", intent.getStringExtra(MediaStore.EXTRA_MEDIA_ARTIST))
            assertEquals("Track A Artist", intent.getStringExtra(SearchManager.QUERY))
        } finally {
            platform.onActivityPaused(activity.get())
            activity.pause().stop().destroy()
        }
    }

    @Test fun `search dispatch rechecks session identity and advertised capability without a fallback launch`() = runBlocking {
        installPlayer("fixture.music")
        grant(true)
        val controller = player("fixture.music")
        val snapshot = platform.read()
        val id = snapshot.players.single().id
        val sessionId = snapshot.sessions.single().id
        assertThrows(MediaFailure::class.java) { runBlocking { platform.playFromSearch(id, sessionId, "Track", null) } }
        shadowOf(controller).setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY_FROM_SEARCH).setState(PlaybackState.STATE_PAUSED, 0, 0f).build())
        assertEquals(listOf("session"), platform.read().players.single().searchModes)
        assertThrows(MediaFailure::class.java) { runBlocking { platform.playFromSearch(id, "stale", "Track", null) } }
        platform.playFromSearch(id, sessionId, "Track", null)
        assertEquals(PlaybackState.ACTION_PLAY_FROM_SEARCH, shadowOf(controller.transportControls).lastPerformedAction)
        assertTrue(shadowOf(context.getSystemService(AudioManager::class.java)).dispatchedMediaKeyEvents.isEmpty())
    }

    private fun installPlayer(packageName: String, search: Boolean = false) {
        val info = ApplicationInfo().apply {
            this.packageName = packageName
            enabled = true
            nonLocalizedLabel = "Fixture Music"
        }
        val activity = ActivityInfo().apply {
            this.packageName = packageName
            name = "$packageName.MainActivity"
            applicationInfo = info
            exported = true
            enabled = true
        }
        val packages = shadowOf(context.packageManager)
        packages.installPackage(PackageInfo().apply {
            this.packageName = packageName
            applicationInfo = info
            activities = arrayOf(activity)
        })
        val resolved = ResolveInfo().apply { activityInfo = activity; isDefault = true }
        packages.addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName), resolved,
        )
        if (search) packages.addResolveInfoForIntent(
            Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(packageName), resolved,
        )
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
