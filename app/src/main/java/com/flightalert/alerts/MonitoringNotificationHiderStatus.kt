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

enum class MonitoringNotificationHiderStatus {
    BOOTING,
    DOWN,
    UP
}