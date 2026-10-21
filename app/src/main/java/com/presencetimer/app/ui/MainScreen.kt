package com.presencetimer.app.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.presencetimer.app.AppSettings
import com.presencetimer.app.Engine
import com.presencetimer.app.Fmt
import com.presencetimer.app.LinkState
import com.presencetimer.app.LiveState
import com.presencetimer.app.NBox
import com.presencetimer.app.PcLink
import com.presencetimer.app.PresenceService
import com.presencetimer.app.StatusKind
import com.presencetimer.app.Store
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow

data class PermState(
    val camera: Boolean, val notifications: Boolean, val overlay: Boolean,
    val battery: Boolean, val tick: Int = 0
)

class Actions(
    val onStartStop: () -> Unit, val onSave: () -> Unit, val onReset: () -> Unit,
    val onResetAll: () -> Unit, val onExport: () -> Unit, val onImport: () -> Unit,
    val onConfirmImport: () -> Unit, val onCancelImport: () -> Unit,
    val onCameraPerm: () -> Unit, val onNotifPerm: () -> Unit,
    val onOverlayPerm: () -> Unit, val onBatteryPerm: () -> Unit, val onContact: () -> Unit
)

private val ticker = flow { while (true) { emit(System.currentTimeMillis()); delay(250) } }
private val tnum = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun MainScreen(lifecycle: Lifecycle, perms: PermState, pendingImportLabel: String?, actions: Actions) {
    val live by Engine.live.collectAsStateWithLifecycle()
    val settings by Store.settingsFlow.collectAsStateWithLifecycle()
    val version by Store.version.collectAsStateWithLifecycle()
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var confirmResetAll by remember { mutableStateOf(false) }

    val stats = remember(version) { Store.stats() }
    val records = remember(version) { Store.recordsSnapshot().asReversed().take(30) }
    val daily = remember(version) {
        Store.dailySnapshot().entries.filter { it.value > 0 }.sortedByDescending { it.key }.take(120)
    }

    BackHandler(enabled = fullscreen) { fullscreen = false }

    Box(Modifier.fillMaxSize().background(Bg).systemBarsPadding()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {

                // Top bar
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(BrandRed))
                            Spacer(Modifier.size(8.dp))
                            Text("Presence Timer", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        }
                        Text("Face + body detection • on-device processing", color = Muted, fontSize = 12.sp,
                            modifier = Modifier.padding(top = 3.dp))
                    }
                    Text(if (live.detecting) "LIVE" else "READY", color = if (live.detecting) Green else Muted, fontSize = 12.sp)
                }

                TimerCard(live) { fullscreen = true }

                StatusPill(live)

                if (!settings.hideCamera) {
                    if (settings.cameraSource == AppSettings.SOURCE_PC) PcCameraCard(live)
                    else CameraCard(live, lifecycle)
                }

                live.error?.let {
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xF0320005))
                        .border(1.dp, RedC.copy(alpha = .45f), RoundedCornerShape(10.dp)).padding(10.dp)) {
                        Text(it, fontSize = 12.5.sp)
                    }
                }

                PcSourceCard(settings, live.detecting)

                PeriodicCard(settings, live)

                // Controls
                Card {
                    SectionTitle("Controls")
                    ToggleRow("Hide camera window",
                        "Detection continues while the preview is hidden. Timer runs only while a face or body is detected.",
                        settings.hideCamera) { v -> Store.updateSettings { it.copy(hideCamera = v) } }
                    ToggleRow("🚨 Human-detected alarm",
                        "When ON, the alarm sound starts when a face or body is detected. It stops when the person disappears.",
                        settings.humanAlarmEnabled) { v -> Store.updateSettings { it.copy(humanAlarmEnabled = v) } }
                    ToggleRow("🚨 Person-missing alarm",
                        "When ON, the alarm starts when a previously detected person disappears. It stops when a face or body is detected again.",
                        settings.alarmEnabled,
                        extra = live.alarmPlayingText
                            ?: if (settings.alarmEnabled || settings.humanAlarmEnabled) "Alarms ON • waiting for detection" else "Alarms OFF • ready",
                        extraColor = if (live.alarmPlayingText != null) RedC else Muted
                    ) { v -> Store.updateSettings { it.copy(alarmEnabled = v) } }
                    ToggleRow("Floating timer over other apps",
                        "Tiny, dim (35% opacity) timer shown on top of other apps while you are outside this app. Drag it anywhere; double tap = pause / start.",
                        settings.overlayEnabled && perms.overlay) { v ->
                        Store.updateSettings { it.copy(overlayEnabled = v) }
                        if (v && !perms.overlay) actions.onOverlayPerm()
                    }
                    if (settings.overlayEnabled) {
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Left", "Center", "Right").forEachIndexed { i, label ->
                                Chip(label, settings.overlayPosition == i, Modifier.weight(1f)) {
                                    Store.updateSettings { it.copy(overlayPosition = i) }
                                }
                            }
                        }
                    }
                }

                Btn(if (live.detecting) "Stop Detection"
                        else if (settings.cameraSource == AppSettings.SOURCE_PC) "Start (PC camera)"
                        else "Allow Camera & Start",
                    bg = if (live.detecting) Color(0xFF271016) else Accent,
                    border = if (live.detecting) RedC.copy(alpha = .5f) else Accent,
                    color = if (live.detecting) Color(0xFFFF8A9C) else Color.White,
                    modifier = Modifier.fillMaxWidth(), onClick = actions.onStartStop)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Btn("Save Snapshot", modifier = Modifier.weight(1f), onClick = actions.onSave)
                    Btn("Reset Timer", modifier = Modifier.weight(1f), onClick = actions.onReset)
                }

                PermissionsCard(perms, actions, settings.cameraSource == AppSettings.SOURCE_PC)

                // Study overview
                Card {
                    TitleRow("Study overview") {
                        SmallBtn("↻") { Store.notifyChanged() }
                        SmallBtn("Reset All", wide = true, color = RedC) { confirmResetAll = true }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Stat("Today", Fmt.time(stats.todayMs), Modifier.weight(1f))
                        Stat("This month", Fmt.time(stats.monthMs), Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Stat("Days this month", stats.daysThisMonth.toString(), Modifier.weight(1f))
                        Stat("Current streak", "${stats.streak} day${if (stats.streak == 1) "" else "s"}", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Stat("Best day", Fmt.time(stats.bestDayMs), Modifier.weight(1f))
                        Stat("All time", Fmt.time(stats.allMs), Modifier.weight(1f))
                    }
                    Text("Daily study time is saved automatically to this device.", color = Color(0xFF666666),
                        fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
                }

                // Saved records
                Card {
                    TitleRow("Saved timer records") {
                        SmallBtn("↻") { Store.notifyChanged() }
                        SmallBtn("×") { Store.clearRecords() }
                    }
                    if (records.isEmpty()) Text("No saved records yet.", color = Color(0xFF666666), fontSize = 12.5.sp)
                    records.forEach { r ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text(r.label, fontSize = 12.5.sp)
                                Text(Fmt.dateTime(r.timestamp), color = Muted, fontSize = 11.sp)
                            }
                            Text(Fmt.time(r.durationMs), fontSize = 12.5.sp, fontWeight = FontWeight.Bold, style = tnum)
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF151515)))
                    }
                }

                // Study history
                Card {
                    TitleRow("Study time history") { SmallBtn("↻") { Store.notifyChanged() } }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Stat("This month", Fmt.time(stats.monthMs), Modifier.weight(1f))
                        Stat("Days studied", stats.daysThisMonth.toString(), Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    if (daily.isEmpty()) Text("Study time will be saved automatically by date.",
                        color = Color(0xFF666666), fontSize = 12.5.sp)
                    daily.forEach { e ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text(Fmt.prettyDate(e.key), fontSize = 12.5.sp)
                                Text(e.key.substring(0, 7), color = Muted, fontSize = 11.sp)
                            }
                            Text(Fmt.time(e.value), fontSize = 12.5.sp, fontWeight = FontWeight.Bold, style = tnum)
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF151515)))
                    }
                }

                // Backup
                Card {
                    SectionTitle("Backup & Restore")
                    Text("Export your timer, study history and saved records to a file. Importing a backup MERGES it into your " +
                        "current data (it also accepts backups exported from the web version).",
                        color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Btn("Export Data", modifier = Modifier.weight(1f), onClick = actions.onExport)
                        Btn("Import & Merge", modifier = Modifier.weight(1f), onClick = actions.onImport)
                    }
                }

                Btn("◉  Contact Developer", modifier = Modifier.fillMaxWidth(), onClick = actions.onContact)

                Text("Camera permission is requested only after you press Allow Camera & Start. " +
                    "Saved timer data stays on this device.",
                    color = Color(0xFF666666), fontSize = 11.5.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp))
                Spacer(Modifier.height(16.dp))
            }
        }

        if (fullscreen) FullscreenTimer(live) { fullscreen = false }
    }

    if (confirmResetAll) {
        AlertDialog(
            onDismissRequest = { confirmResetAll = false },
            title = { Text("Reset ALL study data?") },
            text = { Text("This permanently deletes today's and all daily study history, totals, saved records and the timer.") },
            confirmButton = { TextButton({ confirmResetAll = false; actions.onResetAll() }) { Text("Reset", color = RedC) } },
            dismissButton = { TextButton({ confirmResetAll = false }) { Text("Cancel") } }
        )
    }
    if (pendingImportLabel != null) {
        AlertDialog(
            onDismissRequest = actions.onCancelImport,
            title = { Text("Merge backup?") },
            text = { Text("Merge the Presence Timer backup from $pendingImportLabel?\n\nYour CURRENT data is kept. The backup's timer, " +
                "daily history and saved records are ADDED to it.") },
            confirmButton = { TextButton(actions.onConfirmImport) { Text("Merge") } },
            dismissButton = { TextButton(actions.onCancelImport) { Text("Cancel") } }
        )
    }
}

