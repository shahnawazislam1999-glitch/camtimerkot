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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.presencetimer.app.AppSettings
import com.presencetimer.app.LiveState
import com.presencetimer.app.Store

/** All camera related settings live here, so the main screen stays short. */
@Composable
fun SettingsScreen(settings: AppSettings, live: LiveState, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val pc = settings.cameraSource == AppSettings.SOURCE_PC

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
