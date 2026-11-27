// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ui.windows.reviewer

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.ichi2.anki.R
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber

/**
 * Keeps auto advance of the study screen running while the screen is off or the app is in the background,
 * as long as 'Continue auto advance in the background' is enabled and auto advance is on.
 *
 * Without it, the app is frozen or killed in the background, and the CPU sleeps between the media,
 * so the auto advance countdown stops.
 *
 * It holds a partial wake lock, and shows a notification with a 'Stop' action, which turns off auto advance.
 *
 * Started and stopped by [ReviewerFragment] with [BackgroundListening].
 */
class BackgroundListeningService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // the 'Stop' action of the notification uses startService(), not startForegroundService()
        if (intent?.action == ACTION_STOP) {
            Timber.i("background listening: stop requested from the notification")
            BackgroundListening.requestStop()
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            // service types exist since Android 10
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
        acquireWakeLock()
        return START_NOT_STICKY
    }

    // auto advance may run for hours; the lock is released when the service is destroyed
    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        Timber.i("background listening: acquiring wake lock")
        wakeLock =
            getSystemService<PowerManager>()!!
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
    }

    override fun onDestroy() {
        Timber.i("background listening: service destroyed")
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val notificationManager = NotificationManagerCompat.from(this)
        notificationManager.createNotificationChannel(
            NotificationChannelCompat
                .Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.background_listening_channel))
                .setShowBadge(false)
                .build(),
        )
        // brings the existing task to the front, like the launcher icon
        val openIntent =
            packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
        val openPendingIntent =
            openIntent?.let { PendingIntentCompat.getActivity(this, 0, it, 0, false) }
        val stopPendingIntent =
            PendingIntentCompat.getService(
                this,
                0,
                Intent(this, BackgroundListeningService::class.java).setAction(ACTION_STOP),
                0,
                false,
            )
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_star_notify)
            .setContentTitle(getString(R.string.background_listening_notification_title))
            .setContentText(getString(R.string.background_listening_notification_text))
            .setContentIntent(openPendingIntent)
            .addAction(0, getString(R.string.background_listening_stop), stopPendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        @VisibleForTesting
        const val NOTIFICATION_ID = 0x4c495354 // "LIST"
        private const val CHANNEL_ID = "Background listening"
        private const val WAKE_LOCK_TAG = "AnkiDroid:BackgroundListening"

        @VisibleForTesting
        const val ACTION_STOP = "com.ichi2.anki.action.STOP_BACKGROUND_LISTENING"
    }
}

/**
 * Starts and stops [BackgroundListeningService], and passes the 'Stop' action of its notification
 * to the study screen.
 */
object BackgroundListening {
    private val _stopRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits when the user stops background listening from the notification */
    val stopRequests: SharedFlow<Unit> = _stopRequests.asSharedFlow()

    @VisibleForTesting
    fun requestStop() {
        _stopRequests.tryEmit(Unit)
    }

    /** Must be called while the app is visible: a foreground service can't be started from the background */
    fun start(context: Context) {
        Timber.i("background listening: start")
        try {
            ContextCompat.startForegroundService(context, Intent(context, BackgroundListeningService::class.java))
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException: the app isn't visible
            Timber.w(e, "background listening: failed to start the service")
        }
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, BackgroundListeningService::class.java))
    }
}
