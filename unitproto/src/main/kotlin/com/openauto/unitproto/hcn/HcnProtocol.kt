package com.openauto.unitproto.hcn

/*
 * The HCN firmware (hcn2000.com: Junsun and other units on MediaTek MT8163 /
 * AC8257 and Unisoc UIS8581), as its CarInfo app (com.hcn.autocan, version
 * 30 / 31) shows it. Read from that app for issue #96, not documented by the
 * maker: the app opens the canbox's serial port itself and keeps what it
 * decodes to its own windows, but it mirrors the doors where every app can
 * read them, its MCU service broadcasts the ignition and the reverse wire,
 * and the app broadcasts the outside temperature. Unconfirmed on a unit.
 */
object HcnProtocol {
    const val PACKAGE_CAN = "com.hcn.autocan"
    const val PACKAGE_RADIO = "com.hcn.autoradio"
    const val PACKAGE_BT = "com.hcn.bluetoothservice"
    val PACKAGES = setOf(PACKAGE_CAN, PACKAGE_RADIO, PACKAGE_BT)
    /** The firmware's own: which serial port the canbox hangs on. */
    const val PROP_PORT = "persist.sys.hcnport"
    /** Set to "true" on units whose CarInfo app also writes the speed and gear to Settings.System (car_speed_value, key_can_gear). */
    const val PROP_CERTIFICATE = "ro.vendor.certificate"

    /** The MCU service's ignition: extra [EXTRA_ACC], a boolean. */
    const val ACTION_ACC = "android.mcu.device.action.ACC"
    const val EXTRA_ACC = "AccStatus"
    /** The MCU service's reverse wire: extra [EXTRA_REVERSE], a boolean. */
    const val ACTION_REVERSE = "android.mcu.device.action.REVSTATUS"
    const val EXTRA_REVERSE = "REVSTATUS"
    /** The CarInfo app's outside temperature, extra [EXTRA_OUTSIDE_TEMP]: a text like the QF app's ("12.5℃"). */
    const val ACTION_OUTSIDE_TEMP = "hcn.air.outdoor_temp"
    const val EXTRA_OUTSIDE_TEMP = "hcn.air.extra.outdoor_temp"
    /** The CarInfo app's climate, one extra per control (KEY_VALUE_TEMP, KEY_VALUE_WIND_LEVEL...): for the Signal Finder. */
    const val ACTION_AIR_INFO = "ACTION_AIR_INFO"
    const val ACTION_PHONE_MUTE = "android.mcu.device.action.TELMUTE"
    val BROADCASTS = setOf(ACTION_ACC, ACTION_REVERSE, ACTION_OUTSIDE_TEMP, ACTION_AIR_INFO, ACTION_PHONE_MUTE)

    /**
     * The doors, mirrored for the firmware's 360 camera ("cem") in properties the CarInfo app sets on every door
     * frame, whatever the car's decoder: [DOOR_OPEN] or "1" closed, unset before the first frame. In order: front
     * left, front right, rear left, rear right, boot. Its speed, turn indicators, radar and steering angle go to the
     * same prefix, but only from the decoders of cars sold with a 360 camera.
     */
    val PROP_DOORS = listOf(
        "persist.vendor.cem.cardoor1", "persist.vendor.cem.cardoor2", "persist.vendor.cem.cardoor3",
        "persist.vendor.cem.cardoor4", "persist.vendor.cem.cardoor5"
    )
    const val DOOR_OPEN = "2"

    /** Each door open or not, in [PROP_DOORS]'s order, from the properties [prop] reads; null until the app wrote them. */
    fun doors(prop: (String) -> String?): List<Boolean>? {
        val values = PROP_DOORS.map(prop)
        if (values.all { it == null }) return null
        return values.map { it == DOOR_OPEN }
    }
}
