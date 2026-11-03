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
   * **Chân dung (Portrait):**
     * *Bán thân (`PORT 1/2`):* Lưới 1/3, vạch căn mắt (*Eye-line* 1/3 trên), khung dáng mẫu bán thân.
     * *Toàn thân (`PORT FULL` - Hack chiều cao):* Vạch giới hạn mũi giày sát mép dưới (5%), khung tam giác gợi ý dáng đứng và hướng dẫn góc máy ngửa $+5^\circ \to +12^\circ$.
   * **Phong cảnh (Landscape):** Thước thủy đôi, lưới 1/3 ngang (Đất 1/3 / Trời 1/3), đường dẫn cong chữ S (*S-Curve*) và đường chéo vàng.
   * **Kiến trúc (Architecture):** Lưới đối xứng trục giữa chữ thập và lưới gióng dọc song song chống đổ cột (*Keystone effect*, giữ $\text{Pitch} \approx 0^\circ$).
   * **Đường phố (Street):** Xoắn ốc Tỷ lệ vàng (*Fibonacci Spiral*) có nút lật 4 hướng + vùng khoảng trống hướng nhìn 2/3 (*Looking Room*).

2. **Cố vấn góc máy bằng cảm biến (Sensor Orientation Engine):**
   * Sử dụng cảm biến hợp nhất `TYPE_GAME_ROTATION_VECTOR`, tính Pitch/Roll trực tiếp từ vector thiên đỉnh (Up Vector) của ma trận xoay — không bị Gimbal Lock khi cầm máy dựng đứng, tự nhận hướng cầm dọc/ngang (`ROTATION_0/90/180/270`) từ trọng lực thay vì `display.rotation` (Camera Xiaomi khóa UI dọc).
   * Tính toán chính xác góc chúc/ngửa (*Pitch*) và nghiêng ngang (*Roll*) kèm hiệu chỉnh gốc (**Calibration $0^\circ$** — dựng máy thẳng đứng áp vào tường, không đặt nằm phẳng).
   * Cơ chế **Hysteresis ($0.3^\circ$)** chống nhấp nháy khung viền, rung phản hồi xúc giác 1 nhịp (**Edge-Trigger Haptic**) khi đạt độ cân bằng chuẩn, và tối ưu chỉ vẽ lại Canvas khi góc lệch $> 0.15^\circ$.

3. **Huấn luyện viên quay B-Roll điện ảnh (B-Roll Video Coach):**
   * Kiểm soát vận tốc góc trung bình Gyroscope $\bar{\omega} \in [0.08, 0.25]\ \text{rad/s}$ ($\approx 4.5^\circ/s \to 14.3^\circ/s$) và độ lệch chuẩn cổ tay $\sigma_\omega \le 0.08\ \text{rad/s}$ trong cửa sổ trượt 300ms. Cảnh báo tức thì nếu lia máy quá nhanh hoặc bị khựng/giật.
   * Đồng hồ đếm nhịp **5 giây** (`1s đệm đầu` + `3s hành động chính` + `1s đệm cuối`) có rung báo chuyển pha.
   * Checklist 5 góc máy chuẩn: `[WIDE]`, `[MED]`, `[TIGHT-HANDS]`, `[TIGHT-FACE]`, `[OTS]`.

4. **Tự động bắt log quay chụp (`shot_log.json`):**
   * Lắng nghe sự kiện qua `MediaStore ContentObserver`.
   * Tự động lưu tên file thật (`VID_2026xxxx.mp4` / `IMG_2026xxxx.jpg`) cùng nhãn cỡ cảnh, góc Pitch/Roll trung bình và thông số độ mượt lúc quay sang `/Documents/CameraCoach/shot_log.json` để kết nối thẳng với bàn dựng CapCut trên máy tính.

---

## 🏗️ Kiến trúc kỹ thuật: Dual-Window Overlay

```text
┌──────────────────────────────────────────────────────────┐
│ Cửa sổ 1: GridOverlayView (Chiếm toàn màn hình)          │
│ • Cờ: FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE            │
│ • Truyền xuyên thao tác chạm 100% xuống Camera gốc       │
│ • Hỗ trợ Trusted Accessibility Overlay (alpha = 1.0f)    │
│   hoặc Application Overlay dự phòng (alpha = 0.78f)      │
├──────────────────────────────────────────────────────────┤
│ Cửa sổ 2: PillControlView (Thanh nhỏ ở mép màn hình)     │
│ • Nhận chạm & kéo thả vị trí, thu gọn / mở rộng          │
│ • Đổi nhanh Chế độ / Lật lưới / Khung 4:3-16:9 / Shot Tag│
└──────────────────────────────────────────────────────────┘
```