// ---------------------------------------------------------------- pieces

@Composable
private fun TimerCard(live: LiveState, onFullscreen: () -> Unit) {
    val now by ticker.collectAsStateWithLifecycle(initialValue = System.currentTimeMillis())
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).border(1.dp, Border, RoundedCornerShape(22.dp))) {
        Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 30.dp, bottom = 22.dp)) {
            Text(Fmt.time(live.displayMs(now)), fontSize = 58.sp, fontWeight = FontWeight.Bold, style = tnum,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(Fmt.clock(now), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, style = tnum)
                Text(Fmt.dayLeft(now), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, style = tnum)
            }
        }
        Box(Modifier.align(Alignment.TopEnd).padding(12.dp)) { SmallBtn("⛶", onClick = onFullscreen) }
    }
}

@Composable
private fun FullscreenTimer(live: LiveState, onClose: () -> Unit) {
    val now by ticker.collectAsStateWithLifecycle(initialValue = System.currentTimeMillis())
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    Box(Modifier.fillMaxSize().background(Bg).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(Fmt.time(live.displayMs(now)), fontSize = 72.sp, fontWeight = FontWeight.Bold, style = tnum)
            Spacer(Modifier.height(16.dp))
            Text(Fmt.clock(now), fontSize = 22.sp, style = tnum)
            Text("Day left ${Fmt.dayLeft(now)}", fontSize = 16.sp, color = Muted, style = tnum)
            Spacer(Modifier.height(26.dp))
            Text("Tap anywhere to close", color = Color(0xFF555555), fontSize = 12.sp)
        }
    }
}

