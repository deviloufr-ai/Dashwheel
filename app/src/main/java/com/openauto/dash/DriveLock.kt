package com.openauto.dash

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/*
 * The drive lock: while the car moves, anything that needs more than a glance
 * (arranging tiles, settings, pickers, the app grid) waits until it has
 * stopped. Speed comes from OBD when connected, GPS otherwise.
 */

/** Whether the lock is on (Settings → Advanced). On by default: a launcher for the driver's seat. */
object DriveLockStore {
    private const val PREFS = "drive_lock"
    private const val KEY = "enabled"

    fun load(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun save(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).apply()
    }
}

/** Moving from this speed up; a slow roll in a car park does not lock the launcher. */
internal const val MOVING_KMH = 8
/** Stopped from this speed down (held for [STOPPED_HOLD_MS]), so creeping in traffic does not flicker the lock. */
internal const val STOPPED_KMH = 3
internal const val STOPPED_HOLD_MS = 2_000L

private val NotMoving: State<Boolean> = mutableStateOf(false)

/**
 * The lock as tiles and dialogs see it: [moving] while the car moves with the
 * lock on, and [whenParked] for anything that needs more than a glance (a
 * finder, a settings sheet, a text field), which runs now or shows the
 * parked-only notice instead. The dashboard provides it ([LocalDriveLock]);
 * the default never locks (previews, tests).
 */
@Stable
internal class DriveLockState(val moving: Boolean, private val onLocked: () -> Unit) {
    /** Runs [action] now, or shows the parked-only notice while moving. */
    fun whenParked(action: () -> Unit) {
        if (moving) onLocked() else action()
    }

    /** Shows the notice: something the lock held back. */
    fun noticeLocked() = onLocked()
}

internal val LocalDriveLock = compositionLocalOf { DriveLockState(moving = false) {} }

/**
 * For a dialog that needs more than a glance: closes it, with the notice, as
 * soon as the car moves, and refuses to open while it does. Put it first in
 * the dialog's composable so every way of opening it is covered.
 */
@Composable
internal fun ParkedOnly(onDismiss: () -> Unit) {
    val lock = LocalDriveLock.current
    LaunchedEffect(lock.moving) {
        if (lock.moving) {
            lock.noticeLocked()
            onDismiss()
        }
    }
}

/**
 * True while the car is moving, when the lock is [enabled]. Never in demo
 * mode: its made-up speed must not lock the person exploring the launcher out.
 * With the lock off no speed is read, so the GPS stays off on a phone.
 *
 * The speed is followed in an effect rather than read in composition, so the
 * caller (the whole dashboard) only recomposes when the answer flips.
 */
@Composable
internal fun rememberMoving(enabled: Boolean, demo: Boolean): State<Boolean> {
    if (!enabled || demo) return NotMoving
    val moving = remember { mutableStateOf(false) }
    UseLocationFeed()
    LaunchedEffect(Unit) {
        carSpeedKmh().map { speed ->
            when {
                speed >= MOVING_KMH -> true
                speed <= STOPPED_KMH -> false
                else -> null
            }
        }
            .distinctUntilChanged()
            .collectLatest { fast ->
                when (fast) {
                    true -> moving.value = true
                    false -> {
                        delay(STOPPED_HOLD_MS)
                        moving.value = false
                    }
                    null -> Unit
                }
            }
    }
    return moving
}

/**
 * The car's speed: the OBD's when connected, else the car box's, else the
 * GPS's (which only runs while someone holds [UseLocationFeed]); 0 when none.
 */
internal fun carSpeedKmh(): Flow<Int> = combine(
    ObdBluetoothManager.connectionState,
    ObdBluetoothManager.data,
    CarBox.body,
    LocationFeed.freshSpeedKmh
) { connection, obd, _, gps ->
    (if (connection == ObdConnectionState.CONNECTED) obd.speedKmh else CarBox.freshBody()?.speedKmh ?: gps) ?: 0
}
