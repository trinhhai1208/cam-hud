# 📸 CAM-HUD — Real-time Camera Composition & Movement Coach

> **Lớp phủ hỗ trợ căn chỉnh bố cục, góc chụp và chuyển động quay phim thời gian thực đè trực tiếp lên ứng dụng Camera mặc định của điện thoại.**  
> *Target Device: Xiaomi Redmi Note 13 Pro (Android 13/14 – Xiaomi HyperOS).*

---

## 🌟 Giới thiệu

**CAM-HUD** là ứng dụng Android Native (Kotlin) siêu nhẹ, đóng vai trò như một **Đạo diễn hình ảnh bỏ túi (Pocket DOP)**. Thay vì bắt bạn phải dùng một app chụp ảnh bên thứ ba với chất lượng kém, CAM-HUD tạo ra một **lớp phủ HUD trong suốt (Floating Overlay)** đè thẳng lên ứng dụng Camera gốc của máy (ví dụ Camera Xiaomi 200MP + OIS).

Người dùng vừa tận dụng được 100% thuật toán xử lý ảnh, chống rung và chế độ Pro của thiết bị, vừa có trợ lý hiển thị lưới bố cục, đo độ cân bằng chân trời và cảnh báo tốc độ lia máy theo thời gian thực.

---

## ✨ Tính năng cốt lõi

1. **Thư viện lưới bố cục đa năng (Dynamic Composition Grids):**
   * **Chân dung (Portrait):** Lưới 1/3, vạch căn mắt (*Eye-line*), vạch hack chiều cao toàn thân và khoảng trống hướng nhìn (*Lead room*).
   * **Phong cảnh (Landscape):** Thước thủy đôi, lưới 1/3 ngang, đường dẫn cong chữ S.
   * **Kiến trúc (Architecture):** Lưới đối xứng trục giữa và lưới phối cảnh chống đổ cột (*Keystone effect*).
   * **Đường phố (Street):** Xoắn ốc Tỷ lệ vàng (Fibonacci Spiral) có nút lật 4 hướng.

2. **Cố vấn góc máy bằng cảm biến (Sensor Orientation Engine):**
   * Sử dụng cảm biến `TYPE_GAME_ROTATION_VECTOR` kết hợp tự động đổi trục khi xoay ngang/dọc máy.
   * Tính toán chính xác góc chúc/ngửa (*Pitch*) và nghiêng ngang (*Roll*).
   * Cơ chế **Hysteresis ($0.3^\circ$)** và rung phản hồi (Haptic) khi đạt độ cân bằng chuẩn.

3. **Huấn luyện viên quay B-Roll điện ảnh (B-Roll Video Coach):**
   * Kiểm soát vận tốc góc Gyroscope $\omega \le 0.25\ \text{rad/s}$ ($\approx 14^\circ/s$). Cảnh báo vàng nếu lia máy quá nhanh hoặc giật cục.
   * Đồng hồ đếm nhịp 5 giây (1s đệm đầu + 3s hành động + 1s đệm cuối).
   * Checklist 5 góc máy chuẩn: `[WIDE]`, `[MED]`, `[TIGHT-HANDS]`, `[TIGHT-FACE]`, `[OTS]`.

4. **Tự động bắt log quay chụp (`shot_log.json`):**
   * Lắng nghe sự kiện qua `MediaStore ContentObserver`.
   * Tự động lưu tên file thật (`VID_2026xxxx.mp4`) cùng nhãn cỡ cảnh và thông số độ mượt lúc quay sang `shot_log.json` để kết nối thẳng với bàn dựng CapCut trên máy tính.

---

## 🏗️ Kiến trúc kỹ thuật: Dual-Window Overlay

```
┌──────────────────────────────────────────────────────────┐
│ Cửa sổ 1: GridOverlayView (Chiếm toàn màn hình)         │
│ • Cờ: FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE            │
│ • Truyền xuyên thao tác chạm 100% xuống Camera gốc       │
├──────────────────────────────────────────────────────────┤
│ Cửa sổ 2: PillControlView (Thanh nhỏ ở mép màn hình)    │
│ • Nhận chạm & kéo thả vị trí                             │
│ • Đổi nhanh Thể loại / Lưới / Nhãn cú máy                │
└──────────────────────────────────────────────────────────┘
```

---

## 🚀 Thiết lập & Biên dịch siêu nhẹ (Không cần Android Studio)

Dự án được tối ưu để bạn có thể biên dịch ra file APK chỉ với **JDK 17 + Android Command-line Tools** và dòng lệnh PowerShell:

```powershell
# 1. Biên dịch ra file APK debug
./gradlew assembleDebug

# 2. Cài đặt trực tiếp vào điện thoại qua cáp USB Type-C
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 3. Khởi chạy ứng dụng từ xa
adb shell am start -n com.coach.camera/.MainActivity

# 4. Xem nhật ký log cảm biến thời gian thực
adb logcat -s CameraCoach OrientationEngine MediaShotLogger
```

Chi tiết hướng dẫn cài đặt môi trường từ A-Z xem tại: 📄 [Doc1_Mobile_Camera_Coach_Plan.md](./Doc1_Mobile_Camera_Coach_Plan.md).

---

## 📁 Cấu trúc thư mục

```text
cam-hud/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── assets/
│   │   ├── poses/               # Khung vector SVG dáng mẫu
│   │   └── rules_data.json      # Ngưỡng cảm biến theo 5 thể loại
│   └── kotlin/com/coach/camera/
│       ├── MainActivity.kt      # Màn hình cài đặt & hiệu chỉnh 0 độ
│       ├── CoachOverlayService  # Quản lý 2 cửa sổ nổi
│       ├── sensor/              # Xử lý Rotation Vector & Gyro
│       ├── overlay/             # Custom View vẽ Canvas lưới & thước thủy
│       └── logger/              # ContentObserver ghi shot_log.json
├── Doc1_Mobile_Camera_Coach_Plan.md # Bản đặc tả thiết kế chi tiết v2.0
└── README.md
```

---

## 📄 License
Dự án phục vụ mục đích nghiên cứu, học tập cá nhân về nhiếp ảnh và lập trình hệ thống Android.
