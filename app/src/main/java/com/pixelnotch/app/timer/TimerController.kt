package com.pixelnotch.app.timer

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pixelnotch.app.R
import com.pixelnotch.app.coordinator.IslandCoordinator
import com.pixelnotch.app.settings.PixelNotchSettings

/**
 * In-app managed countdown timers. Ticks via a 1 Hz handler while armed (visible
 * in the island as a persistent timer state) and raises a completion pop-up +
 * vibration + a real notification (when permitted) when the countdown lands.
 */
object TimerController {

    private const val TAG = "TimerController"
    private const val TICK_MS = 1000L

    private val handler = Handler(Looper.getMainLooper())
    private var endAtWallMs: Long = 0L
    private var totalMs: Long = 0L
    private var label: String = "Timer"
    private var appContext: Context? = null

    val isActive: Boolean get() = endAtWallMs > 0L

    fun remainingInfo(): IslandCoordinator.TimerInfo? {
        if (endAtWallMs <= 0L) return null
        val remaining = (endAtWallMs - System.currentTimeMillis()).coerceAtLeast(0L)
        return IslandCoordinator.TimerInfo(label = label, remainingMs = remaining, totalMs = totalMs)
    }

    /** Starts (or restarts) the single in-app timer from a foreground user action. */
    fun start(context: Context, minutes: Int, label: String = "Timer") {
        val now = System.currentTimeMillis()
        appContext = context.applicationContext
        totalMs = minutes * 60_000L
        this.label = label
        endAtWallMs = now + totalMs
        Log.d(TAG, "timer started: ${minutes}m")
        tick()
        IslandCoordinator.onTimerFine(remainingInfo()!!, done = false)
    }

    fun cancel() {
        endAtWallMs = 0L
        totalMs = 0L
        handler.removeCallbacksAndMessages(null)
        IslandCoordinator.onTimerUpdated(null)
    }

    private fun tick() {
        val remaining = remainingInfo()
        if (remaining == null) return
        if (remaining.remainingMs <= 0L) {
            finish()
            return
        }
        IslandCoordinator.onTimerUpdated(remaining)
        handler.postDelayed({ tick() }, TICK_MS)
    }

    /**
     * Timer completed: publishes the done pop-up through the coordinator,
     * vibrates and posts a real system notification (when permitted) so the user
     * is alerted even when the island is turned off.
     */
    fun finish() {
        val total = totalMs
        endAtWallMs = 0L
        IslandCoordinator.onTimerFine(
            info = IslandCoordinator.TimerInfo(label, 0L, total),
            done = true
        )
        vibrate(appContext)
        postNotification(requireNotNull(appContext) { "timer not started" }, total)
        // allow the done pop-up to play out; clear the timer payload afterwards
        handler.postDelayed({ IslandCoordinator.onTimerUpdated(null) }, 6500L)
    }

    private fun vibrate(context: Context?) {
        if (context == null) return
        try {
            val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                context.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
                    ?: return
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            }
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 90, 70, 120), -1))
        } catch (t: Throwable) {
            Log.w(TAG, "vibration unavailable", t)
        }
    }

    private fun postNotification(context: Context, durationMs: Long) {
        val granted = ContextCompat.checkSelfPermission(
            context, "android.permission.POST_NOTIFICATIONS"
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        try {
            val intent = Intent().apply {
                setClassName(context, "com.pixelnotch.app.MainActivity")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = android.app.PendingIntent.getActivity(
                context, 3101, intent, android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val builder = NotificationCompat.Builder(context, ChannelIds.TIMER)
                .setSmallIcon(R.drawable.ic_timer_notification)
                .setContentTitle(context.getString(R.string.timer_done_title))
                .setContentText(context.getString(R.string.timer_done_body))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
            androidx.core.app.NotificationManagerCompat.from(context).notify(3101, builder.build())
        } catch (t: Throwable) {
            Log.w(TAG, "timer notification failed", t)
        }
    }

    object ChannelIds {
        const val ISLAND = "island_service"
        const val TIMER = "timer_events"
    }
}
