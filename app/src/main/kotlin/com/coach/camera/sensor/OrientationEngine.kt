package com.coach.camera.sensor

import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.Surface
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Các chế độ quay chụp chính của CAM-HUD theo đặc tả Doc1 v2.0.
 */
enum class CoachMode(val id: String, val shortLabel: String, val titleVi: String) {
    PORTRAIT_HALF("PORTRAIT_HALF", "PORT 1/2", "Chân dung: Bán thân"),
    PORTRAIT_FULL("PORTRAIT_FULL", "PORT FULL", "Chân dung: Toàn thân (Hack dáng)"),
    LANDSCAPE("LANDSCAPE", "LANDSCAPE", "Phong cảnh (Landscape)"),
    ARCHITECTURE("ARCHITECTURE", "ARCH", "Kiến trúc & Nội thất"),
    STREET("STREET", "STREET", "Đường phố (Fibonacci)"),
    BROLL("BROLL", "B-ROLL", "Quay Video B-Roll");

    fun next(): CoachMode {
        val all = entries
        return all[(ordinal + 1) % all.size]
    }
}

/**
 * Tỷ lệ khung ngắm trên màn hình 20:9 của Redmi Note 13 Pro.
 */
enum class AspectRatioMode(val label: String) {
    RATIO_4_3("4:3"),
    RATIO_16_9("16:9");

    fun toggle(): AspectRatioMode = if (this == RATIO_4_3) RATIO_16_9 else RATIO_4_3
}

/**
 * 4 hướng lật của Xoắn ốc Tỷ lệ vàng (Fibonacci Spiral) và biến thể đường dẫn.
 */
enum class GridVariant(val label: String) {
    VARIANT_1("V1"),
    VARIANT_2("V2"),
    VARIANT_3("V3"),
    VARIANT_4("V4");

    fun next(): GridVariant {
        val all = entries
        return all[(ordinal + 1) % all.size]
    }
}

/**
 * Tổ hợp 5 cú máy B-Roll chuẩn (5-Shot Sequence Tags).
 */
enum class ShotTag(val tag: String, val descVi: String) {
    WIDE("WIDE", "Toàn cảnh"),
    MED("MED", "Trung cảnh"),
    TIGHT_HANDS("TIGHT-HANDS", "Cận tay"),
    TIGHT_FACE("TIGHT-FACE", "Cận mặt"),
    OTS("OTS", "Qua vai");

    fun next(): ShotTag {
        val all = entries
        return all[(ordinal + 1) % all.size]
    }
}

/**
 * Cấu trúc quy tắc ngưỡng cảm biến được nạp từ assets/rules_data.json.
 */
data class ModeRule(
    val mode: CoachMode,
    val displayName: String,
    val pitchMinDeg: Float,
    val pitchMaxDeg: Float,
    val rollMaxDeg: Float,
    val omegaMinRadS: Float = 0.08f,
    val omegaMaxRadS: Float = 0.25f,
    val sigmaOmegaMaxRadS: Float = 0.08f,
    val poseSvgPath: String? = null,
    val tip: String,
    val warningFastPan: String = "Lia máy quá nhanh! Hãy chậm lại (ω ≤ 0.25 rad/s).",
    val warningJerky: String = "Tay đang bị khựng, hãy khóa cổ tay và di chuyển bằng cả cơ thể!",
    val warningTooSlow: String = "Lia máy quá chậm hoặc đang giữ tĩnh (ω ≥ 0.08 rad/s)."
)

/**
 * Trạng thái cảm biến thời gian thực truyền tới GridOverlayView và MainActivity.
 */
data class SensorTelemetry(
    val pitchDeg: Float,
    val rollDeg: Float,
    val rawPitchDeg: Float,
    val rawRollDeg: Float,
    val isPitchAligned: Boolean,
    val isRollAligned: Boolean,
    val isFullyAligned: Boolean,
    val gyroMeanOmegaRadS: Float,
    val gyroSigmaOmegaRadS: Float,
    val isPanTooFast: Boolean,
    val isPanJerky: Boolean,
    val isPanTooSlow: Boolean = false,
    val isSmoothMotion: Boolean,
    val displayRotation: Int
)

/**
 * Thống kê phiên quay/chụp dùng để ghi vào shot_log.json khi MediaStore phát hiện file mới.
 */
data class ShotSessionSummary(
    val avgPitchDeg: Float,
    val avgRollDeg: Float,
    val maxGyroRadS: Float,
    val avgSigmaRadS: Float,
    val isSmooth: Boolean
)

