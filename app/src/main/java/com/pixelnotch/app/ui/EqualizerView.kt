package com.pixelnotch.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import com.pixelnotch.app.audio.SimulatedBands
import com.pixelnotch.app.audio.VisualizerEngine
import com.pixelnotch.app.coordinator.IslandCoordinator
import com.pixelnotch.app.settings.PixelNotchSettings

/**
 * Standalone equalizer preview shown inside the main app. It receives live band
 * data published through [IslandCoordinator]; when the overlay service isn't
 * producing frames it drives its own simulated animation so the preview always
 * looks alive. The animation loop stops whenever the view is hidden.
 */
class EqualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val settings = PixelNotchSettings.get(context)
    private val contentRect = RectF()
    private var visualizerBands: FloatArray = FloatArray(VisualizerEngine.DEFAULT_BANDS)
    private var animating = false
    private var lastBandAt = 0L

    /** Latest band data published by whichever engine owns the visualizer slot. */
    private var feed: FloatArray? = null

    private val bandListener = object : IslandCoordinator.BandListener {
        override fun onBandsUpdated(bands: FloatArray, isReal: Boolean) {
            feed = bands
        }
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!animating || visibility != VISIBLE) {
                animating = false
                feed = null
                return
            }
            simulateIfNeeded()
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun setAnimating(animate: Boolean) {
        if (animating == animate) return
        animating = animate
        feed = null
        if (animate) {
            IslandCoordinator.addBandListener(bandListener)
            Choreographer.getInstance().postFrameCallback(frameCallback)
        } else {
            IslandCoordinator.removeBandListener(bandListener)
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animating) IslandCoordinator.addBandListener(bandListener)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        IslandCoordinator.removeBandListener(bandListener)
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    private fun simulateIfNeeded() {
        if (feed != null) return // a live engine keeps the bands fresh
        val now = System.currentTimeMillis()
        if (now - lastBandAt >= 45) {
            lastBandAt = now
            SimulatedBands.amplitude(now, visualizerBands, visualizerBands.size)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        contentRect.set(0f, 0f, width.toFloat(), height.toFloat())
        val active = feed ?: visualizerBands
        val accent = Color.parseColor("#32D74B")
        val accentSoft = Color.parseColor("#0A84FF")
        VisualizerRenderer.draw(canvas, contentRect, settings.visualizerStyle, active, accent, accentSoft, 255)
    }

    /** Refreshes style/colors from settings (called by hosts when the style changes). */
    fun refreshStyle() {
        invalidate()
    }
}
