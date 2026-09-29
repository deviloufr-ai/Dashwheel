package com.openauto.dash.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.openauto.dash.link.PhoneBattery

/**
 * The phone's battery for the car's bar: the exact level and whether it
 * charges, which the head unit's Bluetooth gives only as five rough bars and
 * never with the charging. Sent on link up and whenever either changes.
 */
object PhoneBatteryWatch {
    private var appContext: Context? = null
    private var last: PhoneBattery? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val now = read(intent) ?: return
            if (now == last) return
            last = now
            LinkServer.send(now)
        }
    }

    fun start(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        // A sticky broadcast: the current state comes straight back.
        app.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { last = read(it) }
    }

    fun stop() {
        val app = appContext ?: return
        runCatching { app.unregisterReceiver(receiver) }
        appContext = null
        last = null
    }

    /** What to tell a car that just linked. */
    fun current(context: Context): PhoneBattery? =
        last ?: context.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let(::read)

    private fun read(intent: Intent): PhoneBattery? {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return PhoneBattery((level * 100 / scale).coerceIn(0, 100), charging)
    }
}
