package com.pixelnotch.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.pixelnotch.app.audio.SimulatedBands
import com.pixelnotch.app.audio.VisualizerEngine
import com.pixelnotch.app.coordinator.IslandCoordinator
import com.pixelnotch.app.coordinator.PixelNotchSettingsProxy
import com.pixelnotch.app.settings.PixelNotchSettings

/**
 * The Dynamic-Island pill itself: a fully Canvas-drawn, spring-resized view
 * shared by the WindowManager overlay and the in-app preview.
 *
 * Behaviors:
 *  - collapsed pill for idle/music/charging/timer states,
 *  - tap to expand a persistent state into its full card (music shows artwork + controls),
 *  - transient pop-ups (notification/call/track-change/charging/timer-done) render at
 *    their pop-up geometry and are dismissed by a tap,
 *  - all resizing runs through [SpringForce] springs and any state change crossfades,
 *    so layouts never snap or flicker,
 *  - touch only reaches this window inside the pill/card area (host windows cover
 *    only the animated view bounds).
 */
class IslandPillView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class MediaAction { PLAY_PAUSE, NEXT, PREVIOUS }

    /** Host hook: overlay service and main activity forward media commands to the session. */
    var mediaCommandListener: ((MediaAction) -> Unit)? = null

    // ------------------------------------------------------------------ geometry
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity

    private data class Geometry(val width: Float, val height: Float)

    private fun screenMaxWidth(): Float {
        val dm = resources.displayMetrics
        return (minOf(dm.widthPixels, dm.heightPixels) - dp(28f)).coerceAtLeast(dp(240f))
    }

    private fun collapsedGeometry(type: IslandCoordinator.IslandType): Geometry = when (type) {
        IslandCoordinator.IslandType.MUSIC -> Geometry(dp(178f), dp(44f))
        IslandCoordinator.IslandType.CHARGING -> Geometry(dp(118f), dp(44f))
        IslandCoordinator.IslandType.TIMER_RUNNING -> Geometry(dp(152f), dp(44f))
        else -> Geometry(dp(120f), dp(40f))
    }

    private fun expandedGeometry(type: IslandCoordinator.IslandType): Geometry = when (type) {
        IslandCoordinator.IslandType.MUSIC -> Geometry(minOf(dp(350f), screenMaxWidth()), dp(140f))
        IslandCoordinator.IslandType.CHARGING -> Geometry(dp(264f), dp(120f))
        IslandCoordinator.IslandType.TIMER_RUNNING -> Geometry(dp(254f), dp(108f))
        else -> Geometry(dp(270f), dp(96f))
    }

    private fun popupGeometry(type: IslandCoordinator.IslandType): Geometry = when (type) {
        IslandCoordinator.IslandType.TRACK_CHANGE -> Geometry(minOf(dp(312f), screenMaxWidth()), dp(128f))
        IslandCoordinator.IslandType.NOTIFICATION, IslandCoordinator.IslandType.CALL ->
            Geometry(minOf(dp(322f), screenMaxWidth()), dp(98f))
        IslandCoordinator.IslandType.TIMER_DONE -> Geometry(dp(284f), dp(102f))
        IslandCoordinator.IslandType.CHARGING -> Geometry(dp(232f), dp(64f))
        else -> Geometry(minOf(dp(302f), screenMaxWidth()), dp(92f))
    }

    private fun targetGeometry(state: IslandCoordinator.IslandState, expanded: Boolean): Geometry =
        if (state.popup) popupGeometry(state.type)
        else if (expanded) expandedGeometry(state.type)
        else collapsedGeometry(state.type)

    // ------------------------------------------------------------------ springs & state
    private val motionFactor: Float get() = PixelNotchSettings.get(context).effectiveMotionFactor(context)

    private var viewWidth = dp(120f)
    private var viewHeight = dp(40f)

    private val widthHolder = FloatValueHolder(viewWidth)
    private val heightHolder = FloatValueHolder(viewHeight)
    private val widthSpring = SpringAnimation(widthHolder)
    private val heightSpring = SpringAnimation(heightHolder)
    private val mixHolder = FloatValueHolder(1f)
    private val mixSpring = SpringAnimation(mixHolder)

    private var expanded = false
    private var state: IslandCoordinator.IslandState = IslandCoordinator.computeState()

    private data class RenderedContent(
        val type: IslandCoordinator.IslandType,
        val popup: Boolean,
        val track: IslandCoordinator.TrackInfo?,
        val battery: IslandCoordinator.BatteryInfo?,
        val timer: IslandCoordinator.TimerInfo?,
        val notification: IslandCoordinator.NotificationInfo?
    )

    private fun contentOf(s: IslandCoordinator.IslandState) = RenderedContent(s.type, s.popup, s.track, s.battery, s.timer, s.notification)
    private var rendered = contentOf(IslandCoordinator.computeState())
    private var previousRendered: RenderedContent? = null

    init {
        widthSpring.addUpdateListener { _, value, _ ->
            viewWidth = value
            requestLayout()
            invalidate()
        }
        heightSpring.addUpdateListener { _, value, _ ->
            viewHeight = value
            requestLayout()
            invalidate()
        }
        mixSpring.addUpdateListener { _, value, _ ->
            if (value > 0.999f) previousRendered = null
            invalidate()
        }
    }

    // ------------------------------------------------------------------ paints & colors
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#0A0B0E")
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.argb(30, 255, 255, 255)
    }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        color = Color.WHITE
        textSize = sp(15f)
    }
    private val secondaryPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.SANS_SERIF
        color = Color.parseColor("#9AA1AD")
        textSize = sp(12f)
    }
    private val bigPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        color = Color.WHITE
        textSize = sp(20f)
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val iconDimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#9AA1AD")
    }
    private val controlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#2A2E36")
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(4.5f)
        strokeCap = Paint.Cap.ROUND
    }
    private val ringTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(4.5f)
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#22252B")
    }
    private val artworkPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val placeholderArtPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#20242C")
    }
    private val accentGreen = Color.parseColor("#32D74B")
    private val accentBlue = Color.parseColor("#0A84FF")
    private val accentOrange = Color.parseColor("#FF9F0A")

    private val tempRect = RectF()
    private val artworkRect = RectF()
    private val chipRect = RectF()
    private val clipPath = Path()

    // ------------------------------------------------------------------ in-pill equalizer
    private var eqBands = FloatArray(VisualizerEngine.DEFAULT_BANDS)
    private var lastEqSimAt = 0L
    private var pillFrameRunning = false

    private val bandListenerImpl = object : IslandCoordinator.BandListener {
        override fun onBandsUpdated(bands: FloatArray, isReal: Boolean) {
            eqBands = if (bands.size == eqBands.size) bands.copyOf() else bands.copyOf(bands.size)
        }
    }

    private val pillFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!pillFrameRunning || visibility != VISIBLE) {
                pillFrameRunning = false
                return
            }
            refreshBands()
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun ensureEqualizerLoop(needed: Boolean) {
        if (needed == pillFrameRunning) return
        if (needed) {
            pillFrameRunning = true
            IslandCoordinator.addBandListener(bandListenerImpl)
            Choreographer.getInstance().postFrameCallback(pillFrameCallback)
        } else {
            pillFrameRunning = false
            Choreographer.getInstance().removeFrameCallback(pillFrameCallback)
            IslandCoordinator.removeBandListener(bandListenerImpl)
        }
    }

    private fun refreshBands() {
        // Only simulate when no engine is publishing; a running engine (real or
        // simulated) keeps eqBands fresh through the band listener.
        if (IslandCoordinator.lastBands == null) {
            val now = System.currentTimeMillis()
            if (now - lastEqSimAt >= 45) {
                lastEqSimAt = now
                SimulatedBands.amplitude(now, eqBands, eqBands.size)
            }
        }
    }

    // ------------------------------------------------------------------ coordinator wiring
    private val stateObserver = object : IslandCoordinator.Observer {
        override fun onIslandStateChanged(newState: IslandCoordinator.IslandState) {
            post { applyState(newState) }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        IslandCoordinator.addObserver(stateObserver)
        rendered = contentOf(IslandCoordinator.computeState())
        state = IslandCoordinator.computeState()
        retargetSprings()
        updateVisibilityLoop()
        post { invalidate() }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        IslandCoordinator.removeObserver(stateObserver)
        ensureEqualizerLoop(false)
    }

    private fun applyState(newState: IslandCoordinator.IslandState) {
        val contentChanged = rendered.type != newState.type || renderPayloadChanged(rendered, newState)
        if (contentChanged) {
            previousRendered = rendered
            rendered = contentOf(newState)
            restartContentMix()
        }
        val wasType = state.type
        state = newState
        if (newState.type != wasType && !newState.popup) {
            expanded = false
        }
        retargetSprings()
        updateVisibilityLoop()
        invalidate()
    }

    private fun renderPayloadChanged(old: RenderedContent, newState: IslandCoordinator.IslandState): Boolean {
        if (old.track?.title != newState.track?.title) return true
        if (old.track?.playing != newState.track?.playing) return true
        if (old.track?.artwork !== newState.track?.artwork) return true
        if (old.battery?.plugged != newState.battery?.plugged) return true
        if (old.battery?.levelPercent != newState.battery?.levelPercent) return true
        if (old.notification?.key != newState.notification?.key) return true
        if (old.timer?.remainingMs != newState.timer?.remainingMs) return true
        return false
    }

    private fun restartContentMix() {
        mixSpring.cancel()
        val factor = motionFactor
        if (factor <= 0.01f) {
            mixHolder.value = 1f
            previousRendered = null
            invalidate()
            return
        }
        mixHolder.value = 0f
        mixSpring.spring = SpringForce().apply {
            dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            stiffness = 620f
        }
        mixSpring.animateToFinalPosition(1f)
    }

    private fun retargetSprings() {
        val geometry = targetGeometry(state, expanded)
        animateSpring(widthSpring, widthHolder, viewWidth, geometry.width)
        animateSpring(heightSpring, heightHolder, viewHeight, geometry.height)
        invalidate()
    }

    private fun animateSpring(spring: SpringAnimation, holder: FloatValueHolder, current: Float, target: Float) {
        spring.cancel()
        val factor = motionFactor
        if (factor <= 0.01f) {
            holder.value = target
            viewWidth = if (spring === widthSpring) target else viewWidth
            viewHeight = if (spring === heightSpring) target else viewHeight
            requestLayout()
            invalidate()
            return
        }
        spring.spring = SpringForce().apply {
            dampingRatio = 0.88f
            stiffness = 430f
        }
        spring.animateToFinalPosition(target)
    }

    // ------------------------------------------------------------------ measure/draw
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(viewWidth.toInt(), widthMeasureSpec),
            resolveSize(viewHeight.toInt(), heightMeasureSpec)
        )
    }

    private val contentRect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()
        if (width < 2f || height < 2f) return
        contentRect.set(0f, 0f, width, height)
        val radius = height / 2f
        backgroundPaint.alpha = 255
        canvas.drawRoundRect(contentRect, radius, radius, backgroundPaint)
        borderPaint.alpha = 30
        canvas.drawRoundRect(contentRect, radius, radius, borderPaint)

        resetControls()
        val mix = mixHolder.value.coerceIn(0f, 1f)
        val prev = previousRendered
        if (prev != null && mix < 1f) {
            canvas.save()
            canvas.translate(0f, dp(6f) * (1f - mix))
            drawContent(canvas, contentRect, prev, ((1f - mix) * 255).toInt())
            canvas.restore()
        }
        canvas.save()
        canvas.translate(0f, dp(3f) * (1f - mix * mix))
        drawContent(canvas, contentRect, rendered, (mix * 255f).toInt())
        canvas.restore()
        invalidateIfSpringsActive()
    }

    private fun invalidateIfSpringsActive() {
        if (widthSpring.isRunning || heightSpring.isRunning || mixSpring.isRunning || pillFrameRunning) {
            postInvalidateOnAnimation()
        }
    }

    private fun drawContent(canvas: Canvas, rect: RectF, content: RenderedContent, alpha: Int) {
        if (alpha <= 3) return
        when (content.type) {
            IslandCoordinator.IslandType.IDLE -> drawIdle(canvas, rect, alpha)
            IslandCoordinator.IslandType.MUSIC -> drawMusic(canvas, rect, content, alpha, expanded && !content.popup)
            IslandCoordinator.IslandType.CHARGING -> drawCharging(canvas, rect, content, alpha)
            IslandCoordinator.IslandType.TRACK_CHANGE -> drawTrackChange(canvas, rect, content, alpha)
            IslandCoordinator.IslandType.NOTIFICATION -> drawNotification(canvas, rect, content, alpha, isCall = false)
            IslandCoordinator.IslandType.CALL -> drawNotification(canvas, rect, content, alpha, isCall = true)
            IslandCoordinator.IslandType.TIMER_RUNNING -> drawTimer(canvas, rect, content, alpha, done = false)
            IslandCoordinator.IslandType.TIMER_DONE -> drawTimer(canvas, rect, content, alpha, done = true)
        }
    }

    // --------------------------------------------------------------- persistent states

    private fun drawIdle(canvas: Canvas, rect: RectF, alpha: Int) {
        val cy = rect.height() / 2f
        iconDimPaint.color = Color.parseColor("#3A3E46")
        iconDimPaint.alpha = (alpha * 0.5f).toInt()
        canvas.drawCircle(rect.right - dp(20f), cy, dp(5f), iconDimPaint)
        iconPaint.color = accentGreen
        iconPaint.alpha = (alpha * 0.4f).toInt()
        canvas.drawCircle(rect.left + dp(20f), cy, dp(4f), iconPaint)
    }

    private fun drawMusic(canvas: Canvas, rect: RectF, content: RenderedContent, alpha: Int, isExpanded: Boolean) {
        val track = content.track
        if (isExpanded) {
            artworkRect.set(
                rect.left + dp(18f), rect.top + dp(18f),
                rect.left + dp(18f) + dp(64f), rect.top + dp(18f) + dp(64f)
            )
            drawArtwork(canvas, artworkRect, track?.artwork, alpha, dp(16f))

            val textLeft = artworkRect.right + dp(14f)
            val textWidth = rect.right - dp(18f) - textLeft
            titlePaint.textSize = sp(15f)
            canvas.drawText(
                ellipsize(track?.title ?: "", titlePaint, textWidth), textLeft, rect.top + dp(40f),
                applyAlpha(titlePaint, alpha)
            )
            secondaryPaint.textSize = sp(12f)
            canvas.drawText(
                ellipsize(track?.artist ?: "", secondaryPaint, textWidth), textLeft, rect.top + dp(59f),
                applyAlpha(secondaryPaint, alpha)
            )

            tempRect.set(rect.left + dp(18f), rect.bottom - dp(52f), rect.right - dp(158f), rect.bottom - dp(24f))
            drawMusicEqualizer(canvas, tempRect, content)
            drawPlaybackControls(canvas, rect, content, alpha)
        } else {
            artworkRect.set(
                rect.left + dp(9f), rect.top + dp(9f),
                rect.left + dp(9f) + dp(26f), rect.top + dp(9f) + dp(26f)
            )
            drawArtwork(canvas, artworkRect, track?.artwork, alpha, dp(8f))

            val eqWidth = dp(38f)
            tempRect.set(
                rect.right - eqWidth - dp(12f), rect.top + dp(13f),
                rect.right - dp(12f), rect.bottom - dp(13f)
            )
            titlePaint.textSize = sp(13f)
            val titleAreaWidth = tempRect.left - (artworkRect.right + dp(10f))
            canvas.drawText(
                ellipsize(track?.title ?: "", titlePaint, titleAreaWidth),
                artworkRect.right + dp(10f), rect.centerY() + sp(4.5f),
                applyAlpha(titlePaint, alpha)
            )

            drawMusicEqualizer(canvas, tempRect, content)
            drawBatteryChipIfCharging(canvas, rect, alpha)
        }
        updateVisibilityLoop()
    }

    private fun drawTrackChange(canvas: Canvas, rect: RectF, content: RenderedContent, alpha: Int) {
        val track = content.track
        secondaryPaint.textSize = sp(10.5f)
        chipRect.set(rect.left + dp(20f), rect.top + dp(13f), rect.left + dp(120f), rect.top + dp(31f))
        canvas.drawText(
            ellipsize("NOW PLAYING", secondaryPaint, chipRect.width()), chipRect.left, chipRect.centerY() + sp(4f),
            applyAlpha(secondaryPaint, alpha)
        )

        artworkRect.set(
            rect.left + dp(20f), rect.top + dp(38f),
            rect.left + dp(20f) + dp(54f), rect.top + dp(38f) + dp(54f)
        )
        drawArtwork(canvas, artworkRect, track?.artwork, alpha, dp(12f))

        val textLeft = artworkRect.right + dp(14f)
        val textWidth = rect.right - dp(20f) - textLeft
        titlePaint.textSize = sp(14.5f)
        canvas.drawText(
            ellipsize(track?.title ?: "", titlePaint, textWidth), textLeft, rect.top + dp(66f),
            applyAlpha(titlePaint, alpha)
        )
        secondaryPaint.textSize = sp(12f)
        canvas.drawText(
            ellipsize(track?.artist ?: "", secondaryPaint, textWidth), textLeft, rect.top + dp(85f),
            applyAlpha(secondaryPaint, alpha)
        )
    }

    private fun drawCharging(canvas: Canvas, rect: RectF, content: RenderedContent, alpha: Int) {
        val battery = content.battery
        val connected = battery?.plugged ?: true
        val level = (battery?.levelPercent ?: 0).coerceIn(0, 100)

        if (content.popup) {
            val cy = rect.centerY()
            iconPaint.color = if (connected) accentGreen else accentOrange
            iconPaint.alpha = alpha
            drawBoltGlyph(canvas, rect.left + dp(22f), cy, dp(16f), iconPaint)
            titlePaint.textSize = sp(13.5f)
            val text = if (connected) "Charging" else "On battery"
            canvas.drawText(text, rect.left + dp(38f), cy + sp(4.6f), applyAlpha(titlePaint, alpha))
            bigPaint.textSize = sp(16f)
            val percent = "$level%"
            canvas.drawText(percent, rect.right - dp(18f) - bigPaint.measureText(percent), cy + sp(5.4f), applyAlpha(bigPaint, alpha))
        } else {
            val cx = rect.centerX()
            val cy = rect.height() * 0.40f
            val radius = dp(33f)
            ringTrackPaint.alpha = (alpha * 0.85f).toInt()
            canvas.drawCircle(cx, cy, radius, ringTrackPaint)
            ringPaint.color = accentGreen
            ringPaint.alpha = alpha
            canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, -90f, 360f * level / 100f, false, ringPaint)
            iconPaint.color = accentGreen
            iconPaint.alpha = alpha
            drawBoltGlyph(canvas, cx, cy, dp(14f), iconPaint)

            bigPaint.textSize = sp(20f)
            canvas.drawText("$level%", cx - bigPaint.measureText("$level%") / 2f, cy + radius + dp(30f), applyAlpha(bigPaint, alpha))
            secondaryPaint.textSize = sp(12f)
            val status = if (connected) "Charging" else "On battery"
            canvas.drawText(status, cx - secondaryPaint.measureText(status) / 2f, cy + radius + dp(48f), applyAlpha(secondaryPaint, alpha))
        }
    }

    private fun drawNotification(canvas: Canvas, rect: RectF, content: RenderedContent, alpha: Int, isCall: Boolean) {
        val info = content.notification
        val cxIcon = rect.left + dp(23f)
        val cyIcon = rect.centerY()
        val iconRadius = dp(20f)
        iconPaint.color = Color.parseColor("#22252B")
        iconPaint.alpha = alpha
        canvas.drawCircle(cxIcon, cyIcon, iconRadius, iconPaint)

        val icon = info?.icon
        if (icon != null) {
            val inset = iconRadius * 0.46f
            clipRect.set(
                cxIcon - iconRadius + inset, cyIcon - iconRadius + inset,
                cxIcon + iconRadius - inset, cyIcon + iconRadius - inset
            )
            canvas.save()
            clipPath.reset()
            clipPath.addCircle(cxIcon, cyIcon, iconRadius * 0.54f, Path.Direction.CW)
            canvas.clipPath(clipPath)
            try {
                icon.mutate().setAlpha(alpha.coerceIn(0, 255))
                icon.setBounds(
                    clipRect.left.toInt(), clipRect.top.toInt(),
                    clipRect.right.toInt(), clipRect.bottom.toInt()
                )
                icon.draw(canvas)
            } catch (t: Throwable) {
                // some remote drawables cannot render in this context; the placeholder keeps a stable look
                iconPaint.color = if (isCall) accentBlue else accentGreen
                iconPaint.alpha = (alpha * 0.9f).toInt()
                canvas.drawCircle(cxIcon, cyIcon, iconRadius * 0.38f, iconPaint)
            }
            canvas.restore()
        } else {
            iconPaint.color = if (isCall) accentBlue else accentGreen
            iconPaint.alpha = (alpha * 0.9f).toInt()
            canvas.drawCircle(cxIcon, cyIcon, iconRadius * 0.38f, iconPaint)
        }

        val textLeft = rect.left + dp(52f)
        val textWidth = rect.right - dp(18f) - textLeft
        secondaryPaint.textSize = sp(10.5f)
        val header = when {
            isCall -> "Incoming call"
            else -> (info?.appName ?: "Notification").ifBlank { "Notification" }
        }
        canvas.drawText(ellipsize(header, secondaryPaint, textWidth), textLeft, rect.top + dp(30f), applyAlpha(secondaryPaint, alpha))

        val title = info?.title.orEmpty()
        val body = info?.text.orEmpty()
        var cursorY = rect.top + dp(48f)
        if (title.isNotBlank()) {
            titlePaint.textSize = sp(14f)
            canvas.drawText(ellipsize(title, titlePaint, textWidth), textLeft, cursorY, applyAlpha(titlePaint, alpha))
            cursorY += dp(17f)
        }
        if (body.isNotBlank()) {
            secondaryPaint.textSize = sp(12f)
            canvas.drawText(ellipsize(body, secondaryPaint, textWidth), textLeft, cursorY, applyAlpha(secondaryPaint, alpha))
        } else if (title.isBlank()) {
            secondaryPaint.textSize = sp(12f)
            canvas.drawText("New notification", textLeft, cursorY, applyAlpha(secondaryPaint, alpha))
        }
    }

    private val clipRect = RectF()

    private fun drawTimer(canvas: Canvas, rect: RectF, content: RenderedContent, alpha: Int, done: Boolean) {
        val timer = content.timer
        val remaining = (timer?.remainingMs ?: 0L).coerceAtLeast(0L)
        val total = (timer?.totalMs ?: 1L).coerceAtLeast(1L)
        val progress = (remaining.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        val isPopup = content.popup || done

        val cx = rect.centerX()
        val radius = if (isPopup) dp(32f) else dp(16f)
        val cy = if (isPopup) rect.height() * 0.42f else rect.height() / 2f

        ringTrackPaint.alpha = (alpha * 0.85f).toInt()
        canvas.drawCircle(cx, cy, radius, ringTrackPaint)
        ringPaint.color = if (done) accentOrange else accentBlue
        ringPaint.alpha = alpha
        val sweep = if (done) 360f else 360f * (1f - progress)
        canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, -90f, sweep, false, ringPaint)

        val label = formatClock(remaining)
        if (isPopup) {
            bigPaint.textSize = sp(17f)
            canvas.drawText(label, cx - bigPaint.measureText(label) / 2f, cy + sp(6f), applyAlpha(bigPaint, alpha))
            secondaryPaint.textSize = sp(12.5f)
            val subtitle = if (done) "Timer finished" else (timer?.label ?: "Timer")
            canvas.drawText(
                ellipsize(subtitle, secondaryPaint, rect.width() - dp(24f)),
                cx - secondaryPaint.measureText(subtitle) / 2f, cy + radius + dp(30f),
                applyAlpha(secondaryPaint, alpha)
            )
        } else {
            titlePaint.textSize = sp(13f)
            val textX = cx + dp(7f)
            canvas.drawText(label, textX, rect.centerY() + sp(4.2f), applyAlpha(titlePaint, alpha))
        }
    }

    // ------------------------------------------------------------------ shared sub-draws

    private val controlRects = ArrayList<Pair<RectF, IslandPillView.MediaAction>>()

    private fun resetControls() {
        controlRects.clear()
    }

    private fun registerControl(rect: RectF, action: IslandPillView.MediaAction) {
        controlRects.add(RectF(rect) to action)
    }

    private fun drawPlaybackControls(canvas: Canvas, rect: RectF, content: RenderedContent, alpha: Int) {
        val cy = rect.bottom - dp(38f)
        val track = content.track
        drawControlButton(canvas, rect.right - dp(66f), cy, dp(40f), MediaAction.PREVIOUS, alpha)
        drawControlButton(canvas, rect.right - dp(102f), cy, dp(44f), MediaAction.PLAY_PAUSE, alpha, track)
        drawControlButton(canvas, rect.right - dp(22f), cy, dp(40f), MediaAction.NEXT, alpha)
    }

    private fun drawControlButton(
        canvas: Canvas, cx: Float, cy: Float, diameter: Float,
        action: IslandPillView.MediaAction, alpha: Int, track: IslandCoordinator.TrackInfo? = null
    ) {
        val radius = diameter / 2f
        controlPaint.alpha = (alpha * 0.92f).toInt()
        canvas.drawCircle(cx, cy, radius, controlPaint)
        tempRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        registerControl(tempRect, action)
        when (action) {
            MediaAction.PLAY_PAUSE -> {
                val playing = track?.playing == true
                iconPaint.color = Color.WHITE
                iconPaint.alpha = alpha
                if (playing) {
                    drawPauseGlyph(canvas, cx, cy, radius * 0.45f)
                } else {
                    drawPlayGlyph(canvas, cx, cy, radius * 0.5f)
                }
            }
            MediaAction.PREVIOUS -> {
                iconDimPaint.color = Color.WHITE
                iconDimPaint.alpha = (alpha * 0.85f).toInt()
                drawSkipGlyph(canvas, cx, cy, radius * 0.55f, forward = false)
            }
            MediaAction.NEXT -> {
                iconDimPaint.color = Color.WHITE
                iconDimPaint.alpha = (alpha * 0.85f).toInt()
                drawSkipGlyph(canvas, cx, cy, radius * 0.55f, forward = true)
            }
        }
    }

    private fun drawPlayGlyph(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val path = Path()
        path.moveTo(cx - radius * 0.6f, cy - radius)
        path.lineTo(cx + radius * 0.8f, cy)
        path.lineTo(cx - radius * 0.6f, cy + radius)
        path.close()
        canvas.drawPath(path, iconPaint)
    }

    private fun drawPauseGlyph(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val barWidth = radius * 0.42f
        val height = radius * 1.6f
        canvas.drawRoundRect(
            RectF(cx - barWidth * 1.7f, cy - height / 2f, cx - barWidth * 0.45f, cy + height / 2f),
            barWidth / 2, barWidth / 2, iconPaint
        )
        canvas.drawRoundRect(
            RectF(cx + barWidth * 0.45f, cy - height / 2f, cx + barWidth * 1.7f, cy + height / 2f),
            barWidth / 2, barWidth / 2, iconPaint
        )
    }

    private fun drawSkipGlyph(canvas: Canvas, cx: Float, cy: Float, radius: Float, forward: Boolean) {
        val direction = if (forward) 1f else -1f
        val path = Path()
        path.moveTo(cx - direction * radius * 0.75f, cy - radius)
        path.lineTo(cx + direction * radius * 0.55f, cy)
        path.lineTo(cx - direction * radius * 0.75f, cy + radius)
        path.close()
        canvas.drawPath(path, iconDimPaint)
        canvas.drawRoundRect(
            RectF(
                cx + direction * radius * 0.62f - dp(1.4f), cy - radius,
                cx + direction * radius * 0.62f + dp(1.4f), cy + radius
            ),
            dp(1.4f), dp(1.4f), iconDimPaint
        )
    }

    private fun drawBoltGlyph(canvas: Canvas, cx: Float, cy: Float, size: Float, paint: Paint) {
        val p = Path()
        p.moveTo(cx - size * 0.05f, cy - size / 2f)
        p.lineTo(cx + size * 0.32f, cy - size * 0.05f)
        p.lineTo(cx + size * 0.08f, cy)
        p.lineTo(cx + size * 0.28f, cy + size * 0.05f)
        p.lineTo(cx - size * 0.14f, cy + size / 2f)
        p.lineTo(cx - size * 0.02f, cy + size * 0.12f)
        p.lineTo(cx - size * 0.26f, cy + size * 0.02f)
        p.close()
        canvas.drawPath(p, paint)
    }

    private fun drawBatteryChipIfCharging(canvas: Canvas, rect: RectF, alpha: Int) {
        val battery = IslandCoordinator.currentBattery ?: return
        if (!battery.plugged) return
        iconPaint.color = accentGreen
        iconPaint.alpha = (alpha * 0.9f).toInt()
        drawBoltGlyph(canvas, rect.left + dp(44f), rect.centerY() + dp(3f), dp(10f), iconPaint)
    }

    private fun drawArtwork(canvas: Canvas, artRect: RectF, artwork: Bitmap?, alpha: Int, corner: Float) {
        placeholderArtPaint.alpha = alpha
        canvas.drawRoundRect(artRect, corner, corner, placeholderArtPaint)
        if (artwork != null && !artwork.isRecycled) {
            canvas.save()
            clipPath.reset()
            clipPath.addRoundRect(artRect, corner, corner, Path.Direction.CW)
            canvas.clipPath(clipPath)
            artworkPaint.alpha = alpha
            canvas.drawBitmap(artwork, null, artRect, artworkPaint)
            canvas.restore()
        } else {
            iconDimPaint.color = Color.parseColor("#8E96A5")
            iconDimPaint.alpha = (alpha * 0.6f).toInt()
            val cx = artRect.centerX()
            val cy = artRect.centerY()
            val r = artRect.width() * 0.11f
            canvas.drawCircle(cx - artRect.width() * 0.06f, cy + artRect.height() * 0.16f, r, iconDimPaint)
            canvas.drawCircle(cx + artRect.width() * 0.16f, cy + artRect.height() * 0.06f, r, iconDimPaint)
            canvas.drawRoundRect(
                RectF(cx - artRect.width() * 0.03f, cy - artRect.height() * 0.32f, cx + artRect.width() * 0.02f + artRect.width() * 0.20f, cy + artRect.height() * 0.14f),
                dp(2f), dp(2f), iconDimPaint
            )
        }
    }

    private fun drawMusicEqualizer(canvas: Canvas, rect: RectF, content: RenderedContent) {
        if (rect.width() <= 0f || rect.height() <= 0f) return
        if (!PixelNotchSettingsProxy.visualizerEnabled) return
        if (content.track?.playing == true) {
            ensureEqualizerLoop(true)
        }
        VisualizerRenderer.draw(
            canvas, rect, PixelNotchSettings.get(context).visualizerStyle,
            eqBands, accentGreen, accentBlue, 235
        )
    }

    // ------------------------------------------------------------------ helpers
    private fun applyAlpha(paint: Paint, alpha: Int): Paint {
        paint.alpha = alpha
        return paint
    }

    private fun ellipsize(text: String, paint: TextPaint, maxWidth: Float): String {
        if (maxWidth <= 0) return ""
        return TextUtils.ellipsize(text, paint, maxWidth, TextUtils.TruncateAt.END)?.toString() ?: ""
    }

    private fun formatClock(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    // ------------------------------------------------------------------ touch & click
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_UP -> {
                if (state.popup) {
                    IslandCoordinator.dismissActivePopup()
                    expanded = false
                    performClick()
                    return true
                }
                if (expanded) {
                    for (pair in controlRects) {
                        if (pair.first.contains(event.x, event.y)) {
                            mediaCommandListener?.invoke(pair.second)
                            performClick()
                            return true
                        }
                    }
                    expanded = false
                } else {
                    expanded = true
                    updateVisibilityLoop()
                }
                retargetSprings()
                performClick()
                return true
            }
        }
        return performClick()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // ------------------------------------------------------------------ external hooks
    fun refreshConfiguration() {
        retargetSprings()
        updateVisibilityLoop()
        invalidate()
    }

    fun updateVisibilityLoop() {
        val playing = state.track?.playing == true
        val eqVisible = state.type == IslandCoordinator.IslandType.MUSIC ||
            (state.popup && state.type == IslandCoordinator.IslandType.TRACK_CHANGE)
        ensureEqualizerLoop(playing && eqVisible)
    }
}
