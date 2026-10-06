@echo off
rem Install Vakya on a phone connected by USB and point it at the server on this PC.
rem Before: phone has USB debugging on, and backend\server.bat is running.
setlocal
cd /d "%~dp0"
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist "%ADB%" (
    echo adb not found at %ADB%. Install Android Studio's SDK Platform-Tools.
    pause
    exit /b 1
)

"%ADB%" get-state >nul 2>&1
if errorlevel 1 (
    echo No phone found. Connect it by USB, turn on USB debugging,
    echo and tap "Allow" on the phone when it asks. Then run this again.
    pause
    exit /b 1
)

set "APK=app\build\outputs\apk\debug\app-debug.apk"
if not exist "%APK%" (
    echo Building the app, the first time takes a few minutes...
    set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
    call "%~dp0gradlew.bat" assembleDebug -q || (pause & exit /b 1)
)

echo Installing Vakya...
"%ADB%" install -r "%APK%" || (pause & exit /b 1)

rem The phone's localhost:8000 now reaches this PC's server over the USB cable.
"%ADB%" reverse tcp:8000 tcp:8000 || exit /b 1

echo.
echo Done. On the phone:
echo   1. Open Vakya, set the server to  http://localhost:8000  and tap "Save and test".
echo   2. Tap "Turn on in Accessibility settings" and enable Vakya.
echo   3. Open a WhatsApp chat, tap the message box and look for the bubble.
echo If you unplug the phone, run this again to reconnect.
echo.
pause

