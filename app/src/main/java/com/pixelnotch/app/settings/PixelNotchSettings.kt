package com.pixelnotch.app.settings

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log

/**
 * Single source of truth for all PIXEL_NOTCH user preferences.
 * Backed by SharedPreferences (simplest storage compatible with this project's
 * dependency set). All property writes broadcast to registered listeners so the
 * overlay, main screen and settings screen stay in sync live.
 */
class PixelNotchSettings private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    enum class ThemeMode { SYSTEM, DARK, LIGHT }
    enum class VisualizerStyle { BARS, WAVE, BOUNCY, MINIMAL }
    enum class AnimationIntensity { FULL, REDUCED, OFF }

    interface Listener {
        /** Called on the main thread whenever any preference changes. */
        fun onSettingChanged(key: String)
    }

    private val listeners = mutableListOf<Listener>()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    // ---------------------------------------------------------------- overlay
    var overlayServiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY_ENABLED, false)
        set(value) = putBoolean(KEY_OVERLAY_ENABLED, value)

    // ------------------------------------------------------------------ music
    var musicIntegrationEnabled: Boolean
        get() = prefs.getBoolean(KEY_MUSIC_ENABLED, true)
        set(value) = putBoolean(KEY_MUSIC_ENABLED, value)

    var trackChangePopupsEnabled: Boolean
        get() = prefs.getBoolean(KEY_TRACK_CHANGE_POPUPS, true)
        set(value) = putBoolean(KEY_TRACK_CHANGE_POPUPS, value)

    // -------------------------------------------------------------- visualizer
    var visualizerEnabled: Boolean
        get() = prefs.getBoolean(KEY_VISUALIZER_ENABLED, true)
        set(value) = putBoolean(KEY_VISUALIZER_ENABLED, value)

    var visualizerStyle: VisualizerStyle
        get() = runCatching { VisualizerStyle.valueOf(prefs.getString(KEY_VISUALIZER_STYLE, VisualizerStyle.BARS.name)!!) }
            .getOrDefault(VisualizerStyle.BARS)
        set(value) = putString(KEY_VISUALIZER_STYLE, value.name)

    /** Whether real (audio-reactive) capture should be attempted when permitted. */
    var preferAudioReactive: Boolean
        get() = prefs.getBoolean(KEY_PREFER_REAL_VISUALIZER, false)
        set(value) = putBoolean(KEY_PREFER_REAL_VISUALIZER, value)

    // -------------------------------------------------------------- battery
    var chargingEventsEnabled: Boolean
        get() = prefs.getBoolean(KEY_CHARGING_ENABLED, true)
        set(value) = putBoolean(KEY_CHARGING_ENABLED, value)

    // ---------------------------------------------------------------- notifications
    var notificationEventsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS_ENABLED, false)
        set(value) = putBoolean(KEY_NOTIFICATIONS_ENABLED, value)

    // ------------------------------------------------------------------ timers
    var timerPopupsEnabled: Boolean
        get() = prefs.getBoolean(KEY_TIMER_POPUPS, true)
        set(value) = putBoolean(KEY_TIMER_POPUPS, value)

    // ------------------------------------------------------------------ popups
    /** How long a transient pop-up stays on screen, in milliseconds. */
    var popupDurationMs: Long
        get() = prefs.getLong(KEY_POPUP_DURATION_MS, DEFAULT_POPUP_DURATION_MS)
            .coerceIn(MIN_POPUP_DURATION_MS, MAX_POPUP_DURATION_MS)
        set(value) = putLong(KEY_POPUP_DURATION_MS, value)

    // ------------------------------------------------------------------ styling
    var themeMode: ThemeMode
        get() = runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.DARK.name)!!) }
            .getOrDefault(ThemeMode.DARK)
        set(value) = putString(KEY_THEME, value.name)

    var animationIntensity: AnimationIntensity
        get() = runCatching { AnimationIntensity.valueOf(prefs.getString(KEY_ANIMATION, AnimationIntensity.FULL.name)!!) }
            .getOrDefault(AnimationIntensity.FULL)
        set(value) = putString(KEY_ANIMATION, value.name)

    /**
     * Motion multiplier [0..1] combining the in-app animation intensity with the
     * system "remove animations" accessibility setting. 0 => no animation.
     */
    fun effectiveMotionFactor(context: Context): Float {
        val systemScale = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
        if (systemScale <= 0.05f) return 0f // system-wide "remove animations"
        val appScale = when (animationIntensity) {
            AnimationIntensity.FULL -> 1f
            AnimationIntensity.REDUCED -> 0.55f
            AnimationIntensity.OFF -> 0f
        }
        return appScale * systemScale.coerceIn(0.7f, 1f)
    }

    // ------------------------------------------------------------------ reset
    fun resetToDefaults(context: Context) {
        prefs.edit().clear().apply()
        notifyListeners("*")
    }

    // -------------------------------------------------------------- plumbing
    private fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
        notifyListeners(key)
    }

    private fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
        notifyListeners(key)
    }

    private fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
        notifyListeners(key)
    }

    @Synchronized
    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    @Synchronized
    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    @Synchronized
    private fun notifyListeners(key: String) {
        val snapshot = listeners.toList()
        handler.post {
            for (i in snapshot.indices) {
                try {
                    snapshot[i].onSettingChanged(key)
                } catch (t: Throwable) {
                    Log.w(TAG, "settings listener failed", t)
                }
            }
        }
    }

    companion object {
        private const val TAG = "PixelNotchSettings"
        private const val PREFS_FILE = "pixelnotch_prefs"

        const val KEY_OVERLAY_ENABLED = "overlay.enabled"
        const val KEY_MUSIC_ENABLED = "music.enabled"
        const val KEY_TRACK_CHANGE_POPUPS = "music.trackChangePopups"
        const val KEY_VISUALIZER_ENABLED = "visualizer.enabled"
        const val KEY_VISUALIZER_STYLE = "visualizer.style"
        const val KEY_PREFER_REAL_VISUALIZER = "visualizer.preferReal"
        const val KEY_CHARGING_ENABLED = "charging.enabled"
        const val KEY_NOTIFICATIONS_ENABLED = "notifications.enabled"
        const val KEY_TIMER_POPUPS = "timer.popups"
        const val KEY_POPUP_DURATION_MS = "popup.durationMs"
        const val KEY_THEME = "ui.theme"
        const val KEY_ANIMATION = "ui.animation"

        const val DEFAULT_POPUP_DURATION_MS = 5000L
        const val MIN_POPUP_DURATION_MS = 2000L
        const val MAX_POPUP_DURATION_MS = 10000L

        @Volatile
        private var instance: PixelNotchSettings? = null

        fun get(context: Context): PixelNotchSettings =
            instance ?: synchronized(this) {
                instance ?: PixelNotchSettings(context).also { instance = it }
            }

        /** User-facing label used in UI copy for each style. */
        fun styleLabel(style: VisualizerStyle): String = when (style) {
            VisualizerStyle.BARS -> "Classic bars"
            VisualizerStyle.WAVE -> "Smooth wave"
            VisualizerStyle.BOUNCY -> "Bouncing bars"
            VisualizerStyle.MINIMAL -> "Minimal pulse"
        }
    }
}
