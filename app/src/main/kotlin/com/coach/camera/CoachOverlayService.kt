package com.coach.camera

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.coach.camera.logger.MediaShotLogger
import com.coach.camera.overlay.GridOverlayView
import com.coach.camera.overlay.PillControlView
import com.coach.camera.sensor.OrientationEngine

/**
 * Quản lý trạng thái toàn cục của 2 cửa sổ nổi (Dual-Window Architecture):
 * - Cửa sổ 1: GridOverlayView (FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE) -> Chạm xuyên 100%
 * - Cửa sổ 2: PillControlView (Nhận chạm & kéo thả) -> Đổi nhanh Chế độ / Lưới / Nhãn Shot
 *
 * Hỗ trợ cả 2 cơ chế trên Xiaomi HyperOS (Android 13/14):
 * 1. TYPE_ACCESSIBILITY_OVERLAY (Trusted Overlay, alpha = 1.0f, không bị HyperOS kill ngầm)
 * 2. TYPE_APPLICATION_OVERLAY (ForegroundService, alpha = 0.78f < 0.8f vượt Untrusted Touch Block)
 */
object OverlaySessionManager {
    private const val TAG = "CameraCoach"

    var accessibilityInstance: CoachAccessibilityService? = null
    private var activeSession: DualWindowSession? = null
    var onStateChangedListener: ((Boolean) -> Unit)? = null

    /**
     * true khi người dùng bấm ✕ trên thanh Pill lúc đang ở trong Camera: chặn Accessibility tự gắn lại HUD
     * cho tới khi người dùng rời khỏi Camera (hoặc tự bật lại từ MainActivity).
     */
    @Volatile
    var dismissedByUser: Boolean = false

    private var sharedEngine: OrientationEngine? = null

    fun getSharedEngine(context: Context): OrientationEngine {
        return sharedEngine ?: synchronized(this) {
            sharedEngine ?: OrientationEngine(context.applicationContext).also { sharedEngine = it }
        }
    }

    val isHudShowing: Boolean
        get() = activeSession != null

    val isAccessibilityConnected: Boolean
        get() = accessibilityInstance != null

    val activeEngine: OrientationEngine?
        get() = sharedEngine ?: activeSession?.engine

    /**
     * Khởi chạy lớp phủ ưu tiên Accessibility Trusted Overlay (alpha = 1.0f),
     * nếu chưa bật Accessibility sẽ tự động dùng Foreground Overlay Service (alpha = 0.78f).
     */
    fun startHud(context: Context): Boolean {
        dismissedByUser = false
        if (isHudShowing) return true

        val accService = accessibilityInstance
        if (accService != null) {
            return accService.attachDualWindows()
        }

        if (!Settings.canDrawOverlays(context)) {
            Toast.makeText(
                context,
                "Vui lòng bật Accessibility Service hoặc cấp quyền 'Hiển thị trên ứng dụng khác'",
                Toast.LENGTH_LONG
            ).show()
            return false
        }

        val intent = Intent(context, CoachOverlayService::class.java).apply {
            action = CoachOverlayService.ACTION_START_OVERLAY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        return true
    }

    fun stopHud(context: Context) {
        activeSession?.destroy()
        activeSession = null
        context.stopService(Intent(context, CoachOverlayService::class.java))
        onStateChangedListener?.invoke(false)
    }

    internal fun attachSession(
        windowContext: Context,
        useAccessibilityTrustedOverlay: Boolean,
        onClosedCallback: () -> Unit
    ): Boolean {
        if (activeSession != null) return true
        return try {
            val session = DualWindowSession(
                context = windowContext,
                useAccessibilityTrustedOverlay = useAccessibilityTrustedOverlay,
                onCloseRequested = {
                    dismissedByUser = true
                    stopHud(windowContext)
                    onClosedCallback()
                }
            )
            session.attach()
            activeSession = session
            onStateChangedListener?.invoke(true)
            Log.i(
                TAG,
                "Dual-Window HUD attached (trustedAccessibility=$useAccessibilityTrustedOverlay)"
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach Dual-Window HUD", e)
            false
        }
    }
}

/**
 * Phiên quản lý 2 cửa sổ độc lập trên WindowManager cùng OrientationEngine và MediaShotLogger.
 */
internal class DualWindowSession(
    private val context: Context,
    private val useAccessibilityTrustedOverlay: Boolean,
    private val onCloseRequested: () -> Unit
) {
    val engine = OverlaySessionManager.getSharedEngine(context)
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var gridView: GridOverlayView? = null
    private var pillView: PillControlView? = null

    private val shotLogger = MediaShotLogger(context, engine) { entry ->
        val name = entry.optString("file_name")
        val tag = entry.optString("shot_tag")
        Toast.makeText(
            context,
            "CAM-HUD đã lưu shot_log: $name [$tag]",
            Toast.LENGTH_SHORT
        ).show()
    }

    fun attach() {
        val windowType = if (useAccessibilityTrustedOverlay) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
        }

        // 1. Cửa sổ 1: GridOverlayView (Chiếm toàn màn hình, chạm xuyên 100% xuống Camera Xiaomi)
        val gridParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Trên Android 12+ nếu dùng TYPE_APPLICATION_OVERLAY, bắt buộc alpha <= 0.8f để chạm xuyên
            alpha = if (useAccessibilityTrustedOverlay) 1.0f else engine.fallbackOverlayAlpha
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val createdGridView = GridOverlayView(context, engine)
        windowManager.addView(createdGridView, gridParams)
        gridView = createdGridView

        // 2. Cửa sổ 2: PillControlView (Thanh điều khiển nhỏ gọn ở rìa phải, nhận chạm & kéo thả)
        val pillParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (10 * context.resources.displayMetrics.density).toInt()
            y = (160 * context.resources.displayMetrics.density).toInt()
            alpha = if (useAccessibilityTrustedOverlay) 0.96f else engine.fallbackOverlayAlpha
        }

        val createdPillView = PillControlView(
            context = context,
            engine = engine,
            gridOverlayView = createdGridView,
            windowManager = windowManager,
            windowLayoutParams = pillParams,
            onCloseRequested = onCloseRequested
        )
        windowManager.addView(createdPillView, pillParams)
        pillView = createdPillView

        // 3. Khởi động bộ xử lý cảm biến & trình lắng nghe MediaStore
        engine.start(this) { telemetry ->
            createdGridView.updateTelemetry(telemetry)
        }
        shotLogger.startObserving()
    }

