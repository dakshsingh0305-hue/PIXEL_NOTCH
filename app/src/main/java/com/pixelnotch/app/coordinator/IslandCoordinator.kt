package com.pixelnotch.app.coordinator

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Single source of truth that decides what the island displays.
 *
 * Every feature (media, battery, timers, notifications) reports into this object;
 * observers (overlay pill view, in-app preview) receive [IslandState] updates.
 *
 * Transient pop-ups:
 *  - a pop-up is shown for [PixelNotchSettings.popupDurationMs], then the island
 *    automatically falls back to the persisted state (music if playing, else
 *    charging/idle) so the underlying music card is never lost or restarted.
 *  - only strictly higher priority events may interrupt an active pop-up;
 *    equal/lower priority events are queued or debounced instead of colliding.
 */
object IslandCoordinator {

    private const val TAG = "IslandCoordinator"

    enum class IslandType {
        IDLE,
        MUSIC,
        CHARGING,
        TRACK_CHANGE,
        NOTIFICATION,
        CALL,
        TIMER_RUNNING,
        TIMER_DONE
    }

    data class TrackInfo(
        val title: String?,
        val artist: String?,
        val album: String?,
        val artwork: Bitmap?,
        val playing: Boolean,
        val playerPackage: String?,
        val canPlayPause: Boolean,
        val canPrevious: Boolean,
        val canNext: Boolean
    )

    data class BatteryInfo(
        val plugged: Boolean,
        val charging: Boolean,
        val levelPercent: Int
    )

    data class TimerInfo(
        val label: String,
        val remainingMs: Long,
        val totalMs: Long
    )

    data class NotificationInfo(
        val appName: String,
        val title: String?,
        val text: String?,
        val icon: Drawable?,
        val isCall: Boolean,
        /** Stable key so the system can hide a pop-up when its notification is removed. */
        val key: String
    )

    data class IslandState(
        val type: IslandType,
        val popup: Boolean,
        val track: TrackInfo?,
        val battery: BatteryInfo?,
        val timer: TimerInfo?,
        val notification: NotificationInfo?
    )

    interface Observer {
        fun onIslandStateChanged(state: IslandState)
    }

    // ------------------------------------------------------------------ input state
    @Volatile var currentTrack: TrackInfo? = null
        private set
    @Volatile var currentBattery: BatteryInfo? = null
        private set
    @Volatile var currentTimer: TimerInfo? = null
        private set
    @Volatile var visualizerReal: Boolean = false
        private set

    private var lastShownTrackKey: String? = null

    // ------------------------------------------------------------------ pop-up engine
    private data class ActivePopup(
        val type: IslandType,
        val priority: Int,
        val payloadTrack: TrackInfo?,
        val payloadBattery: BatteryInfo?,
        val payloadNotification: NotificationInfo?,
        val payloadTimer: TimerInfo?,
        val startedAt: Long,
        val expiresAt: Long
    )

    private var activePopup: ActivePopup? = null
    private val notificationQueue = ArrayDeque<NotificationInfo>()
    private var expiryRunnable: Runnable? = null

    private var lastPopupAtByType: HashMap<IslandType, Long> = HashMap()

    private val handler = Handler(Looper.getMainLooper())
    private val observers = mutableListOf<Observer>()

    fun addObserver(observer: Observer) {
        synchronized(observers) { if (!observers.contains(observer)) observers.add(observer) }
        handler.post { observer.onIslandStateChanged(computeState()) }
    }

    fun removeObserver(observer: Observer) {
        synchronized(observers) { observers.remove(observer) }
    }

    // ------------------------------------------------------------------ media input
    fun onTrack(info: TrackInfo?) {
        handler.post {
            currentTrack = info
            if (info != null && info.playing) {
                val key = (info.title ?: "") + "|" + (info.artist ?: "")
                if (key != lastShownTrackKey && lastShownTrackKey != null) {
                    // different track started playing -> pop-up (gated by settings)
                    if (PixelNotchSettingsProxy.trackChangePopupsEnabled) {
                        presentPopup(
                            type = IslandType.TRACK_CHANGE,
                            priority = PRI_TRACK_CHANGE,
                            track = info,
                            durationMs = popupDuration()
                        )
                    }
                } else if (lastShownTrackKey == null && PixelNotchSettingsProxy.trackChangePopupsEnabled) {
                    // first track observed while a user might care: treat as track change
                    presentPopup(
                        type = IslandType.TRACK_CHANGE,
                        priority = PRI_TRACK_CHANGE,
                        track = info,
                        durationMs = popupDuration() / 2
                    )
                }
                lastShownTrackKey = key
            }
            recompute()
        }
    }

