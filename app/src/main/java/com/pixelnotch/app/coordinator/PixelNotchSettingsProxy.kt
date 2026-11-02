package com.pixelnotch.app.coordinator

import com.pixelnotch.app.settings.PixelNotchSettings

/**
 * Bridge giving [IslandCoordinator] (a Context-free object) read access to the
 * live user preferences. [com.pixelnotch.app.PixelNotchApp] installs this once
 * at process start; until then safe defaults are used.
 */
object PixelNotchSettingsProxy {

    interface ReadPrefs {
        val popupDurationMs: Long
        val trackChangePopupsEnabled: Boolean
        val notificationEventsEnabled: Boolean
        val chargingEventsEnabled: Boolean
        val timerPopupsEnabled: Boolean
        val musicIntegrationEnabled: Boolean
        val visualizerEnabled: Boolean
    }

    @Volatile
    private var impl: ReadPrefs? = null

    fun install(impl: ReadPrefs) {
        this.impl = impl
    }

    private val s: ReadPrefs
        get() = impl ?: error("PixelNotchSettingsProxy not installed")

    val popupDurationMs: Long get() = runCatching { s.popupDurationMs }.getOrDefault(
        PixelNotchSettings.DEFAULT_POPUP_DURATION_MS
    )
    val trackChangePopupsEnabled: Boolean get() = runCatching { s.trackChangePopupsEnabled }.getOrDefault(true)
    val notificationEventsEnabled: Boolean get() = runCatching { s.notificationEventsEnabled }.getOrDefault(false)
    val chargingEventsEnabled: Boolean get() = runCatching { s.chargingEventsEnabled }.getOrDefault(true)
    val timerPopupsEnabled: Boolean get() = runCatching { s.timerPopupsEnabled }.getOrDefault(true)
    val musicIntegrationEnabled: Boolean get() = runCatching { s.musicIntegrationEnabled }.getOrDefault(true)
    val visualizerEnabled: Boolean get() = runCatching { s.visualizerEnabled }.getOrDefault(true)
}
