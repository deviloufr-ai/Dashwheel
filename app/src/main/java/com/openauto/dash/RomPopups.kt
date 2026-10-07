package com.openauto.dash

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * The head unit's own pop-ups, replaced by Dashwheel's, on the QF firmware of
 * ROCO K706 units (worked out from the firmware):
 *  - the Bluetooth call screen: the call card shows the call instead, and the
 *    ROM's window is set aside for each call ([HeadUnitPhone]);
 *  - the firmware's volume bar (com.qf.framework): [VolumeOverlay] shows instead,
 *    once [VolumeRomHide]'s Magisk module hides the firmware's at a start;
 *  - the car app's (com.qf.vehicle) door, parking radar and climate pop-ups:
 *    [DoorAlertOverlay], [RadarOverlay] and [ClimateOverlay] show instead.
 *    The car app skips its door and reversing radar pop-ups for any launcher
 *    listed in the global setting KeyAllPackages whose
 *    "<package>KeyIfHideDoorView" / "<package>keyIfHideRadarView" is 1, the
 *    way the stock launchers' SDK does it; the radar while driving has one
 *    global switch (KeyIfHideRunningRadar), and the climate pop-up follows
 *    the car app's own "popup_enable" broadcast. The reversing radar is only
 *    taken over while the accessibility service is on, the one way to draw
 *    above the reversing camera; otherwise the car's own stays. The settings are written
 *    by Dashwheel itself when it holds WRITE_SECURE_SETTINGS, else through
 *    the root or ADB shell, and last across reboots; a reset of the car
 *    settings turns the climate one back on, so every switch is applied again
 *    at each start.
 * Turning a switch off gives the ROM its pop-up back.
 */
@OptIn(FlowPreview::class)
object RomPopups {
    private const val TAG = "RomPopups"
    private const val PREFS = "rom_popups"
    const val VEHICLE_PACKAGE = "com.qf.vehicle"

    enum class Kind(val key: String) { CALL("call"), DOORS("doors"), RADAR("radar"), AC("ac"), TYRES("tyres"), BELT("belt"), VOLUME("volume") }

    private val _replaced = MutableStateFlow<Set<Kind>>(emptySet())
    /** The kinds whose ROM pop-up Dashwheel replaces (the Settings switches). */
    val replaced: StateFlow<Set<Kind>> = _replaced

    private val _failed = MutableStateFlow<Set<Kind>>(emptySet())
    /** The kinds whose ROM switch could not be written (no root, no ADB, no permission). */
    val failed: StateFlow<Set<Kind>> = _failed

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    /** Whether this unit has the ROM app whose pop-up [kind] replaces. */
    fun available(context: Context, kind: Kind): Boolean = when (kind) {
        Kind.CALL -> HeadUnitPhone.available(context)
        Kind.DOORS, Kind.RADAR, Kind.AC -> isPackageInstalled(context, VEHICLE_PACKAGE)
        Kind.TYRES -> isPackageInstalled(context, Tyres.TPMS_PACKAGE)
        Kind.BELT -> isPackageInstalled(context, VEHICLE_PACKAGE)
        Kind.VOLUME -> isPackageInstalled(context, VolumeRomHide.FIRMWARE_PACKAGE)
    }

