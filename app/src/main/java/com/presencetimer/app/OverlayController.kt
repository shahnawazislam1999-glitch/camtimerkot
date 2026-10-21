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
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView

/**
 * Tiny floating timer drawn over other apps (SYSTEM_ALERT_WINDOW).
 * 35% opacity. Only the tiny pill itself takes touches (double-tap = pause / resume);
 * everything around it still goes to the app underneath.
 */
class OverlayController(
    private val context: Context,
    private val onSingleTap: () -> Unit = {},
    private val onDoubleTap: () -> Unit = {}
) {
    companion object { const val OPACITY = 0.35f }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var position = -1
    private var lastText = ""
    private var absolute = false      // true once the user dragged it (or a saved spot was restored)
    private val prefs = context.getSharedPreferences("overlay_pos", Context.MODE_PRIVATE)

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
                // Picking another preset in Settings overrides a dragged spot.
                absolute = false
                try { prefs.edit().clear().apply() } catch (_: Exception) { }
                p.gravity = gravityFor(pos)
                p.x = dp(8f); p.y = statusBarHeight() + dp(4f)
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
        var dragging = false
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!dragging) onSingleTap()
                return true
            }
            override fun onDoubleTapEvent(e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_UP && !dragging) onDoubleTap()
                return true
            }
        })
        v.setOnTouchListener { view, e ->
            val p = params
            if (p != null) when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = false
                    if (!absolute) {   // switch from preset gravity to absolute screen coordinates
                        val loc = IntArray(2)
                        view.getLocationOnScreen(loc)
                        p.gravity = Gravity.TOP or Gravity.START
                        p.x = loc[0]; p.y = loc[1]
                        absolute = true
                        try { wm.updateViewLayout(view, p) } catch (_: Exception) { }
                    }
                    downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && Math.hypot(dx.toDouble(), dy.toDouble()) > slop) dragging = true
                    if (dragging) {
                        val dm = context.resources.displayMetrics
                        p.x = (startX + dx).toInt().coerceIn(0, maxOf(0, dm.widthPixels - view.width))
                        p.y = (startY + dy).toInt().coerceIn(0, maxOf(0, dm.heightPixels - view.height))
                        try { wm.updateViewLayout(view, p) } catch (_: Exception) { }
                    }
                }
                MotionEvent.ACTION_UP -> if (dragging) {
                    try { prefs.edit().putInt("x", p.x).putInt("y", p.y).putInt("preset", position).apply() } catch (_: Exception) { }
                }
            }
            detector.onTouchEvent(e)
            true
        }
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
        // Restore a spot the user dragged it to (only while the same position preset is selected).
        try {
            if (prefs.contains("x") && prefs.getInt("preset", -1) == pos) {
                p.gravity = Gravity.TOP or Gravity.START
                p.x = prefs.getInt("x", p.x); p.y = prefs.getInt("y", p.y)
                absolute = true
            }
        } catch (_: Exception) { }
        wm.addView(v, p)
        view = v
        params = p
    }

    fun hide() {
        view?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        view = null; params = null; position = -1; lastText = ""; absolute = false
    }
}
