@file:Suppress(
    "FunctionName",
    "LocalVariableName",
)

package com.flightalert.alerts

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService



internal object MonitoringNotificationHider {
    private const val WATCHER_BOOT_TIMEOUT_MS = 30L * 1000L
    private const val SERVICE_CLASS_NAME = "com.flightalert.MonitoringNotificationHiderService"

    // Keep notification-listener state shared across Android component rebinds.
    @JvmField
    @Volatile
    internal var connected = false

    fun is_enabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val expected = component_name(context)
        return enabled.split(':').any { flattened ->
            ComponentName.unflattenFromString(flattened) == expected
        }
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
        if (!is_enabled(context)) return
        runCatching { NotificationListenerService.requestRebind(component_name(context)) }
    }

    private fun component_name(context: Context): ComponentName {
        return ComponentName(context, SERVICE_CLASS_NAME)
    }
}

enum class MonitoringNotificationHiderStatus {
    BOOTING,
    DOWN,
    UP
}
