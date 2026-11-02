package com.pixelnotch.app.audio

import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import androidx.core.content.ContextCompat
import com.pixelnotch.app.coordinator.IslandCoordinator
import com.pixelnotch.app.settings.PixelNotchSettings
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.sin

/**
 * Produces normalized band amplitudes [0..1] for the island equalizer and the
 * in-app preview.
 *
 * Two sources:
 *  1. REAL (best-effort): [Visualizer] attached to the audio output mix. Requires
 *     RECORD_AUDIO (runtime) + MODIFY_AUDIO_SETTINGS, and actual support on the
 *     device. If attaching or enabling fails, the engine automatically falls back
 *     to the SIMULATED source and reports the honest mode via callbacks.
 *  2. SIMULATED: smooth synthetic band motion that looks convincing without any
 *     audio access. Works everywhere.
 *
 * Only one engine runs at a time ([VisualizerSlot]); the idle one simply listens
 * to band data published through [IslandCoordinator].
 */
class VisualizerEngine(
    private val context: Context,
    private val ownerToken: Any,
    private val bandCount: Int = DEFAULT_BANDS
) {

    interface Listener {
        /** `real` reports what is genuinely active; `running` whether engine produces frames. */
        fun onEngineStatus(real: Boolean, running: Boolean)

        /** New frame of normalized band amplitudes. */
        fun onBands(bands: FloatArray, real: Boolean)
    }

    var listener: Listener? = null

    private val handler = Handler(Looper.getMainLooper())
    private val bands = FloatArray(bandCount)
    private val peakEnvelope = FloatArray(bandCount)
    private var visualizer: Visualizer? = null
    private var running: Boolean = false
    private var realMode: Boolean = false
    private var lastPublishAt: Long = 0L

    // ------------------------------------------------------------------ lifecycle

    fun start() {
        if (running) return
        if (!VisualizerSlot.acquire(ownerToken)) {
            // another visualizer owns the slot; we only forward its published bands
            listener?.onEngineStatus(real = false, running = false)
            return
        }
        running = true

        var realWorking = false
        if (PixelNotchSettings.get(context).preferAudioReactive && hasRecordPermission()) {
            realWorking = attemptRealOutputMixCapture()
        }
        realMode = realWorking
        listener?.onEngineStatus(real = realWorking, running = true)
        if (!realWorking) startSimulatedTicker()
    }

    fun stop() {
        if (!running) return
        running = false
        VisualizerSlot.release(ownerToken)
        stopInternal()
        listener?.onEngineStatus(real = false, running = false)
    }

    private fun stopInternal() {
        choreographerCallback.let {
            Choreographer.getInstance().removeFrameCallback(it)
        }
        val v = visualizer
        visualizer = null
        if (v != null) {
            try {
                v.setDataCaptureListener(null, Visualizer.getMaxCaptureRate(), false, false)
                v.enabled = false
                v.release()
            } catch (t: Throwable) {
                Log.w(TAG, "visualizer release failed", t)
            }
        }
        realMode = false
    }

    private fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ real capture

    /**
     * Attempts to attach [Visualizer] to the output mix (session id 0). This is the
     * only legitimately permitted audio-reactive source available to ordinary apps
     * on modern Android; where the device/OS forbids it, we gracefully return false
     * and the engine runs the simulated visualizer instead.
     */
    private fun attemptRealOutputMixCapture(): Boolean {
        return try {
            val v = Visualizer(OUTPUT_MIX_SESSION)
            this.visualizer = v
            val range = Visualizer.getCaptureSizeRange() ?: intArrayOf(128, 512)
            val size = range[1].coerceIn(256, 1024)
            v.captureSize = size
            val rate = min(Visualizer.getMaxCaptureRate(), CAPTURE_RATE_MILLIHERTZ)

            v.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                        // not used; all reactive rendering derives from FFT bands
                    }

                    override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        val now = System.currentTimeMillis()
                        if (fft != null && realMode && now - lastPublishAt >= PUBLISH_INTERVAL_MS) {
                            lastPublishAt = now
                            computeBandsFromFft(fft)
                            publish(bands, real = true)
                        }
                    }
                },
                rate,
                true,
                true
            )
            val enabled = v.enabled == true
            if (!enabled) {
                throw IllegalStateException("device denied enabling output-mix Visualizer")
            }
            Log.i(TAG, "audio-reactive capture enabled (output mix)")
            true
        } catch (denied: Throwable) {
            Log.i(TAG, "real audio capture unavailable -> simulated fallback: ${denied.message}")
            stopInternal()
            false
        }
    }

    private fun computeBandsFromFft(fft: ByteArray) {
        val binCount = fft.size / 2
        if (binCount <= 4) return
        val bandCount = bands.size
        val firstBin = 2
        val lastBin = binCount - 4
        if (lastBin <= firstBin) return
        val ratio = (lastBin.toDouble() / firstBin).pow(1.0 / bandCount)

        var previousBin = firstBin
        for (b in 0 until bandCount) {
            val bin = min(lastBin, (firstBin * ratio.pow(b + 1)).toInt())
            var sum = 0.0
            var count = 0
            var i = previousBin
            while (i < bin && 2 * i + 1 < fft.size) {
                val real = fft[2 * i].toDouble() / 127.0
                val imag = fft[2 * i + 1].toDouble() / 127.0
                sum += sqrt(real * real + imag * imag)
                count++
                i++
            }
            previousBin = bin
            val raw = if (count > 0) (sum / count) else 0.0

            // auto-gain: per-band peak with slow decay
            val decayed = peakEnvelope[b] * 0.995f
            peakEnvelope[b] = max(decayed, raw.toFloat()).coerceAtLeast(0.02f)
            val normalized = (raw / max(peakEnvelope[b].toDouble(), 0.15)).coerceIn(0.0, 1.0)

            val smoothed = bands[b] * 0.72f + normalized.toFloat() * 0.28f
            bands[b] = smoothed
        }
    }

    // ------------------------------------------------------------------ simulated

    private fun startSimulatedTicker() {
        Choreographer.getInstance().postFrameCallback(choreographerCallback)
    }

    private val choreographerCallback: Choreographer.FrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running || realMode) return
            val now = System.currentTimeMillis()
            if (now - lastPublishAt >= PUBLISH_INTERVAL_MS) {
                lastPublishAt = now
                SimulatedBands.amplitude(now, bands, bandCount)
                publish(bands, real = false)
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun publish(bandsValue: FloatArray, real: Boolean) {
        val snapshot = bandsValue.copyOf()
        listener?.onBands(snapshot, real)
        IslandCoordinator.publishBands(snapshot, real)
    }

    companion object {
        private const val TAG = "VisualizerEngine"
        const val DEFAULT_BANDS = 24
        const val OUTPUT_MIX_SESSION = 0
        private const val CAPTURE_RATE_MILLIHERTZ = 20_000 // ~= 20 frames/second
        private const val PUBLISH_INTERVAL_MS = 45L
    }
}

