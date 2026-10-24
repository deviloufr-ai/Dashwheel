package com.openauto.dash

import androidx.annotation.StringRes
import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.Key

/**
 * What a head unit's firmware lets Dashwheel do, one capability at a time:
 * the adapter of each family claims the ones its unit exposes ([CapClaim]),
 * with what each needs (a shell, root, the settings permission). The Settings
 * card and the compatibility report read them; the features themselves ask
 * their adapter, not this list.
 *
 * A capability that is a kind of car data names its [key]: it is live when the vehicle bus has
 * that key ([com.openauto.dash.vehicle.VehicleBus.has]), whoever says it, and nobody marks it.
 * The others (the platform's own: keys, bridges, takeovers) are marked by what they do ([UnitPlatform.markUsed]).
 */
enum class Cap(val key: Key<*>? = null) {
    /** The ignition, on and off, as the unit announces it. */
    IGNITION,
    /** A "going to sleep" warning before the unit sleeps. */
    SLEEP_SOON,
    /** The car's data bus (CAN box frames or the vendor car service). */
    VEHICLE_BUS,
    DOORS(K.DOORS), REVERSE(K.REVERSE), STEERING(K.STEERING_ANGLE), RADAR(K.RADAR), CLIMATE(K.CLIMATE),
    /** The car app's body and trip computer: the mileage stands for it. */
    BODY_TRIP(K.ODOMETER),
    SPEED_RPM(K.VEHICLE_SPEED), VOLTS(K.BATTERY_VOLTS), HEADLIGHTS(K.HEADLIGHTS), OUTSIDE_TEMP(K.OUTSIDE_TEMP),
    /** The unit's own keys (steering wheel, panel) as the firmware hands them out. */
    UNIT_KEYS,
    /** Which app the unit's NAVI / voice / custom keys open. */
    KEY_TARGETS,
    /** Calls on the unit's own Bluetooth: shown, answered, hung up. */
    PHONE_BRIDGE,
    /** The unit's own players (Bluetooth music, radio): what plays, next and previous. */
    MEDIA_BRIDGE,
    /** The unit's own volume, read and set. */
    VOLUME_UNIT,
    /** The firmware's own launcher choice, pointed at Dashwheel. */
    LAUNCHER_TAKEOVER,
    /** The firmware's pop-ups (doors, radar, call, volume) hidden, Dashwheel's shown instead. */
    POPUP_HIDE,
    /** Steps that keep Dashwheel alive through the unit's sleep (whitelists, battery savers). */
    SLEEP_SURVIVAL,
    /** The reversing camera taken from the firmware's own camera app. */
    CAMERA_CONTROL,
    /** The firmware's status bar hidden over floating windows. */
    FREEFORM_BAR,
    BOOT_LOGO,
    /** Tyre pressures from the unit's TPMS app. */
    TYRES(K.TYRES)
}

/** What a capability needs before it works here. */
enum class Need {
    NONE,
    /** WRITE_SECURE_SETTINGS, granted once from a PC or held by a system copy ([PrivilegedShell.settingsGranted]); a shell does too. */
    SETTINGS,
    /** Any privileged shell: root, Shizuku or the unit's ADB ([PrivilegedShell.Access.shell]). */
    SHELL,
    /** `su` itself ([PrivilegedShell.Access.root]). */
    ROOT
}

/** How a claimed capability stands on this unit, for the Settings card. */
enum class CapState {
    /** The firmware doesn't offer it (not claimed). */
    ABSENT,
    /** Claimed, but what it needs isn't there yet. */
    LOCKED,
    /** Claimed and unlocked, nothing heard from it yet this run. */
    READY,
    /** It delivered something this run ([UnitPlatform.markUsed]). */
    LIVE
}

/** One capability an adapter claims, with its [need] and an optional note for the card (how to unlock it). */
data class CapClaim(val cap: Cap, val need: Need = Need.NONE, @StringRes val note: Int? = null)

class UnitCaps(claims: Collection<CapClaim>) {
    private val byCap: Map<Cap, CapClaim> = claims.associateBy { it.cap }

    val claimed: Set<Cap> get() = byCap.keys

    fun has(cap: Cap): Boolean = cap in byCap

    /** What [cap] needs, or null when it isn't claimed. */
    fun need(cap: Cap): Need? = byCap[cap]?.need

    fun note(cap: Cap): Int? = byCap[cap]?.note

    /** Where [cap] stands with this [access] and [settingsGranted], given what was [used] this run. */
    fun state(cap: Cap, access: PrivilegedShell.Access, settingsGranted: Boolean, used: Set<Cap>): CapState {
        val claim = byCap[cap] ?: return CapState.ABSENT
        if (cap in used) return CapState.LIVE
        return if (needMet(claim.need, access, settingsGranted)) CapState.READY else CapState.LOCKED
    }

    companion object {
        val NONE = UnitCaps(emptyList())

        /** Whether [need] is met by this [access], or by the settings permission for [Need.SETTINGS]. */
        fun needMet(need: Need, access: PrivilegedShell.Access, settingsGranted: Boolean): Boolean = when (need) {
            Need.NONE -> true
            Need.SETTINGS -> access.shell || settingsGranted
            Need.SHELL -> access.shell
            Need.ROOT -> access.root
        }
    }
}
