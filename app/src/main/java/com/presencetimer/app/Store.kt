package com.presencetimer.app

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

data class Record(
    val id: String,
    val timestamp: Long,
    val durationMs: Long,
    val dateKey: String,
    val monthKey: String,
    val label: String
)

data class AppSettings(
    val hideCamera: Boolean = false,
    val alarmEnabled: Boolean = false,          // person-missing alarm
    val humanAlarmEnabled: Boolean = false,     // human-detected alarm
    val overlayEnabled: Boolean = false,        // floating timer over other apps
    val overlayPosition: Int = 1,               // 0 = left, 1 = center, 2 = right
    val cameraSource: Int = 0,                  // 0 = phone camera, 1 = PC camera (PresenceCam)
    val periodicEnabled: Boolean = false,       // look for a human only once every N seconds/minutes
    val periodicValue: Int = 10,                // N
    val periodicUnit: Int = AppSettings.UNIT_MINUTES,
    val linkLostAlarmEnabled: Boolean = false,  // buzz immediately when the PC camera connection drops
    val delayedAlarmEnabled: Boolean = false,   // global: alarm only after DELAYED_ALARM_MS of no human / no PC link
    val idleSleepEnabled: Boolean = true,       // camera sleeps after NO_HUMAN_SLEEP_MS without a human, wakes every interval
    val aggressiveMode: Boolean = false,        // multi-signal human verification (head/eyes/nose/movement), chair-proof
    val autoReset24h: Boolean = false,          // main countdown goes back to 0 every 24 hours
    val autoResetAnchorMs: Long = 0L,           // when the current 24 h cycle started
    val eyeClosedDetect: Boolean = false,       // eyes closed for 5 min in a row at a check -> timer stops (+ alarm)
    val eyeClosedAlarm: Boolean = true,         // eye-closed: true = ring the alarm too, false = only stop the timer
    val pauseOnScreenOff: Boolean = false,      // power button / screen lock -> pause (camera sleeps, countdown pauses)
    val resumeOnUnlock: Boolean = true,         // ...and carry on automatically when the phone is unlocked again
    val eyeBlinksRequired: Int = 5              // eye mode: blinks that must be seen during the check to count as awake
) {

    /** Time between two camera checks in periodic mode. */
    val periodicIntervalMs: Long
        get() = periodicValue.toLong() * (if (periodicUnit == UNIT_SECONDS) 1000L else 60_000L)

    companion object {
        /** Delayed alarm: how long the problem must last before the mp3 plays. */
        const val DELAYED_ALARM_MS = 2 * 60 * 1000L
        /** Continuous tracking with nobody in front of the camera for this long -> camera sleeps. */
        /** Aggressive mode: after the camera wakes it keeps checking for a human this long; no human -> alarm + sleep + timer stops. */
        const val AGG_CHECK_WINDOW_MS = 5 * 60 * 1000L
        const val NO_HUMAN_SLEEP_MS = 5 * 60 * 1000L
        /** Eye-closed mode: the camera checks continuously this long; too few blinks in that time -> eyes closed -> stop. */
        const val EYE_CLOSED_LIMIT_MS = 2 * 60 * 1000L
        /** Eye-closed mode: longest a single check may last when no closed-eye streak is running. */
        const val EYE_CHECK_WINDOW_MS = EYE_CLOSED_LIMIT_MS + 30_000L
        /** Auto reset cycle length. */
        const val AUTO_RESET_MS = 24 * 60 * 60 * 1000L
        const val SOURCE_PHONE = 0
        const val SOURCE_PC = 1
        const val UNIT_SECONDS = 0
        const val UNIT_MINUTES = 1

        /** Allowed range: 5 s .. 60 min in seconds mode, 1 .. 1440 min (24 h) in minutes mode. */
        fun minValue(unit: Int) = if (unit == UNIT_SECONDS) 5 else 1
        fun maxValue(unit: Int) = if (unit == UNIT_SECONDS) 3600 else 1440
        fun clampValue(value: Int, unit: Int) = value.coerceIn(minValue(unit), maxValue(unit))
    }
}

