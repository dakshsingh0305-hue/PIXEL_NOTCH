package com.pixelnotch.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.pixelnotch.app.audio.VisualizerEngine
import com.pixelnotch.app.battery.BatteryStateReceiver
import com.pixelnotch.app.coordinator.IslandCoordinator
import com.pixelnotch.app.media.MediaSessionTracker
import com.pixelnotch.app.service.IslandOverlayService
import com.pixelnotch.app.settings.PixelNotchSettings
import com.pixelnotch.app.timer.TimerController
import com.pixelnotch.app.ui.EqualizerView
import com.pixelnotch.app.ui.IslandPillView
import com.pixelnotch.app.ui.SettingsActivity

/**
 * Main screen: live island preview, overlay start/stop, permission setup,
 * music preview with visualizer feedback, and quick timers. Everything remains
 * functional even before any permission is granted.
 */
class MainActivity : androidx.appcompat.app.AppCompatActivity() {

    private val settings by lazy { PixelNotchSettings.get(this) }

    private var pillPreview: IslandPillView? = null
    private var equalizer: EqualizerView? = null
    private var audioEngine: VisualizerEngine? = null
    private var mediaTracker: MediaSessionTracker? = null
    private var batteryReceiver: BatteryStateReceiver? = null

    private var statusDot: View? = null
    private var statusText: TextView? = null
    private var toggleButton: MaterialButton? = null
    private var btnPlayPause: ImageButton? = null

    private val mediaComponent by lazy {
        android.content.ComponentName(this, com.pixelnotch.app.media.PixelNotchNotificationListener::class.java)
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root_main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        bindViews()
        wireActions()
    }

    override fun onResume() {
        super.onResume()
        batteryReceiver = BatteryStateReceiver { }.also { it.register(this) }
        ensureMediaTracker()
        IslandCoordinator.addObserver(coordinatorObserver)
        refreshUi()
        handleEngine()
        pillPreview?.refreshConfiguration()
    }

    override fun onPause() {
        super.onPause()
        batteryReceiver?.unregister(this)
        batteryReceiver = null
        IslandCoordinator.removeObserver(coordinatorObserver)
        equalizer?.setAnimating(false)
        audioEngine?.stop()
    }

    override fun onDestroy() {
        mediaTracker?.stop()
        mediaTracker = null
        audioEngine?.stop()
        audioEngine = null
        super.onDestroy()
    }

    private fun bindViews() {
        pillPreview = findViewById(R.id.island_preview)
        equalizer = findViewById(R.id.equalizer)
        statusDot = findViewById(R.id.status_dot)
        statusText = findViewById(R.id.txt_status)
        toggleButton = findViewById(R.id.btn_toggle_island)
        btnPlayPause = findViewById(R.id.btn_playpause)
    }

    private fun wireActions() {
        pillPreview?.mediaCommandListener = { action -> sendMediaCommand(action) }

        findViewById<MaterialButton>(R.id.btn_toggle_island).setOnClickListener { toggleOverlay() }
        findViewById<ImageButton>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btn_playpause).setOnClickListener { sendMediaCommand(IslandPillView.MediaAction.PLAY_PAUSE) }
        findViewById<ImageButton>(R.id.btn_next).setOnClickListener { sendMediaCommand(IslandPillView.MediaAction.NEXT) }
        findViewById<ImageButton>(R.id.btn_prev).setOnClickListener { sendMediaCommand(IslandPillView.MediaAction.PREVIOUS) }

        findViewById<TextView>(R.id.btn_overlay_grant).setOnClickListener { requestOverlayPermission() }
        findViewById<TextView>(R.id.btn_notif_grant).setOnClickListener { requestNotificationAccess() }
        findViewById<TextView>(R.id.btn_mic_grant).setOnClickListener { requestMicrophone() }

