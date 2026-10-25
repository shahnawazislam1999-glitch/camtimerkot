package com.presencetimer.app.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.presencetimer.app.AppSettings
import com.presencetimer.app.Fmt
import com.presencetimer.app.LiveState
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
                        if (v) it.copy(aggressiveMode = true, blinkOnly = false, idleSleepEnabled = false,
                            periodicEnabled = true,
                            periodicValue = if (it.periodicEnabled) it.periodicValue else 5,
                            periodicUnit = if (it.periodicEnabled) it.periodicUnit else AppSettings.UNIT_MINUTES)
                        else it.copy(aggressiveMode = false)
                    }
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

            Spacer(Modifier.height(16.dp))
        }
    }
}
