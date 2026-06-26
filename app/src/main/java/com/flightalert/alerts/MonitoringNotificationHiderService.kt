@file:Suppress("FunctionName", "PackageName")

package com.flightalert.alerts

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class MonitoringNotificationHiderService : NotificationListenerService() {
    override fun onListenerConnected() {
        hide_active_monitoring_notifications()
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

        fun request_rebind_if_enabled(context: Context) {
            if (!is_enabled(context)) return
            runCatching { requestRebind(component_name(context)) }
        }

        private fun component_name(context: Context): ComponentName {
            return ComponentName(context, MonitoringNotificationHiderService::class.java)
        }
    }
}