@Composable
private fun StatusPill(live: LiveState) {
    val c = when (live.statusKind) {
        StatusKind.RUNNING -> Green
        StatusKind.PAUSED -> RedC
        StatusKind.IDLE -> Color(0xFFC6A9FF)
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.clip(RoundedCornerShape(50)).background(Panel)
            .border(1.dp, c.copy(alpha = .35f), RoundedCornerShape(50)).padding(horizontal = 13.dp, vertical = 7.dp)) {
            Text(live.status, color = c, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun CameraCard(live: LiveState, lifecycle: Lifecycle) {
    val ctx = LocalContext.current
    val pv = remember {
        PreviewView(ctx).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    // Attach the preview only while this screen is visible; the service keeps detecting without it.
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> PresenceService.setPreviewProvider(pv.surfaceProvider)
                Lifecycle.Event.ON_STOP -> PresenceService.setPreviewProvider(null)
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) PresenceService.setPreviewProvider(pv.surfaceProvider)
        onDispose {
            lifecycle.removeObserver(obs)
            PresenceService.setPreviewProvider(null)
        }
    }

    Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(20.dp))
        .background(Bg).border(1.dp, Border, RoundedCornerShape(20.dp))) {
        AndroidView(factory = { pv }, modifier = Modifier.fillMaxSize())
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val paint = android.graphics.Paint().apply {
                textSize = 13.sp.toPx(); isAntiAlias = true; color = android.graphics.Color.WHITE
            }
            fun draw(b: NBox, c: Color) {
                val x = b.l * size.width; val y = b.t * size.height
                drawRect(c, androidx.compose.ui.geometry.Offset(x, y),
                    androidx.compose.ui.geometry.Size((b.r - b.l) * size.width, (b.b - b.t) * size.height),
                    style = Stroke(3.dp.toPx()))
                drawContext.canvas.nativeCanvas.drawText(b.label, x + 4.dp.toPx(), maxOf(y - 5.dp.toPx(), 14.dp.toPx()), paint)
            }
            if (live.detecting && !live.sleeping) {
                live.faces.forEach { draw(it, Green) }
                live.body?.let { draw(it, Blue) }
            }
        }
        Box(Modifier.align(Alignment.Center)) { SleepBadge(live) }
        Box(Modifier.align(Alignment.BottomStart).padding(12.dp).clip(RoundedCornerShape(8.dp))
            .background(Color(0xC7000000)).border(1.dp, Color(0xFF252525), RoundedCornerShape(8.dp))
            .padding(horizontal = 9.dp, vertical = 6.dp)) {
            Text(if (live.detecting && live.sleeping) "Camera asleep (saving power)" else live.detectionText,
                color = Color(0xFFDDDDDD), fontSize = 12.sp)
        }
    }
}