/** The PC this phone is paired with (secret never leaves the phone / PC). */
data class PcPairing(
    val pcId: String,
    val pcName: String,
    val secretHex: String,
    val lastIp: String,
    val port: Int
)

/**
 * Remote control by SMS. Kept outside the backup-compatible settings JSON (it holds a secret PIN).
 * A message is only obeyed when it comes from [number] AND contains [pin].
 */
data class SmsConfig(
    val enabled: Boolean = false,
    val number: String = "",
    val pin: String = "",
    val reply: Boolean = true
) {
    val ready: Boolean
        get() = enabled && number.count { it.isDigit() } >= 7 && pin.length in 4..8 && pin.all { it.isDigit() }
}

data class Stats(
    val todayMs: Long,
    val monthMs: Long,
    val allMs: Long,
    val daysThisMonth: Int,
    val streak: Int,
    val bestDayMs: Long
)

/**
 * Local persistence. Uses the SAME keys and JSON shapes as the web app's localStorage,
 * so backups exported from index.html can be imported here (and vice-versa).
 */
object Store {
    const val KEY_RECORDS = "presenceTimerRecordsV1"
    const val KEY_SETTINGS = "presenceTimerSettingsV1"
    const val KEY_TIMER = "presenceTimerStateV1"
    const val KEY_DAILY = "presenceTimerDailyStudyV1"
    const val KEY_IMPORTED = "presenceTimerImportedBackups"

    private lateinit var prefs: SharedPreferences
    private val lock = Any()
    private val records = mutableListOf<Record>()
    private val daily = LinkedHashMap<String, Long>()
    private var rawSettings = JSONObject()

    val settingsFlow = MutableStateFlow(AppSettings())

    /** Paired PC (null = not paired). Kept outside the backup-compatible settings JSON. */
    val pairingFlow = MutableStateFlow<PcPairing?>(null)

    /** SMS remote control settings (see [SmsConfig]). */
    val smsFlow = MutableStateFlow(SmsConfig())

    /** Bumped whenever stored data changes so the UI re-reads totals / lists. */
    val version = MutableStateFlow(0)

