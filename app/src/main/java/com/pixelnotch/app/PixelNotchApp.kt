package com.pixelnotch.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.pixelnotch.app.coordinator.PixelNotchSettingsProxy
import com.pixelnotch.app.settings.PixelNotchSettings
import com.pixelnotch.app.service.IslandOverlayService
import com.pixelnotch.app.timer.TimerController

class PixelNotchApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val settings = PixelNotchSettings.get(this)
        installSettingsProxy(settings)
        applyNightMode(settings.themeMode)
        createNotificationChannels()
    }

    /** Bridges the Context-free coordinator to live preference values. */
    private fun installSettingsProxy(settings: PixelNotchSettings) {        PixelNotchSettingsProxy.install(object : PixelNotchSettingsProxy.ReadPrefs {
            override val popupDurationMs: Long get() = settings.popupDurationMs
            override val trackChangePopupsEnabled: Boolean get() = settings.trackChangePopupsEnabled
            override val notificationEventsEnabled: Boolean get() = settings.notificationEventsEnabled
            override val chargingEventsEnabled: Boolean get() = settings.chargingEventsEnabled
            override val timerPopupsEnabled: Boolean get() = settings.timerPopupsEnabled
            override val musicIntegrationEnabled: Boolean get() = settings.musicIntegrationEnabled
            override val visualizerEnabled: Boolean get() = settings.visualizerEnabled
        })
    }

    fun applyNightMode(mode: PixelNotchSettings.ThemeMode) {
        val nightMode = when (mode) {
            PixelNotchSettings.ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            PixelNotchSettings.ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            PixelNotchSettings.ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            val island = NotificationChannel(
                TimerController.ChannelIds.ISLAND,
                getString(R.string.channel_island),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_island_desc)
                setShowBadge(false)
            }
            val timer = NotificationChannel(
                TimerController.ChannelIds.TIMER,
                getString(R.string.channel_timer),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.channel_timer_desc)
            }
            manager.createNotificationChannel(island)
            manager.createNotificationChannel(timer)
        } catch (t: Throwable) {
            Log.w("PixelNotchApp", "channel creation failed", t)
        }
    }
}