@Composable
private fun PermissionsCard(p: PermState, a: Actions, pcMode: Boolean) {
    Card {
        SectionTitle("Permissions")
        if (!pcMode) PermRow("Camera", "Face & body detection", p.camera, a.onCameraPerm)
        if (Build.VERSION.SDK_INT >= 33) PermRow("Notifications", "Timer in the notification panel", p.notifications, a.onNotifPerm)
        PermRow("Display over other apps", "Tiny dim timer on top of apps", p.overlay, a.onOverlayPerm)
        PermRow("Unrestricted battery", "Keeps tracking alive in background", p.battery, a.onBatteryPerm)
    }
}

@Composable
private fun PermRow(name: String, help: String, ok: Boolean, onGrant: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 14.sp)
            Text(help, color = Muted, fontSize = 11.5.sp)
        }
        if (ok) Text("✓ Allowed", color = Green, fontSize = 12.sp)
        else Btn("Grant", modifier = Modifier.widthIn(min = 76.dp), onClick = onGrant)
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Bg)
        .border(1.dp, Border, RoundedCornerShape(18.dp)).padding(14.dp)) { content() }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))
}

@Composable
private fun TitleRow(title: String, actions: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

@Composable
private fun ToggleRow(
    label: String, help: String, checked: Boolean,
    extra: String? = null, extraColor: Color = Muted, onChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.5.sp)
            Text(help, color = Muted, fontSize = 11.5.sp, modifier = Modifier.padding(top = 2.dp))
            if (extra != null) Text(extra, color = extraColor, fontSize = 11.5.sp, modifier = Modifier.padding(top = 3.dp))
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(
            checkedTrackColor = Accent, checkedThumbColor = Color.White,
            uncheckedTrackColor = Color(0xFF1A1A1A), uncheckedThumbColor = Color(0xFF777777),
            uncheckedBorderColor = Color(0xFF333333)))
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF050505))
        .border(1.dp, Border, RoundedCornerShape(14.dp)).padding(12.dp)) {
        Text(label, color = Muted, fontSize = 11.5.sp, modifier = Modifier.padding(bottom = 6.dp))
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, style = tnum)
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(10.dp))
        .background(if (selected) Accent.copy(alpha = .25f) else Panel)
        .border(1.dp, if (selected) Accent else Border, RoundedCornerShape(10.dp))
        .clickable(onClick = onClick).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 12.5.sp, color = if (selected) Color.White else Muted)
    }
}

