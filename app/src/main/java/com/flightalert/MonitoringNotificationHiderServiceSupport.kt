@file:Suppress(
    "FunctionName",
)

package com.flightalert

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.flightalert.alerts.MonitoringNotificationHider

internal const val WATCHER_SNOOZE_MS = 24L * 60L * 60L * 1000L