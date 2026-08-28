@file:Suppress(
    "FunctionName",
    "LocalVariableName",
)

package com.flightalert.alerts

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService



internal object MonitoringNotificationHider {
    private const val WATCHER_BOOT_TIMEOUT_MS = 30L * 1000L

    // Keep notification-listener state shared across Android component rebinds.
    @JvmField
    @Volatile
    internal var connected = false

    fun is_enabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.isNotificationListenerAccessGranted(component_name(context))
    }

    fun settings_intent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    fun status(
        context: Context,
        app_opened_elapsed_ms: Long,
        now_elapsed_ms: Long = SystemClock.elapsedRealtime()
    ): MonitoringNotificationHiderStatus {
        if (!is_enabled(context)) return MonitoringNotificationHiderStatus.DOWN
        if (connected) return MonitoringNotificationHiderStatus.UP
        val opened_for_ms = now_elapsed_ms - app_opened_elapsed_ms
        return if (opened_for_ms in 0 until WATCHER_BOOT_TIMEOUT_MS) {
            MonitoringNotificationHiderStatus.BOOTING
        } else {
            MonitoringNotificationHiderStatus.DOWN
        }
    }

    fun request_rebind_if_enabled(context: Context) {
        if (connected) return
        if (!is_enabled(context)) return
        runCatching { NotificationListenerService.requestRebind(component_name(context)) }
    }

    private fun component_name(context: Context): ComponentName {
        return ComponentName(context, MonitoringNotificationHiderService::class.java)
    }
}

enum class MonitoringNotificationHiderStatus {
    BOOTING,
    DOWN,
    UP
}
