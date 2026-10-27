# CCTV Viewer for Android TV

Live RTSP viewer for DVR/NVR/IP cameras. Login is entered once and stored encrypted
(Android Keystore, AES-256). Built to run 24/7.

## Build (Android Studio, ~5 min)
1. Install Android Studio, choose **Open**, select this folder, wait for Gradle sync.
2. **Build > Build APK(s)**. Output: `app/build/outputs/apk/debug/app-debug.apk`.
   (Or in a terminal with the SDK installed: `gradle assembleDebug`.)

## Install on the TV
1. TV: Settings > About > tap **Build** 7 times, then Developer options > enable **USB/Network debugging**.
2. Note the TV's IP (Settings > Network).
3. On your computer:
       adb connect <TV_IP>:5555
       adb install -r app/build/outputs/apk/debug/app-debug.apk
   (Or copy the APK to a USB stick and open it with a file manager app on the TV.)

## First run
Enter DVR IP, RTSP port (default 554), username, password, number of cameras,
and brand (Hikvision / Dahua-CP Plus / Custom). After that it opens straight to the cameras.

## Remote control
- Grid shows 4 cameras per page. Down/Up on the bottom/top row switches page.
- OK = fullscreen (main stream). Left/Right = next/previous camera. Back = grid.
- Menu button or long-press OK = change settings/password.

## 24/7 settings on the TV itself (the app cannot change these)
- Settings > Device Preferences > Screen saver > **Turn off**; set "Put device to sleep" to **Never**.
- Developer options > **Stay awake** (if present).
- Give the TV a fixed IP/DHCP reservation, and use Ethernet instead of Wi-Fi if possible.
- Auto-start on boot: Android 10+ may block this unless you allow
  Settings > Apps > CCTV Viewer > **Display over other apps**.
  If your TV still won't auto-open it, set the app as the launcher/home or use the TV's "autostart" option.