    // ------------------------------------------------------------------ battery input
    fun onBattery(info: BatteryInfo) {
        handler.post {
            val before = currentBattery?.plugged
            currentBattery = info
            if (before != null && before != info.plugged && PixelNotchSettingsProxy.chargingEventsEnabled) {
                presentPopup(
                    type = IslandType.CHARGING,
                    priority = PRI_CHARGING,
                    battery = info,
                    durationMs = popupDuration()
                )
            }
            recompute()
        }
    }

    // ------------------------------------------------------------------ notifications
    fun onNotification(info: NotificationInfo) {
        handler.post {
            if (!PixelNotchSettingsProxy.notificationEventsEnabled) return@post
            val priority = if (info.isCall) PRI_CALL else PRI_NOTIFICATION
            val active = activePopup
            when {
                active == null || priority > active.priority ->
                    presentPopup(
                        type = IslandType.CALL.takeIf { info.isCall } ?: IslandType.NOTIFICATION,
                        priority = priority,
                        notification = info,
                        durationMs = popupDuration()
                    )
                active.type == IslandType.NOTIFICATION || active.type == IslandType.CALL -> {
                    // equal priority notification in flight: debounce quick repeats,
                    // otherwise queue the latest one for after the current pop-up ends
                    val now = System.currentTimeMillis()
                    val started = active.startedAt
                    if (now - started < DEBOUNCE_MS) {
                        queueOrReplace(info)
                    } else {
                        // remember at most two queued items
                        if (notificationQueue.size >= 2) notificationQueue.removeFirst()
                        notificationQueue.addLast(info)
                    }
                }
                else -> queueOrReplace(info)
            }
            recompute()
        }
    }

    /** The responsible part of dismissing pop-ups whose notification was removed. */
    fun onNotificationRemoved(key: String) {
        handler.post {
            notificationQueue.removeAll { it.key == key }
            val active = activePopup
            if (active != null && active.payloadNotification?.key == key) {
                clearActivePopup()
                serveQueue()
            }
            recompute()
        }
    }

    // ------------------------------------------------------------------ timers
    fun onTimerUpdated(info: TimerInfo?) {
        handler.post {
            currentTimer = info
            recompute()
        }
    }

    fun onTimerFine(info: TimerInfo, done: Boolean) {
        handler.post {
            currentTimer = info
            if (done) {
                if (PixelNotchSettingsProxy.timerPopupsEnabled) {
                    presentPopup(
                        type = IslandType.TIMER_DONE,
                        priority = PRI_TIMER_DONE,
                        timer = info,
                        durationMs = (popupDuration() * 1.6f).toLong().coerceAtLeast(6000L)
                    )
                }
            }
            recompute()
        }
    }

    // ------------------------------------------------------------------ visualizer
    /** Publishers (overlay service or in-app preview) announce live band data. */
    @Volatile
    var lastBands: FloatArray? = null
        private set

    interface BandListener {
        fun onBandsUpdated(bands: FloatArray, isReal: Boolean)
    }

    private val bandListeners = mutableListOf<BandListener>()

    fun addBandListener(listener: BandListener) {
        synchronized(bandListeners) { if (!bandListeners.contains(listener)) bandListeners.add(listener) }
    }

    fun removeBandListener(listener: BandListener) {
        synchronized(bandListeners) { bandListeners.remove(listener) }
    }

    fun publishBands(bands: FloatArray, isReal: Boolean) {
        lastBands = bands
        visualizerReal = isReal
        synchronized(bandListeners) {
            for (i in bandListeners.indices) {
                try {
                    bandListeners[i].onBandsUpdated(bands, isReal)
                } catch (t: Throwable) {
                    Log.w(TAG, "band listener failed", t)
                }
            }
        }
    }

    fun setVisualizerReal(real: Boolean) {
        handler.post {
            visualizerReal = real
            recompute()
        }
    }

