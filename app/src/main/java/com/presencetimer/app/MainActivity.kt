package com.presencetimer.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.presencetimer.app.ui.Actions
import com.presencetimer.app.ui.MainScreen
import com.presencetimer.app.ui.PermState
import com.presencetimer.app.ui.PresenceTheme
import java.time.LocalDate

class MainActivity : ComponentActivity() {

    private var resumeTick by mutableIntStateOf(0)
    private var pendingImport by mutableStateOf<Backup.Parsed?>(null)
    private var askedBatteryThisLaunch = false

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun notifOk() = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            resumeTick++
            val pc = Store.settingsFlow.value.cameraSource == AppSettings.SOURCE_PC
            if (pc || granted(Manifest.permission.CAMERA)) {
                PresenceService.start(this)
                requestKeepAlive()
            } else {
                Engine.reportError("Camera permission denied. Open Settings → Apps → Presence Timer → Permissions → Camera → Allow.")
            }
        }

    private val singlePermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { resumeTick++ }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) writeBackup(uri)
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) readBackup(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val actions = Actions(
            onStartStop = ::startStop,
            onSave = { toast(Engine.saveSnapshot()) },
            onReset = { Engine.resetTimer() },
            onResetAll = {
                Engine.stopDetection()
                PresenceService.stop(this)
                Engine.resetAll()
            },
            onExport = {
                exportLauncher.launch("presence-timer-backup-${LocalDate.now()}.json")
            },
            onImport = {
                if (Engine.live.value.detecting) toast("Stop detection before importing a backup.")
                else importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
            },
            onConfirmImport = {
                val p = pendingImport
                pendingImport = null
                if (p != null) {
                    try { toast(Backup.merge(p)) }
                    catch (e: Exception) { toast("Could not import: ${e.message}") }
                }
            },
            onCancelImport = { pendingImport = null },
            onCameraPerm = { permLauncher.launch(arrayOf(Manifest.permission.CAMERA)) },
            onNotifPerm = {
                if (Build.VERSION.SDK_INT >= 33) singlePermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
            onOverlayPerm = {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            },
            onBatteryPerm = {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            },
            onAutostart = ::openAutostartSettings,
            onContact = {
                val msg = Uri.encode("Hello Developer, I need help with Presence Timer.")
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/918399964358?text=$msg")))
            }
        )

        setContent {
            PresenceTheme {
                val tick = resumeTick   // re-read permission state every time the app resumes
                val perms = PermState(
                    camera = granted(Manifest.permission.CAMERA),
                    notifications = notifOk(),
                    overlay = Settings.canDrawOverlays(this),
                    battery = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName),
                    tick = tick
                )
                MainScreen(
                    lifecycle = lifecycle,
                    perms = perms,
                    pendingImportLabel = pendingImport?.exportedLabel,
                    actions = actions
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Engine.checkAutoReset(System.currentTimeMillis())
        resumeTick++
    }

    private fun startStop() {
        if (Engine.live.value.detecting) {
            PresenceService.stop(this)
            return
        }
        Engine.reportError(null)
        val pc = Store.settingsFlow.value.cameraSource == AppSettings.SOURCE_PC
        if (pc && Store.pairingFlow.value == null) {
            toast("Pair with your PC first (Camera source card).")
            return
        }
        val needed = mutableListOf<String>()
        if (!pc && !granted(Manifest.permission.CAMERA)) needed.add(Manifest.permission.CAMERA)
        if (!notifOk()) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        if (needed.isEmpty()) { PresenceService.start(this); requestKeepAlive() }
        else permLauncher.launch(needed.toTypedArray())
    }

    /**
     * While the countdown runs the phone must not stop the app: ask (once per app launch, and only while
     * not yet allowed) to exempt it from battery optimisation. Delayed a moment so the camera service has
     * already started while the app is still on screen.
     */
    private fun requestKeepAlive() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (askedBatteryThisLaunch || pm.isIgnoringBatteryOptimizations(packageName)) return
        askedBatteryThisLaunch = true
        window.decorView.postDelayed({
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Exception) { }
        }, 1500)
    }

    /** Opens the phone maker's "autostart / app launch / protected apps" screen, or the app's own settings. */
    private fun openAutostartSettings() {
        val candidates = listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
            "com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity",
            "com.letv.android.letvsafe" to "com.letv.android.letvsafe.AutobootManageActivity"
        )
        for ((pkg, cls) in candidates) {
            try {
                startActivity(Intent().setClassName(pkg, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) { }
        }
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            toast("Allow autostart / background activity and lock the app in Recents.")
        } catch (_: Exception) { }
    }

    private fun writeBackup(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(Backup.export().toByteArray(Charsets.UTF_8)) }
            toast("Data backup exported successfully")
        } catch (e: Exception) {
            toast("Could not export your data: ${e.message}")
        }
    }

    private fun readBackup(uri: Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
            if (bytes.size > Backup.MAX_BYTES) { toast("This backup file is too large (max 10 MB)."); return }
            val parsed = Backup.parse(String(bytes, Charsets.UTF_8))
            if (Backup.alreadyImported(parsed)) {
                toast("This backup has already been imported. Nothing was added again.")
                return
            }
            pendingImport = parsed
        } catch (e: Exception) {
            toast("Could not import this backup: ${e.message}")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
