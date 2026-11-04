package com.example.mochi_pet.platform.media

import android.app.Activity
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.provider.MediaStore
import android.service.notification.NotificationListenerService
import com.example.mochi_pet.core.agent.tool.ToolErrorCode
import com.example.mochi_pet.core.media.MediaAction
import com.example.mochi_pet.core.apps.AppUiAction
import com.example.mochi_pet.core.apps.AppUiSnapshot
import com.example.mochi_pet.core.apps.AppPlatform
import com.example.mochi_pet.core.apps.AppInfo
import com.example.mochi_pet.core.apps.AppCatalog
import com.example.mochi_pet.core.media.MediaFailure
import com.example.mochi_pet.core.media.MediaPlatform
import com.example.mochi_pet.core.media.MediaPlayerInfo
import com.example.mochi_pet.core.media.MediaSearchRoute
import com.example.mochi_pet.core.media.MediaSessionInfo
import com.example.mochi_pet.core.media.MediaSnapshot
import com.example.mochi_pet.core.media.MediaVolume
import java.util.UUID
import java.lang.ref.WeakReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// Authorization bridge only: never inspect, persist, or forward notification contents.
class MediaSessionAccessService : NotificationListenerService()

fun mediaNotificationAccessGranted(context: Context): Boolean {
    val component = ComponentName(context, MediaSessionAccessService::class.java)
    return if (Build.VERSION.SDK_INT >= 27) {
        context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component)
    } else {
        Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component }
    }
}

