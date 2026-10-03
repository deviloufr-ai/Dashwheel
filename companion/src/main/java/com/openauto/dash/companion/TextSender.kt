package com.openauto.dash.companion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * "On my way": the car asks the phone to text a contact its arrival time,
 * sent as a plain SMS so it goes with the phone in a pocket. Only with the
 * driver's leave (the SMS permission, a setup step); the car is told this
 * phone can ([com.openauto.dash.link.PhoneAbilities]).
 */
object TextSender {
    /**
     * Whether this phone sends texts at all. Android names that feature from
     * 13 on only: asked for by that name on Android 10 to 12, no phone had it,
     * and "On my way" was never offered there.
     */
    fun hasMessaging(context: Context): Boolean = context.packageManager.hasSystemFeature(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) PackageManager.FEATURE_TELEPHONY_MESSAGING
        else PackageManager.FEATURE_TELEPHONY
    )

    fun canSend(context: Context): Boolean =
        hasMessaging(context) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /** Sends [text] to [number]; false when it can't be (no permission, no SIM, refused). */
    fun send(context: Context, number: String, text: String): Boolean {
        if (!canSend(context) || number.isBlank() || text.isBlank()) return false
        return try {
            val sms = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
            val parts = sms.divideMessage(text)
            if (parts.size > 1) sms.sendMultipartTextMessage(number, null, parts, null, null)
            else sms.sendTextMessage(number, null, text, null, null)
            LinkServer.note("text sent to the car's contact")
            true
        } catch (e: Exception) {
            Log.w("TextSender", "send failed", e)
            false
        }
    }
}
