package com.pixelnotch.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.pixelnotch.app.MainActivity
import com.pixelnotch.app.R
import com.pixelnotch.app.audio.VisualizerEngine
import com.pixelnotch.app.battery.BatteryStateReceiver
import com.pixelnotch.app.coordinator.IslandCoordinator
import com.pixelnotch.app.media.MediaSessionTracker
import com.pixelnotch.app.settings.PixelNotchSettings
import com.pixelnotch.app.ui.IslandPillView

/**
 * Foreground service that hosts the Dynamic-Island overlay window.
 *
 * FGS compliance (targetSdk 37):
 *  - declared with `android:foregroundServiceType="specialUse"` plus the
 *    PROPERTY_SPECIAL_USE_FGS_SUBTYPE property (legitimate use: an island overlay
 *    the user enables explicitly from the foreground UI);
 *  - started ONLY from a foreground user interaction (MainActivity toggle) —
 *    specialUse does not grant background-start privileges;
 *  - shows a proper persistent notification on the dedicated channel;
 *  - cleans up the WindowManager view, receiver and engine on stop/restart.
 */
class IslandOverlayService : android.app.Service() {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var pillView: IslandPillView? = null
    private var engine: VisualizerEngine? = null
    private var mediaTracker: MediaSessionTracker? = null
    private var batteryReceiver: BatteryStateReceiver? = null
    private var overlayAttached = false

    private val windowManager: WindowManager by lazy {
        getSystemService(WINDOW_SERVICE) as WindowManager
    }

    private val settings: PixelNotchSettings by lazy { PixelNotchSettings.get(this) }

    private val settingsListener = object : PixelNotchSettings.Listener {
        override fun onSettingChanged(key: String) {
            mainHandler.post {
                pillView?.refreshConfiguration()
                handleEngine()
                if (key == PixelNotchSettings.KEY_OVERLAY_ENABLED && !settings.overlayServiceEnabled) {
                    stopSelf()
                }
            }
        }
    }

    private val coordinatorGate = object : IslandCoordinator.Observer {
        override fun onIslandStateChanged(state: IslandCoordinator.IslandState) {
            mainHandler.post { handleEngine() }
        }
    }

    private val engineListener = object : VisualizerEngine.Listener {
        override fun onEngineStatus(real: Boolean, running: Boolean) {
            IslandCoordinator.setVisualizerReal(real && running)
        }

        override fun onBands(bands: FloatArray, real: Boolean) = Unit // engine already publishes
    }

    private fun handleEngine() {
        val e = engine ?: return
        val shouldRun = settings.visualizerEnabled &&
            IslandCoordinator.currentTrack?.playing == true
        if (shouldRun) e.start() else e.stop()
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "service created")
        if (!Settings.canDrawOverlays(this)) {
            Log.i(TAG, "overlay permission revoked; stopping service")
            settings.overlayServiceEnabled = false
            stopSelf()
            return
        }

        startForegroundCompat()

        engine = VisualizerEngine(applicationContext, ownerToken = this, bandCount = IslandPillViewDefaultBands)
            .also { it.listener = engineListener }

        pillView = IslandPillView(this).also { view ->
            view.mediaCommandListener = { action ->
                mediaTracker?.sendCommand(action)
            }
        }

        if (notificationListenerAccessGranted(applicationContext)) {
            mediaTracker = MediaSessionTracker(applicationContext, listenerComponent).also { it.start() }
        }

        batteryReceiver = BatteryStateReceiver { }.also { it.register(applicationContext) }

        settings.addListener(settingsListener)
        IslandCoordinator.addObserver(coordinatorGate)