@Composable
private fun Btn(
    text: String, modifier: Modifier = Modifier, bg: Color = Panel, border: Color = Color(0xFF2A2A2A),
    color: Color = Color.White, onClick: () -> Unit
) {
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(bg).border(BorderStroke(1.dp, border), RoundedCornerShape(14.dp))
        .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp), contentAlignment = Alignment.Center) {
        Text(text, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SmallBtn(text: String, wide: Boolean = false, color: Color = Color(0xFFAAAAAA), onClick: () -> Unit) {
    val w: Dp = if (wide) 74.dp else 34.dp
    Box(Modifier.size(w, 34.dp).clip(RoundedCornerShape(9.dp)).background(Panel)
        .border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(9.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(text, color = color, fontSize = if (wide) 12.sp else 17.sp)
    }
}


// ---------------------------------------------------------------- PC camera

@Composable
private fun PcSourceCard(settings: AppSettings, detecting: Boolean) {
    val ctx = LocalContext.current
    val link by PcLink.status.collectAsStateWithLifecycle()
    val pairing by Store.pairingFlow.collectAsStateWithLifecycle()
    val notice by PcLink.notice.collectAsStateWithLifecycle()
    var showCode by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    val pc = settings.cameraSource == AppSettings.SOURCE_PC

    Card {
        SectionTitle("Camera source")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Phone camera", !pc, Modifier.weight(1f)) {
                if (!detecting) Store.updateSettings { it.copy(cameraSource = AppSettings.SOURCE_PHONE) }
            }
            Chip("PC camera", pc, Modifier.weight(1f)) {
                if (!detecting) Store.updateSettings { it.copy(cameraSource = AppSettings.SOURCE_PC) }
            }
        }
        if (detecting) {
            Text("Stop detection to change the camera source.", color = Muted, fontSize = 11.5.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
        if (pc) {
            Spacer(Modifier.height(10.dp))
            Text(
                if (pairing != null) "Paired PC: ${pairing!!.pcName}" else "No PC paired yet",
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold
            )
            if (link.state != LinkState.OFF) {
                val c = when (link.state) {
                    LinkState.STREAMING -> Green
                    LinkState.CONNECTING -> Color(0xFFC6A9FF)
                    else -> Color(0xFFFFB454)
                }
                Text(link.text, color = c, fontSize = 12.5.sp, modifier = Modifier.padding(top = 4.dp))
                link.note?.let { Text(it, color = RedC, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp)) }
            }
            notice?.let { Text(it, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn(if (pairing == null) "Pair with PC" else "Pair new PC", modifier = Modifier.weight(1f)) {
                    code = ""; showCode = true
                }
                if (pairing != null && !detecting) {
                    Btn("Test link", modifier = Modifier.weight(1f)) { PcLink.testConnection(ctx) }
                }
            }
            if (pairing != null) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Btn("Forget this PC", modifier = Modifier.fillMaxWidth(), color = RedC) { PcLink.forgetPc() }
                }
            }
            Text(
                "Run PresenceCam on the PC (laptop camera is used only while this app is detecting). " +
                "Pair once with the 6-digit code shown there. The phone then finds the PC by itself and " +
                "reconnects automatically when you change Wi-Fi or hotspot - both devices just need to be on the same network.",
                color = Muted, fontSize = 11.5.sp, modifier = Modifier.padding(top = 10.dp)
            )
        }
    }

    if (showCode) {
        AlertDialog(
            onDismissRequest = { showCode = false },
            title = { Text("Pair with PC") },
            text = {
                Column {
                    Text("Enter the 6-digit code shown in the PresenceCam window on your PC.", fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { v -> code = v.filter { ch -> ch.isDigit() }.take(6) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (code.length == 6) {
                        showCode = false
                        PcLink.beginPairing(ctx, code)
                    }
                }) { Text("Pair") }
            },
            dismissButton = { TextButton(onClick = { showCode = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun PcCameraCard(live: LiveState) {
    val bmp by PcLink.preview.collectAsStateWithLifecycle()
    val link by PcLink.status.collectAsStateWithLifecycle()
    val b = bmp
    val ratio = if (b != null && b.height > 0) b.width.toFloat() / b.height.toFloat() else 4f / 3f
    val showVideo = b != null && live.detecting && !live.sleeping && link.state == LinkState.STREAMING

    Box(Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(20.dp))
        .background(Bg).border(1.dp, Border, RoundedCornerShape(20.dp))) {
        if (showVideo && b != null) {
            Image(bitmap = b.asImageBitmap(), contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        } else {
            Text(
                if (live.detecting) link.text else "PC camera preview appears here while detecting",
                color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(24.dp)
            )
        }
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val paint = android.graphics.Paint().apply {
                textSize = 13.sp.toPx(); isAntiAlias = true; color = android.graphics.Color.WHITE
            }
            fun draw(bx: NBox, c: Color) {
                val x = bx.l * size.width; val y = bx.t * size.height
                drawRect(c, androidx.compose.ui.geometry.Offset(x, y),
                    androidx.compose.ui.geometry.Size((bx.r - bx.l) * size.width, (bx.b - bx.t) * size.height),
                    style = Stroke(3.dp.toPx()))
                drawContext.canvas.nativeCanvas.drawText(bx.label, x + 4.dp.toPx(), maxOf(y - 5.dp.toPx(), 14.dp.toPx()), paint)
            }
            if (showVideo) {
                live.faces.forEach { draw(it, Green) }
                live.body?.let { draw(it, Blue) }
            }
        }
        Box(Modifier.align(Alignment.Center)) { SleepBadge(live) }
        Box(Modifier.align(Alignment.BottomStart).padding(12.dp).clip(RoundedCornerShape(8.dp))
            .background(Color(0xC7000000)).border(1.dp, Color(0xFF252525), RoundedCornerShape(8.dp))
            .padding(horizontal = 9.dp, vertical = 6.dp)) {
            Text(if (live.detecting && live.sleeping) "Camera asleep (saving power)" else live.detectionText,
                color = Color(0xFFDDDDDD), fontSize = 12.sp)
        }
    }
}

// ---------------------------------------------------------------- periodic detection

/** "Camera asleep - next check in mm:ss" badge; draws nothing unless the camera is sleeping. */
@Composable
private fun SleepBadge(live: LiveState) {
    if (!(live.detecting && live.periodic && live.sleeping)) return
    val now by ticker.collectAsStateWithLifecycle(initialValue = System.currentTimeMillis())
    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xCC000000))
        .border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text("😴 Camera asleep\nNext check in ${Fmt.time(maxOf(0L, live.nextCheckAtMs - now))}",
            color = Color(0xFFDDDDDD), fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PeriodicCard(settings: AppSettings, live: LiveState) {
    val now by ticker.collectAsStateWithLifecycle(initialValue = System.currentTimeMillis())
    val focus = LocalFocusManager.current
    var text by remember(settings.periodicValue, settings.periodicUnit) {
        mutableStateOf(settings.periodicValue.toString())
    }
    val unitName = if (settings.periodicUnit == AppSettings.UNIT_SECONDS) "seconds" else "minutes"

    // Applies instantly, also while detection is running: the service reads the interval every second.
    fun applyInterval(unit: Int) {
        val value = AppSettings.clampValue(text.toIntOrNull() ?: settings.periodicValue, unit)
        text = value.toString()
        Store.updateSettings { it.copy(periodicValue = value, periodicUnit = unit) }
        focus.clearFocus()
    }

    Card {
        SectionTitle("Periodic detection (saves battery)")
        ToggleRow(
            "Check for a human only every…",
            "The camera wakes up once per interval, looks for a person for a few seconds, then switches off " +
                "completely. Person found: the timer keeps running until the next check. Nobody found: the timer " +
                "stops. The first check happens as soon as you press Start.",
            settings.periodicEnabled
        ) { v -> Store.updateSettings { it.copy(periodicEnabled = v) } }

        if (settings.periodicEnabled) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { v -> text = v.filter { ch -> ch.isDigit() }.take(4) },
                    singleLine = true,
                    label = { Text("Every") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { applyInterval(settings.periodicUnit) })
                )
                Btn("Apply", modifier = Modifier.widthIn(min = 90.dp)) { applyInterval(settings.periodicUnit) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Seconds", settings.periodicUnit == AppSettings.UNIT_SECONDS, Modifier.weight(1f)) {
                    applyInterval(AppSettings.UNIT_SECONDS)
                }
                Chip("Minutes", settings.periodicUnit == AppSettings.UNIT_MINUTES, Modifier.weight(1f)) {
                    applyInterval(AppSettings.UNIT_MINUTES)
                }
            }
            Text("Camera checks every ${settings.periodicValue} $unitName", fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
            if (live.detecting && live.periodic) {
                val line = when {
                    live.checking -> "🔍 Checking the camera now…"
                    live.sleeping -> "😴 Camera asleep • next check in ${Fmt.time(maxOf(0L, live.nextCheckAtMs - now))}"
                    else -> null
                }
                if (line != null) Text(line, color = Green, fontSize = 12.5.sp, modifier = Modifier.padding(top = 3.dp))
            }
            Text("Allowed: 10–3600 seconds or 1–1440 minutes. You can change it while detection is running. " +
                "The person-missing alarm keeps the camera awake until the person is back; the human-detected " +
                "alarm only sounds while the camera is awake.",
                color = Muted, fontSize = 11.5.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
