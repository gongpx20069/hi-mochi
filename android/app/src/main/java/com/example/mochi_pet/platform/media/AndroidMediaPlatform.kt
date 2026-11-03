package com.example.mochi_pet.platform.media

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
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
import android.service.notification.NotificationListenerService
import com.example.mochi_pet.core.agent.tool.ToolErrorCode
import com.example.mochi_pet.core.media.MediaAction
import com.example.mochi_pet.core.media.MediaFailure
import com.example.mochi_pet.core.media.MediaPlatform
import com.example.mochi_pet.core.media.MediaSessionInfo
import com.example.mochi_pet.core.media.MediaSnapshot
import com.example.mochi_pet.core.media.MediaVolume
import java.util.UUID
import kotlinx.coroutines.Dispatchers
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

class AndroidMediaPlatform(context: Context) : MediaPlatform {
    private val application = context.applicationContext
    private val manager = application.getSystemService(MediaSessionManager::class.java)
    private val audio = application.getSystemService(AudioManager::class.java)
    private val ids = mutableMapOf<MediaSession.Token, String>()

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
        )
    }

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

    private fun controllers(): List<MediaController> =
        manager.getActiveSessions(ComponentName(application, MediaSessionAccessService::class.java))
            .filter { it.packageName != application.packageName }

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
            },
            remotePlayback = playbackInfo?.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE,
        )
    }
}
