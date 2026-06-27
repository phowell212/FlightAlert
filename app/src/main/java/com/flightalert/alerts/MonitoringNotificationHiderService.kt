@file:Suppress("FunctionName", "PackageName")

package com.flightalert.alerts

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

enum class MonitoringNotificationHiderStatus {
    BOOTING,
    DOWN,
    UP
}

class MonitoringNotificationHiderService : NotificationListenerService() {
    override fun onListenerConnected() {
        connected = true
        hide_active_monitoring_notifications()
    }

    override fun onListenerDisconnected() {
        connected = false
    }

    override fun onDestroy() {
        connected = false
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        hide_if_monitoring_notification(sbn)
    }

    override fun onNotificationPosted(
        sbn: StatusBarNotification,
        rankingMap: RankingMap
    ) {
        hide_if_monitoring_notification(sbn)
    }

    private fun hide_active_monitoring_notifications() {
        activeNotifications?.forEach { hide_if_monitoring_notification(it) }
    }

    private fun hide_if_monitoring_notification(sbn: StatusBarNotification) {
        if (!is_monitoring_notification(sbn)) return
        runCatching { snoozeNotification(sbn.key, WATCHER_SNOOZE_MS) }
        runCatching { cancelNotification(sbn.key) }
    }

    private fun is_monitoring_notification(sbn: StatusBarNotification): Boolean {
        return sbn.packageName == packageName &&
                sbn.id == AircraftAlertService.ONGOING_NOTIFICATION_ID &&
                sbn.notification.channelId == AircraftAlertService.MONITORING_CHANNEL_ID &&
                sbn.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0
    }

    companion object {
        private const val WATCHER_SNOOZE_MS = 24L * 60L * 60L * 1000L
        private const val WATCHER_BOOT_TIMEOUT_MS = 30L * 1000L

        @Volatile
        private var connected = false

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
            runCatching { requestRebind(component_name(context)) }
        }

        private fun component_name(context: Context): ComponentName {
            return ComponentName(context, MonitoringNotificationHiderService::class.java)
        }
    }
}