    /**
     * Whether [kind] can work with this access ([PrivilegedShell]): the car
     * app's door, climate and body data, and its radar switch, need its
     * global settings written ([PrivilegedShell.Access.carSettings]); the
     * volume bar a Magisk module, so root.
     */
    fun canWork(kind: Kind, access: PrivilegedShell.Access): Boolean = when (kind) {
        Kind.DOORS, Kind.RADAR, Kind.AC, Kind.BELT -> access.carSettings
        Kind.CALL, Kind.TYRES -> true
        Kind.VOLUME -> access.root
    }

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _replaced.value = Kind.entries.filter { p.getBoolean("replace_${it.key}", false) && available(app, it) }.toSet()
        VolumeRomHide.refresh(app)
        if (Kind.DOORS in _replaced.value) McuReader.start()
        // Checked each start: cheap when already set, and put back if something cleared it.
        for (kind in _replaced.value - Kind.RADAR) apply(app, kind, hide = true)
        // The reversing radar is only taken over while Dashwheel's can be drawn
        // above the camera: without the accessibility service the car's own
        // stays. Settled first, so the service binding at boot writes once.
        scope.launch {
            SplitAccessibilityService.connected.debounce(3_000).collect {
                if (Kind.RADAR in _replaced.value) apply(app, Kind.RADAR, hide = true)
            }
        }
    }

    fun setReplaced(context: Context, kind: Kind, on: Boolean) {
        if ((kind in _replaced.value) == on) return
        _replaced.update { if (on) it + kind else it - kind }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("replace_${kind.key}", on).apply()
        val app = context.applicationContext
        if (kind == Kind.DOORS) if (on) McuReader.start() else McuReader.stop()
        if (on && kind in CAR_DATA) CarBox.register(app)
        apply(app, kind, hide = on)
    }

    /** The alerts built on the car app's shared data ([CarBox]). */
    private val CAR_DATA = setOf(Kind.DOORS, Kind.AC, Kind.BELT, Kind.RADAR)

    private fun apply(context: Context, kind: Kind, hide: Boolean) {
        val me = context.packageName
        val flag = if (hide) 1 else 0
        when (kind) {
            Kind.CALL -> if (!hide) HeadUnitPhone.releaseRomScreen(context)
            Kind.DOORS -> scope.launch { record(kind, hide, writeGlobals(context, mapOf("${me}KeyIfHideDoorView" to flag))) }
            Kind.RADAR -> scope.launch {
                val reversing = if (hide && SplitAccessibilityService.isConnected) 1 else 0
                record(kind, hide, writeGlobals(context, mapOf("${me}keyIfHideRadarView" to reversing, "KeyIfHideRunningRadar" to flag)))
            }
            // The TPMS app's own window has no outside switch: its settings do it ("show UI").
            Kind.TYRES -> Unit
            // Dashwheel's own reminder; the car's chime is its own business.
            Kind.BELT -> Unit
            Kind.VOLUME -> scope.launch { record(kind, hide, VolumeRomHide.apply(context, hide)) }
            // The car app's own switch for it; no root needed.
            Kind.AC -> context.sendBroadcast(
                Intent("com.qf.vehicle.action.popup_enable")
                    .putExtra("extra_popup_type", 1.toByte())
                    .putExtra("extra_popup_enable", !hide)
            )
        }
    }

    private fun record(kind: Kind, hide: Boolean, ok: Boolean) {
        _failed.update { if (ok || !hide) it - kind else it + kind }
    }

    /**
     * Writes the car app's global settings for launchers ([keys], value per
     * key), first listing Dashwheel in KeyAllPackages. Skips what is already
     * set; true when everything holds afterwards.
     */
    internal suspend fun writeGlobals(context: Context, keys: Map<String, Int>): Boolean {
        val me = context.packageName
        val listed = globalString(context, "KeyAllPackages").orEmpty()
            .split(',').map { it.trim() }.filter { it.isNotEmpty() && it != "null" }
        val pending = keys.filter { (key, value) -> globalInt(context, key) != value }
        if (me in listed && pending.isEmpty()) return true
        // Dashwheel's own write when it holds the permission, else the shell's.
        val direct = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        suspend fun put(key: String, value: String) {
            if (direct) Settings.Global.putString(context.contentResolver, key, value)
            else DockShell.shell(context, "settings put global $key $value")
        }
        return runCatching {
            if (me !in listed) put("KeyAllPackages", (listed + me).joinToString(","))
            for ((key, value) in pending) put(key, value.toString())
            check(keys.all { (key, value) -> globalInt(context, key) == value }) { "settings did not change" }
        }.onSuccess { Log.i(TAG, "car app settings written: ${keys.keys}") }
            .onFailure { Log.w(TAG, "could not write the car app settings ${keys.keys}", it) }
            .isSuccess
    }

    private fun globalString(context: Context, key: String): String? =
        runCatching { Settings.Global.getString(context.contentResolver, key) }.getOrNull()

    private fun globalInt(context: Context, key: String): Int? =
        globalString(context, key)?.trim()?.toIntOrNull()
}

/** The doors open in this state, by name: which ones changed matters, not the whole state. */
internal fun McuReader.DoorState.openNames(): Set<String> = buildSet {
    if (frontLeft) add("fl")
    if (frontRight) add("fr")
    if (rearLeft) add("rl")
    if (rearRight) add("rr")
    if (tailgate) add("tailgate")
    if (bonnet) add("bonnet")
}

internal fun isPackageInstalled(context: Context, pkg: String): Boolean =
    runCatching { context.packageManager.getApplicationInfo(pkg, 0); true }.getOrDefault(false)
