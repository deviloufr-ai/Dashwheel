package com.openauto.dash.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Back to listening after a reboot or an update, without opening the app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // A restart drops every Bluetooth link: the car's is heard again when it reconnects.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) CarBluetooth.setInCar(context, false)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            LinkService.sync(context)
        }
    }
}