    // ------------------------------------------------------------------ pop-up plumbing
    private fun presentPopup(
        type: IslandType,
        priority: Int,
        track: TrackInfo? = null,
        battery: BatteryInfo? = null,
        notification: NotificationInfo? = null,
        timer: TimerInfo? = null,
        durationMs: Long
    ) {
        val now = System.currentTimeMillis()
        val last = lastPopupAtByType[type]
        if (last != null && now - last < DEBOUNCE_MS) return // suppress rapid repeats
        lastPopupAtByType[type] = now

        val popup = ActivePopup(type, priority, track, battery, notification, timer, now, now + durationMs)
        activePopup = popup
        scheduleExpiry(popup)
        recompute()
    }

    private fun scheduleExpiry(popup: ActivePopup) {
        expiryRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            if (activePopup === popup) {
                clearActivePopup()
                serveQueue()
                recompute()
            }
        }
        expiryRunnable = runnable
        handler.postDelayed(runnable, (popup.expiresAt - popup.startedAt).coerceAtLeast(500L))
    }

    private fun clearActivePopup() {
        activePopup = null
        expiryRunnable?.let { handler.removeCallbacks(it) }
        expiryRunnable = null
    }

    private fun queueOrReplace(info: NotificationInfo) {
        if (notificationQueue.size >= 2) notificationQueue.removeFirst()
        notificationQueue.addLast(info)
    }

    private fun serveQueue() {
        val next = notificationQueue.removeFirstOrNull() ?: return
        presentPopup(
            type = IslandType.CALL.takeIf { next.isCall } ?: IslandType.NOTIFICATION,
            priority = if (next.isCall) PRI_CALL else PRI_NOTIFICATION,
            notification = next,
            durationMs = popupDuration()
        )
    }

    /** Manually dismiss the active pop-up (e.g. user tapped it). */
    fun dismissActivePopup() {
        handler.post {
            clearActivePopup()
            serveQueue()
            recompute()
        }
    }

    // ------------------------------------------------------------------ compute
    private fun popupDuration(): Long {
        // Sourced via proxy to avoid holding a Context reference inside the object.
        return PixelNotchSettingsProxy.popupDurationMs
    }

    fun computeState(): IslandState {
        val now = System.currentTimeMillis()
        val popup = activePopup
        if (popup != null && now < popup.expiresAt) {
            return IslandState(
                type = popup.type,
                popup = true,
                track = popup.payloadTrack ?: currentTrack,
                battery = popup.payloadBattery ?: currentBattery,
                timer = popup.payloadTimer,
                notification = popup.payloadNotification
            )
        }
        if (popup != null && now >= popup.expiresAt) {
            // expired but runnable hasn't fired yet; treat as no pop-up
            clearActivePopup()
            serveQueue()
        }

        if (currentTimer != null && PixelNotchSettingsProxy.timerPopupsEnabled) {
            return IslandState(IslandType.TIMER_RUNNING, false, currentTrack, currentBattery, currentTimer, null)
        }

        val track = currentTrack
        if (track != null && track.playing && PixelNotchSettingsProxy.musicIntegrationEnabled) {
            return IslandState(IslandType.MUSIC, false, track, currentBattery, null, null)
        }

        val battery = currentBattery
        if (battery != null && battery.plugged && PixelNotchSettingsProxy.chargingEventsEnabled) {
            return IslandState(IslandType.CHARGING, false, track, battery, null, null)
        }

        if (track != null && PixelNotchSettingsProxy.musicIntegrationEnabled) {
            return IslandState(IslandType.MUSIC, false, track, currentBattery, null, null)
        }

        return IslandState(IslandType.IDLE, false, null, currentBattery, null, null)
    }

    private fun recompute() {
        val state = computeState()
        notifyObserves(state)
    }

    private fun notifyObserves(state: IslandState) {
        synchronized(observers) {
            for (i in observers.indices) {
                try {
                    observers[i].onIslandStateChanged(state)
                } catch (t: Throwable) {
                    Log.w(TAG, "observer failed", t)
                }
            }
        }
    }

    // priorities (higher interrupts lower)
    const val PRI_CALL = 6
    const val PRI_TIMER_DONE = 5
    const val PRI_CHARGING = 4
    const val PRI_TRACK_CHANGE = 3
    const val PRI_NOTIFICATION = 2
    private const val DEBOUNCE_MS = 1200L
}