/** Guarantees only one engine animates at a time. */
internal object VisualizerSlot {
    private var owner: Any? = null

    fun acquire(token: Any): Boolean = synchronized(this) {
        val ok = owner == null || owner === token
        if (ok) owner = token
        ok
    }

    fun release(token: Any) = synchronized(this) {
        if (owner === token) owner = null
    }
}

/** Smooth synthetic band generator; the convincing fallback everywhere real capture is unavailable. */
internal object SimulatedBands {

    private val globalPhases = DoubleArray(32).also { out ->
        for (i in out.indices) out[i] = i * 0.647 + sin(i * 2.399).coerceIn(0.0, 1.0) * 0.31
    }

    fun amplitude(timeMs: Long, out: FloatArray, bandCount: Int) {
        val t = timeMs.toDouble()
        val beatCycle = (t % 480.0) / 480.0
        val kick = exp(-4.2 * beatCycle)
        for (i in 0 until bandCount) {
            val fi = if (bandCount > 1) i.toDouble() / (bandCount - 1) else 0.0
            val bassWeight = 1.0 - fi * 0.8
            val w1 = 0.5 + 0.5 * sin(t * (0.0011 + fi * 0.0051) + phase(i))
            val w2 = 0.5 + 0.5 * sin(t * (0.0037 - fi * 0.0018) + phase(i + bandCount))
            val flutter = sin(t * 0.021 + i * 2.399) * 0.05
            var amplitude = 0.16 + 0.46 * bassWeight * (0.45 * w1 + 0.55 * w2) + flutter
            if (i < bandCount / 5) amplitude += kick * (0.4 - fi * 0.35)
            val previous = out[i]
            val target = amplitude.coerceIn(0.03, 1.0).toFloat()
            out[i] = previous + (target - previous) * 0.34f // gentle easing toward target
        }
    }

    private fun phase(index: Int): Double = globalPhases[index % globalPhases.size]
}