        findViewById<Chip>(R.id.chip_timer_1).setOnClickListener { startTimer(1) }
        findViewById<Chip>(R.id.chip_timer_3).setOnClickListener { startTimer(3) }
        findViewById<Chip>(R.id.chip_timer_5).setOnClickListener { startTimer(5) }
        findViewById<Chip>(R.id.chip_timer_10).setOnClickListener { startTimer(10) }
        findViewById<TextView>(R.id.btn_timer_cancel).setOnClickListener {
            TimerController.cancel()
            refreshUi()
        }
    }

    private fun startTimer(minutes: Int) {
        TimerController.start(this, minutes)
        refreshUi()
    }

    // ------------------------------------------------------------------ actions

    private fun toggleOverlay() {
        val currentlyWant = settings.overlayServiceEnabled
        if (currentlyWant) {
            settings.overlayServiceEnabled = false
            IslandOverlayService.stop(this)
            refreshUi()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS
            )
            // still start the service: the FGS itself is fine even without the
            // notification drawer entry, and we avoid blocking the user action
        }
        settings.overlayServiceEnabled = true
        IslandOverlayService.start(this)
        refreshUi()
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "overlay settings intent unavailable", t)
        }
    }

    private fun requestNotificationAccess() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (t: Throwable) {
            Log.w(TAG, "notification listener settings unavailable", t)
        }
    }

    private fun requestMicrophone() {
        settings.preferAudioReactive = true
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        refreshUi()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            if (!granted) settings.preferAudioReactive = false
            refreshUi()
            handleEngine()
        }
        if (requestCode == REQ_NOTIFICATIONS) {
            refreshUi()
        }
    }

    private fun sendMediaCommand(action: IslandPillView.MediaAction) {
        mediaTracker?.sendCommand(action)
    }

    // ------------------------------------------------------------------ engine/media

    private fun ensureMediaTracker() {
        if (!PixelNotchSettings.get(this).musicIntegrationEnabled) {
            return
        }
        if (IslandOverlayService.notificationListenerAccessGranted(this) && mediaTracker == null) {
            mediaTracker = MediaSessionTracker(applicationContext, mediaComponent).also { it.start() }
        }
    }

    private fun handleEngine() {
        val engine = audioEngine ?: VisualizerEngine(applicationContext, ownerToken = this).also {
            it.listener = engineListener
            audioEngine = it
        }
        val playing = IslandCoordinator.currentTrack?.playing == true
        if (playing && settings.visualizerEnabled) engine.start() else engine.stop()
    }

    private val engineListener = object : VisualizerEngine.Listener {
        override fun onEngineStatus(real: Boolean, running: Boolean) {
            runOnUiThread {
                findViewById<TextView>(R.id.chip_visualizer_mode).also { chip ->
                    chip.text = when {
                        !running && IslandCoordinator.currentTrack?.playing != true -> getString(R.string.visualizer_mode_idle)
                        real -> getString(R.string.visualizer_mode_real)
                        else -> getString(R.string.visualizer_mode_simulated)
                    }
                }
                IslandCoordinator.setVisualizerReal(real && running)
            }
        }

        override fun onBands(bands: FloatArray, real: Boolean) = Unit
    }

    // ------------------------------------------------------------------ ui refresh

    private val coordinatorObserver = object : IslandCoordinator.Observer {
        override fun onIslandStateChanged(state: IslandCoordinator.IslandState) {
            runOnUiThread {
                updateMusicCard(state)
                updateTimerCard(state)
                handleEngine()
            }
        }
    }

    private fun refreshUi() {
        val overlayOn = settings.overlayServiceEnabled && Settings.canDrawOverlays(this)
        statusDot?.setBackgroundResource(
            when {
                overlayOn -> R.drawable.dot_green
                Settings.canDrawOverlays(this) -> R.drawable.dot_orange
                else -> R.drawable.dot_grey
            }
        )
        statusText?.text = when {
            overlayOn -> getString(R.string.status_on)
            Settings.canDrawOverlays(this) -> getString(R.string.status_ready)
            else -> getString(R.string.status_off)
        }
        toggleButton?.text = getString(
            if (settings.overlayServiceEnabled) R.string.action_stop_island else R.string.action_start_island
        )

        val overlayGranted = Settings.canDrawOverlays(this)
        findViewById<TextView>(R.id.txt_overlay_status).text = getString(
            if (overlayGranted) R.string.perm_status_granted else R.string.perm_status_missing
        )

        val notifGranted = IslandOverlayService.notificationListenerAccessGranted(this)
        findViewById<TextView>(R.id.txt_notif_status).text = getString(
            if (notifGranted) R.string.perm_status_granted else R.string.perm_status_missing
        )

        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        findViewById<TextView>(R.id.txt_mic_status).text = when {
            micGranted && settings.preferAudioReactive -> getString(R.string.mic_status_reactive)
            micGranted -> getString(R.string.mic_status_granted)
            else -> getString(R.string.mic_status_opt_in)
        }

        updateMusicCard(IslandCoordinator.computeState())
        updateTimerCard(IslandCoordinator.computeState())
    }

    private fun updateMusicCard(state: IslandCoordinator.IslandState) {
        val track = state.track
        val title = findViewById<TextView>(R.id.txt_track_title)
        val artist = findViewById<TextView>(R.id.txt_track_artist)
        val artworkView = findViewById<ImageView>(R.id.img_artwork)

        if (track == null) {
            title.text = getString(R.string.nothing_playing)
            artist.text = getString(R.string.nothing_playing_hint)
            artworkView.setImageResource(R.drawable.ic_note)
            artworkView.scaleType = ImageView.ScaleType.CENTER
            equalizer?.setAnimating(false)
            btnPlayPause?.setImageResource(R.drawable.ic_media_play)
        } else {
            title.text = track.title ?: getString(R.string.unknown_title)
            artist.text = track.artist ?: getString(R.string.unknown_artist)
            val art: Bitmap? = track.artwork
            if (art != null) {
                artworkView.setImageBitmap(art)
                artworkView.scaleType = ImageView.ScaleType.CENTER_CROP
            } else {
                artworkView.setImageResource(R.drawable.ic_note)
                artworkView.scaleType = ImageView.ScaleType.CENTER
            }
            btnPlayPause?.setImageResource(
                if (track.playing) R.drawable.ic_media_pause else R.drawable.ic_media_play
            )
            equalizer?.setAnimating(track.playing && settings.visualizerEnabled)
        }
        handleEngine()
    }

    private fun updateTimerCard(state: IslandCoordinator.IslandState) {
        val remainingView = findViewById<TextView>(R.id.txt_timer_remaining)
        val timer = state.timer
        if (timer == null) {
            remainingView.text = getString(R.string.timer_idle)
            return
        }
        val remainingSeconds = (timer.remainingMs / 1000).coerceAtLeast(0)
        val finished = remainingSeconds <= 0
        remainingView.text = if (finished) {
            getString(R.string.timer_done_label)
        } else {
            val minutes = remainingSeconds / 60
            val seconds = remainingSeconds % 60
            getString(R.string.timer_running_label, "%02d:%02d".format(minutes, seconds), timer.label)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        const val REQ_MIC = 4001
        const val REQ_NOTIFICATIONS = 4002
    }
}
