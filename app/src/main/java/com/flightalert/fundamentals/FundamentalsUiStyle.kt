package com.flightalert.fundamentals

import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.flightalert.VisualTheme
import com.flightalert.ui.FlightMapChromeHost
import com.flightalert.ui.FlightMapChromeRenderer
import com.flightalert.ui.FlightMapChromeStyle
import com.flightalert.ui.FlightMapLayout
import kotlin.math.roundToInt

internal class FundamentalsDialogLayout(context: Context) : LinearLayout(context) {
    var onResize: () -> Unit = {}
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        post { onResize() }
    }
}

/** Use the main app's drawing on retained native controls and dialog backgrounds. */
internal class FundamentalsUiStyle(private val context: Context, val theme: VisualTheme) {
    val colors = theme.colors
    val style = FlightMapChromeStyle(theme)
    fun dp(value: Float) = (value * context.resources.displayMetrics.density).roundToInt()
    fun alpha(color: Int, opacity: Int) = (color and 0xffffff) or (opacity shl 24)
    private val textPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(theme.style.font_family, Typeface.NORMAL)
    }
    val renderer = FlightMapChromeRenderer(Paint(Paint.ANTI_ALIAS_FLAG), Paint(Paint.ANTI_ALIAS_FLAG), textPaint, Path(),
        object : FlightMapChromeHost {
            override fun dp(value: Float) = value * context.resources.displayMetrics.density
            override fun sp(value: Float) = value * context.resources.displayMetrics.scaledDensity
            override fun ellipsize(value: String, max_width: Float) = android.text.TextUtils.ellipsize(
                value, textPaint, max_width.coerceAtLeast(0f), android.text.TextUtils.TruncateAt.END).toString()
        })

    fun drawing(draw: (Canvas, RectF) -> Unit): Drawable = object : Drawable() {
        private var opacity = 255
        override fun draw(canvas: Canvas) {
            val rect = RectF(bounds)
            val save = if (opacity < 255) canvas.saveLayerAlpha(rect, opacity) else canvas.save()
            draw(canvas, rect)
            canvas.restoreToCount(save)
        }
        override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
        override fun setColorFilter(filter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    fun panel(fill: Int = colors.panel_alt, opacity: Int = theme.style.modal_panel_alpha) = drawing { canvas, rect ->
        renderer.draw_panel_surface(canvas, rect, fill, opacity, style)
    }

    fun choiceBackground(): Drawable {
        fun surface(selected: Boolean) = drawing { canvas, rect ->
            val stroke = if (selected) colors.accent_green else colors.button_stroke
            val fill = if (selected) alpha(stroke, colors.selected_fill_alpha) else colors.button_fill
            renderer.draw_control_surface(canvas, rect, fill, stroke, selected, style = style)
        }
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), surface(true))
            addState(intArrayOf(), surface(false))
        }
        return RippleDrawable(ColorStateList.valueOf(alpha(colors.accent_green, 48)), states, null)
    }

    fun button(button: Button) = button.apply {
        isAllCaps = false
        textSize = 12f
        typeface = Typeface.create(theme.style.font_family, Typeface.BOLD)
        includeFontPadding = false
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(colors.muted, colors.accent_green, colors.text)))
        background = choiceBackground()
        minHeight = dp(44f); minimumHeight = dp(44f)
        minWidth = 0; minimumWidth = 0
        setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
    }

    fun dialog(dialog: AlertDialog, fixedHeight: Boolean = false) {
        val window = dialog.window ?: return
        window.setBackgroundDrawable(panel())
        val (w, h) = if (android.os.Build.VERSION.SDK_INT >= 30) {
            val metrics = context.getSystemService(WindowManager::class.java).currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            (metrics.bounds.width() - insets.left - insets.right).toFloat() to
                (metrics.bounds.height() - insets.top - insets.bottom).toFloat()
        } else context.resources.displayMetrics.let { it.widthPixels.toFloat() to it.heightPixels.toFloat() }
        val bounds = FlightMapLayout { it * context.resources.displayMetrics.density }.settings_panel_bounds(w, h)
        window.setLayout(bounds.width().roundToInt(), if (fixedHeight) bounds.height().roundToInt() else ViewGroup.LayoutParams.WRAP_CONTENT)
        fun fonts(view: View) {
            if (view is TextView) view.typeface = Typeface.create(theme.style.font_family, view.typeface?.style ?: Typeface.NORMAL)
            if (view is ViewGroup) for (index in 0 until view.childCount) fonts(view.getChildAt(index))
        }
        fonts(window.decorView)
        window.decorView.findViewById<TextView>(context.resources.getIdentifier("alertTitle", "id", "android"))
            ?.setTextColor(colors.text)
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE).forEach { id ->
            dialog.getButton(id)?.let { button(it) }
        }
    }
}
