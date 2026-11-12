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

## 📖 Hướng dẫn Sử dụng Chi tiết & Ý nghĩa Các Thông số (User Manual & Telemetry Guide)

### 1. Ý nghĩa các thông số hiển thị trên lớp phủ HUD

Khi mở Camera, lớp phủ HUD hiển thị thông số thời gian thực ở mép trên và trung tâm khung ngắm:

```text
┌────────────────────────────────────────────────────────────────────────┐
│ [PORT 1/2] [4:3] | P:+0.5° R:-0.2° | ω:0.14 | ● CÂN BẰNG                │
└────────────────────────────────────────────────────────────────────────┘
```

| Ký hiệu / Thông số | Tên gọi & Giải nghĩa kỹ thuật | Ngưỡng chuẩn & Quy tắc nhiếp ảnh |
| :--- | :--- | :--- |
| **`[PORT 1/2]`** | **Chế độ quay/chụp hiện tại:** 1 trong 5 chế độ (`PORT 1/2`, `PORT FULL`, `LANDSCAPE`, `ARCH`, `B-ROLL`). | Bấm nút trên thanh Pill để chuyển đổi nhanh theo từng thể loại ảnh/video. |
| **`[4:3]` / `[16:9]`** | **Tỷ lệ khung ngắm Viewfinder:** Khớp vùng xem trước của chế độ Chụp ảnh (`4:3`) hoặc Quay video (`16:9`). | Có thể bấm `📐 KHỚP KHUNG` trên thanh Pill để tinh chỉnh mép trên/dưới $\pm 1\%$ cho khớp khít màn hình máy bạn. |
| **`P:+0.5°` (Pitch)** | **Góc chúc / ngửa của máy:** Tính bằng độ theo camera sau.<br>• $0^\circ$: Điện thoại dựng thẳng đứng vuông góc mặt đất.<br>• Dương ($+$): Đang ngửa máy lên trời.<br>• Âm ($-$): Đang chúc máy xuống đất. | • Chân dung bán thân: $P \in [-3^\circ, +3^\circ]$ (ngang tầm mắt mẫu).<br>• Chân dung toàn thân: $P \in [+5^\circ, +12^\circ]$ (ngửa máy từ thắt lưng).<br>• Kiến trúc: $P \in [-1.5^\circ, +1.5^\circ]$ (chống đổ cột). |
| **`R:-0.2°` (Roll)** | **Độ nghiêng đường chân trời:** Tính bằng độ trong mặt phẳng xoay của máy. Đã tự động chuẩn hóa khi cầm dọc hay xoay ngang. | • Chuẩn cân bằng: $|R| \le 1.0^\circ$ (Phong cảnh/Kiến trúc) hoặc $\le 1.5^\circ$ (Chân dung). Đường chân trời hoàn toàn phẳng khi $R \approx 0^\circ$. |
| **`ω:0.14` (Omega)** | **Vận tốc góc lia máy (Gyroscope):** Đo tốc độ xoay cổ tay ($\text{rad/s}$). | • Tốc độ điện ảnh chuẩn: $\omega \in [0.08, 0.25]\ \text{rad/s}$ ($\approx 4.5^\circ/s \to 14^\circ/s$).<br>• Nếu $\omega > 0.25$: Cảnh báo vàng *"Lia máy quá nhanh!"*.<br>• Nếu $\omega < 0.08$: Cảnh báo *"Lia máy quá chậm hoặc đang giữ tĩnh"*. |
| **`σ:0.04` (Sigma)** | **Độ đều tay / Chống giật cục:** Độ lệch chuẩn của vận tốc góc trong 300ms. | • Chuẩn mượt mà: $\sigma_\omega \le 0.08\ \text{rad/s}$. Nếu vượt ngưỡng sẽ cảnh báo *"Tay đang bị khựng, hãy khóa cổ tay!"*. |
| **`● CÂN BẰNG`** | **Trạng thái căn chỉnh (Status Badge):**<br>• `● CÂN BẰNG` (Xanh lá): Đạt cả Pitch và Roll.<br>• `○ LỆCH` (Vàng/Đỏ): Đang bị nghiêng hoặc chúc/ngửa sai góc. | Cơ chế **Hysteresis ($0.3^\circ$)** giữ trạng thái ổn định không bị nhấp nháy viền. Máy sẽ **rung phản hồi xúc giác 1 nhịp (Haptic)** ngay khi đạt chuẩn. |

---

### 2. Các nút thao tác trên thanh nổi (Pill Control View)

Thanh nổi nhỏ gọn ở mép phải màn hình giúp bạn điều khiển HUD mà không cần thoát khỏi Camera:

