package com.pixelnotch.app.media

import android.app.Notification
import android.content.ComponentName
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.pixelnotch.app.coordinator.IslandCoordinator

/**
 * The notification-listener service is bound by the system once the user grants
 * "notification access". It powers both:
 *
 *  1. music integration — by discovering active media sessions (the supported way
 *     for ordinary apps to read track metadata and send transport commands), and
 *  2. notification pop-ups — filtered, privacy-respecting previews forwarded to
 *     the island coordinator.
 *
 * It never posts or modifies notifications and relies solely on the content the
 * system itself delivers to listeners.
 */
class PixelNotchNotificationListener : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private var tracker: MediaSessionTracker? = null
    private var connected = false

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        tracker = MediaSessionTracker(applicationContext, ComponentName(this, PixelNotchNotificationListener::class.java))
        tracker?.start()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        connected = false
        tracker?.stop()
        tracker = null
        // ask for a rebind so music keeps working if the system drops us while the
        // user still has notification access granted (harmless request)
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            runCatching { requestRebind(ComponentName(this, PixelNotchNotificationListener::class.java)) }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        handler.post {
            if (!connected) return@post
            val info = extractPopUpInfo(sbn) ?: return@post
            IslandCoordinator.onNotification(info)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val key = stableKey(sbn)
        IslandCoordinator.onNotificationRemoved(key)
    }

    // ------------------------------------------------------------------ filtering
    private fun extractPopUpInfo(sbn: StatusBarNotification): IslandCoordinator.NotificationInfo? {
        if (sbn.packageName == packageName) return null

        // ongoing notifications (media, downloads, headset indicators) never pop up;
        // their change spam would interrupt real content. Group summaries are
        // children-visible detail containers, not user-facing notifications either.
        if (sbn.isOngoing || (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return null
        if (isRepeatedUpdate(sbn)) return null

        val notification = sbn.notification
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
        if (title.isNullOrBlank() && text.isNullOrBlank()) {
            // sensitive/redacted or icon-only posts: nothing permissible to show
            return null
        }

        val appContext = applicationContext
        val appName = runCatching {
            appContext.packageManager.getApplicationLabel(
                appContext.packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        }.getOrDefault(sbn.packageName)

        val icon: Drawable? = runCatching {
            notification.smallIcon?.loadDrawable(appContext)
        }.getOrNull()

        val isCall = notification.category == Notification.CATEGORY_CALL

        return IslandCoordinator.NotificationInfo(
            appName = appName,
            title = title?.takeIf { it.isNotBlank() },
            text = text?.takeIf { it.isNotBlank() },
            icon = icon,
            isCall = isCall,
            key = stableKey(sbn)
        )
    }

    private fun isRepeatedUpdate(sbn: StatusBarNotification): Boolean {
        val key = stableKey(sbn)
        val now = System.currentTimeMillis()
        val seen = recentKeys[key]
        if (seen != null && now - seen < UPDATE_DEBOUNCE_MS) return true
        recentKeys[key] = now
        globalPruneRecentKeys()
        return false
    }

    private fun globalPruneRecentKeys() {
        val now = System.currentTimeMillis()
        val iterator = recentKeys.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value > 4 * UPDATE_DEBOUNCE_MS) iterator.remove()
        }
        // cap the map defensively
        while (recentKeys.size > 96) {
            val oldest = recentKeys.keys.firstOrNull() ?: break
            recentKeys.remove(oldest)
        }
    }

    private fun stableKey(sbn: StatusBarNotification): String = sbn.key ?: "${sbn.packageName}|${sbn.id}|${sbn.tag.orEmpty()}"

    private val recentKeys = HashMap<String, Long>()

    companion object {
        private const val TAG = "PixelNotchNLS"
        private const val UPDATE_DEBOUNCE_MS = 800L
    }
}
