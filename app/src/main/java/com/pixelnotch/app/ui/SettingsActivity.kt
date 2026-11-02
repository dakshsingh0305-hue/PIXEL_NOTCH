package com.pixelnotch.app.ui

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.pixelnotch.app.PixelNotchApp
import com.pixelnotch.app.R
import com.pixelnotch.app.settings.PixelNotchSettings

/**
 * Full settings control: overlay/music/notifications/charging/timer toggles,
 * pop-up duration, visualizer style and real-audio preference, theme and motion,
 * plus reset-to-defaults.
 */
class SettingsActivity : androidx.appcompat.app.AppCompatActivity() {

    private val settings by lazy { PixelNotchSettings.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        bindAll()
    }

    // ------------------------------------------------------------------ binding
    private fun bindSwitch(id: Int, getter: () -> Boolean, setter: (Boolean) -> Unit) {
        val sw = findViewById<MaterialSwitch>(id)
        sw.isChecked = getter()
        sw.setOnCheckedChangeListener { _, checked -> setter(checked) }
    }

    private fun bindAll() {
        bindSwitch(R.id.sw_overlay, { settings.overlayServiceEnabled }, { settings.overlayServiceEnabled = it })
        bindSwitch(R.id.sw_music, { settings.musicIntegrationEnabled }, { settings.musicIntegrationEnabled = it })
        bindSwitch(R.id.sw_track_popups, { settings.trackChangePopupsEnabled }, { settings.trackChangePopupsEnabled = it })
        bindSwitch(R.id.sw_visualizer, { settings.visualizerEnabled }, { settings.visualizerEnabled = it })
        bindSwitch(R.id.sw_charging, { settings.chargingEventsEnabled }, { settings.chargingEventsEnabled = it })
        bindSwitch(R.id.sw_notifications, { settings.notificationEventsEnabled }, { settings.notificationEventsEnabled = it })
        bindSwitch(R.id.sw_timer, { settings.timerPopupsEnabled }, { settings.timerPopupsEnabled = it })
        bindSwitch(R.id.sw_audio_reactive, { settings.preferAudioReactive }, { settings.preferAudioReactive = it })

        val durationLabel = findViewById<TextView>(R.id.txt_duration_value)
        val slider = findViewById<Slider>(R.id.slider_duration)
        slider.value = (settings.popupDurationMs / 1000f).coerceIn(2f, 10f)
        durationLabel.text =
            getString(R.string.duration_value, (settings.popupDurationMs / 1000L).toString())
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                settings.popupDurationMs = (value * 1000).toLong()
                durationLabel.text = getString(R.string.duration_value, value.toInt().toString())
            }
        }

        bindStyleChips()
        bindThemeChips()
        bindMotionChips()

        findViewById<MaterialButton>(R.id.btn_reset).setOnClickListener {
            settings.resetToDefaults(this)
            Toast.makeText(this, R.string.reset_done, Toast.LENGTH_SHORT).show()
            recreate()
        }
    }

    private fun bindStyleChips() {
        val group = findViewById<ChipGroup>(R.id.chipgroup_style)
        when (settings.visualizerStyle) {
            PixelNotchSettings.VisualizerStyle.BARS -> group.check(R.id.chip_style_bars)
            PixelNotchSettings.VisualizerStyle.WAVE -> group.check(R.id.chip_style_wave)
            PixelNotchSettings.VisualizerStyle.BOUNCY -> group.check(R.id.chip_style_bouncy)
            PixelNotchSettings.VisualizerStyle.MINIMAL -> group.check(R.id.chip_style_minimal)
        }
        group.setOnCheckedStateChangeListener { _, checkedIds ->
            val style = when (checkedIds.firstOrNull()) {
                R.id.chip_style_wave -> PixelNotchSettings.VisualizerStyle.WAVE
                R.id.chip_style_bouncy -> PixelNotchSettings.VisualizerStyle.BOUNCY
                R.id.chip_style_minimal -> PixelNotchSettings.VisualizerStyle.MINIMAL
                else -> PixelNotchSettings.VisualizerStyle.BARS
            }
            settings.visualizerStyle = style
        }
    }

    private fun bindThemeChips() {
        val group = findViewById<ChipGroup>(R.id.chipgroup_theme)
        when (settings.themeMode) {
            PixelNotchSettings.ThemeMode.SYSTEM -> group.check(R.id.chip_theme_system)
            PixelNotchSettings.ThemeMode.DARK -> group.check(R.id.chip_theme_dark)
            PixelNotchSettings.ThemeMode.LIGHT -> group.check(R.id.chip_theme_light)
        }
        group.setOnCheckedStateChangeListener { _, checkedIds ->
            val mode = when (checkedIds.firstOrNull()) {
                R.id.chip_theme_light -> PixelNotchSettings.ThemeMode.LIGHT
                R.id.chip_theme_dark -> PixelNotchSettings.ThemeMode.DARK
                else -> PixelNotchSettings.ThemeMode.SYSTEM
            }
            settings.themeMode = mode
            (application as PixelNotchApp).applyNightMode(mode)
        }
    }

    private fun bindMotionChips() {
        val group = findViewById<ChipGroup>(R.id.chipgroup_motion)
        when (settings.animationIntensity) {
            PixelNotchSettings.AnimationIntensity.FULL -> group.check(R.id.chip_motion_full)
            PixelNotchSettings.AnimationIntensity.REDUCED -> group.check(R.id.chip_motion_reduced)
            PixelNotchSettings.AnimationIntensity.OFF -> group.check(R.id.chip_motion_off)
        }
        group.setOnCheckedStateChangeListener { _, checkedIds ->
            val intensity = when (checkedIds.firstOrNull()) {
                R.id.chip_motion_reduced -> PixelNotchSettings.AnimationIntensity.REDUCED
                R.id.chip_motion_off -> PixelNotchSettings.AnimationIntensity.OFF
                else -> PixelNotchSettings.AnimationIntensity.FULL
            }
            settings.animationIntensity = intensity
        }
    }
}
