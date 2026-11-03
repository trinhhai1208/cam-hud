package com.coach.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.coach.camera.logger.MediaShotLogger
import com.coach.camera.sensor.AspectRatioMode
import com.coach.camera.sensor.OrientationEngine
import java.util.Locale

/**
 * Màn hình điều khiển chính của CAM-HUD:
 * - Cấp quyền Accessibility Trusted Overlay (alpha = 1.0f) hoặc App Overlay (alpha = 0.78f)
 * - Cấp quyền đọc MediaStore (kiểm tra đầy đủ cả Ảnh + Video và Android 14 full access)
 * - Hiệu chỉnh mặt phẳng 0° (Pitch/Roll Calibration) và khớp khung ngắm 4:3 / 16:9
 * - Dùng chung 1 OrientationEngine duy nhất (Singleton) đồng bộ 100% với PillControlView
 * - Khởi chạy nhanh lớp phủ HUD kèm mở thẳng ứng dụng Camera gốc của Xiaomi
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_PERMISSIONS = 1001
    }

    private lateinit var engine: OrientationEngine

    private lateinit var tvHudStatus: TextView
    private lateinit var tvPermissionStatus: TextView
    private lateinit var tvLiveSensor: TextView
    private lateinit var tvCalibStatus: TextView
    private lateinit var tvViewfinderStatus: TextView
    private lateinit var tvShotLogPreview: TextView
    private lateinit var btnToggleHud: Button

    private val density: Float by lazy { resources.displayMetrics.density }
    private fun dp(value: Int): Int = (value * density).toInt()

    private val configListener = {
        runOnUiThread { refreshUiState() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        engine = OverlaySessionManager.getSharedEngine(this)
        engine.addConfigChangeListener(configListener)

        setContentView(buildDashboardUi())

        OverlaySessionManager.onStateChangedListener = {
            runOnUiThread { refreshUiState() }
        }
    }

    override fun onResume() {
        super.onResume()
        engine.start(this) { telemetry ->
            runOnUiThread {
                val rule = engine.getCurrentRule()
                tvLiveSensor.text = String.format(
                    Locale.US,
                    "Pitch (Chúc/Ngửa): %+6.2f°  [Chuẩn: %.1f° .. %.1f°]\n" +
                        "Roll  (Chân trời): %+6.2f°  [Chuẩn: |R| ≤ %.1f°]\n" +
                        "Gyro  (Tốc độ ω) :  %5.2f rad/s (≤ %.2f) | Độ giật σ: %.2f\n" +
                        "Trạng thái       : %s",
                    telemetry.pitchDeg,
                    rule.pitchMinDeg,
                    rule.pitchMaxDeg,
                    telemetry.rollDeg,
                    rule.rollMaxDeg,
                    telemetry.gyroMeanOmegaRadS,
                    rule.omegaMaxRadS,
                    telemetry.gyroSigmaOmegaRadS,
                    if (telemetry.isFullyAligned) "● CÂN BẰNG CHUẨN (Hysteresis ±0.3°)" else "○ ĐANG LỆCH GÓC"
                )
                tvLiveSensor.setTextColor(
                    if (telemetry.isFullyAligned) Color.parseColor("#10B981")
                    else Color.parseColor("#FBBF24")
                )
            }
        }
        refreshUiState()
        refreshShotLogPreview()
    }

    override fun onPause() {
        super.onPause()
        // Chỉ hủy listener của màn hình này; cảm biến vẫn chạy nếu HUD overlay còn đang dùng engine
        engine.stop(this)
    }

    override fun onDestroy() {
        engine.removeConfigChangeListener(configListener)
        OverlaySessionManager.onStateChangedListener = null
        super.onDestroy()
    }

    private fun buildDashboardUi(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F172A"))
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }

        // 1. Tiêu đề ứng dụng
        root.addView(TextView(this).apply {
            text = "📸 CAM-HUD — Pocket DOP v2.0"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Lớp phủ Bố cục, Cân bằng Cảm biến & B-Roll Coach cho Xiaomi Redmi Note 13 Pro"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, dp(4), 0, dp(14))
        })

        // 2. Card Điều khiển chính & Mở nhanh Camera Xiaomi
        val launchCard = createCardContainer()
        tvHudStatus = TextView(this).apply {
            setTextColor(Color.parseColor("#10B981"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        launchCard.addView(tvHudStatus)

        btnToggleHud = createActionButton("BẬT LỚP PHỦ CAM-HUD", "#10B981").apply {
            setOnClickListener {
                if (OverlaySessionManager.isHudShowing) {
                    OverlaySessionManager.stopHud(this@MainActivity)
                } else {
                    OverlaySessionManager.startHud(this@MainActivity)
                }
                refreshUiState()
            }
        }
        launchCard.addView(btnToggleHud, createTopMarginParams(dp(10)))

        val btnLaunchXiaomiCam = createActionButton(
            "🚀 BẬT HUD + MỞ CAMERA GỐC XIAOMI",
            "#0284C7"
        ).apply {
            setOnClickListener {
                if (OverlaySessionManager.startHud(this@MainActivity)) {
                    launchStockXiaomiCamera()
                }
            }
        }
        launchCard.addView(btnLaunchXiaomiCam, createTopMarginParams(dp(8)))
        root.addView(launchCard, createCardMarginParams())

        // 3. Card Trạng thái Quyền Hệ thống (Accessibility Trusted Overlay vs App Overlay)
        val permCard = createCardContainer()
        permCard.addView(createSectionTitle("1. Cấp quyền Lớp phủ & MediaStore (Xiaomi HyperOS)"))
        tvPermissionStatus = TextView(this).apply {
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(6), 0, dp(6))
        }
        permCard.addView(tvPermissionStatus)

        permCard.addView(
            createActionButton(
                "① Bật Accessibility Trusted Overlay (Alpha 1.0 - Khuyên dùng)",
                "#334155"
            ).apply {
                setOnClickListener {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            },
            createTopMarginParams(dp(6))
        )

        permCard.addView(
            createActionButton(
                "② Cấp quyền Cửa sổ nổi dự phòng (SYSTEM_ALERT_WINDOW)",
                "#334155"
            ).apply {
                setOnClickListener {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                }
            },
            createTopMarginParams(dp(6))
        )

        permCard.addView(
            createActionButton(
                "③ Cấp quyền đọc MediaStore (Tự động ghi shot_log.json)",
                "#334155"
            ).apply {
                setOnClickListener { requestMediaAndNotificationPermissions() }
            },
            createTopMarginParams(dp(6))
        )
        root.addView(permCard, createCardMarginParams())

        // 4. Card Cảm biến thời gian thực & Hiệu chỉnh 0 độ (Calibration 0°)
        val sensorCard = createCardContainer()
        sensorCard.addView(createSectionTitle("2. Cảm biến Góc nghiêng & Hiệu chỉnh 0°"))
        // Pitch = 0° nghĩa là camera nhìn ngang tầm mắt (máy dựng đứng), KHÔNG phải máy nằm phẳng.
        // Hiệu chỉnh khi đặt máy nằm trên bàn sẽ lưu offset Pitch ≈ -90° và làm sai mọi ngưỡng.
        sensorCard.addView(TextView(this).apply {
            text = "Cách hiệu chỉnh: dựng máy THẲNG ĐỨNG, áp mặt lưng hoặc cạnh máy vào tường / khung cửa, " +
                "rồi bấm 🎯. Không đặt máy nằm phẳng trên bàn."
            setTextColor(Color.parseColor("#FBBF24"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(0, dp(4), 0, 0)
        })
        tvLiveSensor = TextView(this).apply {
            text = "Đang đọc TYPE_GAME_ROTATION_VECTOR (Zero-Gimbal-Lock)..."
            setTextColor(Color.parseColor("#10B981"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(6), 0, dp(6))
        }
        sensorCard.addView(tvLiveSensor)

        tvCalibStatus = TextView(this).apply {
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        }
        sensorCard.addView(tvCalibStatus)

        val calibRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        calibRow.addView(
            createActionButton("🎯 Hiệu chỉnh 0°", "#0F766E").apply {
                setOnClickListener {
                    engine.calibrateZeroPoint()
                    refreshUiState()
                    Toast.makeText(
                        this@MainActivity,
                        "Đã lưu tư thế máy dựng đứng làm chuẩn 0°!",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(4)
            }
        )
        calibRow.addView(
            createActionButton("↺ Reset về 0° gốc", "#475569").apply {
                setOnClickListener {
                    engine.resetZeroPointCalibration()
                    refreshUiState()
                }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(4)
            }
        )
        sensorCard.addView(calibRow, createTopMarginParams(dp(8)))
        root.addView(sensorCard, createCardMarginParams())

        // 5. Card Cấu hình Chế độ Lưới & Khung ngắm (Dùng chung engine)
        val modeCard = createCardContainer()
        modeCard.addView(createSectionTitle("3. Cấu hình Chế độ Lưới, Khung ngắm & Nhãn Cỡ cảnh"))
        tvViewfinderStatus = TextView(this).apply {
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(6), 0, dp(6))
        }
        modeCard.addView(tvViewfinderStatus)

        modeCard.addView(
            createActionButton("🔄 Đổi Chế độ (5 Chế độ Quay Chụp)", "#1E293B").apply {
                setOnClickListener {
                    engine.cycleMode()
                }
            },
            createTopMarginParams(dp(6))
        )

        val ratioAndTagRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        ratioAndTagRow.addView(
            createActionButton("📐 Đổi Khung 4:3 / 16:9", "#1E293B").apply {
                setOnClickListener {
                    engine.toggleAspectRatio()
                }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(4)
            }
        )
        ratioAndTagRow.addView(
            createActionButton("🏷 Đổi Nhãn Shot [W-M-T]", "#1E293B").apply {
                setOnClickListener {
                    engine.cycleShotTag()
                }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(4)
            }
        )
        modeCard.addView(ratioAndTagRow, createTopMarginParams(dp(6)))

        modeCard.addView(
            createActionButton("↺ Khôi phục tọa độ khung ngắm Redmi Note 13 Pro", "#334155").apply {
                setOnClickListener {
                    engine.resetViewfinderBounds()
                    Toast.makeText(
                        this@MainActivity,
                        "Đã khôi phục khung ngắm chuẩn 4:3 & 16:9!",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            createTopMarginParams(dp(6))
        )
        root.addView(modeCard, createCardMarginParams())

        // 6. Card Nhật ký cú máy tự động (shot_log.json)
        val logCard = createCardContainer()
        logCard.addView(createSectionTitle("4. Nhật ký Cú máy Tự động (/Documents/CameraCoach/shot_log.json)"))
        tvShotLogPreview = TextView(this).apply {
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(6), 0, dp(6))
        }
        logCard.addView(tvShotLogPreview)

        logCard.addView(
            createActionButton("🔄 Làm mới / Đọc lại shot_log.json", "#0369A1").apply {
                setOnClickListener {
                    refreshShotLogPreview()
                    Toast.makeText(
                        this@MainActivity,
                        "Đã cập nhật danh sách shot_log!",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            createTopMarginParams(dp(6))
        )
        root.addView(logCard, createCardMarginParams())

        scroll.addView(root)
        return scroll
    }

    private fun refreshUiState() {
        val isHudOn = OverlaySessionManager.isHudShowing
        val isAccReady = OverlaySessionManager.isAccessibilityConnected
        val canDrawOverlay = Settings.canDrawOverlays(this)
        val hasMediaPerm = hasMediaReadPermissions()

        val modeDesc = if (isAccReady) {
            "Trusted Accessibility Overlay (alpha = 1.0f • Chạm xuyên 100%)"
        } else {
            "App Overlay Fallback (alpha = 0.78f • Android 12+ Pass-through)"
        }

        tvHudStatus.text = if (isHudOn) {
            "🟢 TRẠNG THÁI: ĐANG HIỂN THỊ LỚP PHỦ HUD\nCơ chế: $modeDesc"
        } else {
            "⚪ TRẠNG THÁI: ĐANG TẮT\nCơ chế sẵn sàng: $modeDesc"
        }
        tvHudStatus.setTextColor(
            if (isHudOn) Color.parseColor("#10B981") else Color.parseColor("#F59E0B")
        )

        btnToggleHud.text = if (isHudOn) "⏹ TẮT LỚP PHỦ CAM-HUD" else "▶ BẬT LỚP PHỦ CAM-HUD"
        (btnToggleHud.background as? GradientDrawable)?.setColor(
            Color.parseColor(if (isHudOn) "#DC2626" else "#10B981")
        )

        val isPartialAndroid14 = isPartialMediaAccess()

        tvPermissionStatus.text = buildString {
            append("• Accessibility Trusted Overlay : ")
            appendLine(if (isAccReady) "✓ ĐÃ BẬT (Tối ưu nhất)" else "○ Chưa bật")
            append("• Quyền Cửa sổ nổi dự phòng     : ")
            appendLine(if (canDrawOverlay) "✓ ĐÃ CẤP" else "✗ Chưa cấp")
            append("• Quyền MediaStore Shot Logger  : ")
            if (isPartialAndroid14) {
                appendLine("⚠ Đang chọn 'Một số ảnh' (Cần 'Cho phép tất cả')")
            } else {
                appendLine(if (hasMediaPerm) "✓ ĐÃ CẤP ĐẦY ĐỦ" else "○ Chưa cấp")
            }
        }

        tvCalibStatus.text = String.format(
            Locale.US,
            "Offset 0° đã lưu: Pitch %+.2f° | Roll %+.2f°",
            engine.calibPitchOffset,
            engine.calibRollOffset
        )

        val (top43, bot43) = engine.getViewfinderBoundsNormalized(AspectRatioMode.RATIO_4_3)
        val (top169, bot169) = engine.getViewfinderBoundsNormalized(AspectRatioMode.RATIO_16_9)
        tvViewfinderStatus.text = buildString {
            appendLine("• Chế độ hiện tại : ${engine.currentMode.titleVi}")
            appendLine("• Khung ngắm đang : ${engine.currentAspectRatio.label}")
            appendLine("• Nhãn B-Roll Tag : [${engine.currentShotTag.tag}] (${engine.currentShotTag.descVi})")
            appendLine("• Tọa độ khung 4:3: Trên ${(top43 * 100).toInt()}% ── Dưới ${(bot43 * 100).toInt()}%")
            append("• Tọa độ 16:9     : Trên ${(top169 * 100).toInt()}% ── Dưới ${(bot169 * 100).toInt()}%")
        }
    }

    private fun refreshShotLogPreview() {
        val allShots = MediaShotLogger.readAllLoggedShots(this)
        val pubPath = MediaShotLogger.getPublicShotLogFile().absolutePath
        val total = allShots.length()

        if (total == 0) {
            tvShotLogPreview.text = "File: $pubPath\nChưa có bản ghi nào. Khi bạn bấm chụp/quay trên Camera Xiaomi, thông số sẽ tự động lưu vào đây."
            return
        }

        val lastEntry = allShots.optJSONObject(total - 1)
        tvShotLogPreview.text = buildString {
            appendLine("File: $pubPath")
            appendLine("Tổng số cú máy đã ghi: $total")
            appendLine("Bản ghi mới nhất:")
            append(lastEntry?.toString(2) ?: "{}")
        }
    }

    /**
     * Mở trực tiếp ứng dụng Camera gốc của Xiaomi (com.android.camera) hoặc Camera hệ thống.
     */
    private fun launchStockXiaomiCamera() {
        val xiaomiPackages = listOf("com.android.camera", "com.miui.camera")
        for (pkg in xiaomiPackages) {
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                return
            }
        }
        try {
            val stillCameraIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(stillCameraIntent)
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "Không tìm thấy App Camera mặc định, hãy mở Camera từ màn hình chính.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Android 14: khi người dùng chọn "Cho phép tất cả", hệ thống cấp READ_MEDIA_IMAGES/VIDEO
     * VÀ cả READ_MEDIA_VISUAL_USER_SELECTED. Chỉ coi là "Một số ảnh" khi có USER_SELECTED
     * nhưng KHÔNG có quyền đầy đủ.
     */
    private fun isPartialMediaAccess(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        val hasFull = isGranted(Manifest.permission.READ_MEDIA_VIDEO) ||
            isGranted(Manifest.permission.READ_MEDIA_IMAGES)
        return !hasFull && isGranted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    }

    private fun hasMediaReadPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasVideo = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_VIDEO
            ) == PackageManager.PERMISSION_GRANTED
            val hasImages = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
            hasVideo && hasImages
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestMediaAndNotificationPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            perms.add(Manifest.permission.READ_MEDIA_VIDEO)
            perms.add(Manifest.permission.READ_MEDIA_IMAGES)
            perms.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.READ_MEDIA_VIDEO)
            perms.add(Manifest.permission.READ_MEDIA_IMAGES)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), REQ_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshUiState()
    }

    private fun createCardContainer(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#1E293B"))
                setStroke(dp(1), Color.parseColor("#334155"))
            }
        }
    }

    private fun createSectionTitle(title: String): TextView {
        return TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
    }

    private fun createActionButton(textLabel: String, bgHex: String): Button {
        return Button(this).apply {
            text = textLabel
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor(bgHex))
            }
        }
    }

    private fun createCardMarginParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(12)
        }
    }

    private fun createTopMarginParams(topPx: Int): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = topPx
        }
    }
}
