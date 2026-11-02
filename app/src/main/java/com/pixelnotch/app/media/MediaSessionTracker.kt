package com.pixelnotch.app.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.pixelnotch.app.coordinator.IslandCoordinator
import com.pixelnotch.app.ui.IslandPillView
import kotlin.math.min

/**
 * Watches active [MediaSession]s (framework API) and publishes the active track
 * through [IslandCoordinator]. Requires notification-listener access, which is
 * the permission Android grants for apps to observe media sessions.
 *
 * Transport commands are executed only when the session advertises the matching
 * capability no fabricated "success".
 */
class MediaSessionTracker(
    private val context: Context,
    private val listenerComponent: ComponentName
) {

    private val handler = Handler(Looper.getMainLooper())
    private var mediaSessionManager: MediaSessionManager? = null
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var started = false

    private data class Watch(val controller: MediaController, val callback: MediaController.Callback)
    private val watches = HashMap<MediaSession.Token, Watch>()

    fun start() {
        if (started) return
        started = true
        mediaSessionManager = context.getSystemService(MediaSessionManager::class.java)
        val msm = mediaSessionManager ?: return
        val listener = object : MediaSessionManager.OnActiveSessionsChangedListener {
            override fun onActiveSessionsChanged(controllers: MutableList<MediaController>?) {
                handler.post { handleSessionChange(controllers) }
            }
        }
        sessionsListener = listener
        try {
            msm.addOnActiveSessionsChangedListener(listener, listenerComponent, handler)
        } catch (t: Throwable) {
            Log.w(TAG, "registering active-session listener failed", t)
            started = false
            return
        }
        // first snapshot immediately (on our thread)
        handler.post {
            try {
                handleSessionChange(msm.getActiveSessions(listenerComponent))
            } catch (denied: SecurityException) {
                Log.i(TAG, "notification access not granted; media sessions unavailable")
            } catch (t: Throwable) {
                Log.w(TAG, "initial active-session snapshot failed", t)
            }
        }
    }

    fun stop() {
        if (!started) return
        started = false
        val msm = mediaSessionManager
        val listener = sessionsListener
        if (msm != null && listener != null) {
            try {
                msm.removeOnActiveSessionsChangedListener(listener)
            } catch (t: Throwable) {
                Log.w(TAG, "removing active-session listener failed", t)
            }
            sessionsListener = null
        }
        synchronized(watches) {
            for ((_, pair) in watches) {
                pair.controller.unregisterCallback(pair.callback)
            }
            watches.clear()
        }
        IslandCoordinator.onTrack(null)
    }

    // ------------------------------------------------------------------ session tracking
    private fun handleSessionChange(controllers: MutableList<MediaController>?) {
        if (!started) return
        val incoming = controllers ?: emptyList()
        synchronized(watches) {
            val newTokens = HashSet<MediaSession.Token>()
            for (controller in incoming) {
                val token = controller.sessionToken
                newTokens.add(token)
                if (!watches.containsKey(token)) {
                    val callback = ControllerCallback(token)
                    try {
                        controller.registerCallback(callback, handler)
                        watches[token] = Watch(controller, callback)
                    } catch (t: Throwable) {
                        Log.w(TAG, "registering controller callback failed", t)
                    }
                }
            }
            val stale = watches.keys.filterNot { it in newTokens }
            for (token in stale) {
                watches.remove(token)?.let {
                    try {
                        it.controller.unregisterCallback(it.callback)
                    } catch (t: Throwable) {
                        Log.w(TAG, "unregister failed", t)
                    }
                }
            }
        }
        publishBest(incoming)
    }

    private fun publishBest(list: List<MediaController>?) {
        if (!started) return
        val controllers = list ?: synchronized(watches) { watches.values.map { it.controller } }
        val active = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        }
        if (active == null) {
            // nothing actively playing: fall back to the most recently seen session so a
            // paused track stays visible (which matches system media UI behavior)
            val any = controllers.lastOrNull()
            if (any == null) {
                IslandCoordinator.onTrack(null)
            } else {
                publish(any)
            }
            return
        }
        publish(active)
    }

    private fun publish(controller: MediaController) {
        val playback = controller.playbackState
        val metadata = controller.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val artwork = downscale(metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART))

        val actions = playback?.actions ?: 0L
        val info = IslandCoordinator.TrackInfo(
            title = title?.takeIf { it.isNotBlank() },
            artist = artist?.takeIf { it.isNotBlank() },
            album = album?.takeIf { it.isNotBlank() },
            artwork = artwork,
            playing = playback?.state == PlaybackState.STATE_PLAYING,
            playerPackage = controller.packageName,
            canPlayPause = (actions and PlaybackState.ACTION_PLAY_PAUSE) != 0L ||
                (actions and PlaybackState.ACTION_PLAY) != 0L ||
                (actions and PlaybackState.ACTION_PAUSE) != 0L,
            canPrevious = (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L,
            canNext = (actions and PlaybackState.ACTION_SKIP_TO_NEXT) != 0L
        )
        IslandCoordinator.onTrack(info)
    }

    // ------------------------------------------------------------------ transport
    fun sendCommand(action: IslandPillView.MediaAction, controller: MediaController? = pickActive()) {
        val target = controller ?: return
        val playback = target.playbackState
        val transport = target.transportControls ?: return
        try {
            when (action) {
                IslandPillView.MediaAction.PLAY_PAUSE -> {
                    val canToggle = (playback?.actions ?: 0L) and
                        (PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE) != 0L
                    if (canToggle) {
                        val playingNow = playback?.state == PlaybackState.STATE_PLAYING
                        if (playingNow) transport.pause() else transport.play()
                    }
                }
                IslandPillView.MediaAction.PREVIOUS ->
                    if ((playback?.actions ?: 0L) and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L) {
                        transport.skipToPrevious()
                    }
                IslandPillView.MediaAction.NEXT ->
                    if ((playback?.actions ?: 0L) and PlaybackState.ACTION_SKIP_TO_NEXT != 0L) {
                        transport.skipToNext()
                    }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "transport command failed for ${target.packageName}", t)
        }
    }

    private fun pickActive(): MediaController? =
        synchronized(watches) {
            watches.values.map { it.controller }
                .firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: watches.values.firstOrNull()?.controller
        }

    // ------------------------------------------------------------------ callbacks
    private inner class ControllerCallback(private val token: MediaSession.Token) : MediaController.Callback() {
        override fun onPlaybackStateChanged(playbackState: PlaybackState?) {
            publishBest(null)
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            publishBest(null)
        }
    }

    // ------------------------------------------------------------------ artwork
    private fun downscale(source: Bitmap?): Bitmap? {
        if (source == null) return null
        val maxSide = 512
        if (source.width <= maxSide && source.height <= maxSide) return source
        val scale = min(maxSide.toFloat() / source.width.toFloat(), maxSide.toFloat() / source.height.toFloat())
        val newWidth = (source.width * scale).toInt().coerceAtLeast(1)
        val newHeight = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, newWidth, newHeight, true)
    }

    companion object {
        private const val TAG = "MediaSessionTracker"
    }
}
