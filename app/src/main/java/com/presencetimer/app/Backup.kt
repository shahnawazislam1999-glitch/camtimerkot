package com.presencetimer.app

import org.json.JSONObject

/** Export / import, compatible with the web app's "presence-timer-backup" files. */
object Backup {
    const val FORMAT = "presence-timer-backup"
    const val VERSION = 1
    const val MAX_BYTES = 10 * 1024 * 1024

    class Parsed(val storage: JSONObject, val backupId: String, val exportedLabel: String)

    fun fnvId(input: String): String {
        var hash = -0x7ee3623b // 2166136261 as Int
        for (ch in input) {
            hash = hash xor ch.code
            hash *= 16777619
        }
        return String.format("%08x", hash)
    }

    fun export(): String {
        val storage = Store.exportStorage()
        val o = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("app", "Presence Timer")
            .put("exportedAt", java.time.Instant.now().toString())
            .put("storage", storage)
            .put("backupId", fnvId(storage.toString()))
        return o.toString(2)
    }

    fun parse(text: String): Parsed {
        val o = try { JSONObject(text) } catch (e: Exception) {
            throw IllegalArgumentException("The selected file is not valid JSON.")
        }
        if (o.optString("format") != FORMAT) throw IllegalArgumentException("The selected file is not a Presence Timer backup.")
        if (o.optInt("version", 0) > VERSION) throw IllegalArgumentException("This backup was made by a newer version of the app.")
        val storage = o.optJSONObject("storage") ?: throw IllegalArgumentException("The backup has no data.")
        val id = o.optString("backupId").ifEmpty { fnvId(storage.toString()) }
        val label = try {
            Fmt.dateTime(java.time.Instant.parse(o.optString("exportedAt")).toEpochMilli())
        } catch (_: Exception) { "an earlier time" }
        return Parsed(storage, id, label)
    }

    fun alreadyImported(p: Parsed) = p.backupId in Store.importedIds()

    /** Merges (never replaces) timer, daily history and saved records. Detection must be stopped. */
    fun merge(p: Parsed): String {
        check(!Engine.live.value.detecting) { "Stop detection before importing a backup." }

        // 1) timer totals
        val t = try { JSONObject(p.storage.optString(Store.KEY_TIMER, "{}")) } catch (_: Exception) { JSONObject() }
        val bAcc = maxOf(0L, t.optLong("accumulatedTime"))
        Engine.addImportedTimer(bAcc, t.optLong("savedThroughTime"))

        // 2) daily ledger
        val daily = HashMap<String, Long>()
        try {
            val d = JSONObject(p.storage.optString(Store.KEY_DAILY, "{}"))
            for (k in d.keys()) daily[k] = maxOf(0L, d.optLong(k))
        } catch (_: Exception) { }
        Store.mergeDaily(daily)

        // 3) saved records (ids make this idempotent)
        val records = ArrayList<Record>()
        try {
            val arr = org.json.JSONArray(p.storage.optString(Store.KEY_RECORDS, "[]"))
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                val ts = r.optLong("timestamp")
                records.add(Record(r.optString("id"), ts, r.optLong("durationMs"),
                    r.optString("dateKey", Fmt.dateKey(ts)), r.optString("monthKey", Fmt.monthKey(ts)),
                    r.optString("label", "Saved timer")))
            }
        } catch (_: Exception) { }
        val added = Store.mergeRecords(records)

        Store.rememberImported(p.backupId)
        Store.notifyChanged()
        return "Backup merged • +${Fmt.time(bAcc)} timer • $added new record${if (added == 1) "" else "s"}"
    }
}
