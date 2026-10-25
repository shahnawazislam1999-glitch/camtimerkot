# Presence Timer (native Android, Kotlin)

Native port of `index.html`: face + body detection starts a timer, with daily history, streaks,
saved records, alarms and backup/restore. Adds what a web page cannot do:

| Feature | How |
|---|---|
| Timer in notification panel | Foreground service notification, updated every second, with **Pause/Resume** and Stop buttons |
| Tiny dim timer over other apps | `SYSTEM_ALERT_WINDOW` overlay, 35% opacity. **Drag it anywhere; double tap = pause, double tap again = start instantly (forces an instant check in periodic mode)**. Shown only while you are outside the app |
| Keeps tracking when minimized | Foreground service (type `camera`) + CameraX + ML Kit + partial wake lock |
| Alarms in background | Bundled `alarm.mp3` (extracted from your HTML) on the alarm stream |
| Icon | Red dot on black adaptive icon (+ themed monochrome version) |

Detection is fully offline: ML Kit face detection and pose detection (body) ship inside the APK.

## New in 1.6.0 (v22)
- **Auto reset is now daily at 12 AM** (local midnight) instead of every 24 hours. Same toggle in Settings -> Auto reset; the unsaved time is saved as a record first, daily history untouched.
- **Study time history** now sits right below the main countdown (no scrolling).

## New in 1.5.0 (v5) - Aggressive mode: new 5-minute check logic
- Set the interval (e.g. 5 min). When it is due the camera **wakes** and checks continuously for a real human
  (8 signals, chair-proof - see `HumanVerifier.kt`).
- The **main countdown keeps running** during the check, even while the camera fails to capture a human; it keeps rechecking.
- Human confirmed at any moment -> camera sleeps until the next interval, countdown continues.
- No human for the full **5 minutes** -> **alarm rings, camera goes to sleep, countdown stops**.
- The alarm stops with Pause / Resume / Stop (Resume also starts an immediate new check) or when a later check confirms a human.
- Removed: the old 10/20/30 s checks and the 2-minute recheck. Window length: `AppSettings.AGG_CHECK_WINDOW_MS`.
- Temporary photos stay in the private cache and are deleted when the check ends.

## New in 1.3.0
- **Camera sleeps when nobody is there**: no human on camera for 5 minutes in a row -> camera switches off. It wakes only at
  the interval you typed (Settings -> Camera power saving, 5 s .. 24 h) for a short check. Person found = normal tracking
  again, nobody = sleeps again. (Not used while paused, while the PC link is down, or while the person-missing alarm sounds.)
- **Blink verification** toggle (Settings -> Detection mode): a person only counts after a blink is seen (30 s validity per blink).
  OFF = normal face + body detection.
- **24 h auto reset** toggle (Settings -> Auto reset): main countdown returns to 0 every 24 h; the time is saved as a record first.
- **Stay awake**: wake lock is renewed (it used to expire after 24 h), Wi-Fi lock in PC mode, battery-exemption prompt when you press Start,
  "Autostart / background" button in the Permissions card, notification restored when the app is cleared from Recents.

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
- Swiping the app out of Recents does **not** stop tracking (service is `stopWithTask=false`, sticky, wake-locked). If a phone maker force-kills it anyway, Android restarts it when allowed; phone-camera mode cannot reopen the camera from the background, so lock the app in Recents too.
- Some phones (Xiaomi, Oppo, Vivo, Huawei, Samsung) kill background apps aggressively. Set battery to
  "Unrestricted" and enable Autostart / "lock in recents".
- Some devices pause camera frames when the screen is off. The timer then pauses (no frames = nobody seen).

## Data compatibility
Storage keys and the backup JSON format match the web app. You can **Export** in the web version and
**Import & Merge** here.


## PC camera mode (v1.1)
Use the **laptop's webcam** instead of the phone camera: run `PresenceCam` (separate folder in the zip)
on the PC, then in the app choose *Camera source -> PC camera -> Pair with PC* and enter the 6-digit code once.
The phone receives the frames and does the same face + body detection. It finds the PC automatically
(UDP beacon, last address, USB loopback, subnet scan) and reconnects by itself after Wi-Fi/hotspot changes.
While the link is down the timer pauses and no "person missing" alarm sounds.


## Periodic detection (v1.2) - saves battery / CPU
Instead of watching the camera all the time, open **Periodic detection** in the app, switch it on and type a
number, then pick **Seconds** or **Minutes** (e.g. `10` + Minutes, or `60` + Seconds) and press **Apply**.

* Press Start: the camera does a first check right away (a few seconds), then **switches off completely**
  (phone camera released / PC webcam released, no face or body processing).
* After every interval it wakes up once, looks for a person, and goes back to sleep.
* **Person found** at a check: the timer keeps running until the next check.
  **Nobody found**: the timer stops at that check and the next check comes after the interval.
* Change the number or unit at any time, even while detecting: the new interval is used immediately.
  (If the new interval is shorter than the time already slept, a check happens right away.)
* Allowed range: 10-3600 seconds or 1-1440 minutes.
* Alarms: the *person-missing* alarm keeps the camera awake until the person is back; the
  *human-detected* alarm only sounds while the camera is awake (during a check).
* PC camera mode: the phone tells the PC to release the webcam between checks (needs PresenceCam 1.2+; an older
  PresenceCam still works, the phone just ignores its frames while sleeping).

Note: the app keeps a small CPU wake lock so checks happen on time with the screen off; everything heavy
(camera + ML Kit) is off while sleeping.

## PC server (background mode)
See `PresenceTimerServer/README.md`: `start.bat` runs it hidden (closing the window does not stop it), running it
again does nothing, `stop-server.bat` stops it, and it searches for the phone in FULL mode until the phone connects.