    fun init(context: Context) {
        synchronized(lock) {
            if (::prefs.isInitialized) return
            prefs = context.applicationContext.getSharedPreferences("presence_timer_store", Context.MODE_PRIVATE)

            try {
                val arr = JSONArray(prefs.getString(KEY_RECORDS, "[]"))
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    val dur = o.optLong("durationMs")
                    val ts = o.optLong("timestamp")
                    if (id.isEmpty() || dur <= 0 || ts <= 0) continue
                    records.add(Record(id, ts, dur, o.optString("dateKey", Fmt.dateKey(ts)),
                        o.optString("monthKey", Fmt.monthKey(ts)), o.optString("label", "Saved timer")))
                }
            } catch (_: Exception) { }

            try {
                val o = JSONObject(prefs.getString(KEY_DAILY, "{}"))
                for (k in o.keys()) {
                    val v = o.optLong(k)
                    if (DATE_RE.matches(k) && v > 0) daily[k] = v
                }
            } catch (_: Exception) { }

            try {
                rawSettings = JSONObject(prefs.getString(KEY_SETTINGS, "{}"))
            } catch (_: Exception) { }
            settingsFlow.value = AppSettings(
                hideCamera = rawSettings.optBoolean("hideCamera", false),
                alarmEnabled = rawSettings.optBoolean("alarmEnabled", false),
                humanAlarmEnabled = rawSettings.optBoolean("humanDetectedAlarmEnabled", false),
                overlayEnabled = rawSettings.optBoolean("overlayEnabled", false),
                overlayPosition = rawSettings.optInt("overlayPosition", 1).coerceIn(0, 2),
                cameraSource = rawSettings.optInt("cameraSource", 0).coerceIn(0, 1),
                periodicEnabled = rawSettings.optBoolean("periodicEnabled", false),
                linkLostAlarmEnabled = rawSettings.optBoolean("linkLostAlarmEnabled", false),
                delayedAlarmEnabled = rawSettings.optBoolean("delayedAlarmEnabled", false),
                idleSleepEnabled = rawSettings.optBoolean("idleSleepEnabled", true),
                aggressiveMode = rawSettings.optBoolean("aggressiveMode", false),
                autoReset24h = rawSettings.optBoolean("autoReset24h", false),
                autoResetAnchorMs = rawSettings.optLong("autoResetAnchorMs", 0L),
                eyeClosedDetect = rawSettings.optBoolean("eyeClosedDetect", false),
                eyeClosedAlarm = rawSettings.optBoolean("eyeClosedAlarm", true),
                pauseOnScreenOff = rawSettings.optBoolean("pauseOnScreenOff", false),
                resumeOnUnlock = rawSettings.optBoolean("resumeOnUnlock", true),
                eyeBlinksRequired = rawSettings.optInt("eyeBlinksRequired", 5).coerceIn(1, 12),
                periodicUnit = rawSettings.optInt("periodicUnit", AppSettings.UNIT_MINUTES)
                    .coerceIn(AppSettings.UNIT_SECONDS, AppSettings.UNIT_MINUTES)
            ).let { it.copy(periodicValue = AppSettings.clampValue(rawSettings.optInt("periodicValue", 10), it.periodicUnit)) }
            pairingFlow.value = readPairing()
            smsFlow.value = readSms()
        }
    }

    private val DATE_RE = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    // ---------- settings ----------

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        synchronized(lock) {
            val s = transform(settingsFlow.value)
            // Merge into the raw JSON so unknown keys (e.g. from a web backup) survive.
            rawSettings.put("hideCamera", s.hideCamera)
            rawSettings.put("alarmEnabled", s.alarmEnabled)
            rawSettings.put("humanDetectedAlarmEnabled", s.humanAlarmEnabled)
            rawSettings.put("overlayEnabled", s.overlayEnabled)
            rawSettings.put("overlayPosition", s.overlayPosition)
            rawSettings.put("cameraSource", s.cameraSource)
            rawSettings.put("periodicEnabled", s.periodicEnabled)
            rawSettings.put("periodicValue", s.periodicValue)
            rawSettings.put("periodicUnit", s.periodicUnit)
            rawSettings.put("linkLostAlarmEnabled", s.linkLostAlarmEnabled)
            rawSettings.put("delayedAlarmEnabled", s.delayedAlarmEnabled)
            rawSettings.put("idleSleepEnabled", s.idleSleepEnabled)
            rawSettings.put("aggressiveMode", s.aggressiveMode)
            rawSettings.put("autoReset24h", s.autoReset24h)
            rawSettings.put("autoResetAnchorMs", s.autoResetAnchorMs)
            rawSettings.put("eyeClosedDetect", s.eyeClosedDetect)
            rawSettings.put("eyeClosedAlarm", s.eyeClosedAlarm)
            rawSettings.put("pauseOnScreenOff", s.pauseOnScreenOff)
            rawSettings.put("resumeOnUnlock", s.resumeOnUnlock)
            rawSettings.put("eyeBlinksRequired", s.eyeBlinksRequired)
            prefs.edit().putString(KEY_SETTINGS, rawSettings.toString()).apply()
            settingsFlow.update { s }
        }
    }

    // ---------- SMS remote control ----------

    private const val KEY_SMS = "smsControlV1"

    private fun readSms(): SmsConfig = try {
        val raw = prefs.getString(KEY_SMS, null)
        if (raw == null) SmsConfig() else {
            val o = JSONObject(raw)
            SmsConfig(o.optBoolean("enabled", false), o.optString("number", ""),
                o.optString("pin", ""), o.optBoolean("reply", true))
        }
    } catch (_: Exception) { SmsConfig() }

    fun saveSms(c: SmsConfig) {
        synchronized(lock) {
            val o = JSONObject().put("enabled", c.enabled).put("number", c.number)
                .put("pin", c.pin).put("reply", c.reply)
            prefs.edit().putString(KEY_SMS, o.toString()).apply()
            smsFlow.value = c
        }
    }

    // ---------- PC camera pairing ----------

    private const val KEY_PC_PAIRING = "pcCamPairingV1"
    private const val KEY_DEVICE_ID = "pcCamDeviceIdV1"

    private fun readPairing(): PcPairing? = try {
        val raw = prefs.getString(KEY_PC_PAIRING, null)
        if (raw == null) null else {
            val o = JSONObject(raw)
            PcPairing(o.getString("pcId"), o.optString("pcName", "PC"), o.getString("secret"),
                o.optString("lastIp", ""), o.optInt("port", PcLink.TCP_PORT))
        }
    } catch (_: Exception) { null }

    /** Stable random id of this phone, used when authenticating to the PC. */
    fun deviceId(): String = synchronized(lock) {
        var id = prefs.getString(KEY_DEVICE_ID, null)
        if (id.isNullOrEmpty()) {
            id = java.util.UUID.randomUUID().toString().replace("-", "")
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        id
    }

    fun savePairing(p: PcPairing) {
        synchronized(lock) {
            val o = JSONObject().put("pcId", p.pcId).put("pcName", p.pcName).put("secret", p.secretHex)
                .put("lastIp", p.lastIp).put("port", p.port)
            prefs.edit().putString(KEY_PC_PAIRING, o.toString()).apply()
            pairingFlow.value = p
        }
    }

    fun updateLastIp(ip: String, port: Int) {
        synchronized(lock) {
            val p = pairingFlow.value ?: return
            if (p.lastIp == ip && p.port == port) return
            savePairing(p.copy(lastIp = ip, port = port))
        }
    }

    fun clearPairing() {
        synchronized(lock) {
            prefs.edit().remove(KEY_PC_PAIRING).apply()
            pairingFlow.value = null
        }
    }

    // ---------- timer state ----------

    data class TimerSnapshot(val accumulatedMs: Long, val savedThroughMs: Long)

    fun loadTimer(): TimerSnapshot = synchronized(lock) {
        try {
            val o = JSONObject(prefs.getString(KEY_TIMER, "{}"))
            val acc = maxOf(0L, o.optLong("accumulatedTime"))
            TimerSnapshot(acc, minOf(acc, maxOf(0L, o.optLong("savedThroughTime"))))
        } catch (_: Exception) {
            TimerSnapshot(0, 0)
        }
    }

    fun saveTimer(accumulatedMs: Long, savedThroughMs: Long, isPresent: Boolean, sessionStart: Long) {
        synchronized(lock) {
            val o = JSONObject()
                .put("accumulatedTime", accumulatedMs)
                .put("savedThroughTime", savedThroughMs)
                .put("isPresent", isPresent)
                .put("sessionStartTime", sessionStart)
                .put("savedAt", System.currentTimeMillis())
            prefs.edit().putString(KEY_TIMER, o.toString()).apply()
        }
    }

    fun clearTimer() {
        synchronized(lock) { prefs.edit().remove(KEY_TIMER).apply() }
    }

    // ---------- daily ledger ----------

    /** Adds an interval to the right calendar day(s); splits automatically across midnight. */
    fun addInterval(startTs: Long, endTs: Long) {
        if (startTs <= 0 || endTs <= startTs) return
        synchronized(lock) {
            var start = startTs
            while (start < endTs) {
                val segEnd = minOf(endTs, Fmt.nextMidnight(start))
                val dur = segEnd - start
                if (dur > 0) {
                    val key = Fmt.dateKey(start)
                    daily[key] = (daily[key] ?: 0L) + dur
                }
                start = segEnd
            }
            persistDaily()
        }
    }

    private fun persistDaily() {
        val o = JSONObject()
        daily.forEach { (k, v) -> o.put(k, v) }
        prefs.edit().putString(KEY_DAILY, o.toString()).apply()
    }

    fun dailySnapshot(): Map<String, Long> = synchronized(lock) { LinkedHashMap(daily) }

    fun mergeDaily(extra: Map<String, Long>) {
        synchronized(lock) {
            for ((k, v) in extra) {
                if (!DATE_RE.matches(k) || v <= 0) continue
                daily[k] = (daily[k] ?: 0L) + v
            }
            persistDaily()
        }
        notifyChanged()
    }

    fun todayMs(now: Long = System.currentTimeMillis()): Long =
        synchronized(lock) { daily[Fmt.dateKey(now)] ?: 0L }

    fun stats(now: Long = System.currentTimeMillis()): Stats = synchronized(lock) {
        val today = Fmt.dateKey(now)
        val month = Fmt.monthKey(now)
        val todayMs = daily[today] ?: 0L
        var monthMs = 0L; var allMs = 0L; var days = 0; var best = 0L
        for ((k, v) in daily) {
            allMs += v
            if (k.startsWith(month)) {
                monthMs += v
                if (v > 0) days++
            }
            if (v > best) best = v
        }
        var streak = 0
        var cursor = LocalDate.parse(today)
        if (todayMs <= 0) cursor = cursor.minusDays(1)
        while ((daily[cursor.toString()] ?: 0L) > 0) {
            streak++
            cursor = cursor.minusDays(1)
        }
        Stats(todayMs, monthMs, allMs, days, streak, best)
    }

    // ---------- saved records ----------

    fun recordsSnapshot(): List<Record> = synchronized(lock) { records.toList() }

    fun addRecord(r: Record) {
        synchronized(lock) { records.add(r); persistRecords() }
        notifyChanged()
    }

    fun clearRecords() {
        synchronized(lock) { records.clear(); persistRecords() }
        notifyChanged()
    }

    /** Returns how many new records were added. Existing ids are skipped (idempotent). */
    fun mergeRecords(extra: List<Record>): Int {
        var added = 0
        synchronized(lock) {
            val ids = records.map { it.id }.toHashSet()
            for (r in extra) {
                if (r.id.isEmpty() || r.id in ids || r.durationMs <= 0 || r.timestamp <= 0) continue
                records.add(r); ids.add(r.id); added++
            }
            records.sortBy { it.timestamp }
            persistRecords()
        }
        notifyChanged()
        return added
    }

    private fun persistRecords() {
        val arr = JSONArray()
        records.forEach {
            arr.put(JSONObject().put("id", it.id).put("timestamp", it.timestamp)
                .put("durationMs", it.durationMs).put("dateKey", it.dateKey)
                .put("monthKey", it.monthKey).put("label", it.label))
        }
        prefs.edit().putString(KEY_RECORDS, arr.toString()).apply()
    }

    fun clearAll() {
        synchronized(lock) {
            records.clear(); daily.clear()
            prefs.edit().remove(KEY_RECORDS).remove(KEY_DAILY).remove(KEY_TIMER).apply()
        }
        notifyChanged()
    }

    // ---------- backup support ----------

    /** All app data as {key: jsonString}, matching the web app's backup "storage" object. */
    fun exportStorage(): JSONObject = synchronized(lock) {
        val o = JSONObject()
        val arr = JSONArray()
        records.forEach {
            arr.put(JSONObject().put("id", it.id).put("timestamp", it.timestamp)
                .put("durationMs", it.durationMs).put("dateKey", it.dateKey)
                .put("monthKey", it.monthKey).put("label", it.label))
        }
        o.put(KEY_RECORDS, arr.toString())
        val d = JSONObject(); daily.forEach { (k, v) -> d.put(k, v) }
        o.put(KEY_DAILY, d.toString())
        prefs.getString(KEY_TIMER, null)?.let { o.put(KEY_TIMER, it) }
        o.put(KEY_SETTINGS, rawSettings.toString())
        o
    }

    fun importedIds(): List<String> = synchronized(lock) {
        try {
            val a = JSONArray(prefs.getString(KEY_IMPORTED, "[]"))
            (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotEmpty() } }
        } catch (_: Exception) { emptyList() }
    }

    fun rememberImported(id: String) {
        synchronized(lock) {
            val ids = importedIds().toMutableList()
            if (id !in ids) ids.add(id)
            val arr = JSONArray(); ids.takeLast(50).forEach { arr.put(it) }
            prefs.edit().putString(KEY_IMPORTED, arr.toString()).apply()
        }
    }

    fun notifyChanged() { version.update { it + 1 } }
}
