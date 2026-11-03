package com.coach.camera.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.coach.camera.sensor.OrientationEngine
import kotlin.math.abs

/**
 * Cửa sổ 2 (PillControlView):
 * - Nhận thao tác chạm & kéo thả vị trí độc lập hoàn toàn với GridOverlayView
 * - Cho phép đổi nhanh Chế độ (5 chế độ), Lật lưới/Xoắn ốc, Khung 4:3 - 16:9,
 *   Nhãn cú máy [WIDE/MED/TIGHT/OTS], Đếm nhịp 5s B-Roll và Cân chỉnh mép khung ngắm.
 */
@SuppressLint("ViewConstructor")
class PillControlView(
    context: Context,
    private val engine: OrientationEngine,
    private val gridOverlayView: GridOverlayView,
    private val windowManager: WindowManager,
    private val windowLayoutParams: WindowManager.LayoutParams,
    private val onCloseRequested: () -> Unit
) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    private fun dp(value: Int): Int = (value * density).toInt()

    private var isCollapsed = false
    private var isFrameAdjustExpanded = false

    private val btnDragToggle: TextView
    private val controlsContainer: LinearLayout
    private val frameAdjustContainer: LinearLayout

    private val btnMode: TextView
    private val btnVariant: TextView
    private val btnRatio: TextView
    private val btnShotTag: TextView
    private val btnRhythm5s: TextView
    private val btnZeroCalib: TextView
    private val btnFrameToggle: TextView
    private val btnClose: TextView

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(6), dp(6), dp(6), dp(6))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(18).toFloat()
            setColor(Color.argb(220, 15, 23, 42))
            setStroke(dp(1), Color.parseColor("#334155"))
        }

        // Nút đầu thanh: Vừa là tay nắm kéo thả (Drag Handle), vừa bấm để Thu gọn / Mở rộng
        btnDragToggle = createPillButton("⠿ HUD", "#10B981", isBold = true)
        setupDragAndCollapseGesture(btnDragToggle)
        addView(btnDragToggle, buildMarginParams())

        // Nhóm nút điều khiển chính
        controlsContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        btnMode = createPillButton(engine.currentMode.shortLabel, "#1E293B").apply {
            setOnClickListener {
                val nextMode = engine.cycleMode()
                text = nextMode.shortLabel
                engine.triggerShortHaptic(15L)
            }
        }
        controlsContainer.addView(btnMode, buildMarginParams())

        btnVariant = createPillButton("LẬT:${engine.currentVariant.label}", "#1E293B").apply {
            setOnClickListener {
                val nextVar = engine.cycleVariant()
                text = "LẬT:${nextVar.label}"
                engine.triggerShortHaptic(15L)
            }
        }
        controlsContainer.addView(btnVariant, buildMarginParams())

        btnRatio = createPillButton("KHUNG:${engine.currentAspectRatio.label}", "#1E293B").apply {
            setOnClickListener {
                val nextRatio = engine.toggleAspectRatio()
                text = "KHUNG:${nextRatio.label}"
                engine.triggerShortHaptic(15L)
            }
        }
        controlsContainer.addView(btnRatio, buildMarginParams())

        btnShotTag = createPillButton("[${engine.currentShotTag.tag}]", "#0F766E").apply {
            setOnClickListener {
                val nextTag = engine.cycleShotTag()
                text = "[${nextTag.tag}]"
                engine.triggerShortHaptic(15L)
            }
        }
        controlsContainer.addView(btnShotTag, buildMarginParams())

        btnRhythm5s = createPillButton("⏱ NHỊP 5S", "#B45309").apply {
            setOnClickListener {
                val running = gridOverlayView.toggleBrollRhythmTimer()
                text = if (running) "⏹ DỪNG 5S" else "⏱ NHỊP 5S"
            }
        }
        controlsContainer.addView(btnRhythm5s, buildMarginParams())

        btnZeroCalib = createPillButton("🎯 SET 0°", "#334155").apply {
            setOnClickListener {
                engine.calibrateZeroPoint()
                text = "✓ ĐÃ 0°"
                postDelayed({ text = "🎯 SET 0°" }, 1200L)
            }
            setOnLongClickListener {
                engine.resetZeroPointCalibration()
                text = "↺ GỐC 0°"
                postDelayed({ text = "🎯 SET 0°" }, 1200L)
                true
            }
        }
        controlsContainer.addView(btnZeroCalib, buildMarginParams())

        btnFrameToggle = createPillButton("📐 KHỚP KHUNG", "#334155").apply {
            setOnClickListener {
                isFrameAdjustExpanded = !isFrameAdjustExpanded
                gridOverlayView.isViewfinderCalibrationMode = isFrameAdjustExpanded
                frameAdjustContainer.visibility = if (isFrameAdjustExpanded) View.VISIBLE else View.GONE
                text = if (isFrameAdjustExpanded) "✓ XONG KHUNG" else "📐 KHỚP KHUNG"
            }
        }
        controlsContainer.addView(btnFrameToggle, buildMarginParams())

        // Cụm nút tinh chỉnh mép Trên / Dưới của khung ngắm khớp 100% với Camera Xiaomi
        frameAdjustContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
        }
        frameAdjustContainer.addView(
            createPillButton("▲ TRÊN -1%", "#0369A1").apply {
                setOnClickListener { adjustViewfinderBound(deltaTop = -0.01f, deltaBottom = 0f) }
            },
            buildMarginParams()
        )
        frameAdjustContainer.addView(
            createPillButton("▼ TRÊN +1%", "#0369A1").apply {
                setOnClickListener { adjustViewfinderBound(deltaTop = +0.01f, deltaBottom = 0f) }
            },
            buildMarginParams()
        )
        frameAdjustContainer.addView(
            createPillButton("▲ DƯỚI -1%", "#0369A1").apply {
                setOnClickListener { adjustViewfinderBound(deltaTop = 0f, deltaBottom = -0.01f) }
            },
            buildMarginParams()
        )
        frameAdjustContainer.addView(
            createPillButton("▼ DƯỚI +1%", "#0369A1").apply {
                setOnClickListener { adjustViewfinderBound(deltaTop = 0f, deltaBottom = +0.01f) }
            },
            buildMarginParams()
        )
        controlsContainer.addView(frameAdjustContainer)

        btnClose = createPillButton("✕ TẮT HUD", "#991B1B").apply {
            setOnClickListener {
                onCloseRequested()
            }
        }
        controlsContainer.addView(btnClose, buildMarginParams())

        addView(controlsContainer)
    }

    private val configListener = {
        post { syncLabelsFromEngine() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        engine.addConfigChangeListener(configListener)
        syncLabelsFromEngine()
    }

    override fun onDetachedFromWindow() {
        engine.removeConfigChangeListener(configListener)
        super.onDetachedFromWindow()
    }

    fun syncLabelsFromEngine() {
        btnMode.text = engine.currentMode.shortLabel
        btnVariant.text = "LẬT:${engine.currentVariant.label}"
        btnRatio.text = "KHUNG:${engine.currentAspectRatio.label}"
        btnShotTag.text = "[${engine.currentShotTag.tag}]"
    }

    private fun adjustViewfinderBound(deltaTop: Float, deltaBottom: Float) {
        val ratio = engine.currentAspectRatio
        val (topNorm, bottomNorm) = engine.getViewfinderBoundsNormalized(ratio)
        engine.saveViewfinderBoundsNormalized(
            ratio = ratio,
            topNorm = topNorm + deltaTop,
            bottomNorm = bottomNorm + deltaBottom
        )
        engine.triggerShortHaptic(12L)
    }

    private fun createPillButton(label: String, bgHex: String, isBold: Boolean = false): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.create(
                Typeface.MONOSPACE,
                if (isBold) Typeface.BOLD else Typeface.NORMAL
            )
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
            minWidth = dp(78)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor(bgHex))
            }
        }
    }

    private fun buildMarginParams(): LayoutParams {
        return LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(3)
            bottomMargin = dp(3)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragAndCollapseGesture(handleView: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        handleView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = windowLayoutParams.x
                    initialY = windowLayoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) {
                        isDragging = true
                        // Với Gravity.TOP or Gravity.END: x là khoảng cách tới mép phải màn hình
                        // Ngón tay kéo sang phải (dx > 0) -> khoảng cách tới mép phải giảm
                        windowLayoutParams.x = (initialX - dx).coerceAtLeast(0)
                        windowLayoutParams.y = (initialY + dy).coerceAtLeast(0)
                        try {
                            windowManager.updateViewLayout(this@PillControlView, windowLayoutParams)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        isCollapsed = !isCollapsed
                        controlsContainer.visibility = if (isCollapsed) View.GONE else View.VISIBLE
                        btnDragToggle.text = if (isCollapsed) "⠿+" else "⠿ HUD"
                        engine.triggerShortHaptic(15L)
                    }
                    true
                }
                else -> false
            }
        }
    }
}