* **Tay nắm `⠿ HUD`:** 
  * **Kéo thả:** Chạm giữ và kéo để di chuyển thanh Pill tới bất kỳ góc nào trên màn hình.
  * **Bấm nhanh:** Thu gọn thanh thành nút nhỏ `⠿+` để không che tầm nhìn, bấm lại để mở rộng.
* **Nút 1 — `[TÊN CHẾ ĐỘ]`:** Chuyển vòng tròn qua 5 chế độ: `PORT 1/2` $\to$ `PORT FULL` $\to$ `LANDSCAPE` $\to$ `ARCH` $\to$ `STREET` $\to$ `B-ROLL`.
* **Nút 2 — `LẬT:V1..V4`:** Đổi biến thể bố cục:
  * Trong *Street:* Lật xoắn ốc Tỷ lệ vàng Fibonacci theo 4 hướng.
  * Trong *Landscape:* Đổi đường dẫn cong chữ S (*S-Curve*) sang đường chéo (*Diagonals*) hoặc khung Tiền cảnh.
  * Trong *B-Roll:* Đổi hướng lia máy (Pan Trái $\to$ Phải, Pan Phải $\to$ Trái, Tilt Dưới $\to$ Trên, Orbit Vòng cung).
* **Nút 3 — `KHUNG:4:3 / 16:9`:** Đổi nhanh tỷ lệ khung ngắm giữa Chụp ảnh (`4:3`) và Quay phim (`16:9`).
* **Nút 4 — `[SHOT TAG]`:** Chọn nhãn cú máy điện ảnh: `[WIDE]` (Toàn cảnh), `[MED]` (Trung cảnh), `[TIGHT-HANDS]` (Cận tay), `[TIGHT-FACE]` (Cận mặt), `[OTS]` (Qua vai).
* **Nút 5 — `⏱ NHỊP 5S`:** Kích hoạt thanh đếm nhịp quay 5 giây chuẩn Hollywood (1s đệm đầu + 3s lia mượt + 1s đệm cuối) có rung nhẹ báo chuyển pha.
* **Nút 6 — `🎯 SET 0°`:** 
  * Chạm nhanh: Hiệu chỉnh mặt phẳng hiện tại thành mốc $0^\circ$ chuẩn.
  * Giữ lâu (Long press): Reset offset về $0^\circ$ mặc định của cảm biến.
* **Nút 7 — `📐 KHỚP KHUNG`:** Mở cụm nút `▲/▼ TRÊN` và `▲/▼ DƯỚI` ($\pm 1\%$) để căn mép khung lưới khít 100% với vùng preview của Camera Xiaomi.
* **Nút 8 — `✕ TẮT HUD`:** Tắt hoàn toàn 2 cửa sổ nổi khi chụp xong.

---

### 3. Hướng dẫn chụp & quay theo từng thể loại

#### A. Chân dung Bán thân (`PORT 1/2`) — Từ thắt lưng lên đầu
1. Bấm chọn `PORT 1/2` trên thanh Pill. Chọn ống kính **2x** trên Camera Xiaomi (chống méo mặt).
2. Canh mắt người mẫu nằm đúng vào vạch ngang màu xanh **EYE-LINE** (1/3 trên).
3. Đặt đỉnh đầu và vai mẫu vào khung dáng bán thân gợi ý ở giữa màn hình.
4. Giữ máy ngang tầm mắt mẫu (Pitch $\approx 0^\circ$). Khi khung viền đổi sang màu **Xanh lá** và máy **rung nhẹ 1 nhịp**, bấm nút chụp.

#### B. Chân dung Toàn thân (`PORT FULL`) — Kéo dài chân, "Hack" dáng
1. Bấm chọn `PORT FULL`. 
2. Canh mũi giày của mẫu chạm sát vào vạch màu xanh lá **VẠCH MŨI GIÀY** ở mép dưới màn hình (chừa 5% khoảng đệm dưới chân).
3. Hạ thấp điện thoại xuống ngang thắt lưng của bạn, hơi **ngửa máy lên một góc $+5^\circ \to +12^\circ$**.
4. Khung viền báo Xanh lá $\rightarrow$ Chân mẫu sẽ dài miên man tự nhiên mà không bị biến dạng cơ thể!

#### C. Phong cảnh (`LANDSCAPE`) — Chiều sâu 3 lớp
1. Bấm chọn `LANDSCAPE`.
2. Giữ thước thủy trung tâm cân bằng nằm ngang ($|R| \le 1.0^\circ$).
3. **Quy tắc 1/3:** 
   * Nếu trời đẹp (hoàng hôn, mây vần): Đặt chân trời ở vạch 1/3 dưới (bầu trời chiếm 2/3).
   * Nếu đất/biển đẹp: Đặt chân trời ở vạch 1/3 trên.
