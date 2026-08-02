@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.dictionary

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import android.os.SystemClock
import androidx.core.content.edit
import com.flightalert.MainActivity
import com.flightalert.map.reference.CatalogControlStatus
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors

class ReferenceDictionaryDownloadService : Service() {
    private val state_lock = Any()
    private val executor = Executors.newSingleThreadExecutor()
    private var active_downloader: ReferenceDictionaryDownloader? = null
    private var download_generation = 0L
    private var download_running = false
    private var foreground_active = false
    private var last_progress_update_ms = 0L
    private var latest_start_id = 0

    override fun onCreate() {
        super.onCreate()
        notification_manager().createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Reference dictionary download",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Offline map labels and boundaries download progress"
                setShowBadge(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latest_start_id = startId
        val explicitly_paused = preferences().getBoolean(KEY_EXPLICITLY_PAUSED, false)
        if (!foreground_active) {
            val initial_notification = if (
                intent?.action == ACTION_PAUSE ||
                (intent?.action != ACTION_RESUME && explicitly_paused)
            ) {
                paused_notification()
            } else {
                downloading_notification()
            }
            startForeground(
                NOTIFICATION_ID,
                initial_notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            foreground_active = true
        }
        when (intent?.action) {
            ACTION_PAUSE -> pause_download()
            ACTION_RESUME -> {
                preferences().edit(commit = true) {
                    putBoolean(KEY_EXPLICITLY_PAUSED, false)
                }
                start_download()
            }
            else -> {
                if (explicitly_paused) {
                    stop_with_notification(paused_notification(), cancel_download = true)
                } else {
                    start_download()
                }
            }
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stop_with_notification(timeout_notification(), cancel_download = true)
    }

    override fun onDestroy() {
        val downloader_to_cancel = synchronized(state_lock) {
            download_generation += 1L
            download_running = false
            active_downloader.also { active_downloader = null }
        }
        downloader_to_cancel?.cancel()
        foreground_active = false
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun start_download() {
        val generation = synchronized(state_lock) {
            if (download_running) return
            download_running = true
            last_progress_update_ms = 0L
            download_generation += 1L
            download_generation
        }
        executor.execute {
            val downloader = ReferenceDictionaryDownloader(this)
            val run_is_current = synchronized(state_lock) {
                if (generation != download_generation || !download_running) {
                    false
                } else {
                    active_downloader = downloader
                    true
                }
            }
            if (!run_is_current) return@execute
            try {
                val completed = downloader.download { downloaded, total ->
                    update_progress(generation, downloaded, total)
                }
                post_result(generation) {
                    if (completed) {
                        stop_with_notification(completed_notification(), cancel_download = false)
                    } else {
                        stop_with_notification(error_notification("Download was interrupted"), false)
                    }
                }
            } catch (exception: IOException) {
                post_result(generation) {
                    stop_with_notification(
                        error_notification(exception.message ?: "Download failed"),
                        cancel_download = false,
                    )
                }
            } finally {
                synchronized(state_lock) {
                    if (active_downloader === downloader) active_downloader = null
                }
            }
        }
    }

    private fun update_progress(generation: Long, downloaded: Long, total: Long) {
        val now = SystemClock.elapsedRealtime()
        synchronized(state_lock) {
            if (generation != download_generation || !download_running) return
            if (now - last_progress_update_ms < PROGRESS_UPDATE_INTERVAL_MS) return
            last_progress_update_ms = now
        }
        mainExecutor.execute {
            synchronized(state_lock) {
                if (generation != download_generation || !download_running) return@execute
                notification_manager().notify(
                    NOTIFICATION_ID,
                    downloading_notification(downloaded, total),
                )
            }
        }
    }

    private fun post_result(generation: Long, result: () -> Unit) {
        mainExecutor.execute {
            synchronized(state_lock) {
                if (generation != download_generation || !download_running) return@execute
                download_running = false
            }
            result()
        }
    }

    private fun pause_download() {
        preferences().edit(commit = true) {
            putBoolean(KEY_EXPLICITLY_PAUSED, true)
        }
        stop_with_notification(paused_notification(), cancel_download = true)
    }

    private fun stop_with_notification(notification: Notification, cancel_download: Boolean) {
        val downloader_to_cancel = synchronized(state_lock) {
            download_generation += 1L
            download_running = false
            active_downloader.also { active_downloader = null }
        }
        if (cancel_download) downloader_to_cancel?.cancel()
        if (foreground_active) stopForeground(STOP_FOREGROUND_DETACH)
        foreground_active = false
        notification_manager().notify(NOTIFICATION_ID, notification)
        stopSelfResult(latest_start_id)
    }

    private fun downloading_notification(
        downloaded: Long? = null,
        total: Long? = null,
    ): Notification {
        val builder = base_notification(
            title = "Downloading reference dictionary",
            text = if (downloaded == null || total == null) {
                "Preparing download…"
            } else {
                exact_progress_text(downloaded, total)
            },
            ongoing = true,
        ).addAction(
            Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                "Pause",
                service_action(ACTION_PAUSE, PAUSE_REQUEST_CODE),
            ).build(),
        )
        if (downloaded != null && total != null && total > 0L) {
            val bounded = downloaded.coerceIn(0L, total)
            val scaled = (
                bounded.toDouble() / total.toDouble() * NOTIFICATION_PROGRESS_MAX.toDouble()
            ).toInt().coerceIn(0, NOTIFICATION_PROGRESS_MAX)
            builder.setProgress(NOTIFICATION_PROGRESS_MAX, scaled, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun paused_notification(): Notification = base_notification(
        title = "Reference dictionary download paused",
        text = "Downloaded bytes are saved.",
        ongoing = false,
    ).addAction(
        Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_media_play),
            "Resume",
            service_action(ACTION_RESUME, RESUME_REQUEST_CODE),
        ).build(),
    ).build()

    private fun error_notification(message: String): Notification = base_notification(
        title = "Reference dictionary download stopped",
        text = message.take(MAX_ERROR_TEXT_LENGTH),
        ongoing = false,
    ).addAction(
        Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_media_play),
            "Resume",
            service_action(ACTION_RESUME, RESUME_REQUEST_CODE),
        ).build(),
    ).build()

    private fun completed_notification(): Notification = base_notification(
        title = "Reference dictionary ready",
        text = "The offline reference dictionary is ready.",
        ongoing = false,
    ).setSmallIcon(android.R.drawable.stat_sys_download_done).build()

    private fun timeout_notification(): Notification = base_notification(
        title = "Reference dictionary download stopped",
        text = "Reopen Flight Alert to continue the download.",
        ongoing = false,
    ).addAction(
        Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_view),
            "Open Flight Alert",
            open_app_action(),
        ).build(),
    ).build()

