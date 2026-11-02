package com.pixelnotch.app.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.pixelnotch.app.coordinator.IslandCoordinator

/**
 * Publishes charging state / battery level into the coordinator using system
 * broadcasts only (sticky BATTERY_CHANGED + POWER_CONNECTED/DISCONNECTED): no
 * polling, no background work beyond what these events push at us.
 */
class BatteryStateReceiver(private val onBattery: (IslandCoordinator.BatteryInfo) -> Unit) : BroadcastReceiver() {

    private var registered = false

    @Volatile
    private var lastInfo: IslandCoordinator.BatteryInfo? = null

    fun register(context: Context) {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(this, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                ContextCompat.registerReceiver(context, this, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            }
            registered = true
        } catch (t: Throwable) {
            Log.w(TAG, "could not register battery receiver", t)
            return
        }
        publish(currentBatteryInfo(context))
    }

    fun unregister(context: Context) {
        if (!registered) return
        try {
            context.unregisterReceiver(this)
        } catch (t: Throwable) {
            // already unregistered
        }
        registered = false
    }

    override fun onReceive(context: Context, intent: Intent) {
        publish(currentBatteryInfo(context))
    }

    private fun publish(info: IslandCoordinator.BatteryInfo) {
        synchronized(this) {
            val prior = lastInfo
            if (prior != null && prior == info) return
            lastInfo = info
        }
        onBattery(info)
        IslandCoordinator.onBattery(info)
    }

    companion object {
        const val TAG = "BatteryStateReceiver"

        fun currentBatteryInfo(context: Context): IslandCoordinator.BatteryInfo {
            if (Build.VERSION.SDK_INT >= 26) {
                val manager = context.getSystemService(BatteryManager::class.java)
                if (manager != null) {
                    val level = runCatching {
                        manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    }.getOrDefault(Int.MIN_VALUE)
                    val status = runCatching {
                        manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
                            .takeIf { it != Int.MIN_VALUE } ?: -1
                    }.getOrDefault(-1)
                    val plugged = runCatching {
                        manager.isCharging
                    }.getOrDefault(false) || status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                    if (level in 0..100 && status != Int.MIN_VALUE) {
                        return IslandCoordinator.BatteryInfo(
                            plugged = plugged,
                            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
                            levelPercent = level
                        )
                    }
                }
            }
            // Fallback: parse the sticky BATTERY_CHANGED broadcast when available
            val intent = runCatching {
                context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            }.getOrNull()
            if (intent != null) return fromIntent(context, intent)

            return IslandCoordinator.BatteryInfo(plugged = false, charging = false, levelPercent = -1)
        }

        fun fromIntent(context: Context, intent: Intent): IslandCoordinator.BatteryInfo {
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val percent = if (level in 0..Int.MAX_VALUE && scale > 0) {
                ((level * 100) / scale).coerceIn(0, 100)
            } else -1
            return IslandCoordinator.BatteryInfo(
                plugged = plugged != 0,
                charging = charging,
                levelPercent = percent
            )
        }
    }
}
