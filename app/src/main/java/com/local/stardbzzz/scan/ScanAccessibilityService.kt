package com.local.stardbzzz.scan

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import com.local.stardbzzz.MainActivity
import com.local.stardbzzz.ZZZAchievementApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Performs the scan's taps and swipes and hosts the floating control panel
 * (an accessibility overlay needs no separate "draw over other apps" permission).
 */
class ScanAccessibilityService : AccessibilityService(), GestureInput {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val coordinator get() = (application as ZZZAchievementApp).container.scanCoordinator
    private var overlay: ScanOverlay? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = ScanOverlay(this)
        coordinator.attachInput(this)
        scope.launch {
            coordinator.state.collect { overlay?.render(it) }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    /** The panel is laid out for the screen size at render time; rotating (app in portrait, game in landscape) moves it. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.relayout()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        coordinator.detachInput(this)
        overlay?.remove()
        overlay = null
        scope.cancel()
        super.onDestroy()
    }

    override suspend fun tap(point: ScreenPoint) {
        val (x, y) = toPixels(point)
        dispatchOrFail(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, TapMillis))
    }

    override suspend fun swipe(from: ScreenPoint, to: ScreenPoint, durationMillis: Long, holdMillis: Long) {
        val (startX, startY) = toPixels(from)
        val (endX, endY) = toPixels(to)
        val drag = Path().apply { moveTo(startX, startY); lineTo(endX, endY) }
        if (holdMillis <= 0) {
            dispatchOrFail(GestureDescription.StrokeDescription(drag, 0, durationMillis.coerceAtLeast(1)))
            return
        }
        // The same finger stays on the end point before lifting, so it lets go at rest and the list stops where
        // the drag ended instead of flinging on by however fast the last touch events happened to arrive.
        val stroke = GestureDescription.StrokeDescription(drag, 0, durationMillis.coerceAtLeast(1), true)
        dispatchOrFail(stroke)
        dispatchOrFail(stroke.continueStroke(Path().apply { moveTo(endX, endY) }, 0, holdMillis, false))
    }

    /**
     * A gesture the system cancelled (usually because the screen was touched) means the page may have
     * changed under the scan; retrying could attribute rows to the wrong category, so stop instead.
     */
    private suspend fun dispatchOrFail(stroke: GestureDescription.StrokeDescription) {
        if (!dispatch(stroke)) throw IllegalStateException("自动操作被系统取消，扫描期间请不要触碰屏幕")
    }

    private fun toPixels(point: ScreenPoint): Pair<Float, Float> {
        val size = currentDisplaySize(getSystemService(DisplayManager::class.java))
        return point.x * size.x to point.y * size.y
    }

    private suspend fun dispatch(stroke: GestureDescription.StrokeDescription): Boolean = withContext(Dispatchers.Main) {
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        suspendCancellableCoroutine { continuation ->
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }
            if (!dispatchGesture(gesture, callback, null) && continuation.isActive) continuation.resume(false)
        }
    }

