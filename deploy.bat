@echo off
echo ==========================================================
echo [CAM-HUD] Building APK, Installing via ADB & Launching App
echo ==========================================================

call .\gradlew.bat assembleDebug
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Build failed!
    exit /b %ERRORLEVEL%
)

echo.
echo [1/2] Installing app-debug.apk to connected Android device...
adb install -r app/build/outputs/apk/debug/app-debug.apk
if %ERRORLEVEL% neq 0 (
    echo [ERROR] ADB install failed! Check USB connection and USB Debugging.
    exit /b %ERRORLEVEL%
)

echo.
echo [2/2] Starting CAM-HUD MainActivity...
adb shell am start -n com.coach.camera/.MainActivity
echo [DONE] CAM-HUD deployed and launched successfully!