class AndroidMediaPlatform(
    context: Context,
    private val allowedPackages: suspend () -> Set<String> = { DEFAULT_MUSIC_APPS },
) : MediaPlatform, AppPlatform {
    private val application = context.applicationContext
    private val manager = application.getSystemService(MediaSessionManager::class.java)
    private val audio = application.getSystemService(AudioManager::class.java)
    private val ids = mutableMapOf<MediaSession.Token, String>()
    private val playerIds = mutableMapOf<String, String>()
    private var resumedActivity: WeakReference<Activity>? = null
    private var returnPackage: String? = null

    fun onActivityResumed(activity: Activity) {
        resumedActivity = WeakReference(activity)
    }

    fun onActivityPaused(activity: Activity) {
        if (resumedActivity?.get() === activity) resumedActivity = null
    }

    override suspend fun read(): MediaSnapshot = withContext(Dispatchers.Main.immediate) {
        var access = mediaNotificationAccessGranted(application)
        val controllers = if (access) {
            try {
                controllers()
            } catch (_: SecurityException) {
                // A revoked or not-yet-bound listener must not block local volume.
                access = false
                emptyList()
            }
        } else emptyList()
        ids.keys.retainAll(controllers.map { it.sessionToken }.toSet())
        val players = discoverPlayers(controllers)
        MediaSnapshot(
            notificationAccess = access,
            sessions = controllers.take(32).map { controller ->
                controller.info(ids.getOrPut(controller.sessionToken) { UUID.randomUUID().toString() })
            },
            volume = MediaVolume(
                current = audio.getStreamVolume(AudioManager.STREAM_MUSIC),
                minimum = if (Build.VERSION.SDK_INT >= 28) audio.getStreamMinVolume(AudioManager.STREAM_MUSIC) else 0,
                maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                muted = audio.isStreamMute(AudioManager.STREAM_MUSIC),
                fixed = audio.isVolumeFixed,
            ),
            sessionsTruncated = controllers.size > 32,
            players = players.take(32),
            playersTruncated = players.size > 32,
        )
    }

    override suspend fun apps(): AppCatalog = withContext(Dispatchers.Main.immediate) {
        AppCatalog(discoverApps(),
            appAssistanceGranted(application), AppAccessibilityService.current() != null)
    }

    private suspend fun requireApp(id: String): AppInfo =
        apps().apps.firstOrNull { it.id == id }
            ?: throw MediaFailure(ToolErrorCode.NOT_FOUND, "App unavailable. Use app_read again.")

    override suspend fun open(appId: String, checkActive: suspend () -> Unit): Unit = withContext(Dispatchers.Main.immediate) {
        val player = requireApp(appId)
        val intent = application.packageManager.getLaunchIntentForPackage(player.packageName)
            ?: throw MediaFailure(ToolErrorCode.NOT_FOUND, "The player's launch entry is no longer available.")
        checkActive()
        launch(intent)
    }

    override suspend fun playFromSearch(
        playerId: String,
        sessionId: String?,
        title: String,
        artist: String?,
        checkActive: suspend () -> Unit,
    ): MediaSearchRoute = withContext(Dispatchers.Main.immediate) {
        checkActive()
        val player = requirePlayer(playerId)
        if (!mediaNotificationAccessGranted(application)) {
            throw MediaFailure(ToolErrorCode.PERMISSION_DENIED, "Grant notification access in Tools > Media Control.")
        }
        val intent = searchIntent(player.packageName).apply {
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
            putExtra(MediaStore.EXTRA_MEDIA_TITLE, title)
            if (artist != null) putExtra(MediaStore.EXTRA_MEDIA_ARTIST, artist)
            putExtra(SearchManager.QUERY, listOfNotNull(title, artist).joinToString(" "))
        }
        if (sessionId != null) {
            val controller = controllers().firstOrNull {
                ids[it.sessionToken] == sessionId && it.packageName == player.packageName
            } ?: throw MediaFailure(ToolErrorCode.NOT_FOUND, "The selected search session expired. No request was sent.")
            if ((controller.playbackState?.actions ?: 0L) and PlaybackState.ACTION_PLAY_FROM_SEARCH == 0L) {
                throw MediaFailure(ToolErrorCode.CONFLICT, "The selected session no longer supports song requests.")
            }
            checkActive()
            controller.transportControls.playFromSearch(intent.getStringExtra(SearchManager.QUERY), intent.extras)
            MediaSearchRoute.SESSION
        } else {
            if (!supportsSearchIntent(player.packageName)) {
                throw MediaFailure(ToolErrorCode.CONFLICT, "This player no longer exposes a standard search-and-play entry.")
            }
            checkActive()
            launch(intent)
            MediaSearchRoute.INTENT
        }
    }

    override suspend fun appUi(
        appId: String, action: AppUiAction, ref: String?, text: String?, owner: String,
        checkActive: suspend () -> Unit,
    ): AppUiSnapshot? = withContext(Dispatchers.Main.immediate) {
        checkActive()
        val player = requireApp(appId)
        val service = AppAccessibilityService.current()
            ?: throw MediaFailure(ToolErrorCode.PERMISSION_DENIED, "App assistance is not connected. Check App Control accessibility access and reopen Mochi.")
        checkActive()
        if (player.packageName !in allowedPackages() || !appCanBeSelected(application, player.packageName)) {
            throw MediaFailure(ToolErrorCode.PERMISSION_DENIED, "App access was removed. No further action was taken.")
        }
        service.execute(player.packageName, action, ref, text, owner).also {
            if (action == AppUiAction.RETURN) returnPackage = player.packageName
        }
    }

    override suspend fun returnToMochi(checkActive: suspend () -> Unit): Boolean = withContext(Dispatchers.Main.immediate) {
        val target = returnPackage
        returnPackage = null
        if (target == null || target !in allowedPackages()) return@withContext false
        checkActive()
        if (resumedActivity?.get() != null) return@withContext true
        val requested = try {
            AppAccessibilityService.current()?.returnToMochi() == true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: MediaFailure) {
            false
        }
        if (requested) repeat(30) {
            checkActive()
            if (resumedActivity?.get() != null) return@withContext true
            delay(100)
        }
        false
    }

    private suspend fun requirePlayer(id: String): MediaPlayerInfo =
        read().players.firstOrNull { it.id == id }
            ?: throw MediaFailure(ToolErrorCode.NOT_FOUND, "Player unavailable. Use media_read again.")

    private fun launch(intent: Intent) {
        val activity = resumedActivity?.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed ||
            application.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            throw MediaFailure(ToolErrorCode.CONFLICT, "Unlock the phone and bring Mochi to the foreground before opening a player.")
        }
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            throw MediaFailure(ToolErrorCode.NOT_FOUND, "The player is no longer available. No alternative app was opened.")
        }
    }

    private suspend fun discoverApps(): List<AppInfo> {
        val packages = application.packageManager
        val candidates = allowedPackages().filter { appCanBeSelected(application, it) }
        val players = candidates.mapNotNull { packageName ->
            val info = try {
                packages.getApplicationInfo(packageName, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                return@mapNotNull null
            }
            if (!info.enabled) return@mapNotNull null
            AppInfo(
                id = playerIds.getOrPut(packageName) { UUID.randomUUID().toString() },
                packageName = packageName,
                appName = info.loadLabel(packages).toString().take(100),
                canOpen = packages.getLaunchIntentForPackage(packageName) != null,
            )
        }
        playerIds.keys.retainAll(players.map { it.packageName }.toSet())
        return players
    }

    private suspend fun discoverPlayers(controllers: List<MediaController>): List<MediaPlayerInfo> =
        discoverApps().map { app ->
            MediaPlayerInfo(app.id, app.packageName, app.appName, app.canOpen, buildList {
                if (controllers.any {
                    it.packageName == app.packageName &&
                        (it.playbackState?.actions ?: 0L) and PlaybackState.ACTION_PLAY_FROM_SEARCH != 0L
                }) add("session")
                if (supportsSearchIntent(app.packageName)) add("intent")
            })
        }

    private fun searchIntent(packageName: String) =
        Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(packageName)

    private fun supportsSearchIntent(packageName: String): Boolean =
        application.packageManager.queryIntentActivities(searchIntent(packageName), PackageManager.MATCH_DEFAULT_ONLY)
            .any { it.activityInfo.exported && it.activityInfo.enabled }

    override suspend fun control(id: String, action: MediaAction, positionMs: Long?): Unit =
        withContext(Dispatchers.Main.immediate) {
            if (!mediaNotificationAccessGranted(application)) {
                throw MediaFailure(ToolErrorCode.PERMISSION_DENIED, "Grant notification access in Tools > Media Control.")
            }
            val controller = controllers().firstOrNull { ids[it.sessionToken] == id }
                ?: throw MediaFailure(ToolErrorCode.NOT_FOUND, "Media session expired; read available players again.")
            if (action.name.lowercase() !in controller.info(id).actions) {
                throw MediaFailure(ToolErrorCode.CONFLICT, "The player no longer supports this action.")
            }
            val controls = controller.transportControls
            when (action) {
                MediaAction.PLAY -> controls.play()
                MediaAction.PAUSE -> controls.pause()
                MediaAction.NEXT -> controls.skipToNext()
                MediaAction.PREVIOUS -> controls.skipToPrevious()
                MediaAction.SEEK -> controls.seekTo(requireNotNull(positionMs))
            }
        }

    override suspend fun setVolume(index: Int): Unit = withContext(Dispatchers.Main.immediate) {
        if (audio.isVolumeFixed) throw MediaFailure(ToolErrorCode.CONFLICT, "Local media volume is fixed.")
        require(index in 0..audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
    }

    private suspend fun controllers(): List<MediaController> =
        manager.getActiveSessions(ComponentName(application, MediaSessionAccessService::class.java))
            .filter { it.packageName in allowedPackages() && appCanBeSelected(application, it.packageName) }

    private fun MediaController.info(id: String): MediaSessionInfo {
        val playback = playbackState
        val metadata = metadata
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 }
        val reportedPosition = playback?.position?.takeIf { it >= 0 }
        val elapsed = if (playback?.state == PlaybackState.STATE_PLAYING && playback.lastPositionUpdateTime > 0) {
            ((SystemClock.elapsedRealtime() - playback.lastPositionUpdateTime).coerceAtLeast(0) * playback.playbackSpeed).toLong()
        } else 0
        val position = reportedPosition?.let { (it + elapsed).coerceAtLeast(0).coerceAtMost(duration ?: Long.MAX_VALUE) }
        val supported = playback?.actions ?: 0L
        val appName = try {
            application.packageManager.getApplicationInfo(packageName, 0)
                .loadLabel(application.packageManager).toString().take(100)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        return MediaSessionInfo(
            id = id, packageName = packageName, appName = appName,
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.take(200),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.take(200),
            mediaId = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.take(200),
            queueItemId = playback?.activeQueueItemId?.takeIf { it != -1L },
            state = when (playback?.state) {
                PlaybackState.STATE_PLAYING -> "playing"
                PlaybackState.STATE_PAUSED -> "paused"
                PlaybackState.STATE_STOPPED -> "stopped"
                PlaybackState.STATE_BUFFERING -> "buffering"
                PlaybackState.STATE_CONNECTING -> "connecting"
                PlaybackState.STATE_ERROR -> "error"
                else -> "unknown"
            },
            positionMs = position, durationMs = duration,
            actions = buildList {
                if (supported and PlaybackState.ACTION_PLAY != 0L) add("play")
                if (supported and PlaybackState.ACTION_PAUSE != 0L) add("pause")
                if (supported and PlaybackState.ACTION_SKIP_TO_NEXT != 0L) add("next")
                if (supported and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L) add("previous")
                if (supported and PlaybackState.ACTION_SEEK_TO != 0L) add("seek")
                if (supported and PlaybackState.ACTION_PLAY_FROM_SEARCH != 0L) add("play_from_search")
            },
            remotePlayback = playbackInfo?.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE,
        )
    }
}