    fun destroy() {
        shotLogger.stopObserving()
        engine.stop(this)

        gridView?.let {
            try {
                windowManager.removeViewImmediate(it)
            } catch (_: Exception) {
            }
        }
        gridView = null

        pillView?.let {
            try {
                windowManager.removeViewImmediate(it)
            } catch (_: Exception) {
            }
        }
        pillView = null
    }
}

/**
 * Cách 1 (Khuyên dùng trên Xiaomi HyperOS):
 * AccessibilityService tạo cửa sổ TYPE_ACCESSIBILITY_OVERLAY (Trusted Overlay, alpha = 1.0f).
 */
class CoachAccessibilityService : AccessibilityService() {

    companion object {
        private val CAMERA_PACKAGES = setOf(
            "com.android.camera",
            "com.miui.camera",
            "com.sec.android.app.camera",
            "com.google.android.GoogleCamera",
            "com.oplus.camera",
            "com.vivo.camera"
        )

        // Các cửa sổ hệ thống thoáng qua (kéo thanh thông báo, hộp thoại quyền...) không được coi là "rời Camera"
        private val TRANSIENT_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller"
        )
    }

    // HUD đang gắn với phiên dùng Camera (do tự hiện, hoặc người dùng đã mang HUD vào Camera)
    // -> tự ẩn khi người dùng chuyển sang app khác
    private var hudOwnedByCamera = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        OverlaySessionManager.accessibilityInstance = this
        Log.i("CameraCoach", "CoachAccessibilityService connected (Trusted Overlay ready)")
    }

    fun attachDualWindows(): Boolean {
        return OverlaySessionManager.attachSession(
            windowContext = this,
            useAccessibilityTrustedOverlay = true,
            onClosedCallback = {}
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg in TRANSIENT_PACKAGES) return

        if (pkg in CAMERA_PACKAGES) {
            // Tự động gắn HUD khi người dùng mở ứng dụng Camera (trừ khi vừa bấm ✕ tắt tay trong Camera)
            if (OverlaySessionManager.isHudShowing) {
                hudOwnedByCamera = true
            } else if (!OverlaySessionManager.dismissedByUser && attachDualWindows()) {
                hudOwnedByCamera = true
            }
            return
        }

        // Người dùng đã rời Camera sang app khác: xóa cờ tắt tay & tự ẩn HUD của phiên Camera
        OverlaySessionManager.dismissedByUser = false
        if (hudOwnedByCamera && OverlaySessionManager.isHudShowing) {
            OverlaySessionManager.stopHud(this)
        }
        hudOwnedByCamera = false
    }

    override fun onInterrupt() {
        // Không xử lý
    }

    override fun onUnbind(intent: Intent?): Boolean {
        OverlaySessionManager.stopHud(this)
        OverlaySessionManager.accessibilityInstance = null
        return super.onUnbind(intent)
    }
}

/**
 * Cách 2 (Dự phòng không cần bật Accessibility):
 * ForegroundService tạo cửa sổ TYPE_APPLICATION_OVERLAY với alpha = 0.78f (< 0.8f trên Android 12+).
 */
class CoachOverlayService : Service() {

    companion object {
        const val ACTION_START_OVERLAY = "com.coach.camera.action.START_OVERLAY"
        const val ACTION_STOP_OVERLAY = "com.coach.camera.action.STOP_OVERLAY"
        private const val CHANNEL_ID = "cam_hud_overlay_channel"
        private const val NOTIFICATION_ID = 2026
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_OVERLAY) {
            OverlaySessionManager.stopHud(this)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        OverlaySessionManager.attachSession(
            windowContext = this,
            useAccessibilityTrustedOverlay = false,
            onClosedCallback = { stopSelf() }
        )

        return START_STICKY
    }

    override fun onDestroy() {
        OverlaySessionManager.stopHud(this)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "CAM-HUD Camera Coach Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Duy trì lớp phủ lưới bố cục và cố vấn góc máy trên Camera Xiaomi"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, CoachOverlayService::class.java).apply {
            action = ACTION_STOP_OVERLAY
        }
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_camera_hud)
            .setContentTitle("CAM-HUD đang hoạt động")
            .setContentText("Chạm xuyên 100% trên Camera gốc • Tự động lưu shot_log.json")
            .setContentIntent(pendingOpen)
            .addAction(0, "Tắt HUD", pendingStop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