    private fun base_notification(
        title: String,
        text: String,
        ongoing: Boolean,
    ): Notification.Builder = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(Notification.BigTextStyle().bigText(text))
        .setContentIntent(open_app_action())
        .setCategory(Notification.CATEGORY_PROGRESS)
        .setOnlyAlertOnce(true)
        .setOngoing(ongoing)
        .setAutoCancel(!ongoing)

    private fun service_action(action: String, request_code: Int): PendingIntent =
        PendingIntent.getForegroundService(
            this,
            request_code,
            Intent(this, ReferenceDictionaryDownloadService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun open_app_action(): PendingIntent = PendingIntent.getActivity(
        this,
        OPEN_APP_REQUEST_CODE,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun exact_progress_text(downloaded: Long, total: Long): String = String.format(
        Locale.US,
        "%,d / %,d bytes",
        downloaded.coerceAtLeast(0L),
        total.coerceAtLeast(0L),
    )

    private fun notification_manager(): NotificationManager =
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    private fun preferences() = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)

    companion object {
        private const val CHANNEL_ID = "reference_dictionary_download"
        private const val NOTIFICATION_ID = 2200
        private const val NOTIFICATION_PROGRESS_MAX = 10_000
        private const val PROGRESS_UPDATE_INTERVAL_MS = 500L
        private const val MAX_ERROR_TEXT_LENGTH = 120
        private const val PREFERENCES_NAME = "reference_dictionary_download"
        private const val KEY_EXPLICITLY_PAUSED = "explicitly_paused"
        private const val ACTION_PAUSE =
            "com.flightalert.map.reference.dictionary.action.PAUSE"
        private const val ACTION_RESUME =
            "com.flightalert.map.reference.dictionary.action.RESUME"
        private const val PAUSE_REQUEST_CODE = 2201
        private const val RESUME_REQUEST_CODE = 2202
        private const val OPEN_APP_REQUEST_CODE = 2203

        fun start_if_missing(context: Context) {
            val app_context = context.applicationContext
            val package_available = ReferenceDictionaryPackage.package_candidates(app_context).any {
                val opened = ReferenceDictionaryPackage.open_if_available(it) ?: return@any false
                opened.use { reference_package ->
                    reference_package.reference_class_catalog.status == CatalogControlStatus.AVAILABLE
                }
            }
            if (package_available) return
            if (
                app_context.getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
                    .getBoolean(KEY_EXPLICITLY_PAUSED, false)
            ) return
            app_context.startForegroundService(
                Intent(app_context, ReferenceDictionaryDownloadService::class.java),
            )
        }
    }
}
