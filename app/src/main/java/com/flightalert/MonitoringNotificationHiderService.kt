@file:Suppress(
    "FunctionName",
)

package com.flightalert

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.flightalert.alerts.MonitoringNotificationHider



class MonitoringNotificationHiderService : NotificationListenerService() {
    override fun onListenerConnected() {
        MonitoringNotificationHider.connected = true
        hide_active_monitoring_notifications()
    }

    override fun onListenerDisconnected() {
        MonitoringNotificationHider.connected = false
    }

    override fun onDestroy() {
        MonitoringNotificationHider.connected = false
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
                sbn.id == FlightAlertAppSettings.AlertService.ONGOING_NOTIFICATION_ID &&
                sbn.notification.channelId ==
                FlightAlertAppSettings.AlertService.MONITORING_CHANNEL_ID &&
                sbn.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0
    }

    private companion object {
        const val WATCHER_SNOOZE_MS = 24L * 60L * 60L * 1000L
    }
}
