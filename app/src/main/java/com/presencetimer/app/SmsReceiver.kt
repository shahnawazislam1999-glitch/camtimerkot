package com.presencetimer.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.ContextCompat

/**
 * Lets you pause / start the timer by text message, e.g. "PAUSE 1234", "START 1234", "STATUS 1234".
 *
 * Safety rules (all of them must hold, otherwise the message is silently ignored):
 *  1. SMS control is switched on in Settings and a sender number + PIN are saved.
 *  2. The message comes from the saved number.
 *  3. The message contains the saved PIN.
 * 5 wrong-PIN commands in 10 minutes lock SMS control for 10 minutes. Nobody else ever gets a reply.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        try {
            val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
            if (msgs.isEmpty()) return
            val from = msgs[0].originatingAddress ?: return
            val body = msgs.joinToString("") { it.messageBody ?: "" }
            SmsControl.handle(context.applicationContext, from, body)
        } catch (_: Exception) {
            // Never crash the app because of a strange message.
        }
    }
}

object SmsControl {
    private val lock = Any()
    private val badAttempts = ArrayDeque<Long>()
    private var lockedUntil = 0L

    private const val MAX_BAD = 5
    private const val WINDOW_MS = 10 * 60 * 1000L
    private val COMMANDS = mapOf(
        "pause" to "pause", "stop" to "pause", "hold" to "pause",
        "start" to "resume", "resume" to "resume", "play" to "resume", "go" to "resume",
        "status" to "status", "info" to "status", "time" to "status"
    )

    /** Same phone number even if one side has "+91" / "0" and the other does not (compares the last 10 digits). */
    internal fun sameNumber(a: String, b: String): Boolean {
        val x = a.filter { it.isDigit() }
        val y = b.filter { it.isDigit() }
        if (x.length < 7 || y.length < 7) return false
        val n = minOf(10, minOf(x.length, y.length))
        return x.takeLast(n) == y.takeLast(n)
    }

    private fun samePin(a: String, b: String): Boolean =
        java.security.MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    /** Returns the reply that was (or would have been) sent, or null when the message was ignored. */
    fun handle(ctx: Context, from: String, body: String, nowMs: Long = SystemClock.elapsedRealtime()): String? {
        val cfg = Store.smsFlow.value
        if (!cfg.ready) return null
        if (!sameNumber(from, cfg.number)) return null            // strangers: no reaction, no reply

        val tokens = body.trim().lowercase().split(Regex("[\\s,;:]+")).filter { it.isNotEmpty() }
        val cmdWord = tokens.firstOrNull { COMMANDS.containsKey(it) } ?: return null   // normal chat, not a command
        val pinOk = tokens.any { samePin(it, cfg.pin) }

        synchronized(lock) {
            while (badAttempts.isNotEmpty() && nowMs - badAttempts.first() > WINDOW_MS) badAttempts.removeFirst()
            if (nowMs < lockedUntil) return null
            if (!pinOk) {
                badAttempts.addLast(nowMs)
                if (badAttempts.size >= MAX_BAD) { lockedUntil = nowMs + WINDOW_MS; badAttempts.clear() }
                return null                                       // wrong / missing PIN: no reply
            }
            badAttempts.clear()
        }

        val reply = PresenceService.remote(COMMANDS.getValue(cmdWord))
        if (cfg.reply) sendReply(ctx, from, reply)
        return reply
    }

    @Suppress("DEPRECATION")
    private fun sendReply(ctx: Context, to: String, text: String) {
        try {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return
            val sm = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
            sm?.sendTextMessage(to, null, text.take(150), null, null)
        } catch (_: Exception) { }
    }
}
