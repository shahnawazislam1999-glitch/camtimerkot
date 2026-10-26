package com.presencetimer.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.presencetimer.app.AppSettings
import com.presencetimer.app.Fmt
import com.presencetimer.app.LiveState
import com.presencetimer.app.SmsConfig
import com.presencetimer.app.Store

/** All camera related settings live here, so the main screen stays short. */
@Composable
fun SettingsScreen(settings: AppSettings, live: LiveState, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val pc = settings.cameraSource == AppSettings.SOURCE_PC
    val now by ticker.collectAsStateWithLifecycle(initialValue = System.currentTimeMillis())

    Column(Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {

            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Btn("‹ Back", onClick = onBack)
                Text("Settings", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            // Camera source + PC pairing
            PcSourceCard(settings, live.detecting)

            // Aggressive human detection
            Card {
                SectionTitle("Aggressive human detection")
                ToggleRow(
                    "🛡 Aggressive mode (chair-proof)",
                    "Every interval (default 5 min) the camera wakes up and checks CONTINUOUSLY for a REAL human: face, " +
                        "eyes, nose, head shape, head / hand / body movement and an offline photo check. A majority must " +
                        "pass plus one sign of life (blink / movement), so a chair, coat or photo is not counted.\n" +
                        "The main countdown keeps running during the check. If the camera fails to capture a human it " +
                        "keeps rechecking, for up to 5 minutes. Human found -> camera sleeps until the next interval.\n" +
                        "No human for the whole 5 minutes -> alarm rings, camera goes to sleep and the countdown stops " +
                        "(Pause / Resume / Stop silence the alarm).\n" +
                        "A few small temporary photos are kept in the app's private cache, analysed on the phone " +
                        "(nothing is uploaded) and deleted when the check ends.\n" +
                        "Uses the interval of Periodic detection; replaces Blink verification and the 2-minute alarm while ON.",
                    settings.aggressiveMode,
                    extra = if (settings.aggressiveMode) live.detectionText else null
                ) { v ->
                    Store.updateSettings {
                        if (v) it.copy(aggressiveMode = true, eyeClosedDetect = false, blinkOnly = false, idleSleepEnabled = false,
                            periodicEnabled = true,
                            periodicValue = if (it.periodicEnabled) it.periodicValue else 5,
                            periodicUnit = if (it.periodicEnabled) it.periodicUnit else AppSettings.UNIT_MINUTES)
                        else it.copy(aggressiveMode = false)
                    }
                }
            }

            // Eye-closed (sleep) detection
            Card {
                SectionTitle("Eye-closed detection")
                ToggleRow(
                    "😴 Eye-closed detection",
                    "Every interval (default 5 min) the camera wakes up and watches your eyes for up to 2 minutes. The main countdown " +
                        "keeps running during the check (it never pauses while looking).\n" +
                        "You must be AWAKE: the camera has to see your eyes OPEN. Seen open -> all fine, camera sleeps until the next interval.\n" +
                        "No open eyes for 2 minutes in a row (eyes closed, eyes not visible, face hidden, or only your " +
                        "body / head detected) -> the main countdown STOPS, the alarm rings (if the option below is on) " +
                        "and the camera switches OFF FOR GOOD: it does not wake up again by itself. Press Resume to start again.\n" +
                        "Nobody on camera at all -> timer stops like normal periodic mode. Pause / Resume / Stop silence the alarm.\n" +
                        "Uses the interval of Periodic detection; replaces Aggressive mode and Blink verification while ON.",
                    settings.eyeClosedDetect,
                    extra = if (settings.eyeClosedDetect) live.detectionText else null
                ) { v ->
                    Store.updateSettings {
                        if (v) it.copy(eyeClosedDetect = true, aggressiveMode = false, blinkOnly = false,
                            periodicEnabled = true,
                            periodicValue = if (it.periodicEnabled) it.periodicValue else 5,
                            periodicUnit = if (it.periodicEnabled) it.periodicUnit else AppSettings.UNIT_MINUTES)
                        else it.copy(eyeClosedDetect = false)
                    }
                }
                if (settings.eyeClosedDetect) {
                    Text("When no open eyes are seen for 2 minutes", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("🔔 Ring alarm + stop timer", settings.eyeClosedAlarm, Modifier.weight(1f)) {
                            Store.updateSettings { it.copy(eyeClosedAlarm = true) }
                        }
                        Chip("⏹ Only stop timer", !settings.eyeClosedAlarm, Modifier.weight(1f)) {
                            Store.updateSettings { it.copy(eyeClosedAlarm = false) }
                        }
                    }
                    Text(if (settings.eyeClosedAlarm)
                        "The alarm rings until you press Pause / Resume / Stop. The camera stays off until you press Resume."
                    else "No sound: the countdown just stops and the camera stays off until you press Resume.",
                        color = Muted, fontSize = 11.5.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }

            // Blink verification
            Card {
                SectionTitle("Detection mode")
                ToggleRow(
                    "👁 Blink verification",
                    "When ON, a person only counts after the camera sees them BLINK (both eyes close and open). " +
                        "A face that never blinks (photo, screen, poster) is ignored, and the person is " +
                        "confirmed for 30 seconds after each blink. Body detection is not used in this mode. " +
                        "When OFF, normal face + body detection is used.",
                    settings.blinkOnly,
                    extra = if (settings.blinkOnly) "Look at the camera and blink once to start the timer" else null
                ) { v -> Store.updateSettings { it.copy(blinkOnly = v) } }
            }

            // Daily 12 AM auto reset
            Card {
                SectionTitle("Auto reset")
                val left = Fmt.nextMidnight(maxOf(settings.autoResetAnchorMs, 1L)) - now
                ToggleRow(
                    "🔄 Reset main countdown every day at 12 AM",
                    "When ON, the main countdown goes back to 00:00:00 automatically every day at 12 AM (midnight). " +
                        "The time is saved as a record first, and your daily study history is never touched.",
                    settings.autoReset24h,
                    extra = if (settings.autoReset24h && settings.autoResetAnchorMs > 0) "Next automatic reset in ${Fmt.time(left)}" else null
                ) { v ->
                    Store.updateSettings { it.copy(autoReset24h = v,
                        autoResetAnchorMs = if (v) System.currentTimeMillis() else 0L) }
                }
            }

            // Global delayed alarm (2 minutes)
            Card {
                SectionTitle("Alarm delay")
                ToggleRow(
                    "⏱ Alarm after 2 minutes",
                    "When ON, the alarm mp3 does NOT play immediately. It plays only if the PC camera stays disconnected " +
                        "for 2 minutes, or no human is detected on the camera for 2 minutes. If the PC reconnects or a human " +
                        "is seen before that, the 2-minute wait starts over. This replaces the immediate person-missing and " +
                        "PC-disconnected alarms while it is ON. Pause / Stop silences it.",
                    settings.delayedAlarmEnabled,
                    extra = live.alarmPlayingText?.takeIf { settings.delayedAlarmEnabled }
                ) { v -> Store.updateSettings { it.copy(delayedAlarmEnabled = v) } }
            }

            // Alarm for a dropped PC camera connection (only meaningful with the PC camera)
            Card {
                SectionTitle("Connection alarm")
                ToggleRow(
                    "🚨 PC camera disconnected alarm",
                    "When ON, the alarm buzzes immediately if the PC camera connection is lost while detecting " +
                        "(PC off or asleep, Wi-Fi / hotspot dropped, PresenceCam closed). It stops as soon as the PC is connected again " +
                        "or you press Pause / Stop." +
                        if (!pc) "\nOnly used when the camera source is “PC camera”." else "",
                    settings.linkLostAlarmEnabled,
                    extra = live.alarmPlayingText?.takeIf { it.contains("disconnected") }
                ) { v -> Store.updateSettings { it.copy(linkLostAlarmEnabled = v) } }
            }

            // Preview visibility
            Card {
                SectionTitle("Camera window")
                ToggleRow("Hide camera window",
                    "Detection continues while the preview is hidden. Timer runs only while a face or body is detected.",
                    settings.hideCamera) { v -> Store.updateSettings { it.copy(hideCamera = v) } }
            }

            // Periodic detection
            PeriodicCard(settings, live)

            // Pause / start by text message
            SmsControlCard()

            Spacer(Modifier.height(16.dp))
        }
    }
}


/** Pause / start the timer by SMS. Off by default; needs your own number AND a secret PIN. */
@Composable
private fun SmsControlCard() {
    val ctx = LocalContext.current
    val cfg by Store.smsFlow.collectAsStateWithLifecycle()
    var number by remember(cfg.number) { mutableStateOf(cfg.number) }
    var pin by remember(cfg.pin) { mutableStateOf(cfg.pin) }
    var permTick by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permTick++ }
    fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    val canReceive = permTick >= 0 && has(Manifest.permission.RECEIVE_SMS)
    val canSend = permTick >= 0 && has(Manifest.permission.SEND_SMS)

    fun save(enabled: Boolean = cfg.enabled, reply: Boolean = cfg.reply) {
        val n = number.trim()
        val p = pin.trim()
        when {
            n.count { it.isDigit() } < 7 -> { note = "Type the phone number you will send the SMS from (with country code)."; return }
            p.length !in 4..8 || !p.all { it.isDigit() } -> { note = "PIN must be 4 to 8 digits."; return }
        }
        Store.saveSms(SmsConfig(enabled = enabled, number = n, pin = p, reply = reply))
        note = "Saved ✓"
    }

    Card {
        SectionTitle("Control by SMS")
        ToggleRow(
            "📩 Pause / start the timer by text message",
            "Send an SMS to this phone from YOUR number:  PAUSE 1234  ·  START 1234  ·  STATUS 1234  " +
                "(1234 = your PIN). Messages from other numbers, or without the PIN, are ignored silently. " +
                "5 wrong-PIN tries lock it for 10 minutes. The timer must already be running (started in the app).",
            cfg.enabled,
            extra = when {
                !cfg.enabled -> null
                !cfg.ready -> "Fill in your number and PIN below, then press Save."
                !canReceive -> "SMS permission missing — press “Allow SMS” below."
                else -> "✓ Active — only ${cfg.number} with the PIN can control the timer."
            },
            extraColor = if (cfg.enabled && cfg.ready && canReceive) Green else Muted
        ) { v ->
            if (!v) Store.saveSms(cfg.copy(enabled = false)) else save(enabled = true)
        }

        OutlinedTextField(
            value = number,
            onValueChange = { v -> number = v.filter { ch -> ch.isDigit() || ch == '+' || ch == ' ' }.take(20) },
            singleLine = true,
            label = { Text("Your phone number (the one you text from)") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
        )
        OutlinedTextField(
            value = pin,
            onValueChange = { v -> pin = v.filter { ch -> ch.isDigit() }.take(8) },
            singleLine = true,
            label = { Text("Secret PIN (4–8 digits)") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
        )

        ToggleRow(
            "Reply with an SMS",
            "The phone texts back “Paused at 01:23:45…”. Your carrier may charge for these replies.",
            cfg.reply
        ) { v -> if (cfg.ready || (number.isNotBlank() && pin.isNotBlank())) save(reply = v) else Store.saveSms(cfg.copy(reply = v)) }

        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Save", modifier = Modifier.weight(1f)) { save() }
            if (!canReceive || (cfg.reply && !canSend)) {
                Btn("Allow SMS", modifier = Modifier.weight(1f)) {
                    launcher.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS))
                }
            }
        }
        if (note != null) Text(note!!, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        Text("Anyone who can fake your number AND guess the PIN could pause your timer, so keep the PIN private. " +
            "Pause is the only thing SMS can do besides start and status: it can never stop the app or read your data.",
            color = Muted, fontSize = 11.5.sp, modifier = Modifier.padding(top = 6.dp))
    }
}
