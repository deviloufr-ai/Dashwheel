package com.openauto.dash

import android.content.Context
import android.os.SystemClock
import com.openauto.dash.link.CallState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/*
 * Dashwheel's alerts said out loud, for the ones the driver chose
 * ([AlertStyleStore.spoken]), through the car's voice ([CarVoice]):
 *  - a call, as it starts to ring: who is calling;
 *  - the doors, only when it matters: one opening while the car moves, or
 *    the car setting off with one still open. Opening a door to get out,
 *    parked, stays silent, or it would talk at every stop.
 * The speed is the OBD's when connected, else the car box's ([CarBox]),
 * else the GPS's, which runs only while a door is open.
 */
object AlertVoice {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var gpsHeld = false

    /** The same sentence isn't said again this soon (a door rattling open and shut). */
    private const val REPEAT_MS = 20_000L
    private var lastSaid: Pair<String, Long>? = null

    private val doorOrder = listOf("fl", "fr", "rl", "rr", "tailgate", "bonnet")

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        CarVoice.setContext(app)
        // Quiet while reversing: the parking sensors come first; what waited is said after.
        scope.launch { CarBox.reversing.collect { CarVoice.hold(it) } }

        // A call starting to ring (a new one, not every update of it).
        scope.launch {
            PhoneCallOverlay.call
                .map { call -> call?.takeIf { it.phase == CallState.Phase.RINGING && !it.dialing && !it.preview } }
                .distinctUntilChanged { a, b -> (a == null) == (b == null) }
                .collect { ringing -> if (ringing != null) sayCall(app, ringing) }
        }

        // The GPS speed only while it's needed: a door open, and doors spoken.
        scope.launch {
            combine(RomPopups.replaced, AlertStyleStore.spoken, McuReader.doorState) { replaced, spoken, doors ->
                RomPopups.Kind.DOORS in replaced && AlertKind.DOORS in spoken && doors?.anyOpen == true
            }.distinctUntilChanged().collect { want ->
                if (want && !gpsHeld) gpsHeld = LocationFeed.acquire(app)
                else if (!want && gpsHeld) {
                    LocationFeed.release()
                    gpsHeld = false
                }
            }
        }

        scope.launch {
            val speed = combine(ObdBluetoothManager.connectionState, ObdBluetoothManager.data, CarBox.body, LocationFeed.freshSpeedKmh) { connection, obd, _, gps ->
                if (connection == ObdConnectionState.CONNECTED) obd.speedKmh else CarBox.freshBody()?.speedKmh ?: gps
            }
            var before: Set<String> = emptySet()
            var wasMoving = false
            combine(McuReader.doorState, speed) { doors, kmh -> doors to kmh }.collect { (doors, kmh) ->
                val open = doors?.openNames() ?: return@collect
                val moving = isMoving(kmh, wasMoving)
                val wanted = RomPopups.Kind.DOORS in RomPopups.replaced.value && !DemoMode.isOn
                if (wanted) doorsToSay(before, open, wasMoving, moving)?.let { sayDoors(app, it) }
                before = open
                wasMoving = moving
            }
        }
    }

    /** "Call from Alex": the name when the phone knows it. */
    fun sayCall(context: Context, call: PhoneCall, force: Boolean = false) {
        if (AlertKind.CALL !in AlertStyleStore.spoken.value) return
        // CarPlay / Android Auto announces its own calls.
        if (!force && UnitSignals.projectionOnScreen.value) return
        val res = AppLanguage.wrap(context.applicationContext).resources
        val text = call.name?.let { res.getString(R.string.alert_say_call, it) } ?: res.getString(R.string.phone_call_incoming)
        say(context, text, force)
    }

    /** "Door open: front left, tailgate", for the doors named in [open] (see [openNames]). */
    fun sayDoors(context: Context, open: Set<String>, force: Boolean = false) {
        if (AlertKind.DOORS !in AlertStyleStore.spoken.value || open.isEmpty()) return
        val res = AppLanguage.wrap(context.applicationContext).resources
        val names = doorOrder.filter { it in open }.map { key ->
            res.getString(
                when (key) {
                    "fl" -> R.string.vehicle_door_front_left
                    "fr" -> R.string.vehicle_door_front_right
                    "rl" -> R.string.vehicle_door_rear_left
                    "rr" -> R.string.vehicle_door_rear_right
                    "tailgate" -> R.string.vehicle_door_tailgate
                    else -> R.string.vehicle_door_bonnet
                }
            )
        }
        say(context, res.getString(R.string.alert_say_doors, names.joinToString(", ")), force)
    }

    /** "Fasten your seat belt". */
    fun sayBelt(context: Context, force: Boolean = false) {
        if (AlertKind.BELT !in AlertStyleStore.spoken.value) return
        say(context, AppLanguage.wrap(context.applicationContext).resources.getString(R.string.car_belt_fasten), force)
    }

    /** "Front left tyre: low pressure". */
    fun sayTyre(context: Context, pos: TyrePos, problem: TyreProblem, force: Boolean = false) {
        if (AlertKind.TYRES !in AlertStyleStore.spoken.value) return
        val res = AppLanguage.wrap(context.applicationContext).resources
        say(context, res.getString(R.string.alert_say_tyre, res.getString(pos.labelRes), res.getString(problem.labelRes)), force)
    }

    /** [force]: said even if it was just said ("Try it" pressed again). */
    private fun say(context: Context, text: String, force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        lastSaid?.let { (said, at) -> if (!force && said == text && now - at < REPEAT_MS) return }
        lastSaid = text to now
        val wrapped = AppLanguage.wrap(context.applicationContext)
        CarVoice.setContext(context)
        CarVoice.speak(text, wrapped.resources.configuration.locales[0])
    }
}

/** Moving from [MOVING_KMH] up, stopped again only at [STOPPED_KMH] or below, so traffic doesn't flicker it; unknown speed is stopped. */
internal fun isMoving(kmh: Int?, wasMoving: Boolean): Boolean =
    kmh != null && (kmh >= MOVING_KMH || wasMoving && kmh > STOPPED_KMH)

/**
 * The doors worth saying now, or null for silence: those that just opened
 * while moving, or all the open ones as the car sets off. Parked, nothing.
 */
internal fun doorsToSay(before: Set<String>, open: Set<String>, wasMoving: Boolean, moving: Boolean): Set<String>? = when {
    !moving -> null
    (open - before).isNotEmpty() -> open - before
    !wasMoving && open.isNotEmpty() -> open
    else -> null
}
