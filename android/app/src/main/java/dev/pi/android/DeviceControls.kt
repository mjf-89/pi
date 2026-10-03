package dev.pi.android

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** A movable task indicator with an always-visible, single-tap stop target. */
class DeviceControls(private val context: Context, private val stop: () -> Unit) {
    private class DragHandle(context: Context) : TextView(context) {
        override fun performClick(): Boolean { super.performClick(); return true }
    }
    private val manager = context.getSystemService(WindowManager::class.java)
    private var overlay: View? = null
    private var x = dp(16)
    private var y = dp(72)
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun background(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    fun clear() {
        overlay?.let { try { manager.removeView(it) } catch (_: IllegalArgumentException) {} }
        overlay = null
    }
    fun show(description: String?, approve: (() -> Unit)?) {
        clear()
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = background(Color.rgb(27, 32, 41), 24)
            elevation = dp(8).toFloat()
        }
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = this@DeviceControls.x; y = this@DeviceControls.y }
        fun reposition() {
            val metrics = context.resources.displayMetrics
            params.x = params.x.coerceIn(0, (metrics.widthPixels - panel.width).coerceAtLeast(0))
            params.y = params.y.coerceIn(0, (metrics.heightPixels - panel.height - dp(48)).coerceAtLeast(0))
            x = params.x; y = params.y
            if (overlay === panel) manager.updateViewLayout(panel, params)
        }
        val bar = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(4), 0) }
        val handle = DragHandle(context).apply {
            setText(R.string.device_task_handle); textSize = 15f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(183, 236, 211)); gravity = Gravity.CENTER
            contentDescription = context.getString(R.string.move_device_controls)
            setOnClickListener { announceForAccessibility(context.getString(R.string.move_device_controls)) }
        }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        handle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > dp(6)) moved = true
                    if (moved) { params.x = startX + dx.toInt(); params.y = startY + dy.toInt(); reposition() }
                }
                MotionEvent.ACTION_UP -> if (!moved) view.performClick()
            }
            true
        }
        bar.addView(handle, LinearLayout.LayoutParams(dp(64), dp(48)))
        bar.addView(ImageButton(context).apply {
            setImageResource(R.drawable.ic_stop_task)
            contentDescription = context.getString(R.string.stop_device_task)
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.rgb(90, 60, 65)),
                background(Color.TRANSPARENT, 24), null)
            setPadding(dp(13), dp(13), dp(13), dp(13))
            setOnClickListener { stop() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        panel.addView(bar)
        if (description != null && approve != null) {
            val width = dp(300).coerceAtMost(context.resources.displayMetrics.widthPixels - dp(32))
            panel.addView(ScrollView(context).apply {
                addView(TextView(context).apply {
                    text = description; textSize = 15f; setTextColor(Color.WHITE)
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                })
            }, LinearLayout.LayoutParams(width, dp(144)))
            panel.addView(Button(context).apply {
                setText(R.string.allow_once); isAllCaps = false
                setTextColor(Color.rgb(183, 236, 211))
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.rgb(49, 75, 68)),
                    background(Color.TRANSPARENT, 24), null)
                setOnClickListener { show(null, null); approve() }
            }, LinearLayout.LayoutParams(width, dp(48)))
        }
        manager.addView(panel, params)
        overlay = panel
        panel.post { if (overlay === panel) reposition() }
    }
}
