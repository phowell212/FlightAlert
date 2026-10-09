package com.flightalert.fundamentals

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.view.ContextThemeWrapper
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.flightalert.FlightAlertSettings
import com.flightalert.ReferenceDictionaryDownloadService
import com.flightalert.ReferenceDictionaryDownloadService.Phase
import java.util.Locale
import kotlin.math.roundToInt

/** UI ownership only: bytes, cancellation and publication stay in the shared download service. */
internal class FundamentalsDictionary(
    private val context: Context,
    private val onChanged: () -> Unit,
    private val onInstalled: () -> Unit,
) {
    private val prefs = ReferenceDictionaryDownloadService.preferences(context)
    private var installed = false
    private var listening = false
    private var lastState: ReferenceDictionaryDownloadService.DownloadState? = null
    private var dialog: AlertDialog? = null
    private var dialogStatus: TextView? = null
    private var progress: ProgressBar? = null
    private var requestNotifications: (() -> Unit)? = null
    private var startError: String? = null
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

    fun start() {
        if (!listening) {
            prefs.registerOnSharedPreferenceChangeListener(listener)
            listening = true
        }
        checkInstalled()
        if (!installed && notificationsGranted()) perform { ReferenceDictionaryDownloadService.start_if_missing(context) }
        refresh()
    }

    fun stop() {
        if (listening) prefs.unregisterOnSharedPreferenceChangeListener(listener)
        listening = false
        dialog?.dismiss()
    }

    fun close() = stop()

    fun status(): String {
        if (installed) return "Dictionary ready"
        startError?.let { return "Dictionary download stopped · $it" }
        val state = ReferenceDictionaryDownloadService.state(context)
        val bytes = if (state.total > 0) String.format(Locale.US, "%,d / %,d bytes",
            state.downloaded.coerceAtLeast(0), state.total) else ""
        return when (state.phase) {
            Phase.PREPARING -> "Dictionary download preparing"
            Phase.DOWNLOADING -> "Dictionary downloading · $bytes" +
                (state.bytes_per_second?.let { String.format(Locale.US, " · %.1f MB/s", it / 1_000_000f) } ?: "")
            Phase.PAUSED -> "Dictionary download paused" + if (bytes.isNotEmpty()) " · $bytes" else ""
            Phase.ERROR -> "Dictionary download error · ${state.message}"
            Phase.TIMEOUT -> "Dictionary download timed out · Tap Retry to continue"
            Phase.STOPPED -> "Dictionary download stopped · Tap Resume to continue"
            Phase.MISSING, Phase.READY -> if (notificationsGranted()) "Dictionary unavailable · Tap Start download"
                else "Dictionary download needs notification permission"
        }
    }

    fun showControls(onRequestNotifications: () -> Unit) {
        dialog?.dismiss()
        checkInstalled()
        requestNotifications = onRequestNotifications
        val theme = FlightAlertSettings.read_visual_theme(context)
        val themed = ContextThemeWrapper(context, if (theme.style.treatment == com.flightalert.ThemeTreatment.DAYLIGHT_CARD)
            android.R.style.Theme_Material_Light_Dialog_Alert else android.R.style.Theme_Material_Dialog_Alert)
        val colors = theme.colors
        fun dp(value: Float) = (value * context.resources.displayMetrics.density).roundToInt()
        val content = FundamentalsDialogLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(12f), dp(20f), dp(12f))
        }
        fun text(value: String, muted: Boolean = false) = TextView(themed).apply {
            text = value
            textSize = if (muted) 12f else 14f
            includeFontPadding = false
            setTextColor(if (muted) colors.muted else colors.text)
            typeface = Typeface.create(theme.style.font_family, Typeface.NORMAL)
            setPadding(0, dp(6f), 0, dp(8f))
            content.addView(this)
        }
        dialogStatus = text(status()).apply { accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE }
        progress = ProgressBar(themed, null, android.R.attr.progressBarStyleHorizontal).also {
            it.progressTintList = android.content.res.ColorStateList.valueOf(colors.accent_green)
            content.addView(it, LinearLayout.LayoutParams(-1, dp(12f)))
        }
        text("Offline map labels and boundaries. Pause and Resume retain downloaded files. " +
            "A finished dictionary loads into this map automatically.", muted = true)
        val opened = AlertDialog.Builder(themed).setTitle("Reference dictionary").setView(content)
            .setPositiveButton("Start download", null).setNegativeButton("Done", null).create()
        val ui = FundamentalsUiStyle(context, theme)
        content.onResize = { if (opened.isShowing) ui.dialog(opened) }
        dialog = opened
        opened.setOnDismissListener {
            if (dialog === opened) { dialog = null; dialogStatus = null; progress = null; requestNotifications = null }
        }
        opened.show()
        ui.dialog(opened)
        opened.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            when {
                installed -> Unit
                ReferenceDictionaryDownloadService.state(context).phase in setOf(Phase.PREPARING, Phase.DOWNLOADING) ->
                    perform { ReferenceDictionaryDownloadService.pause(context) }
                !notificationsGranted() -> { opened.dismiss(); onRequestNotifications() }
                else -> perform { ReferenceDictionaryDownloadService.resume(context) }
            }
        }
        renderDialog()
    }

    private fun notificationsGranted(): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun checkInstalled() {
        if (!installed && ReferenceDictionaryDownloadService.is_available(context)) {
            installed = true
            onInstalled()
        }
    }

    private fun refresh() {
        val state = ReferenceDictionaryDownloadService.state(context)
        if (lastState != state) {
            lastState = state
            if (state.phase in setOf(Phase.READY, Phase.PAUSED, Phase.STOPPED, Phase.ERROR, Phase.TIMEOUT)) checkInstalled()
            onChanged()
            renderDialog()
        }
    }

    private fun renderDialog() {
        val opened = dialog ?: return
        val state = ReferenceDictionaryDownloadService.state(context)
        dialogStatus?.text = status()
        val active = !installed && state.phase in setOf(Phase.PREPARING, Phase.DOWNLOADING)
        progress?.apply {
            visibility = if (active) android.view.View.VISIBLE else android.view.View.GONE
            isIndeterminate = state.phase == Phase.PREPARING || state.total <= 0
            max = 10_000
            if (state.total > 0) progress = (state.downloaded.coerceIn(0, state.total).toDouble() / state.total * max).toInt()
        }
        opened.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            visibility = if (installed) android.view.View.GONE else android.view.View.VISIBLE
            text = when {
                active -> "Pause"
                !notificationsGranted() -> "Allow notifications"
                state.phase == Phase.PAUSED || state.phase == Phase.STOPPED -> "Resume"
                state.phase == Phase.ERROR || state.phase == Phase.TIMEOUT -> "Retry"
                else -> "Start download"
            }
            setTextColor(FlightAlertSettings.read_visual_theme(context).colors.accent_green)
        }
    }

    private fun perform(action: () -> Unit) {
        startError = null
        try {
            action()
        } catch (exception: IllegalStateException) {
            startError = exception.message ?: "Reopen the app and retry"
        } catch (_: SecurityException) {
            startError = "Notification permission changed · Allow notifications to continue"
            val request = requestNotifications
            dialog?.dismiss()
            request?.invoke()
        }
        onChanged()
        renderDialog()
    }
}