/**
 * Bộ xử lý cảm biến trung tâm (Zero-Gimbal-Lock Architecture):
 * - TYPE_GAME_ROTATION_VECTOR: Tính toán Pitch và Roll trực tiếp từ World Up Vector [R[6], R[7], R[8]]
 * - Triệt tiêu hoàn toàn Gimbal Lock khi cầm máy chụp thẳng đứng (Portrait Upright)
 * - Tự động nhận diện hướng xoay màn hình (0°, 90°, 270°, 180°) từ trọng lực có vùng trễ Hysteresis
 *   (không dùng display.rotation để tránh lỗi UnsupportedOperationException và UI bị Xiaomi khóa dọc)
 * - Calibration 0° (Pitch/Roll Offset)
 * - Cửa sổ trượt Gyroscope 300ms đo vận tốc góc ω và độ giật cổ tay σ_ω bằng timestamp phần cứng (ns)
 * - Bộ đệm lịch sử cú máy 120 giây phục vụ trích xuất chính xác theo DURATION của video
 */
class OrientationEngine(private val context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "OrientationEngine"
        private const val PREFS_NAME = "cam_hud_prefs"

        private const val KEY_CALIB_PITCH = "calib_pitch_offset"
        private const val KEY_CALIB_ROLL = "calib_roll_offset"
        private const val KEY_VF_43_TOP = "vf_43_top"
        private const val KEY_VF_43_BOTTOM = "vf_43_bottom"
        private const val KEY_VF_169_TOP = "vf_169_top"
        private const val KEY_VF_169_BOTTOM = "vf_169_bottom"
        private const val KEY_ACTIVE_MODE = "active_mode"
        private const val KEY_ASPECT_RATIO = "aspect_ratio"
        private const val KEY_SHOT_TAG = "shot_tag"

        const val DEFAULT_HYSTERESIS_DEG = 0.3f
        const val DEFAULT_REDRAW_THRESHOLD_DEG = 0.15f
        const val DEFAULT_GYRO_WINDOW_MS = 300L

        // Khi máy gần nằm ngang (quay flat-lay từ trên xuống), hình chiếu Up Vector lên mặt phẳng XY
        // quá nhỏ khiến atan2(upX, upY) nhảy loạn -> giữ nguyên hướng xoay & Roll của lần đo trước
        private const val FLAT_LAY_PLANAR_MIN = 0.3f
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val rotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gyroSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vm?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    // Ma trận xoay 3x3 tái sử dụng
    private val rotationMatrix = FloatArray(9)

    // Cấu hình từ rules_data.json
    var hysteresisDeg: Float = DEFAULT_HYSTERESIS_DEG
        private set
    var redrawThresholdDeg: Float = DEFAULT_REDRAW_THRESHOLD_DEG
        private set
    var gyroWindowMs: Long = DEFAULT_GYRO_WINDOW_MS
        private set
    var fallbackOverlayAlpha: Float = 0.78f
        private set

    private val rulesMap = mutableMapOf<CoachMode, ModeRule>()

    // Trạng thái hiện tại
    var currentMode: CoachMode = CoachMode.PORTRAIT_HALF
        private set
    var currentVariant: GridVariant = GridVariant.VARIANT_1
        private set
    var currentAspectRatio: AspectRatioMode = AspectRatioMode.RATIO_4_3
        private set
    var currentShotTag: ShotTag = ShotTag.WIDE
        private set

    // Offset hiệu chỉnh 0 độ
    var calibPitchOffset: Float = prefs.getFloat(KEY_CALIB_PITCH, 0f)
        private set
    var calibRollOffset: Float = prefs.getFloat(KEY_CALIB_ROLL, 0f)
        private set

    // Giá trị cảm biến mới nhất
    private var latestRawPitchDeg = 0f
    private var latestRawRollDeg = 0f
    private var lastDispatchedPitchDeg = -999f
    private var lastDispatchedRollDeg = -999f

    // Trạng thái nhận diện hướng xoay màn hình từ vector trọng lực
    private var currentDetectedRotation: Int = Surface.ROTATION_0

    // Trạng thái Hysteresis
    private var isPitchAlignedState = false
    private var isRollAlignedState = false
    private var wasFullyAlignedState = false
    private var lastHapticTimeMs = 0L

    // Cửa sổ trượt Gyroscope 300ms đo bằng timestamp phần cứng (nanoseconds)
    private data class GyroSample(val timestampNs: Long, val omegaRadS: Float)
    private val gyroWindow = ArrayDeque<GyroSample>()
    private var latestGyroMeanOmega = 0f
    private var latestGyroSigmaOmega = 0f
    private var lastGyroDispatchMs = 0L

    // Bộ đệm lịch sử cú máy 120 giây (downsampled 80ms) phục vụ trích xuất thống kê chính xác theo thời lượng video
    private data class TelemetryRecord(
        val timeMs: Long,
        val pitch: Float,
        val roll: Float,
        val omega: Float,
        val sigma: Float
    )
    private val sessionBuffer = ArrayDeque<TelemetryRecord>()
    private var lastRecordSampleMs = 0L

    // Nhiều nơi cùng dùng chung 1 engine (MainActivity + HUD overlay): mỗi owner giữ 1 listener riêng,
    // cảm biến chỉ dừng khi không còn owner nào
    private val telemetryListeners = LinkedHashMap<Any, (SensorTelemetry) -> Unit>()
    private var listenerSnapshot: List<(SensorTelemetry) -> Unit> = emptyList()
    private var isRunning = false

    private val configChangeListeners = mutableListOf<() -> Unit>()

    init {
        loadRulesFromAssets()
        restoreSavedPreferences()
    }

    fun addConfigChangeListener(listener: () -> Unit) {
        if (!configChangeListeners.contains(listener)) {
            configChangeListeners.add(listener)
        }
    }

    fun removeConfigChangeListener(listener: () -> Unit) {
        configChangeListeners.remove(listener)
    }

    private fun notifyConfigChanged() {
        for (l in configChangeListeners) {
            try {
                l.invoke()
            } catch (e: Exception) {
                Log.w(TAG, "Error notifying config listener", e)
            }
        }
    }

    private fun loadRulesFromAssets() {
        try {
            val jsonText = context.assets.open("rules_data.json").bufferedReader().use { it.readText() }
            val root = JSONObject(jsonText)

            root.optJSONObject("sensor_config")?.let { cfg ->
                hysteresisDeg = cfg.optDouble("hysteresis_deg", 0.3).toFloat()
                redrawThresholdDeg = cfg.optDouble("redraw_threshold_deg", 0.15).toFloat()
                gyroWindowMs = cfg.optLong("gyro_window_ms", 300L)
                fallbackOverlayAlpha = cfg.optDouble("fallback_overlay_alpha", 0.78).toFloat()
            }

            val modesObj = root.optJSONObject("modes")
            for (mode in CoachMode.entries) {
                val m = modesObj?.optJSONObject(mode.id)
                if (m != null) {
                    rulesMap[mode] = ModeRule(
                        mode = mode,
                        displayName = m.optString("display_name", mode.titleVi),
                        pitchMinDeg = m.optDouble("pitch_min_deg", -3.0).toFloat(),
                        pitchMaxDeg = m.optDouble("pitch_max_deg", 3.0).toFloat(),
                        rollMaxDeg = m.optDouble("roll_max_deg", 1.5).toFloat(),
                        omegaMinRadS = m.optDouble("omega_min_rad_s", 0.08).toFloat(),
                        omegaMaxRadS = m.optDouble("omega_max_rad_s", 0.25).toFloat(),
                        sigmaOmegaMaxRadS = m.optDouble("sigma_omega_max_rad_s", 0.08).toFloat(),
                        poseSvgPath = m.optString("pose_svg", "").takeIf { it.isNotBlank() },
                        tip = m.optString("tip", ""),
                        warningFastPan = m.optString(
                            "warning_fast_pan",
                            "Lia máy quá nhanh! Hãy chậm lại (ω ≤ 0.25 rad/s)."
                        ),
                        warningJerky = m.optString(
                            "warning_jerky",
                            "Tay đang bị khựng, hãy khóa cổ tay và di chuyển bằng cả cơ thể!"
                        ),
                        warningTooSlow = m.optString(
                            "warning_too_slow",
                            "Lia máy quá chậm hoặc đang giữ tĩnh (ω ≥ 0.08 rad/s)."
                        )
                    )
                }
            }
            Log.i(TAG, "Loaded ${rulesMap.size} camera coach rules from rules_data.json")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading rules_data.json, using builtin defaults", e)
            populateFallbackRules()
        }
    }

    private fun populateFallbackRules() {
        rulesMap[CoachMode.PORTRAIT_HALF] = ModeRule(
            mode = CoachMode.PORTRAIT_HALF,
            displayName = "Chân dung: Bán thân",
            pitchMinDeg = -3.0f,
            pitchMaxDeg = 3.0f,
            rollMaxDeg = 1.5f,
            poseSvgPath = "poses/portrait_half.svg",
            tip = "Đặt mắt mẫu vào vạch ngang 1/3 trên. Chọn ống kính 2x."
        )
        rulesMap[CoachMode.PORTRAIT_FULL] = ModeRule(
            mode = CoachMode.PORTRAIT_FULL,
            displayName = "Chân dung: Toàn thân",
            pitchMinDeg = 5.0f,
            pitchMaxDeg = 12.0f,
            rollMaxDeg = 1.5f,
            poseSvgPath = "poses/portrait_full_triangle.svg",
            tip = "Mép dưới khung hình chạm sát mũi giày (5%). Ngửa máy +5° đến +12°."
        )
        rulesMap[CoachMode.LANDSCAPE] = ModeRule(
            mode = CoachMode.LANDSCAPE,
            displayName = "Phong cảnh",
            pitchMinDeg = -35.0f,
            pitchMaxDeg = 35.0f,
            rollMaxDeg = 1.0f,
            tip = "Trời đẹp đặt chân trời 1/3 dưới; Đất đẹp đặt 1/3 trên."
        )
        rulesMap[CoachMode.ARCHITECTURE] = ModeRule(
            mode = CoachMode.ARCHITECTURE,
            displayName = "Kiến trúc",
            pitchMinDeg = -1.5f,
            pitchMaxDeg = 1.5f,
            rollMaxDeg = 1.0f,
            tip = "Giữ Pitch = 0° để tránh hiệu ứng đổ cột (Keystone Effect)."
        )
        rulesMap[CoachMode.STREET] = ModeRule(
            mode = CoachMode.STREET,
            displayName = "Đường phố",
            pitchMinDeg = -25.0f,
            pitchMaxDeg = 25.0f,
            rollMaxDeg = 2.0f,
            poseSvgPath = "poses/street_walking.svg",
            tip = "Đặt chủ thể vào tâm xoắn ốc. Chừa 2/3 khoảng trống hướng nhìn."
        )
        rulesMap[CoachMode.BROLL] = ModeRule(
            mode = CoachMode.BROLL,
            displayName = "Video B-Roll",
            pitchMinDeg = -45.0f,
            pitchMaxDeg = 45.0f,
            rollMaxDeg = 1.5f,
            tip = "Giữ nhịp 5 giây (1s - 3s - 1s). Tốc độ lia ω ≤ 0.25 rad/s."
        )
    }

    private fun restoreSavedPreferences() {
        val savedModeName = prefs.getString(KEY_ACTIVE_MODE, CoachMode.PORTRAIT_HALF.name)
        currentMode = CoachMode.entries.find { it.name == savedModeName } ?: CoachMode.PORTRAIT_HALF

        val savedRatioName = prefs.getString(KEY_ASPECT_RATIO, AspectRatioMode.RATIO_4_3.name)
        currentAspectRatio = AspectRatioMode.entries.find { it.name == savedRatioName }
            ?: AspectRatioMode.RATIO_4_3

        val savedTagName = prefs.getString(KEY_SHOT_TAG, ShotTag.WIDE.name)
        currentShotTag = ShotTag.entries.find { it.name == savedTagName } ?: ShotTag.WIDE
    }

    fun getCurrentRule(): ModeRule {
        return rulesMap[currentMode] ?: rulesMap.values.first()
    }

    fun setMode(mode: CoachMode) {
        currentMode = mode
        isPitchAlignedState = false
        isRollAlignedState = false
        wasFullyAlignedState = false
        prefs.edit().putString(KEY_ACTIVE_MODE, mode.name).apply()
        notifyConfigChanged()
        forceDispatch()
    }

    fun cycleMode(): CoachMode {
        setMode(currentMode.next())
        return currentMode
    }

    fun cycleVariant(): GridVariant {
        currentVariant = currentVariant.next()
        notifyConfigChanged()
        forceDispatch()
        return currentVariant
    }

    fun toggleAspectRatio(): AspectRatioMode {
        currentAspectRatio = currentAspectRatio.toggle()
        prefs.edit().putString(KEY_ASPECT_RATIO, currentAspectRatio.name).apply()
        notifyConfigChanged()
        forceDispatch()
        return currentAspectRatio
    }

    fun cycleShotTag(): ShotTag {
        currentShotTag = currentShotTag.next()
        prefs.edit().putString(KEY_SHOT_TAG, currentShotTag.name).apply()
        notifyConfigChanged()
        forceDispatch()
        return currentShotTag
    }

    /**
     * Hiệu chỉnh mặt phẳng gốc 0° tại tư thế đặt máy hiện tại.
     */
    fun calibrateZeroPoint() {
        calibPitchOffset = latestRawPitchDeg
        calibRollOffset = latestRawRollDeg
        prefs.edit()
            .putFloat(KEY_CALIB_PITCH, calibPitchOffset)
            .putFloat(KEY_CALIB_ROLL, calibRollOffset)
            .apply()
        notifyConfigChanged()
        forceDispatch()
        triggerShortHaptic()
        Log.i(TAG, "Calibrated 0° offsets: pitchOffset=$calibPitchOffset, rollOffset=$calibRollOffset")
    }

    fun resetZeroPointCalibration() {
        calibPitchOffset = 0f
        calibRollOffset = 0f
        prefs.edit()
            .putFloat(KEY_CALIB_PITCH, 0f)
            .putFloat(KEY_CALIB_ROLL, 0f)
            .apply()
        notifyConfigChanged()
        forceDispatch()
    }

    /**
     * Quản lý tọa độ chuẩn hóa [0..1] của khung ngắm 4:3 và 16:9 trên màn hình 20:9.
     */
    fun getViewfinderBoundsNormalized(ratio: AspectRatioMode = currentAspectRatio): Pair<Float, Float> {
        return when (ratio) {
            AspectRatioMode.RATIO_4_3 -> {
                val top = prefs.getFloat(KEY_VF_43_TOP, 0.145f)
                val bottom = prefs.getFloat(KEY_VF_43_BOTTOM, 0.745f)
                top to bottom
            }
            AspectRatioMode.RATIO_16_9 -> {
                val top = prefs.getFloat(KEY_VF_169_TOP, 0.095f)
                val bottom = prefs.getFloat(KEY_VF_169_BOTTOM, 0.895f)
                top to bottom
            }
        }
    }

    fun saveViewfinderBoundsNormalized(ratio: AspectRatioMode, topNorm: Float, bottomNorm: Float) {
        val safeTop = topNorm.coerceIn(0.0f, 0.45f)
        val safeBottom = bottomNorm.coerceIn(0.55f, 1.0f)
        val editor = prefs.edit()
        when (ratio) {
            AspectRatioMode.RATIO_4_3 -> {
                editor.putFloat(KEY_VF_43_TOP, safeTop)
                editor.putFloat(KEY_VF_43_BOTTOM, safeBottom)
            }
            AspectRatioMode.RATIO_16_9 -> {
                editor.putFloat(KEY_VF_169_TOP, safeTop)
                editor.putFloat(KEY_VF_169_BOTTOM, safeBottom)
            }
        }
        editor.apply()
        notifyConfigChanged()
        forceDispatch()
    }

    fun resetViewfinderBounds() {
        prefs.edit()
            .putFloat(KEY_VF_43_TOP, 0.145f)
            .putFloat(KEY_VF_43_BOTTOM, 0.745f)
            .putFloat(KEY_VF_169_TOP, 0.095f)
            .putFloat(KEY_VF_169_BOTTOM, 0.895f)
            .apply()
        notifyConfigChanged()
        forceDispatch()
    }

    /**
     * Đăng ký [owner] nhận telemetry. Gọi lại với cùng owner sẽ thay listener của owner đó
     * mà không ảnh hưởng tới các owner khác.
     */
    fun start(owner: Any, onTelemetryUpdate: (SensorTelemetry) -> Unit) {
        telemetryListeners[owner] = onTelemetryUpdate
        listenerSnapshot = telemetryListeners.values.toList()
        if (isRunning) {
            forceDispatch()
            return
        }
        isRunning = true

        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        Log.i(TAG, "OrientationEngine started (rotation=${rotationSensor?.name}, gyro=${gyroSensor?.name})")
    }

    /**
     * Hủy đăng ký [owner]; chỉ tắt cảm biến khi không còn owner nào đang dùng engine.
     */
    fun stop(owner: Any) {
        telemetryListeners.remove(owner)
        listenerSnapshot = telemetryListeners.values.toList()
        if (!isRunning || telemetryListeners.isNotEmpty()) return
        isRunning = false
        sensorManager.unregisterListener(this)
        Log.i(TAG, "OrientationEngine stopped")
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Không cần xử lý
    }

    override fun onSensorChanged(event: SensorEvent) {
        val nowMs = System.currentTimeMillis()

        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                handleRotationVectorEvent(event, nowMs)
            }
            Sensor.TYPE_GYROSCOPE -> {
                handleGyroscopeEvent(event, nowMs)
            }
        }
    }

    /**
     * Tính toán góc Pitch và Roll trực tiếp từ Up Vector [R[6], R[7], R[8]]:
     * Triệt tiêu hoàn toàn hiện tượng Gimbal Lock và nhận diện hướng xoay màn hình từ trọng lực.
     */
    private fun handleRotationVectorEvent(event: SensorEvent, nowMs: Long) {
        // 1. Chuyển Vector cảm biến thành Ma trận xoay 3x3
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

        // 2. Vector "Up" (thiên đỉnh) của thế giới thực biểu diễn trong hệ trục tọa độ của điện thoại
        // R biến đổi từ device -> world, ma trận trực giao nên R^-1 = R^T
        // v_dev = R^T * [0, 0, 1]^T = [R[6], R[7], R[8]]^T
        val upX = rotationMatrix[6]
        val upY = rotationMatrix[7]
        val upZ = rotationMatrix[8]

        // 3. Tính góc Pitch (chúc/ngửa camera -Z) trực tiếp từ upZ:
        // upZ = 0 khi máy dựng đứng thẳng; upZ < 0 khi ngửa máy (+); upZ > 0 khi chúc máy (-)
        latestRawPitchDeg = Math.toDegrees(asin(-upZ.coerceIn(-1f, 1f).toDouble())).toFloat()

        // 4. Nhận diện góc xoay trong mặt phẳng màn hình XY từ trọng lực (không phụ thuộc display.rotation).
        // Bỏ qua khi máy gần nằm ngang (flat-lay) vì upX, upY ≈ 0 làm atan2 không ổn định.
        if (sqrt(upX * upX + upY * upY) >= FLAT_LAY_PLANAR_MIN) {
            val rawAngleDeg = Math.toDegrees(atan2(upX.toDouble(), upY.toDouble())).toFloat()
            val (detectedRot, horizonTilt) = resolveRotationAndHorizon(rawAngleDeg)
            currentDetectedRotation = detectedRot
            latestRawRollDeg = horizonTilt
        }

        val pitchDeg = latestRawPitchDeg - calibPitchOffset
        val rollDeg = latestRawRollDeg - calibRollOffset

        // 5. Lưu vào bộ đệm lịch sử 120 giây (lấy mẫu tối đa 12Hz = mỗi 80ms)
        recordSessionTelemetry(nowMs, pitchDeg, rollDeg)

        // 6. Chỉ vẽ lại Canvas khi góc lệch > 0.15 độ
        if (abs(rollDeg - lastDispatchedRollDeg) > redrawThresholdDeg ||
            abs(pitchDeg - lastDispatchedPitchDeg) > redrawThresholdDeg
        ) {
            dispatchTelemetry(pitchDeg, rollDeg, currentDetectedRotation, nowMs)
        }
    }

    /**
     * Xác định hướng xoay thiết bị (0°, 90°, 270°, 180°) từ góc nghiêng trọng lực rawAngleDeg (-180..+180)
     * kèm vùng đệm Hysteresis chống rung giật trạng thái và tính toán độ nghiêng chân trời tương đối.
     */
    private fun resolveRotationAndHorizon(rawAngleDeg: Float): Pair<Int, Float> {
        val newRotation = when (currentDetectedRotation) {
            Surface.ROTATION_0 -> {
                when {
                    rawAngleDeg > 55f && rawAngleDeg < 125f -> Surface.ROTATION_90
                    rawAngleDeg < -55f && rawAngleDeg > -125f -> Surface.ROTATION_270
                    abs(rawAngleDeg) >= 125f -> Surface.ROTATION_180
                    else -> Surface.ROTATION_0
                }
            }
            Surface.ROTATION_90 -> {
                when {
                    rawAngleDeg < 35f && rawAngleDeg > -35f -> Surface.ROTATION_0
                    rawAngleDeg >= 145f || rawAngleDeg <= -145f -> Surface.ROTATION_180
                    rawAngleDeg < -55f && rawAngleDeg > -125f -> Surface.ROTATION_270
                    else -> Surface.ROTATION_90
                }
            }
            Surface.ROTATION_270 -> {
                when {
                    rawAngleDeg > -35f && rawAngleDeg < 35f -> Surface.ROTATION_0
                    rawAngleDeg >= 145f || rawAngleDeg <= -145f -> Surface.ROTATION_180
                    rawAngleDeg > 55f && rawAngleDeg < 125f -> Surface.ROTATION_90
                    else -> Surface.ROTATION_270
                }
            }
            Surface.ROTATION_180 -> {
                when {
                    abs(rawAngleDeg) <= 35f -> Surface.ROTATION_0
                    rawAngleDeg > 55f && rawAngleDeg < 125f -> Surface.ROTATION_90
                    rawAngleDeg < -55f && rawAngleDeg > -125f -> Surface.ROTATION_270
                    else -> Surface.ROTATION_180
                }
            }
            else -> Surface.ROTATION_0
        }

        val horizonTilt = when (newRotation) {
            Surface.ROTATION_0 -> rawAngleDeg
            Surface.ROTATION_90 -> rawAngleDeg - 90f
            Surface.ROTATION_270 -> rawAngleDeg - (-90f)
            Surface.ROTATION_180 -> if (rawAngleDeg > 0f) rawAngleDeg - 180f else rawAngleDeg + 180f
            else -> rawAngleDeg
        }

        return newRotation to horizonTilt
    }

    private fun handleGyroscopeEvent(event: SensorEvent, nowMs: Long) {
        val wx = event.values[0]
        val wy = event.values[1]
        val wz = event.values[2]
        val omega = sqrt(wx * wx + wy * wy + wz * wz)

        // Dùng timestamp phần cứng (nanoseconds) để đo chính xác cả khi cảm biến gửi theo lô
        val eventTimestampNs = event.timestamp
        gyroWindow.addLast(GyroSample(eventTimestampNs, omega))

        val cutoffNs = eventTimestampNs - (gyroWindowMs * 1_000_000L)
        while (gyroWindow.isNotEmpty() && gyroWindow.first().timestampNs < cutoffNs) {
            gyroWindow.removeFirst()
        }

        if (gyroWindow.isNotEmpty()) {
            var sum = 0f
            for (sample in gyroWindow) {
                sum += sample.omegaRadS
            }
            val mean = sum / gyroWindow.size

            var varianceSum = 0f
            for (sample in gyroWindow) {
                val diff = sample.omegaRadS - mean
                varianceSum += diff * diff
            }
            val sigma = sqrt(varianceSum / gyroWindow.size)

            latestGyroMeanOmega = mean
            latestGyroSigmaOmega = sigma
        }

        // Trong chế độ B-Roll, cập nhật thanh vận tốc góc mỗi 60ms ngay cả khi góc nghiêng ít đổi
        if (currentMode == CoachMode.BROLL && (nowMs - lastGyroDispatchMs) >= 60L) {
            lastGyroDispatchMs = nowMs
            val pitchDeg = latestRawPitchDeg - calibPitchOffset
            val rollDeg = latestRawRollDeg - calibRollOffset
            dispatchTelemetry(pitchDeg, rollDeg, currentDetectedRotation, nowMs)
        }
    }

    private fun recordSessionTelemetry(nowMs: Long, pitchDeg: Float, rollDeg: Float) {
        // Chỉ lưu mẫu nếu đã trôi qua ít nhất 80ms kể từ mẫu trước để tiết kiệm bộ nhớ
        if (nowMs - lastRecordSampleMs < 80L) return
        lastRecordSampleMs = nowMs

        sessionBuffer.addLast(
            TelemetryRecord(
                timeMs = nowMs,
                pitch = pitchDeg,
                roll = rollDeg,
                omega = latestGyroMeanOmega,
                sigma = latestGyroSigmaOmega
            )
        )
        // Duy trì bộ đệm tối đa 120 giây (khoảng 1500 mẫu)
        val cutoff = nowMs - 120_000L
        while (sessionBuffer.isNotEmpty() && sessionBuffer.first().timeMs < cutoff) {
            sessionBuffer.removeFirst()
        }
    }

    /**
     * Xuất thống kê cú máy cắt đúng khoảng thời gian [startMs, endMs] (mốc kết thúc clip lùi lại DURATION).
     */
    fun getSessionSummaryForWindow(startMs: Long, endMs: Long): ShotSessionSummary {
        val rule = getCurrentRule()
        val targetRecords = sessionBuffer.filter { it.timeMs in startMs..endMs }
        if (targetRecords.isEmpty()) {
            return getRecentSessionSummary()
        }

        var sumPitch = 0f
        var sumRoll = 0f
        var maxOmega = 0f
        var sumSigma = 0f
        for (rec in targetRecords) {
            sumPitch += rec.pitch
            sumRoll += rec.roll
            if (rec.omega > maxOmega) maxOmega = rec.omega
            sumSigma += rec.sigma
        }
        val count = targetRecords.size.toFloat()
        val avgPitch = sumPitch / count
        val avgRoll = sumRoll / count
        val avgSigma = sumSigma / count
        val isSmooth = maxOmega <= rule.omegaMaxRadS && avgSigma <= rule.sigmaOmegaMaxRadS

        return ShotSessionSummary(
            avgPitchDeg = avgPitch,
            avgRollDeg = avgRoll,
            maxGyroRadS = maxOmega,
            avgSigmaRadS = avgSigma,
            isSmooth = isSmooth
        )
    }

    /**
     * Xuất thống kê cú máy trong 15 giây gần nhất (dùng cho ảnh hoặc fallback).
     */
    fun getRecentSessionSummary(): ShotSessionSummary {
        val now = System.currentTimeMillis()
        return getSessionSummaryForWindow(now - 15_000L, now)
    }

    private fun forceDispatch() {
        val pitchDeg = latestRawPitchDeg - calibPitchOffset
        val rollDeg = latestRawRollDeg - calibRollOffset
        dispatchTelemetry(pitchDeg, rollDeg, currentDetectedRotation, System.currentTimeMillis())
    }

    private fun dispatchTelemetry(pitchDeg: Float, rollDeg: Float, displayRot: Int, nowMs: Long) {
        lastDispatchedPitchDeg = pitchDeg
        lastDispatchedRollDeg = rollDeg

        val rule = getCurrentRule()

        // Áp dụng vùng trễ Hysteresis (0.3°) cho Pitch
        isPitchAlignedState = if (isPitchAlignedState) {
            pitchDeg >= (rule.pitchMinDeg - hysteresisDeg) &&
                pitchDeg <= (rule.pitchMaxDeg + hysteresisDeg)
        } else {
            pitchDeg >= rule.pitchMinDeg && pitchDeg <= rule.pitchMaxDeg
        }

        // Áp dụng vùng trễ Hysteresis (0.3°) cho Roll
        val absRoll = abs(rollDeg)
        isRollAlignedState = if (isRollAlignedState) {
            absRoll <= (rule.rollMaxDeg + hysteresisDeg)
        } else {
            absRoll <= rule.rollMaxDeg
        }

        val isFullyAligned = isPitchAlignedState && isRollAlignedState

        // Edge-Trigger Haptic: Rung nhẹ đúng 1 nhịp khi vừa đạt trạng thái cân bằng chuẩn
        if (isFullyAligned && !wasFullyAlignedState && (nowMs - lastHapticTimeMs) > 650L) {
            lastHapticTimeMs = nowMs
            triggerShortHaptic()
        }
        wasFullyAlignedState = isFullyAligned

        val isPanTooFast = latestGyroMeanOmega > rule.omegaMaxRadS
        val isPanJerky = latestGyroSigmaOmega > rule.sigmaOmegaMaxRadS
        val isPanTooSlow = currentMode == CoachMode.BROLL && latestGyroMeanOmega < rule.omegaMinRadS
        val isSmooth = !isPanTooFast && !isPanJerky

        if (listenerSnapshot.isEmpty()) return
        val telemetry = SensorTelemetry(
            pitchDeg = pitchDeg,
            rollDeg = rollDeg,
            rawPitchDeg = latestRawPitchDeg,
            rawRollDeg = latestRawRollDeg,
            isPitchAligned = isPitchAlignedState,
            isRollAligned = isRollAlignedState,
            isFullyAligned = isFullyAligned,
            gyroMeanOmegaRadS = latestGyroMeanOmega,
            gyroSigmaOmegaRadS = latestGyroSigmaOmega,
            isPanTooFast = isPanTooFast,
            isPanJerky = isPanJerky,
            isPanTooSlow = isPanTooSlow,
            isSmoothMotion = isSmooth,
            displayRotation = displayRot
        )
        for (l in listenerSnapshot) {
            l.invoke(telemetry)
        }
    }

    fun triggerShortHaptic(durationMs: Long = 22L) {
        try {
            vibrator?.let { vib ->
                if (!vib.hasVibrator()) return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(VibrationEffect.createOneShot(durationMs, 110))
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(durationMs)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to trigger haptic feedback", e)
        }
    }
}
