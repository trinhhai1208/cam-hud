package com.coach.camera.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.View
import com.coach.camera.sensor.AspectRatioMode
import com.coach.camera.sensor.CoachMode
import com.coach.camera.sensor.GridVariant
import com.coach.camera.sensor.ModeRule
import com.coach.camera.sensor.OrientationEngine
import com.coach.camera.sensor.SensorTelemetry
import com.coach.camera.sensor.ShotTag
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Cửa sổ 1 (GridOverlayView):
 * - Gắn cờ FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE trên WindowManager
 * - Chạm xuyên 100% xuống ứng dụng Camera gốc Xiaomi
 * - Vẽ toàn bộ Lưới bố cục, Thước thủy, Khung dáng mẫu, Vector B-Roll và Thanh đếm nhịp 5s
 */
class GridOverlayView(
    context: Context,
    private val engine: OrientationEngine
) : View(context) {

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Float = value * density

    // Bảng màu chuẩn HUD điện ảnh
    private val colorEmerald = Color.parseColor("#10B981")
    private val colorAmber = Color.parseColor("#F59E0B")
    private val colorRed = Color.parseColor("#EF4444")
    private val colorCyan = Color.parseColor("#38BDF8")
    private val colorWhiteTranslucent = Color.argb(175, 255, 255, 255)
    private val colorGridLine = Color.argb(135, 255, 255, 255)
    private val colorHudBg = Color.argb(165, 15, 23, 42)

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
        color = colorEmerald
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.1f)
        color = colorGridLine
    }

    private val dashedAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.6f)
        color = colorCyan
        pathEffect = DashPathEffect(floatArrayOf(dp(8f), dp(5f)), 0f)
    }

    private val goldenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.8f)
        color = colorAmber
    }

    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.8f)
        strokeCap = Paint.Cap.ROUND
        color = colorEmerald
    }

    private val hudBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colorHudBg
    }

    private val warnPaintBg = Paint(hudBgPaint).apply {
        color = Color.argb(215, 180, 83, 9)
    }

    private val rhythmFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        alpha = 185
    }

    private val calibGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3.2f)
        color = colorCyan
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(11.5f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val tipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(11f)
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }

    private val viewfinderRect = RectF()
    private val tempRect = RectF()
    private val dynamicPath = Path()

    private var latestTelemetry = SensorTelemetry(
        pitchDeg = 0f,
        rollDeg = 0f,
        rawPitchDeg = 0f,
        rawRollDeg = 0f,
        isPitchAligned = true,
        isRollAligned = true,
        isFullyAligned = true,
        gyroMeanOmegaRadS = 0f,
        gyroSigmaOmegaRadS = 0f,
        isPanTooFast = false,
        isPanJerky = false,
        isSmoothMotion = true,
        displayRotation = 0
    )

    // Chế độ cân chỉnh mép khung ngắm 4:3 / 16:9
    var isViewfinderCalibrationMode: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    // Đồng hồ đếm nhịp 5 giây cho B-Roll (1s Hold + 3s Action + 1s Hold)
    private val mainHandler = Handler(Looper.getMainLooper())
    var isRhythmTimerRunning: Boolean = false
        private set
    private var rhythmStartTimeMs: Long = 0L
    private var lastSecondTick: Int = -1

    private val rhythmTicker = object : Runnable {
        override fun run() {
            if (!isRhythmTimerRunning) return
            val elapsedMs = System.currentTimeMillis() - rhythmStartTimeMs
            val currentSec = (elapsedMs / 1000L).toInt()
            if (currentSec != lastSecondTick && currentSec <= 5) {
                lastSecondTick = currentSec
                // Rung nhẹ báo chuyển pha tại 0s, 1s (bắt đầu lia), 4s (dừng lia), 5s (hoàn tất)
                if (currentSec in listOf(0, 1, 4, 5)) {
                    engine.triggerShortHaptic(if (currentSec == 1 || currentSec == 4) 35L else 20L)
                }
            }
            if (elapsedMs >= 5000L) {
                isRhythmTimerRunning = false
                invalidate()
            } else {
                invalidate()
                mainHandler.postDelayed(this, 33L)
            }
        }
    }

    fun updateTelemetry(telemetry: SensorTelemetry) {
        latestTelemetry = telemetry
        invalidate()
    }

    fun toggleBrollRhythmTimer(): Boolean {
        if (isRhythmTimerRunning) {
            isRhythmTimerRunning = false
            mainHandler.removeCallbacks(rhythmTicker)
            invalidate()
            return false
        }
        isRhythmTimerRunning = true
        rhythmStartTimeMs = System.currentTimeMillis()
        lastSecondTick = -1
        mainHandler.post(rhythmTicker)
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        isRhythmTimerRunning = false
        mainHandler.removeCallbacks(rhythmTicker)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 1. Xác định vùng khung ngắm (4:3 hoặc 16:9) đã lưu trong SharedPreferences
        val (topNorm, bottomNorm) = engine.getViewfinderBoundsNormalized()
        viewfinderRect.set(
            dp(6f),
            (h * topNorm).coerceIn(dp(6f), h * 0.48f),
            w - dp(6f),
            (h * bottomNorm).coerceIn(h * 0.52f, h - dp(6f))
        )

        val rule = engine.getCurrentRule()
        val mode = engine.currentMode

        // 2. Vẽ khung viền Viewfinder đổi màu theo trạng thái cân bằng (Hysteresis 0.3°)
        drawViewfinderBorder(canvas, viewfinderRect, latestTelemetry)

        // 3. Vẽ lưới bố cục đặc trưng cho từng chế độ trong 5 chế độ
        when (mode) {
            CoachMode.PORTRAIT_HALF -> drawPortraitHalfOverlay(canvas, viewfinderRect)
            CoachMode.PORTRAIT_FULL -> drawPortraitFullOverlay(canvas, viewfinderRect)
            CoachMode.LANDSCAPE -> drawLandscapeOverlay(canvas, viewfinderRect, engine.currentVariant)
            CoachMode.ARCHITECTURE -> drawArchitectureOverlay(canvas, viewfinderRect, engine.currentVariant)
            CoachMode.STREET -> drawStreetFibonacciOverlay(canvas, viewfinderRect, engine.currentVariant)
            CoachMode.BROLL -> drawBrollCoachOverlay(canvas, viewfinderRect, rule, engine.currentVariant)
        }

        // 4. Vẽ thước thủy cân bằng trung tâm (Spirit Level)
        drawCenterSpiritLevel(canvas, viewfinderRect, latestTelemetry)

        // 5. Vẽ thanh thông số cảm biến & lời nhắc quy tắc (HUD Header + Tip Footer)
        drawTopTelemetryHud(canvas, viewfinderRect, rule, latestTelemetry)
        drawBottomCoachingTip(canvas, viewfinderRect, rule)

        // 6. Nếu đang bật chế độ Cân chỉnh khung ngắm (Viewfinder Calibration), vẽ thanh căn mép
        if (isViewfinderCalibrationMode) {
            drawViewfinderCalibrationGuides(canvas, viewfinderRect, engine.currentAspectRatio)
        }
    }

    private fun drawViewfinderBorder(canvas: Canvas, rect: RectF, t: SensorTelemetry) {
        framePaint.color = when {
            t.isFullyAligned && (!t.isPanTooFast && !t.isPanJerky || engine.currentMode != CoachMode.BROLL) ->
                colorEmerald
            abs(t.rollDeg) > 4.0f || t.isPanTooFast -> colorRed
            else -> colorAmber
        }
        framePaint.strokeWidth = if (t.isFullyAligned) dp(2.4f) else dp(1.8f)

        // Vẽ viền khung ngắm mảnh kèm 4 góc chữ L đậm kiểu máy quay chuyên nghiệp
        gridPaint.color = Color.argb(85, 255, 255, 255)
        canvas.drawRect(rect, gridPaint)

        val cornerLen = dp(22f)
        // Top-Left
        canvas.drawLine(rect.left, rect.top, rect.left + cornerLen, rect.top, framePaint)
        canvas.drawLine(rect.left, rect.top, rect.left, rect.top + cornerLen, framePaint)
        // Top-Right
        canvas.drawLine(rect.right - cornerLen, rect.top, rect.right, rect.top, framePaint)
        canvas.drawLine(rect.right, rect.top, rect.right, rect.top + cornerLen, framePaint)
        // Bottom-Left
        canvas.drawLine(rect.left, rect.bottom - cornerLen, rect.left, rect.bottom, framePaint)
        canvas.drawLine(rect.left, rect.bottom, rect.left + cornerLen, rect.bottom, framePaint)
        // Bottom-Right
        canvas.drawLine(rect.right - cornerLen, rect.bottom, rect.right, rect.bottom, framePaint)
        canvas.drawLine(rect.right, rect.bottom - cornerLen, rect.right, rect.bottom, framePaint)
    }

    private fun drawRuleOfThirdsGrid(canvas: Canvas, rect: RectF, showPowerPoints: Boolean = true) {
        gridPaint.color = colorGridLine
        gridPaint.strokeWidth = dp(1.0f)

        val thirdW = rect.width() / 3f
        val thirdH = rect.height() / 3f

        val x1 = rect.left + thirdW
        val x2 = rect.left + thirdW * 2f
        val y1 = rect.top + thirdH
        val y2 = rect.top + thirdH * 2f

        canvas.drawLine(x1, rect.top, x1, rect.bottom, gridPaint)
        canvas.drawLine(x2, rect.top, x2, rect.bottom, gridPaint)
        canvas.drawLine(rect.left, y1, rect.right, y1, gridPaint)
        canvas.drawLine(rect.left, y2, rect.right, y2, gridPaint)

        if (showPowerPoints) {
            goldenPaint.style = Paint.Style.STROKE
            goldenPaint.strokeWidth = dp(1.5f)
            goldenPaint.color = colorAmber
            val r = dp(4.5f)
            canvas.drawCircle(x1, y1, r, goldenPaint)
            canvas.drawCircle(x2, y1, r, goldenPaint)
            canvas.drawCircle(x1, y2, r, goldenPaint)
            canvas.drawCircle(x2, y2, r, goldenPaint)
        }
    }

    /**
     * Chế độ 1A: Chân dung Bán thân (Rule of Thirds + Eye-line 1/3 trên + Khung dáng Bán thân)
     */
    private fun drawPortraitHalfOverlay(canvas: Canvas, rect: RectF) {
        drawRuleOfThirdsGrid(canvas, rect, showPowerPoints = true)

        // Vạch ngang Eye-line tại 1/3 trên
        val eyeY = rect.top + rect.height() / 3f
        dashedAccentPaint.color = colorCyan
        canvas.drawLine(rect.left + dp(12f), eyeY, rect.right - dp(12f), eyeY, dashedAccentPaint)

        textPaint.color = colorCyan
        textPaint.textSize = dp(10.5f)
        canvas.drawText("EYE-LINE (ĐẶT MẮT MẪU) • ỐNG KÍNH 2X", rect.left + dp(14f), eyeY - dp(6f), textPaint)

        // Khung gợi ý dáng bán thân (Đầu & Vai) ở trung tâm
        val cx = rect.centerX()
        val headRx = rect.width() * 0.11f
        val headRy = rect.height() * 0.095f
        tempRect.set(cx - headRx, eyeY - headRy * 0.9f, cx + headRx, eyeY + headRy * 1.1f)
        canvas.drawOval(tempRect, dashedAccentPaint)

        // Đường vai buông lỏng
        dynamicPath.reset()
        val shoulderTopY = tempRect.bottom + rect.height() * 0.03f
        val shoulderWideX = rect.width() * 0.28f
        val waistBottomY = rect.top + rect.height() * 0.86f
        dynamicPath.moveTo(cx - headRx * 0.45f, tempRect.bottom)
        dynamicPath.lineTo(cx - shoulderWideX, shoulderTopY + rect.height() * 0.06f)
        dynamicPath.lineTo(cx - shoulderWideX * 1.08f, waistBottomY)
        dynamicPath.moveTo(cx + headRx * 0.45f, tempRect.bottom)
        dynamicPath.lineTo(cx + shoulderWideX, shoulderTopY + rect.height() * 0.04f)
        dynamicPath.lineTo(cx + shoulderWideX * 1.08f, waistBottomY)
        canvas.drawPath(dynamicPath, dashedAccentPaint)

        // Vạch giới hạn không cắt khớp cổ tay / khuỷu tay
        dashedAccentPaint.color = colorEmerald
        canvas.drawLine(cx - shoulderWideX, waistBottomY, cx + shoulderWideX, waistBottomY, dashedAccentPaint)
    }

    /**
     * Chế độ 1B: Chân dung Toàn thân - Hack chiều cao (Vạch mũi giày 5% mép dưới + Khung tam giác)
     */
    private fun drawPortraitFullOverlay(canvas: Canvas, rect: RectF) {
        drawRuleOfThirdsGrid(canvas, rect, showPowerPoints = false)

        // Vạch giới hạn bàn chân sát mép dưới (chừa 5% khoảng trống dưới chân)
        val footY = rect.bottom - rect.height() * 0.05f
        val headTopY = rect.top + rect.height() * 0.44f
        val cx = rect.centerX()

        goldenPaint.color = colorEmerald
        goldenPaint.strokeWidth = dp(2.0f)
        canvas.drawLine(rect.left + rect.width() * 0.15f, footY, rect.right - rect.width() * 0.15f, footY, goldenPaint)

        textPaint.color = colorEmerald
        textPaint.textSize = dp(10.5f)
        canvas.drawText("VẠCH MŨI GIÀY (CHỪA 5% MÉP DƯỚI)", rect.left + rect.width() * 0.16f, footY - dp(6f), textPaint)

        // Khung gợi ý dáng đứng tam giác (Triangle Pose) giúp kéo dài chân
        dashedAccentPaint.color = colorAmber
        dynamicPath.reset()
        dynamicPath.moveTo(cx, headTopY)
        dynamicPath.lineTo(cx - rect.width() * 0.24f, footY)
        dynamicPath.lineTo(cx + rect.width() * 0.24f, footY)
        dynamicPath.close()
        canvas.drawPath(dynamicPath, dashedAccentPaint)

        // Vòng tròn vị trí đầu ở khoảng giữa khung hình (~48%)
        dashedAccentPaint.color = colorCyan
        val headRadius = rect.width() * 0.065f
        canvas.drawCircle(cx, headTopY + headRadius * 1.2f, headRadius, dashedAccentPaint)
        textPaint.color = colorCyan
        canvas.drawText("ĐẦU MẪU (~GIỮA KHUNG) • NGỬA MÁY +5°..+12°", cx - dp(115f), headTopY - dp(8f), textPaint)
    }

    /**
     * Chế độ 2: Phong cảnh (Thước thủy + Lưới 1/3 ngang + Đường dẫn chữ S / Đường chéo)
     */
    private fun drawLandscapeOverlay(canvas: Canvas, rect: RectF, variant: GridVariant) {
        drawRuleOfThirdsGrid(canvas, rect, showPowerPoints = true)

        val yTopThird = rect.top + rect.height() / 3f
        val yBottomThird = rect.top + (rect.height() * 2f) / 3f

        // Nhấn mạnh 2 đường chân trời 1/3
        dashedAccentPaint.color = colorCyan
        canvas.drawLine(rect.left, yTopThird, rect.right, yTopThird, dashedAccentPaint)
        canvas.drawLine(rect.left, yBottomThird, rect.right, yBottomThird, dashedAccentPaint)

        textPaint.color = colorCyan
        textPaint.textSize = dp(10f)
        canvas.drawText("CHÂN TRỜI 1/3 TRÊN (ĐẤT ĐẸP)", rect.left + dp(12f), yTopThird - dp(6f), textPaint)
        canvas.drawText("CHÂN TRỜI 1/3 DƯỚI (TRỜI ĐẸP)", rect.left + dp(12f), yBottomThird - dp(6f), textPaint)

        when (variant) {
            GridVariant.VARIANT_1 -> {
                // Đường dẫn cong chữ S (S-Curve Leading Line) từ tiền cảnh dưới lên tiêu điểm 1/3 trên
                goldenPaint.color = colorAmber
                goldenPaint.strokeWidth = dp(2.0f)
                dynamicPath.reset()
                dynamicPath.moveTo(rect.left + rect.width() * 0.18f, rect.bottom - dp(10f))
                dynamicPath.cubicTo(
                    rect.left + rect.width() * 0.85f, rect.top + rect.height() * 0.72f,
                    rect.left + rect.width() * 0.15f, rect.top + rect.height() * 0.50f,
                    rect.left + rect.width() * 0.667f, yTopThird
                )
                canvas.drawPath(dynamicPath, goldenPaint)
            }
            GridVariant.VARIANT_2 -> {
                // Đường dẫn chéo (Leading Diagonals)
                goldenPaint.color = colorAmber
                goldenPaint.strokeWidth = dp(1.5f)
                canvas.drawLine(rect.left, rect.bottom, rect.right, rect.top, goldenPaint)
                canvas.drawLine(rect.right, rect.bottom, rect.left + rect.width() / 3f, yTopThird, goldenPaint)
            }
            else -> {
                // Khung gợi ý Tiền cảnh (Foreground Anchor) ở góc dưới
                dashedAccentPaint.color = colorAmber
                tempRect.set(
                    rect.left + rect.width() * 0.08f,
                    yBottomThird + dp(12f),
                    rect.left + rect.width() * 0.42f,
                    rect.bottom - dp(12f)
                )
                canvas.drawRoundRect(tempRect, dp(8f), dp(8f), dashedAccentPaint)
                textPaint.color = colorAmber
                canvas.drawText("ĐẶT TIỀN CẢNH (LỚP 1)", tempRect.left + dp(8f), tempRect.centerY(), textPaint)
            }
        }
    }

    /**
     * Chế độ 3: Kiến trúc & Nội thất (Trục đối xứng chữ thập + Vertical Grid chống đổ cột Keystone)
     */
    private fun drawArchitectureOverlay(canvas: Canvas, rect: RectF, variant: GridVariant) {
        val cx = rect.centerX()
        val cy = rect.centerY()

        // Các đường gióng dọc song song (Vertical Parallel Grid) kiểm tra độ thẳng đứng của cột/tòa nhà
        val columns = if (variant == GridVariant.VARIANT_2) 8 else 6
        val stepX = rect.width() / columns
        gridPaint.color = Color.argb(140, 56, 189, 248)
        gridPaint.strokeWidth = dp(1.1f)
        for (i in 1 until columns) {
            val x = rect.left + i * stepX
            canvas.drawLine(x, rect.top, x, rect.bottom, gridPaint)
        }

        // Các đường gióng ngang kiểm tra mặt phẳng sàn & trần
        val rows = 6
        val stepY = rect.height() / rows
        gridPaint.color = Color.argb(100, 255, 255, 255)
        for (j in 1 until rows) {
            val y = rect.top + j * stepY
            canvas.drawLine(rect.left, y, rect.right, y, gridPaint)
        }

        // Trục đối xứng chữ thập chính giữa (Central Symmetry Crosshair)
        goldenPaint.color = if (latestTelemetry.isFullyAligned) colorEmerald else colorAmber
        goldenPaint.strokeWidth = dp(2.0f)
        canvas.drawLine(cx, rect.top, cx, rect.bottom, goldenPaint)
        canvas.drawLine(rect.left, cy, rect.right, cy, goldenPaint)

        textPaint.color = goldenPaint.color
        textPaint.textSize = dp(10.5f)
        val keystoneStatus = if (latestTelemetry.isPitchAligned) {
            "KEYSTONE OK (CỘT THẲNG ĐỨNG • PITCH ≈ 0°)"
        } else {
            "⚠ CẢNH BÁO ĐỔ CỘT! HÃY GIỮ PITCH = 0° (${String.format("%+.1f°", latestTelemetry.pitchDeg)})"
        }
        canvas.drawText(keystoneStatus, rect.left + dp(14f), rect.bottom - dp(38f), textPaint)
    }

    /**
     * Chế độ 4: Đường phố & Đời thường (Xoắn ốc Tỷ lệ vàng Fibonacci có lật 4 hướng + Looking Room)
     */
    private fun drawStreetFibonacciOverlay(canvas: Canvas, rect: RectF, variant: GridVariant) {
        drawRuleOfThirdsGrid(canvas, rect, showPowerPoints = false)

        // Xác định hướng lật của Xoắn ốc Fibonacci theo 4 hướng (Variant 1..4)
        val flipX = variant == GridVariant.VARIANT_2 || variant == GridVariant.VARIANT_3
        val flipY = variant == GridVariant.VARIANT_3 || variant == GridVariant.VARIANT_4

        val cx = rect.centerX()
        val cy = rect.centerY()

        canvas.save()
        canvas.scale(if (flipX) -1f else 1f, if (flipY) -1f else 1f, cx, cy)

        // Vẽ đường xoắn ốc Fibonacci mượt mà hội tụ về điểm vàng 1/3
        val focalX = rect.left + rect.width() * 0.382f
        val focalY = rect.top + rect.height() * 0.382f

        goldenPaint.color = colorAmber
        goldenPaint.strokeWidth = dp(2.2f)
        goldenPaint.style = Paint.Style.STROKE

        dynamicPath.reset()
        dynamicPath.moveTo(rect.right, rect.bottom)
        dynamicPath.cubicTo(
            rect.left + rect.width() * 0.1f, rect.bottom,
            rect.left, rect.top + rect.height() * 0.15f,
            focalX, rect.top + rect.height() * 0.12f
        )
        dynamicPath.cubicTo(
            rect.left + rect.width() * 0.68f, rect.top + rect.height() * 0.12f,
            rect.left + rect.width() * 0.65f, rect.top + rect.height() * 0.56f,
            focalX, focalY
        )
        canvas.drawPath(dynamicPath, goldenPaint)

        // Tâm xoắn ốc (Vị trí đặt chủ thể)
        dashedAccentPaint.color = colorCyan
        canvas.drawCircle(focalX, focalY, dp(18f), dashedAccentPaint)
        canvas.drawCircle(focalX, focalY, dp(4f), goldenPaint)

        // Mũi tên chỉ hướng nhìn 2/3 (Looking Room / Lead Room)
        val arrowEndX = rect.left + rect.width() * 0.86f
        canvas.drawLine(focalX + dp(22f), focalY, arrowEndX, focalY, dashedAccentPaint)
        canvas.drawLine(arrowEndX - dp(10f), focalY - dp(7f), arrowEndX, focalY, dashedAccentPaint)
        canvas.drawLine(arrowEndX - dp(10f), focalY + dp(7f), arrowEndX, focalY, dashedAccentPaint)

        canvas.restore()

        textPaint.color = colorAmber
        textPaint.textSize = dp(10.5f)
        val dirLabel = when (variant) {
            GridVariant.VARIANT_1 -> "FIBONACCI HƯỚNG 1 (TRÊN-TRÁI) • LOOKING ROOM →"
            GridVariant.VARIANT_2 -> "FIBONACCI HƯỚNG 2 (TRÊN-PHẢI) • ← LOOKING ROOM"
            GridVariant.VARIANT_3 -> "FIBONACCI HƯỚNG 3 (DƯỚI-PHẢI) • ← LOOKING ROOM"
            GridVariant.VARIANT_4 -> "FIBONACCI HƯỚNG 4 (DƯỚI-TRÁI) • LOOKING ROOM →"
        }
        canvas.drawText(dirLabel, rect.left + dp(14f), rect.top + dp(46f), textPaint)
    }

    /**
     * Chế độ 5: Quay Video B-Roll (Vector hướng lia + Cảnh báo Gyro + Thanh đếm nhịp 5 giây)
     */
    private fun drawBrollCoachOverlay(
        canvas: Canvas,
        rect: RectF,
        rule: ModeRule,
        variant: GridVariant
    ) {
        drawRuleOfThirdsGrid(canvas, rect, showPowerPoints = false)

        val cx = rect.centerX()
        val cy = rect.centerY()
        val t = latestTelemetry

        // 1. Vẽ mũi tên Vector hướng dẫn chiều lia máy (Pan / Tilt / Orbit)
        val vectorColor = if (t.isSmoothMotion) colorCyan else colorAmber
        goldenPaint.color = vectorColor
        goldenPaint.strokeWidth = dp(2.4f)

        val vecHalfW = rect.width() * 0.25f
        val vecHalfH = rect.height() * 0.18f
        val vectorLabel = when (variant) {
            GridVariant.VARIANT_1 -> {
                val y = cy - dp(45f)
                canvas.drawLine(cx - vecHalfW, y, cx + vecHalfW, y, goldenPaint)
                canvas.drawLine(cx + vecHalfW - dp(12f), y - dp(8f), cx + vecHalfW, y, goldenPaint)
                canvas.drawLine(cx + vecHalfW - dp(12f), y + dp(8f), cx + vecHalfW, y, goldenPaint)
                "PAN TRÁI → PHẢI"
            }
            GridVariant.VARIANT_2 -> {
                val y = cy - dp(45f)
                canvas.drawLine(cx + vecHalfW, y, cx - vecHalfW, y, goldenPaint)
                canvas.drawLine(cx - vecHalfW + dp(12f), y - dp(8f), cx - vecHalfW, y, goldenPaint)
                canvas.drawLine(cx - vecHalfW + dp(12f), y + dp(8f), cx - vecHalfW, y, goldenPaint)
                "PAN PHẢI → TRÁI"
            }
            GridVariant.VARIANT_3 -> {
                val x = cx + rect.width() * 0.24f
                canvas.drawLine(x, cy + vecHalfH, x, cy - vecHalfH, goldenPaint)
                canvas.drawLine(x - dp(8f), cy - vecHalfH + dp(12f), x, cy - vecHalfH, goldenPaint)
                canvas.drawLine(x + dp(8f), cy - vecHalfH + dp(12f), x, cy - vecHalfH, goldenPaint)
                "TILT DƯỚI ↑ LÊN"
            }
            GridVariant.VARIANT_4 -> {
                tempRect.set(cx - vecHalfW, cy - dp(70f), cx + vecHalfW, cy - dp(10f))
                canvas.drawArc(tempRect, 200f, 140f, false, goldenPaint)
                "ORBIT VÒNG CUNG ↻"
            }
        }

        // 2. Nhãn Cỡ cảnh hiện tại (5-Shot Sequence Tag) + Hướng chuyển động
        val shotTag: ShotTag = engine.currentShotTag
        textPaint.color = Color.WHITE
        textPaint.textSize = dp(11.5f)
        val badgeText = "SHOT: [${shotTag.tag}] (${shotTag.descVi}) • $vectorLabel"
        val badgeW = textPaint.measureText(badgeText) + dp(20f)
        tempRect.set(cx - badgeW / 2f, rect.top + dp(40f), cx + badgeW / 2f, rect.top + dp(64f))
        canvas.drawRoundRect(tempRect, dp(6f), dp(6f), hudBgPaint)
        canvas.drawText(badgeText, tempRect.left + dp(10f), tempRect.centerY() + dp(4f), textPaint)

        // 3. Cảnh báo tốc độ lia máy Gyroscope (ω ≤ 0.25 rad/s, σ_ω ≤ 0.08 rad/s, ω ≥ 0.08 rad/s)
        // "Lia quá chậm" chỉ có nghĩa trong 3s lia chính; 2 nhịp đệm đầu/cuối vốn phải đứng yên
        val showTooSlow = t.isPanTooSlow && isInRhythmActionPhase()
        if (t.isPanTooFast || t.isPanJerky || showTooSlow) {
            val warnMsg = when {
                t.isPanTooFast -> rule.warningFastPan
                t.isPanJerky -> rule.warningJerky
                else -> rule.warningTooSlow
            }
            val warnW = (rect.width() - dp(24f)).coerceAtMost(dp(340f))
            tempRect.set(cx - warnW / 2f, rect.top + dp(72f), cx + warnW / 2f, rect.top + dp(102f))
            canvas.drawRoundRect(tempRect, dp(8f), dp(8f), warnPaintBg)

            textPaint.color = Color.WHITE
            textPaint.textSize = dp(10f)
            canvas.drawText("⚠ $warnMsg", tempRect.left + dp(8f), tempRect.centerY() + dp(3.5f), textPaint)
        }

        // 4. Thanh đếm nhịp 5 giây (1s đệm đầu + 3s hành động chính + 1s đệm cuối)
        drawBrollFiveSecondRhythmBar(canvas, rect)
    }

    private fun isInRhythmActionPhase(): Boolean {
        if (!isRhythmTimerRunning) return false
        val elapsedMs = System.currentTimeMillis() - rhythmStartTimeMs
        return elapsedMs in 1000L until 4000L
    }

    private fun drawBrollFiveSecondRhythmBar(canvas: Canvas, rect: RectF) {
        val barLeft = rect.left + dp(16f)
        val barRight = rect.right - dp(16f)
        val barBottom = rect.bottom - dp(38f)
        val barTop = barBottom - dp(22f)
        val barWidth = barRight - barLeft

        tempRect.set(barLeft, barTop, barRight, barBottom)
        canvas.drawRoundRect(tempRect, dp(6f), dp(6f), hudBgPaint)

        // Vạch chia tỷ lệ 1s (20%) - 3s (60%) - 1s (20%)
        val x1s = barLeft + barWidth * 0.20f
        val x4s = barLeft + barWidth * 0.80f

        if (isRhythmTimerRunning) {
            val elapsedSec = ((System.currentTimeMillis() - rhythmStartTimeMs) / 1000f).coerceIn(0f, 5f)
            val progressRatio = elapsedSec / 5f
            val fillColor = when {
                elapsedSec < 1f -> colorAmber // 1s Đệm đầu (Giữ im)
                elapsedSec < 4f -> colorEmerald // 3s Lia máy chính
                else -> colorAmber // 1s Đệm cuối (Giữ im)
            }
            rhythmFillPaint.color = fillColor
            rhythmFillPaint.alpha = 185
            tempRect.set(barLeft, barTop, barLeft + barWidth * progressRatio, barBottom)
            canvas.drawRoundRect(tempRect, dp(6f), dp(6f), rhythmFillPaint)

            val phaseText = when {
                elapsedSec < 1f -> String.format(Locale.US, "PHA 1 (GIỮ IM ĐẦU): %.1fs / 1.0s", elapsedSec)
                elapsedSec < 4f -> String.format(Locale.US, "PHA 2 (LIA MƯỢT 3S): %.1fs / 4.0s", elapsedSec)
                else -> String.format(Locale.US, "PHA 3 (GIỮ IM CUỐI): %.1fs / 5.0s", elapsedSec)
            }
            textPaint.color = Color.WHITE
            textPaint.textSize = dp(10f)
            canvas.drawText(phaseText, barLeft + dp(8f), (barTop + barBottom) / 2f + dp(3.5f), textPaint)
        } else {
            textPaint.color = colorWhiteTranslucent
            textPaint.textSize = dp(9.5f)
            canvas.drawText(
                "NHỊP 5S: [1s ĐỆM] ── [3s LIA] ── [1s ĐỆM] (Bấm 5s trên Pill)",
                barLeft + dp(8f),
                (barTop + barBottom) / 2f + dp(3.5f),
                textPaint
            )
        }

        gridPaint.color = Color.WHITE
        canvas.drawLine(x1s, barTop, x1s, barBottom, gridPaint)
        canvas.drawLine(x4s, barTop, x4s, barBottom, gridPaint)
    }

    /**
     * Thước thủy cân bằng đôi (Roll & Pitch) ở tâm khung ngắm.
     */
    private fun drawCenterSpiritLevel(canvas: Canvas, rect: RectF, t: SensorTelemetry) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val radius = min(rect.width(), rect.height()) * 0.14f

        levelPaint.color = if (t.isFullyAligned) colorEmerald else colorAmber
        levelPaint.strokeWidth = dp(2.8f) // Khôi phục độ dày 2.8f sau mỗi frame

        // Camera Xiaomi khóa UI dọc nên overlay không tự xoay: khi cầm ngang, xoay thước thủy
        // theo hướng cầm máy (nhận từ trọng lực) để vạch chuẩn song song với chân trời thật
        val levelRotationDeg = when (t.displayRotation) {
            Surface.ROTATION_90 -> 90f
            Surface.ROTATION_270 -> -90f
            Surface.ROTATION_180 -> 180f
            else -> 0f
        }
        canvas.save()
        canvas.rotate(levelRotationDeg, cx, cy)

        // Vạch chuẩn ngang cố định ở hai bên tâm
        gridPaint.color = Color.argb(170, 255, 255, 255)
        gridPaint.strokeWidth = dp(1.5f)
        canvas.drawLine(cx - radius * 1.35f, cy, cx - radius * 0.95f, cy, gridPaint)
        canvas.drawLine(cx + radius * 0.95f, cy, cx + radius * 1.35f, cy, gridPaint)

        // Đường chân trời nghiêng theo góc Roll và tịnh tiến dọc theo độ lệch Pitch
        val pitchOffsetPx = (t.pitchDeg * dp(2.2f)).coerceIn(-radius * 0.75f, radius * 0.75f)
        val rollRad = Math.toRadians(-t.rollDeg.toDouble())
        val dx = (cos(rollRad) * radius * 0.85f).toFloat()
        val dy = (sin(rollRad) * radius * 0.85f).toFloat()

        canvas.drawLine(
            cx - dx,
            cy + pitchOffsetPx - dy,
            cx + dx,
            cy + pitchOffsetPx + dy,
            levelPaint
        )

        // Vòng tròn tâm ngắm
        levelPaint.style = Paint.Style.STROKE
        levelPaint.strokeWidth = dp(1.8f)
        canvas.drawCircle(cx, cy, dp(6f), levelPaint)
        canvas.restore()
    }

    /**
     * Thanh HUD phía trên hiển thị Chế độ, Khung tỷ lệ, Góc Pitch/Roll và Tốc độ Gyroscope.
     */
    private fun drawTopTelemetryHud(
        canvas: Canvas,
        rect: RectF,
        rule: ModeRule,
        t: SensorTelemetry
    ) {
        val pad = dp(8f)
        val barTop = rect.top + pad
        val barHeight = dp(24f)

        val statusDot = if (t.isFullyAligned) "●" else "○"
        val hudLine = String.format(
            Locale.US,
            "%s [%s] P:%+.1f° R:%+.1f° ω:%.2f %s",
            engine.currentMode.shortLabel,
            engine.currentAspectRatio.label,
            t.pitchDeg,
            t.rollDeg,
            t.gyroMeanOmegaRadS,
            statusDot
        )

        textPaint.textSize = dp(9.5f)
        val textW = (textPaint.measureText(hudLine) + dp(14f)).coerceAtMost(rect.width() - dp(16f))
        tempRect.set(rect.left + pad, barTop, rect.left + pad + textW, barTop + barHeight)
        canvas.drawRoundRect(tempRect, dp(6f), dp(6f), hudBgPaint)

        textPaint.color = if (t.isFullyAligned) colorEmerald else colorAmber
        canvas.drawText(hudLine, tempRect.left + dp(7f), tempRect.centerY() + dp(3.5f), textPaint)
    }

    /**
     * Thanh nhắc nhở quy tắc nhiếp ảnh ngắn gọn ở sát mép dưới khung ngắm.
     */
    private fun drawBottomCoachingTip(canvas: Canvas, rect: RectF, rule: ModeRule) {
        val barHeight = dp(26f)
        val barBottom = rect.bottom - dp(6f)
        val barTop = barBottom - barHeight

        tempRect.set(rect.left + dp(8f), barTop, rect.right - dp(8f), barBottom)
        canvas.drawRoundRect(tempRect, dp(6f), dp(6f), hudBgPaint)

        tipTextPaint.color = Color.WHITE
        tipTextPaint.textSize = dp(10f)
        val maxChars = ((tempRect.width() - dp(16f)) / dp(5.6f)).toInt().coerceAtLeast(20)
        val displayTip = if (rule.tip.length > maxChars) {
            rule.tip.take(maxChars - 1) + "…"
        } else {
            rule.tip
        }
        canvas.drawText(displayTip, tempRect.left + dp(8f), tempRect.centerY() + dp(3.5f), tipTextPaint)
    }

    /**
     * Hiển thị thanh căn chỉnh mép Trên/Dưới của khung ngắm (Viewfinder Calibration).
     */
    private fun drawViewfinderCalibrationGuides(
        canvas: Canvas,
        rect: RectF,
        ratio: AspectRatioMode
    ) {
        canvas.drawLine(0f, rect.top, width.toFloat(), rect.top, calibGuidePaint)
        canvas.drawLine(0f, rect.bottom, width.toFloat(), rect.bottom, calibGuidePaint)

        textPaint.color = colorCyan
        textPaint.textSize = dp(11.5f)
        val (topNorm, bottomNorm) = engine.getViewfinderBoundsNormalized(ratio)
        canvas.drawText(
            "▲ MÉP TRÊN KHUNG ${ratio.label} (${(topNorm * 100).toInt()}%)",
            dp(16f),
            rect.top - dp(8f),
            textPaint
        )
        canvas.drawText(
            "▼ MÉP DƯỚI KHUNG ${ratio.label} (${(bottomNorm * 100).toInt()}%)",
            dp(16f),
            rect.bottom + dp(16f),
            textPaint
        )
    }
}