    /**
     * Small panel at the top of the screen, between the game's "完成数" counter (right edge ~0.14 of the width)
     * and the tab bar (from ~0.43), so it never covers anything the scan reads or touches. It starts at 0.20:
     * any closer and OCR merged its text with the counter into one line. Its text avoids tab and category names
     * and stays short so the panel ends well before the tab bar.
     * Outside a run it can be dragged out of the way (e.g. off the game's menu button); a run always
     * puts it back at that spot, since the panel shows up in the captured frames.
     */
    private inner class ScanOverlay(service: AccessibilityService) {
        private val windowManager = service.getSystemService(WindowManager::class.java)
        private val status = TextView(service).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        private val primary = button(service)
        private val secondary = button(service)
        private val tertiary = button(service)
        private val root = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.argb(220, 20, 20, 20))
            }
            addView(status)
            addView(primary)
            addView(secondary)
            addView(tertiary)
            setOnTouchListener(DragListener())
        }
        private var attached = false
        private var running = false
        private var dragged: Pair<Int, Int>? = null
        private var params: WindowManager.LayoutParams? = null

        fun render(state: ScanState) {
            running = state is ScanState.Running
            when (state) {
                ScanState.Idle -> {
                    remove()
                    return
                }
                ScanState.Ready -> {
                    // 快速 compares with the last scan; 全盘 reads everything (and is needed once first).
                    status.text = "成就页选扫描"
                    primary.bind("快速") { coordinator.start(ScanMode.Quick) }
                    secondary.bind("全盘") { coordinator.start(ScanMode.Full) }
                    tertiary.bind("退出") { coordinator.endSession() }
                }
                is ScanState.Running -> {
                    val progress = state.progress
                    status.text = when {
                        progress.quick -> "快速同步 · 已读 ${progress.rows} 条"
                        progress.capturing -> "已截 ${progress.queued} 屏 · 已读 ${progress.rows} 条"
                        // The game is no longer touched; the rest is OCR of the queued pages.
                        else -> "截图完成，可切走 · 识别 ${progress.read}/${progress.queued}"
                    }
                    primary.bind("停止") { coordinator.stop() }
                    secondary.bind(null) {}
                    tertiary.bind(null) {}
                }
                is ScanState.Finished -> {
                    val quick = state.report.quick
                    status.text = when {
                        quick == null -> "完成 · ${state.report.diffs.size} 处差异"
                        quick.expectedNew <= 0 -> "没有新成就"
                        else -> "新完成 ${quick.expectedNew} 个，找到 ${quick.found} 个"
                    }
                    primary.bind("查看") { openApp() }
                    secondary.bind("关闭") { coordinator.endSession() }
                    tertiary.bind(null) {}
                }
                is ScanState.Failed -> {
                    status.text = state.message
                    primary.bind("重试") { coordinator.start() }
                    secondary.bind("退出") { coordinator.endSession() }
                    tertiary.bind(null) {}
                }
            }
            show()
        }

        fun relayout() {
            // A spot dragged to in one orientation can be off-screen or over the scan area in the other.
            dragged = null
            if (attached) show()
        }

        fun remove() {
            if (attached) {
                runCatching { windowManager.removeView(root) }
                attached = false
            }
        }

        private fun show() {
            val width = currentDisplaySize(getSystemService(DisplayManager::class.java)).x
            status.maxWidth = (width * StatusMaxWidth).toInt()
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                val position = dragged.takeUnless { running } ?: ((width * PanelLeft).toInt() to dp(4))
                x = position.first
                y = position.second
            }
            this.params = params
            if (attached) {
                windowManager.updateViewLayout(root, params)
            } else {
                windowManager.addView(root, params)
                attached = true
            }
        }

        /** Moves the panel with the finger; touches on its buttons still go to the buttons. */
        private inner class DragListener : View.OnTouchListener {
            private var startRawX = 0f
            private var startRawY = 0f
            private var startX = 0
            private var startY = 0

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                val current = params ?: return false
                if (running) return false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startRawX = event.rawX
                        startRawY = event.rawY
                        startX = current.x
                        startY = current.y
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val screen = currentDisplaySize(getSystemService(DisplayManager::class.java))
                        current.x = (startX + event.rawX - startRawX).toInt()
                            .coerceIn(0, (screen.x - root.width).coerceAtLeast(0))
                        current.y = (startY + event.rawY - startRawY).toInt()
                            .coerceIn(0, (screen.y - root.height).coerceAtLeast(0))
                        dragged = current.x to current.y
                        if (attached) runCatching { windowManager.updateViewLayout(root, current) }
                    }
                }
                return true
            }
        }

        private fun openApp() {
            coordinator.requestOpenScanScreen()
            startActivity(
                Intent(this@ScanAccessibilityService, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }

        private fun button(service: AccessibilityService) = TextView(service).apply {
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(Color.rgb(0xF7, 0xD9, 0x4C))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(6) }
        }

        private fun TextView.bind(label: String?, onClick: () -> Unit) {
            visibility = if (label == null) View.GONE else View.VISIBLE
            text = label
            setOnClickListener { onClick() }
        }

        private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    }

    private companion object {
        const val TapMillis = 60L

        /** Default panel position and status text width, as fractions of the screen width. */
        const val PanelLeft = 0.20f
        const val StatusMaxWidth = 0.09f
    }
}
