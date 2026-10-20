# Presence Timer (native Android, Kotlin)

Native port of `index.html`: face + body detection starts a timer, with daily history, streaks,
saved records, alarms and backup/restore. Adds what a web page cannot do:

| Feature | How |
|---|---|
| Timer in notification panel | Foreground service notification, updated every second, with a Stop button |
| Tiny dim timer over other apps | `SYSTEM_ALERT_WINDOW` overlay, 30% opacity, touch-through. Shown only while you are outside the app |
| Keeps tracking when minimized | Foreground service (type `camera`) + CameraX + ML Kit + partial wake lock |
| Alarms in background | Bundled `alarm.mp3` (extracted from your HTML) on the alarm stream |
| Icon | Red dot on black adaptive icon (+ themed monochrome version) |

Detection is fully offline: ML Kit face detection and pose detection (body) ship inside the APK.

## Build the APK on GitHub
1. Create a GitHub repo and push this whole folder (the `.github` folder must be included).
2. Open the **Actions** tab, then **Build Android APK** (it runs on every push; or press **Run workflow**).
3. When it finishes, open the run and download the **PresenceTimer-debug-apk** (or **-release-apk**) artifact.
4. Unzip, copy the `.apk` to your phone and install it (allow "Install unknown apps").

No `gradle-wrapper.jar` is needed: the workflow installs Gradle 8.9 itself.
To build locally: open the folder in Android Studio (Hedgehog or newer) and press Run.

## Permissions
- Camera, Notifications (Android 13+): asked when you press **Allow Camera & Start**.
- Display over other apps: asked when you turn on **Floating timer** (or the Permissions card).
- Unrestricted battery: use the **Permissions** card. Strongly recommended.
- Also declared: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CAMERA`, `WAKE_LOCK`.

## Android limits to know about
- Android shows a green camera indicator whenever the camera is used. This cannot be removed.
- Start tracking while the app is open. Android will not let an app start a camera service from the background.
- If you swipe the app away and the system kills the service, tracking stops (it cannot restart itself).
- Some phones (Xiaomi, Oppo, Vivo, Huawei, Samsung) kill background apps aggressively. Set battery to
  "Unrestricted" and enable Autostart / "lock in recents".
- Some devices pause camera frames when the screen is off. The timer then pauses (no frames = nobody seen).

## Data compatibility
Storage keys and the backup JSON format match the web app. You can **Export** in the web version and
**Import & Merge** here.