4. Bấm `LẬT` để bật đường cong chữ S (*S-Curve*) dẫn mắt từ vật thể tiền cảnh (con đường, tảng đá) vào chủ thể chính.

#### D. Kiến trúc & Nội thất (`ARCH`) — Chống đổ cột (Keystone Effect)
1. Bấm chọn `ARCH`.
2. Màn hình sẽ hiện các đường gióng dọc song song và trục đối xứng chính giữa.
3. Luôn giữ máy vuông góc với mặt đất ($\text{Pitch} \in [-1.5^\circ, +1.5^\circ]$).
4. Canh mép tường hoặc cột nhà song song với lưới gióng dọc. Muốn lấy trọn đỉnh tòa nhà cao tầng, hãy **lùi ra xa** hoặc chuyển sang camera siêu rộng **0.6x** nhưng vẫn giữ $\text{Pitch} = 0^\circ$ thay vì ngửa máy lên.

#### E. Quay Video B-Roll (`B-ROLL`) — Lia máy mượt mà & Đếm nhịp 5 giây
1. Bấm chọn `B-ROLL`, chọn nhãn cú máy (ví dụ `[TIGHT-HANDS]` khi quay tay pha cà phê, hoặc `[WIDE]` khi quay phong cảnh).
2. Bấm nút **`⏱ NHỊP 5S`**:
   * **Pha 1 (1.0s đầu - Màu Vàng):** Giữ máy đứng yên tuyệt đối để lấy khung hình tĩnh đầu clip.
   * **Pha 2 (3.0s giữa - Màu Xanh lá):** Bắt đầu lia máy mượt theo hướng mũi tên Vector. Chú ý khóa cứng cổ tay, xoay bằng cả phần hông và thân trên.
   * **Pha 3 (1.0s cuối - Màu Vàng):** Dừng máy và giữ im 1 giây trước khi bấm tắt quay.
3. Nếu bạn lia máy quá nhanh hoặc giật tay, HUD sẽ hiện cảnh báo tức thì để bạn quay lại cú máy hoàn hảo hơn.

---

### 4. Tự động ghi nhật ký cú máy (`shot_log.json`)

Bạn không cần ghi chép thủ công. Ngay khi bấm dừng quay trên Camera Xiaomi, `MediaShotLogger` sẽ tự động bắt file mới sinh ra trong `DCIM/Camera/` và lưu thông số vào:  
📁 `/Documents/CameraCoach/shot_log.json`

Ví dụ nội dung file:
```json
[
  {
    "file_name": "VID_20261112_140520.mp4",
    "timestamp": 1794467120,
    "mode": "BROLL",
    "shot_tag": "TIGHT-HANDS",
    "avg_pitch_deg": -5.2,
    "avg_roll_deg": 0.3,
    "max_gyro_rad_s": 0.16,
    "is_smooth": true
  }
]
```

**Cách khai thác khi dựng video:**
* Khi chép video sang máy tính để edit CapCut / Premiere, bạn chỉ cần mở file `shot_log.json` để biết ngay clip nào là cỡ cảnh gì (`TIGHT-HANDS` hay `WIDE`).
* Lọc những clip có `"is_smooth": true` để đưa vào timeline dựng mà không mất công bấm mở xem lại từng video bị rung lắc!

---

### 5. Xử lý các thông báo bảo mật thường gặp khi cài đặt

* **Thông báo "Bị chặn bởi Play Protect" (Google Play Protect):**  
  Do app được build trực tiếp từ mã nguồn chưa tải lên Google Play Store. Bạn chỉ cần bấm **"Chi tiết khác" (More details)** $\rightarrow$ Bấm **"Vẫn cài đặt" (Install anyway)**.
* **Thông báo "Cài đặt bị hạn chế" (Restricted Settings) trên Xiaomi HyperOS/MIUI:**  
  Vào **Cài đặt** điện thoại $\rightarrow$ **Ứng dụng** $\rightarrow$ **Quản lý ứng dụng** $\rightarrow$ Chọn **CAM-HUD Coach** $\rightarrow$ Bấm vào dấu **`⋮`** ở góc trên bên phải $\rightarrow$ Chọn **"Cho phép cài đặt bị hạn chế"**. Sau đó quay lại bật quyền Trợ năng bình thường.
* **Quyền ảnh/video trên Android 14:**  
  Khi hộp thoại quyền MediaStore hiện lên, hãy chọn **"Cho phép tất cả ảnh và video"** để app phát hiện được mọi file mới quay.

---

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
