package com.example.mochi_pet.platform.wake

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.example.mochi_pet.MainActivity
import com.example.mochi_pet.R
import com.example.mochi_pet.core.settings.AppLanguage
import com.example.mochi_pet.core.wake.WakeCaptureStatus
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class WakeCaptureService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val commandVersion = AtomicLong()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var engine: SherpaWakeEngine? = null
    private var mediaSession: MediaSession? = null
    private var enabled = false
    private var lastTriggerAtMillis = 0L
    private var currentStatus = WakeCaptureStatus.DISABLED
    private var currentTriggerSource: String? = null
    private var currentError: String? = null
    private val languageChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(
            context: Context?,
            intent: Intent?,
        ) {
            if (
                intent?.action !=
                AppLanguage.ACTION_APP_LANGUAGE_CHANGED
            ) {
                return
            }
            createNotificationChannel()
            createDetectedNotificationChannel()
            if (enabled) {
                ensureForeground()
            }
            val manager = getSystemService(NotificationManager::class.java)
            if (
                manager.activeNotifications.any {
                    it.id == DETECTED_NOTIFICATION_ID
                }
            ) {
                manager.notify(
                    DETECTED_NOTIFICATION_ID,
                    buildDetectedNotification(
                        currentTriggerSource ?: "wake",
                    ),
                )
            }
        }
    }
    private val resumeAfterDetection = Runnable {
        if (enabled && currentStatus == WakeCaptureStatus.PAUSED) {
            resumeCapture()
        }
    }

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(
            this,
            languageChangedReceiver,
            IntentFilter(AppLanguage.ACTION_APP_LANGUAGE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        engine = SherpaWakeEngine(
            context = applicationContext,
            onWakeDetected = { keyword ->
                mainHandler.post { handleVoiceTrigger("wake:$keyword") }
            },
            onFailure = { message ->
                mainHandler.post {
                    publishState(WakeCaptureStatus.ERROR, error = message)
                }
            },
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> stopWakeService()
            ACTION_PAUSE -> pauseCapture()
            ACTION_RESUME -> resumeCapture()
            ACTION_QUERY_STATE -> {
                publishState(
                    currentStatus,
                    triggerSource = currentTriggerSource,
                    error = currentError,
                )
                if (!enabled) {
                    stopSelf()
                }
            }
            ACTION_MEDIA_TRIGGER -> {
                val keepRunning = enabled
                if (!ensureForegroundSafely()) {
                    return START_NOT_STICKY
                }
                handleVoiceTrigger("media_button")
                if (!keepRunning) {
                    removeForeground()
                    stopSelf()
                }
            }
            else -> startCapture()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        commandVersion.incrementAndGet()
        mainHandler.removeCallbacks(resumeAfterDetection)
        unregisterReceiver(languageChangedReceiver)
        engine?.close()
        engine = null
        mediaSession?.release()
        mediaSession = null
        worker.shutdownNow()
        removeForeground()
        super.onDestroy()
    }

    private fun startCapture() {
        mainHandler.removeCallbacks(resumeAfterDetection)
        enabled = true
        if (!ensureForegroundSafely()) {
            return
        }
        val version = commandVersion.incrementAndGet()
        publishState(WakeCaptureStatus.STARTING)
        worker.execute {
            try {
                val currentEngine = checkNotNull(engine)
                currentEngine.initialize()
                if (version != commandVersion.get() || !enabled) {
                    return@execute
                }
                currentEngine.start()
                publishState(WakeCaptureStatus.LISTENING)
            } catch (error: IOException) {
                publishState(
                    WakeCaptureStatus.ERROR,
                    error = "Wake model installation failed",
                )
            } catch (error: IllegalStateException) {
                publishState(
                    WakeCaptureStatus.ERROR,
                    error = error.message ?: "Wake capture failed",
                )
            } catch (error: RuntimeException) {
                publishState(
                    WakeCaptureStatus.ERROR,
                    error = "Wake engine failed to start",
                )
            } catch (error: LinkageError) {
                publishState(
                    WakeCaptureStatus.ERROR,
                    error = "Wake native library is unavailable",
                )
            }
        }
    }

    private fun pauseCapture(triggerSource: String? = null) {
        mainHandler.removeCallbacks(resumeAfterDetection)
        if (!enabled) {
            triggerSource?.let(::notifyVoiceTrigger)
            return
        }
        commandVersion.incrementAndGet()
        worker.execute {
            engine?.stop()
            publishState(
                WakeCaptureStatus.PAUSED,
                triggerSource = triggerSource,
            )
            triggerSource?.let { source ->
                mainHandler.post { notifyVoiceTrigger(source) }
            }
        }
    }

    private fun resumeCapture() {
        if (enabled) {
            startCapture()
        }
    }

    private fun stopWakeService() {
        mainHandler.removeCallbacks(resumeAfterDetection)
        enabled = false
        commandVersion.incrementAndGet()
        worker.execute { engine?.stop() }
        publishState(WakeCaptureStatus.DISABLED)
        mediaSession?.isActive = false
        removeForeground()
        stopSelf()
    }

    private fun handleVoiceTrigger(source: String) {
        val now = SystemClock.elapsedRealtime()
        if (
            lastTriggerAtMillis > 0 &&
            now - lastTriggerAtMillis < TRIGGER_DEBOUNCE_MILLIS
        ) {
            return
        }
        lastTriggerAtMillis = now
        pauseCapture(source)
    }

    private fun notifyVoiceTrigger(source: String) {
        getSystemService(NotificationManager::class.java).notify(
            DETECTED_NOTIFICATION_ID,
            buildDetectedNotification(source),
        )
        sendBroadcast(
            Intent(ACTION_VOICE_TRIGGERED)
                .setPackage(packageName)
                .putExtra(EXTRA_TRIGGER_SOURCE, source),
        )
        mainHandler.postDelayed(
            resumeAfterDetection,
            DETECTION_PAUSE_TIMEOUT_MILLIS,
        )
        if (source.startsWith("media_")) {
            try {
                openVoicePendingIntent(source).send()
            } catch (error: PendingIntent.CanceledException) {
                publishState(
                    WakeCaptureStatus.ERROR,
                    error = "Voice trigger could not open Mochi",
                )
            }
        }
    }

    private fun ensureForeground() {
        createNotificationChannel()
        ensureMediaSession()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureForegroundSafely(): Boolean {
        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            enabled = false
            publishState(
                WakeCaptureStatus.ERROR,
                error = "Microphone permission is required for wake word",
            )
            stopSelf()
            return false
        }
        return try {
            ensureForeground()
            true
        } catch (error: SecurityException) {
            enabled = false
            publishState(
                WakeCaptureStatus.ERROR,
                error = "Android blocked background microphone access",
            )
            stopSelf()
            false
        }
    }

    private fun createNotificationChannel() {
        val localizedContext = AppLanguage.localizedContext(this)
        val channel = NotificationChannel(
            CHANNEL_ID,
            localizedContext.getString(
                R.string.wake_notification_channel,
            ),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val localizedContext = AppLanguage.localizedContext(this)
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val talk = PendingIntent.getService(
            this,
            1,
            Intent(this, WakeCaptureService::class.java)
                .setAction(ACTION_MEDIA_TRIGGER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            2,
            Intent(this, WakeCaptureService::class.java)
                .setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(
                localizedContext.getString(
                    R.string.wake_notification_title,
                ),
            )
            .setContentText(
                localizedContext.getString(
                    R.string.wake_notification_text,
                ),
            )
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(
                Notification.Action.Builder(
                    null,
                    localizedContext.getString(
                        R.string.wake_notification_talk,
                    ),
                    talk,
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    null,
                    localizedContext.getString(
                        R.string.wake_notification_stop,
                    ),
                    stop,
                ).build(),
            )
            .build()
    }

    private fun buildDetectedNotification(source: String): Notification {
        val localizedContext = AppLanguage.localizedContext(this)
        createDetectedNotificationChannel()
        return Notification.Builder(this, DETECTED_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(
                localizedContext.getString(
                    R.string.wake_detected_title,
                ),
            )
            .setContentText(
                localizedContext.getString(
                    R.string.wake_detected_text,
                ),
            )
            .setContentIntent(openVoicePendingIntent(source))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
    }

    private fun createDetectedNotificationChannel() {
        val localizedContext = AppLanguage.localizedContext(this)
        val channel = NotificationChannel(
            DETECTED_CHANNEL_ID,
            localizedContext.getString(
                R.string.wake_detected_channel,
            ),
            NotificationManager.IMPORTANCE_HIGH,
        )
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun openVoicePendingIntent(source: String): PendingIntent =
        PendingIntent.getActivity(
            this,
            4,
            Intent(this, MainActivity::class.java)
                .setAction(ACTION_OPEN_VOICE)
                .putExtra(EXTRA_TRIGGER_SOURCE, source)
                .addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun ensureMediaSession() {
        if (mediaSession != null) {
            mediaSession?.isActive = true
            return
        }
        mediaSession = MediaSession(this, "MochiVoiceSession").apply {
            setCallback(
                object : MediaSession.Callback() {
                    override fun onMediaButtonEvent(
                        mediaButtonIntent: Intent,
                    ): Boolean {
                        val event = IntentCompat.getParcelableExtra(
                            mediaButtonIntent,
                            Intent.EXTRA_KEY_EVENT,
                            KeyEvent::class.java,
                        ) ?: return false
                        return handleMediaKey(event)
                    }

                    override fun onPlay() {
                        handleVoiceTrigger("media_session_play")
                    }

                    override fun onPause() {
                        handleVoiceTrigger("media_session_pause")
                    }
                },
                mainHandler,
            )
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or
                            PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_PLAY_PAUSE,
                    )
                    .setState(PlaybackState.STATE_PAUSED, 0L, 0f)
                    .build(),
            )
            isActive = true
        }
    }

    private fun handleMediaKey(event: KeyEvent): Boolean {
        if (
            event.action != KeyEvent.ACTION_DOWN ||
            event.repeatCount != 0 ||
            event.keyCode !in WAKE_MEDIA_KEYS
        ) {
            return false
        }
        handleVoiceTrigger("media_session:${event.keyCode}")
        return true
    }

    private fun publishState(
        status: WakeCaptureStatus,
        triggerSource: String? = null,
        error: String? = null,
    ) {
        sendBroadcast(
            Intent(ACTION_STATE_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_STATUS, status.name)
                .putExtra(EXTRA_TRIGGER_SOURCE, triggerSource)
                .putExtra(EXTRA_ERROR, error),
        )
        currentStatus = status
        currentTriggerSource = triggerSource ?: currentTriggerSource
        currentError = error
    }

    private fun removeForeground() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    companion object {
        const val ACTION_START =
            "com.example.mochi_pet.action.START_WAKE_CAPTURE"
        const val ACTION_STOP =
            "com.example.mochi_pet.action.STOP_WAKE_CAPTURE"
        const val ACTION_PAUSE =
            "com.example.mochi_pet.action.PAUSE_WAKE_CAPTURE"
        const val ACTION_RESUME =
            "com.example.mochi_pet.action.RESUME_WAKE_CAPTURE"
        const val ACTION_MEDIA_TRIGGER =
            "com.example.mochi_pet.action.MEDIA_VOICE_TRIGGER"
        const val ACTION_QUERY_STATE =
            "com.example.mochi_pet.action.QUERY_WAKE_STATE"
        const val ACTION_STATE_CHANGED =
            "com.example.mochi_pet.action.WAKE_STATE_CHANGED"
        const val ACTION_VOICE_TRIGGERED =
            "com.example.mochi_pet.action.VOICE_TRIGGERED"
        const val ACTION_OPEN_VOICE =
            "com.example.mochi_pet.action.OPEN_VOICE"
        const val EXTRA_STATUS = "wake_status"
        const val EXTRA_TRIGGER_SOURCE = "wake_trigger_source"
        const val EXTRA_ERROR = "wake_error"

        private const val CHANNEL_ID = "mochi_wake_capture"
        private const val DETECTED_CHANNEL_ID = "mochi_wake_detected"
        private const val NOTIFICATION_ID = 1002
        const val DETECTED_NOTIFICATION_ID = 1003
        private const val TRIGGER_DEBOUNCE_MILLIS = 2_500L
        private const val DETECTION_PAUSE_TIMEOUT_MILLIS = 30_000L
        private val WAKE_MEDIA_KEYS = setOf(
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        )
    }
}
