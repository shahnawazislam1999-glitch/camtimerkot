package com.presencetimer.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView

/**
 * Tiny floating timer drawn over other apps (SYSTEM_ALERT_WINDOW).
 * 35% opacity. Only the tiny pill itself takes touches (double-tap = pause / resume);
 * everything around it still goes to the app underneath.
 */
class OverlayController(private val context: Context, private val onDoubleTap: () -> Unit = {}) {
    companion object { const val OPACITY = 0.35f }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var position = -1
    private var lastText = ""

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).toInt()

    private fun statusBarHeight(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else dp(24f)
    }

    private fun gravityFor(pos: Int) = Gravity.TOP or when (pos) {
        0 -> Gravity.START
        2 -> Gravity.END
        else -> Gravity.CENTER_HORIZONTAL
    }

    fun show(time: String, running: Boolean, pos: Int, paused: Boolean = false) {
        if (!Settings.canDrawOverlays(context)) return
        try {
            if (view == null) create(pos)
            val v = view ?: return
            val p = params ?: return

            val prefix = if (paused) "❚❚" else "●"
            val text = "$prefix $time"
            if (text != lastText || running != (v.tag == true)) {
                val s = SpannableString(text)
                s.setSpan(ForegroundColorSpan(
                        if (paused) Color.parseColor("#FFB020") else if (running) Color.parseColor("#FF2D3F") else Color.GRAY),
                    0, prefix.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                v.text = s
                v.tag = running
                lastText = text
            }
            if (pos != position) {
                position = pos
                p.gravity = gravityFor(pos)
                wm.updateViewLayout(v, p)
            }
        } catch (_: Exception) { }
    }

    private fun create(pos: Int) {
        val v = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dp(10f).toFloat()
            }
            alpha = OPACITY   // 35% opacity
        }
        val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onDoubleTap(e: MotionEvent): Boolean { onDoubleTap(); return true }
        })
        v.setOnTouchListener { _, e -> detector.onTouchEvent(e); true }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = gravityFor(pos)
            x = dp(8f)
            y = statusBarHeight() + dp(4f)
        }
        position = pos
        wm.addView(v, p)
        view = v
        params = p
    }

    fun hide() {
        view?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        view = null; params = null; position = -1; lastText = ""
    }
}