---

## 📦 Các công cụ & Thư viện cần cài đặt

Dự án được thiết kế **100% Native Kotlin** không phụ thuộc thư viện bên thứ ba nặng nề. Khi clone về laptop ở nhà, bạn chỉ cần chuẩn bị các thành phần dưới đây:

### 1. Công cụ hệ thống cần cài trên máy tính (System Toolchain)

| Thành phần | Phiên bản yêu cầu | Cách cài đặt nhanh trên Windows (PowerShell) |
| :--- | :--- | :--- |
| **JDK (Java Development Kit)** | **JDK 17 LTS** (Eclipse Temurin) | `winget install EclipseAdoptium.Temurin.17.JDK` |
| **Android Command-Line Tools** | Bản `latest` | Tải từ [developer.android.com/studio#command-tools](https://developer.android.com/studio#command-tools) $\rightarrow$ Giải nén vào `C:\AndroidSDK\cmdline-tools\latest\` |
| **Android Platform-Tools (ADB)** | Bản `latest` | `sdkmanager "platform-tools"` hoặc tải gói zip giải nén vào `C:\AndroidSDK\platform-tools\` |
| **Android SDK Platform 34** | **API 34 (Android 14)** | `sdkmanager "platforms;android-34"` |
| **Android SDK Build-Tools** | **34.0.0** | `sdkmanager "build-tools;34.0.0"` |

> **Biến môi trường Windows cần trỏ:**
> * `JAVA_HOME`: Đường dẫn thư mục JDK 17 (ví dụ `C:\Program Files\Eclipse Adoptium\jdk-17.x.x-hotspot`)
> * `ANDROID_HOME`: Đường dẫn thư mục SDK (ví dụ `C:\AndroidSDK`)
> * Thêm vào `Path`: `%JAVA_HOME%\bin`, `%ANDROID_HOME%\cmdline-tools\latest\bin`, `%ANDROID_HOME%\platform-tools`
> *(Hoặc tạo file `local.properties` ở thư mục gốc dự án với dòng: `sdk.dir=C:\\AndroidSDK`)*.

### 2. Các thư viện Gradle & AndroidX trong dự án (Tự động tải khi Build)

Khi bạn chạy lệnh `./gradlew.bat assembleDebug`, **Gradle Wrapper 8.5** (đã tích hợp sẵn trong repo) sẽ tự động tải các plugin và thư viện sau từ Google Maven (`google()`) & Maven Central (`mavenCentral()`):

* **Build Plugins (`build.gradle.kts`):**
  * `com.android.application` — Phiên bản **`8.2.2`** (Android Gradle Plugin)
  * `org.jetbrains.kotlin.android` — Phiên bản **`1.9.22`** (Kotlin Compiler target JVM 17)
* **Thư viện Runtime (`app/build.gradle.kts`):**
  * `androidx.core:core-ktx:1.12.0` — Kotlin extensions cho Android Core, quản lý quyền và Notification.
  * `androidx.appcompat:appcompat:1.6.1` — Tương thích giao diện Activity trên các bản Android.
  * `com.google.android.material:material:1.11.0` — Hệ thống Theme `Theme.MaterialComponents.DayNight.NoActionBar`.
* **Module hệ thống Android SDK 34 (Có sẵn trong `platforms;android-34`):**
  * `android.hardware.SensorManager` (`TYPE_GAME_ROTATION_VECTOR`, `TYPE_GYROSCOPE`)
  * `android.view.WindowManager` & `android.accessibilityservice.AccessibilityService`
  * `android.provider.MediaStore` & `android.database.ContentObserver`
  * `org.json.JSONObject` / `org.json.JSONArray`

---

## 🚀 Hướng dẫn Clone, Biên dịch & Chạy thử trên Điện thoại

```powershell
# 1. Clone dự án về máy tính ở nhà
git clone <url-repo-cua-ban>
cd cam-hud

# 2. Chấp nhận license SDK và cài SDK 34 (nếu máy chưa tải)
sdkmanager --licenses
sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"

# 3. Biên dịch ra file APK debug
.\gradlew.bat assembleDebug

# 4. Cài đặt trực tiếp vào Xiaomi Redmi Note 13 Pro qua cáp USB Type-C
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 5. Khởi chạy ứng dụng từ xa
adb shell am start -n com.coach.camera/.MainActivity

# 6. Xem nhật ký log cảm biến & shot_log thời gian thực
adb logcat -s CameraCoach OrientationEngine MediaShotLogger
```

> 💡 **Mẹo 1-Click:** Bạn có thể chạy trực tiếp file kịch bản tự động có sẵn trong dự án:
> ```powershell
> .\deploy.bat
> ```
> Lệnh này sẽ tự động Build APK $\rightarrow$ Cài vào máy qua ADB $\rightarrow$ Mở thẳng màn hình chính của CAM-HUD.

### Lưu ý cấp quyền lần đầu trên Xiaomi HyperOS (Redmi Note 13 Pro):
1. Trong **Tùy chọn nhà phát triển (Developer Options)** trên điện thoại: Bật cả **Gỡ lỗi USB (USB debugging)** và **Cài đặt qua USB (Install via USB)**.
2. Mở app **CAM-HUD Coach**:
   * **Cách 1 (Khuyên dùng):** Bấm nút **① Bật Accessibility Trusted Overlay** $\rightarrow$ Vào mục *Ứng dụng đã tải xuống (Downloaded apps)* $\rightarrow$ Bật **CAM-HUD Camera Coach Overlay**. *(Nếu HyperOS báo "Cài đặt bị hạn chế / Restricted setting", vào Cài đặt $\rightarrow$ Ứng dụng $\rightarrow$ CAM-HUD Coach $\rightarrow$ Bấm dấu `⋮` ở góc trên $\rightarrow$ Chọn "Cho phép cài đặt bị hạn chế")*.
   * **Cách 2 (Dự phòng nhanh):** Bấm nút **② Cấp quyền Cửa sổ nổi dự phòng (`SYSTEM_ALERT_WINDOW`)** để chạy ở chế độ `alpha = 0.78f`.
   * Bấm nút **③ Cấp quyền đọc MediaStore** để tính năng tự động ghi `/Documents/CameraCoach/shot_log.json` hoạt động.

Chi tiết đặc tả thiết kế và công thức toán học xem tại: 📄 [Doc1_Mobile_Camera_Coach_Plan.md](./Doc1_Mobile_Camera_Coach_Plan.md).

---

## 📁 Cấu trúc thư mục

```text
cam-hud/
├── app/
│   ├── build.gradle.kts                     # Cấu hình SDK 34 & thư viện AndroidX
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml              # Khai báo Accessibility, Overlay, MediaStore & Xiaomi Camera queries
│       ├── assets/
│       │   ├── poses/                       # Khung vector SVG dáng mẫu (Bán thân, Toàn thân, Đường phố)
│       │   │   ├── portrait_half.svg
│       │   │   ├── portrait_full_triangle.svg
│       │   │   └── street_walking.svg
│       │   └── rules_data.json              # Ngưỡng cảm biến, Hysteresis 0.3° & lời nhắc cho 5 chế độ
│       ├── res/
│       │   ├── drawable/ic_camera_hud.xml   # Vector icon của ứng dụng & Notification
│       │   ├── values/strings.xml
│       │   ├── values/themes.xml
│       │   └── xml/accessibility_service_config.xml
│       └── kotlin/com/coach/camera/
│           ├── MainActivity.kt              # Màn hình cài đặt, Calibration 0° & khớp khung 4:3 / 16:9
│           ├── CoachOverlayService.kt       # Quản lý 2 cửa sổ nổi (Accessibility & Foreground Service)
│           ├── sensor/
│           │   └── OrientationEngine.kt     # Xử lý TYPE_GAME_ROTATION_VECTOR, Gyro 300ms & Hysteresis
│           ├── overlay/
│           │   ├── GridOverlayView.kt       # Custom View vẽ Canvas 5 chế độ lưới, thước thủy & nhịp 5s
│           │   └── PillControlView.kt       # Thanh điều khiển nổi thu gọn & kéo thả vị trí
│           └── logger/
│               └── MediaShotLogger.kt       # ContentObserver bắt file mới & ghi shot_log.json
├── gradle/wrapper/                          # Gradle Wrapper 8.5 (tích hợp sẵn gradle-wrapper.jar)
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat                    # Script chạy Gradle (có tự động tải wrapper nếu thiếu)
├── deploy.bat                               # Script 1-Click Build + ADB Install + Launch
├── Doc1_Mobile_Camera_Coach_Plan.md         # Bản đặc tả thiết kế chi tiết v2.0
└── README.md
```

---

## 📄 License
Dự án phục vụ mục đích nghiên cứu, học tập cá nhân về nhiếp ảnh và lập trình hệ thống Android.
