package com.pixelnotch.app.ui

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import com.pixelnotch.app.settings.PixelNotchSettings

/**
 * Pure drawing routines for the four visualizer styles. Shared by
 * [EqualizerView] (main app preview) and the in-pill equalizer of the overlay
 * so both contexts render identically.
 */
object VisualizerRenderer {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val wavePathLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPath = Path()
    private val fillArea = Path()

    fun draw(
        canvas: Canvas,
        rect: RectF,
        style: PixelNotchSettings.VisualizerStyle,
        bands: FloatArray,
        accent: Int,
        accentSoft: Int,
        alpha: Int
    ) {
        val visible = rect.right - rect.left
        if (visible <= 0 || rect.height() <= 0 || alpha <= 0) return
        when (style) {
            PixelNotchSettings.VisualizerStyle.BARS ->
                drawClassicBars(canvas, rect, bands, accent, accentSoft, alpha)
            PixelNotchSettings.VisualizerStyle.WAVE ->
                drawWave(canvas, rect, bands, accent, accentSoft, alpha)
            PixelNotchSettings.VisualizerStyle.BOUNCY ->
                drawBouncy(canvas, rect, bands, accent, alpha)
            PixelNotchSettings.VisualizerStyle.MINIMAL ->
                drawMinimal(canvas, rect, bands, accent, alpha)
        }
    }

    /** Classic equalizer columns with a subtle vertical gradient. */
    private fun drawClassicBars(canvas: Canvas, rect: RectF, bands: FloatArray, accent: Int, accentSoft: Int, alpha: Int) {
        val count = bands.size
        val gap = rect.width() / count * 0.34f
        val barWidth = rect.width() / count - gap
        barPaint.shader = LinearGradient(
            0f, rect.bottom, 0f, rect.top,
            accentSoft, accent, Shader.TileMode.CLAMP
        )
        barPaint.alpha = alpha
        for (i in 0 until count) {
            val amplitude = bands[i].coerceIn(0f, 1f)
            val height = maxOf(rect.height() * 0.08f, rect.height() * amplitude)
            val left = rect.left + i * rect.width() / count + gap / 2
            val rounded = RectF(left, rect.bottom - height, left + barWidth, rect.bottom)
            canvas.drawRoundRect(rounded, barWidth / 2, barWidth / 2, barPaint)
        }
    }

    /** Smooth waveform: a filled curve built from the band values, plus its center line. */
    private fun drawWave(canvas: Canvas, rect: RectF, bands: FloatArray, accent: Int, accentSoft: Int, alpha: Int) {
        val count = bands.size
        fillArea.reset()
        fillArea.moveTo(rect.left, rect.centerY())
        val halfHeight = rect.height() / 2f
        var x = rect.left
        val step = rect.width() / (count - 1).coerceAtLeast(1)
        for (i in 0 until count) {
            val amplitude = bands[i].coerceIn(0.02f, 1f) * halfHeight
            x = rect.left + i * step
            fillArea.lineTo(x, rect.centerY() - amplitude)
        }
        for (i in count - 1 downTo 0) {
            val amplitude = bands[i].coerceIn(0.02f, 1f) * halfHeight
            x = rect.left + i * step
            fillArea.lineTo(x, rect.centerY() + amplitude)
        }
        fillArea.close()
        pathPaint.shader = LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom,
            accentSoft, accent, Shader.TileMode.CLAMP
        )
        pathPaint.alpha = (alpha * 0.55f).toInt().coerceIn(0, 255)
        fillPath.set(fillArea)
        canvas.drawPath(fillPath, pathPaint)

        wavePathLine.color = accent
        wavePathLine.strokeWidth = maxOf(3f, rect.height() * 0.05f)
        wavePathLine.alpha = alpha
        canvas.drawLines(topLinePoints(rect, bands), wavePathLine)
    }

    private fun topLinePoints(rect: RectF, bands: FloatArray): FloatArray {
        val count = bands.size
        val step = rect.width() / (count - 1).coerceAtLeast(1)
        val halfHeight = rect.height() / 2f
        val result = FloatArray((count - 1) * 4)
        var index = 0
        var previousX = rect.left
        var previousY = rect.centerY() - bands[0].coerceIn(0.02f, 1f) * halfHeight
        for (i in 1 until count) {
            val x = rect.left + i * step
            val y = rect.centerY() - bands[i].coerceIn(0.02f, 1f) * halfHeight
            result[index++] = previousX
            result[index++] = previousY
            result[index++] = x
            result[index++] = y
            previousX = x
            previousY = y
        }
        return result
    }

    /** Rounded bouncing bars with a per-bar squash at the top, for a heavier look. */
    private fun drawBouncy(canvas: Canvas, rect: RectF, bands: FloatArray, accent: Int, alpha: Int) {
        val count = bands.size
        val gap = rect.width() / count * 0.24f
        val barWidth = rect.width() / count - gap
        barPaint.shader = null
        barPaint.color = accent
        barPaint.alpha = alpha
        for (i in 0 until count) {
            val amplitude = bands[i].coerceIn(0f, 1f)
            val height = maxOf(rect.height() * 0.1f, rect.height() * amplitude)
            val squash = barWidth * 0.32f * amplitude
            val left = rect.left + i * rect.width() / count + gap / 2
            val rounded = RectF(left, rect.bottom - height, left + barWidth, rect.bottom)
            canvas.drawRoundRect(rounded, barWidth / 2, barWidth / 2, barPaint)
            if (squash > 2f) {
                canvas.drawCircle(left + barWidth / 2, rect.bottom - height + squash, squash / 2, barPaint)
            }
        }
    }

    /** Minimal single-cell pulse: one soft blob + a thin reactive ring. */
    private fun drawMinimal(canvas: Canvas, rect: RectF, bands: FloatArray, accent: Int, alpha: Int) {
        var energy = 0f
        for (value in bands) energy += value.coerceIn(0f, 1f)
        energy /= bands.size.coerceAtLeast(1).toFloat()
        barPaint.shader = null
        barPaint.color = accent
        barPaint.alpha = (alpha * 0.85f).toInt()
        val radius = maxOf(rect.height() / 2f * (0.32f + energy * 0.6f), 4f)
        barPaint.alpha = (alpha * 0.30f).toInt()
        canvas.drawCircle(rect.centerX(), rect.centerY(), radius + rect.height() * 0.14f * energy, barPaint)
        barPaint.alpha = alpha
        canvas.drawCircle(rect.centerX(), rect.centerY(), radius, barPaint)
    }
}
