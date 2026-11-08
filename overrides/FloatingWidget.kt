package com.k2fsa.sherpa.onnx.vad.asr

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * Plávajúci widget nad obrazovkou (SYSTEM_ALERT_WINDOW).
 * Polopriehladny, presuvatelny potahom, tlacidla:
 *   [Start/Stop] [Vloz do aktivnej] [Kopirovat vsetko] [Skryt]
 * Potahnutim k okraju sa ukotvi - zobrazi sa len pruzina, ktora sa da znova roztiahnut.
 */
class FloatingWidget(private val ctx: Context) {

    interface Host {
        fun fwToggleRecording()          // start/stop nahravania
        fun fwIsRecording(): Boolean
        fun fwPasteToActive()            // vloz text do aktivnej appky
        fun fwCopyAll()                  // skopiruj cely text
    }

    private var wm: WindowManager? = null
    private var root: View? = null
    private var collapsed = false

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    fun show(host: Host) {
        if (root != null) return
        wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16); y = dp(120)
        }

        val bg = GradientDrawable().apply {
            setColor(Color.argb(170, 30, 33, 44))
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), Color.argb(120, 255, 255, 255))
        }

        val row = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = bg
        }

        fun mkBtn(label: String, fg: Int = Color.WHITE, onClick: () -> Unit): android.widget.TextView {
            val b = android.widget.TextView(ctx)
            b.text = label
            b.setTextColor(fg)
            b.textSize = 13f
            b.setPadding(dp(12), dp(8), dp(12), dp(8))
            b.background = GradientDrawable().apply {
                setColor(Color.argb(70, 255, 255, 255))
                cornerRadius = dp(14).toFloat()
            }
            b.setOnClickListener {
                if (collapsed) return@setOnClickListener
                onClick()
                (it.parent?.parent?.parent as? View)?.invalidate()
            }
            return b
        }

        // pruzina na roztiahnutie, ked je zbaleny
        val handle = android.widget.TextView(ctx).apply {
            text = "⬤"
            setTextColor(Color.argb(180, 255, 255, 255))
            textSize = 14f
            setPadding(dp(10), dp(10), dp(6), dp(10))
        }

        val btnRec = mkBtn("⏺") { host.fwToggleRecording() }
        val btnStop = mkBtn("⏹") { if (host.fwIsRecording()) host.fwToggleRecording() }
        val btnPaste = mkBtn("VLOŽ") { host.fwPasteToActive() }
        val btnCopy = mkBtn("KOPIA") { host.fwCopyAll() }
        val btnHide = mkBtn("✕") { hide() }

        fun expand() {
            collapsed = false
            row.visibility = View.VISIBLE
            handle.visibility = View.GONE
        }
        fun collapse() {
            collapsed = true
            row.visibility = View.GONE
            handle.visibility = View.VISIBLE
        }
        handle.setOnClickListener { expand() }

        val wrapper = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            addView(handle); handle.visibility = View.GONE
            addView(row.apply {
                addView(btnRec); addView(btnStop); addView(btnPaste); addView(btnCopy); addView(btnHide)
            })
        }

        // presunanie potahom + ukotvenie k okraju
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var moved = false
        wrapper.setOnTouchListener(object : View.OnTouchListener {
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY
                        startX = params.x; startY = params.y; moved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - downX).toInt()
                        val dy = (e.rawY - downY).toInt()
                        if (dx * dx + dy * dy > 100) moved = true
                        params.x = startX + dx
                        params.y = startY + dy
                        try { wm?.updateViewLayout(wrapper, params) } catch (ex: Exception) { }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved) {
                            // ukotvenie k najblizsiemu okraju
                            val w = ctx.resources.displayMetrics.widthPixels
                            val wWidth = wrapper.width.takeIf { it > 0 } ?: dp(200)
                            val center = params.x + wWidth / 2
                            if (center < w / 2) { params.x = 0 } else { params.x = w - wWidth }
                            try { wm?.updateViewLayout(wrapper, params) } catch (ex: Exception) { }
                            if (params.x <= 0 || params.x >= w - wWidth) collapse()
                        } else {
                            if (collapsed) expand()
                        }
                        return true
                    }
                }
                return false
            }
        })

        root = wrapper
        try { wm?.addView(wrapper, params) } catch (e: Exception) { root = null }
    }

    fun hide() {
        try { root?.let { wm?.removeView(it) } } catch (e: Exception) { }
        root = null
    }

    fun isVisible() = root != null
}