        attachOverlay()
        handleEngine()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                settings.overlayServiceEnabled = false
                stopSelf()
                return android.app.Service.START_NOT_STICKY
            }
            else -> Unit // normal start/restart; keep the overlay
        }
        return android.app.Service.START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "service destroyed; detaching overlay")
        settings.removeListener(settingsListener)
        IslandCoordinator.removeObserver(coordinatorGate)
        batteryReceiver?.unregister(applicationContext)
        batteryReceiver = null
        engine?.stop()
        engine = null
        mediaTracker?.stop()
        mediaTracker = null
        detachOverlay()
        pillView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------ overlay plumbing

    private fun attachOverlay() {
        val view = pillView ?: return
        if (overlayAttached) return
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.x = 0
        lp.y = topSafeOffsetPx()
        try {
            windowManager.addView(view, lp)
            overlayAttached = true
            Log.d(TAG, "overlay attached at y=${lp.y}")
        } catch (denied: SecurityException) {
            Log.w(TAG, "overlay permission missing", denied)
            settings.overlayServiceEnabled = false
            stopSelf()
        } catch (t: Throwable) {
            Log.w(TAG, "overlay attach failed", t)
            stopSelf()
        }
    }

    private fun detachOverlay() {
        val view = pillView ?: run { overlayAttached = false; return }
        if (!overlayAttached) return
        try {
            windowManager.removeView(view)
        } catch (t: Throwable) {
            Log.w(TAG, "overlay detach failed (view may already be detached)", t)
        }
        overlayAttached = false
    }

    /**
     * Vertical placement below the status bar & display cutout (Pixel 10a top
     * hole). Uses real window insets on modern Android, falls back to the
     * platform status-bar dimen resource.
     */
    private fun topSafeOffsetPx(): Int {
        val extra = resources.displayMetrics.density * 12f
        return try {
            if (Build.VERSION.SDK_INT >= 30) {
                val metrics = windowManager.maximumWindowMetrics
                val insetTop = metrics.windowInsets.getInsets(WindowInsets.Type.statusBars()).top
                insetTop + extra.toInt()
            } else {
                statusBarLegacyHeight() + extra.toInt()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "inset resolution failed; using legacy status bar height", t)
            statusBarLegacyHeight() + extra.toInt()
        }
    }

    private fun statusBarLegacyHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    // ------------------------------------------------------------------ FGS notification

    private fun startForegroundCompat() {
        val notification = buildServiceNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(SERVICE_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(SERVICE_NOTIFICATION_ID, notification)
        }
    }

    private fun buildServiceNotification(): Notification {
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 9001, contentIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, com.pixelnotch.app.timer.TimerController.ChannelIds.ISLAND)
            .setSmallIcon(R.drawable.ic_island_notification)
            .setContentTitle(getString(R.string.island_active_title))
            .setContentText(getString(R.string.island_active_body))
            .setContentIntent(pending)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "IslandOverlayService"
        const val ACTION_STOP = "com.pixelnotch.app.action.STOP_ISLAND"
        const val SERVICE_NOTIFICATION_ID = 9001

        /** Number of equalizer bands shared between overlay and preview. */
        const val IslandPillViewDefaultBands = 24

        private val listenerComponent: android.content.ComponentName
            get() = android.content.ComponentName(
                "com.pixelnotch.app",
                "com.pixelnotch.app.media.PixelNotchNotificationListener"
            )

        /** Whether the user granted notification access (needed for media sessions). */
        fun notificationListenerAccessGranted(context: android.content.Context): Boolean = try {
            val pkg = context.packageName
            androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(pkg)
        } catch (t: Throwable) {
            false
        }

        /** Foreground-safe entry point; call only while the app is in the foreground. */
        fun start(context: android.content.Context) {
            val intent = Intent(context, IslandOverlayService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                // e.g. ForegroundServiceStartNotAllowedException: honest log + honest UI stop
                Log.w(TAG, "could not start overlay service from background context", t)
                PixelNotchSettings.get(context).overlayServiceEnabled = false
            }
        }

        fun stop(context: android.content.Context) {
            try {
                context.stopService(Intent(context, IslandOverlayService::class.java))
            } catch (t: Throwable) {
                Log.w(TAG, "stopping service failed", t)
            }
        }
    }
}
