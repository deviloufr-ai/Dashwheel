package com.openauto.dash

import com.openauto.dash.vehicle.Source
import com.openauto.dash.vehicle.SourceKind
import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.Vehicle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/**
 * Where a vehicle bus other than the QF car app's pushes what it decodes
 * ([VehicleBusSource]): one sink, forwarding to the flows the dashboard
 * already reads (doors in [McuReader], reverse and steering in [CarBox],
 * headlights and volts in [UnitSignals]), plus speed and RPM of its own for
 * units whose bus says them. On the QF firmware nothing writes here: its car
 * app and MCU feed the same flows directly, as they always did.
 */
object CarFeed : CarSink {
    private val _speedKmh = MutableStateFlow<Float?>(null)
    /** The car's speed from the unit's bus, km/h, or null when no bus says it. */
    val speedKmh: StateFlow<Float?> = _speedKmh

    /**
     * The unit's bus on the vehicle bus: its speed, with the driver's correction. A bus says it on a
     * change (or now and then), so each value lives [BUS_HOLD_MS] instead of the key's 3 s.
     */
    private val busPublisher = Vehicle.bus.register(Source("unit.bus", SourceKind.CANBOX, "Unit bus"))
    private const val BUS_HOLD_MS = 10_000L

    private val _rpm = MutableStateFlow<Int?>(null)
    val rpm: StateFlow<Int?> = _rpm

    @Volatile private var doorsSeen = false

    /** Whether a bus here has fed what the alert of [kind] is built on: a unit without the QF car app can still show it. */
    fun provides(kind: RomPopups.Kind): Boolean = when (kind) {
        RomPopups.Kind.DOORS, RomPopups.Kind.BELT -> doorsSeen
        else -> false
    }

    override fun doors(state: McuReader.DoorState) {
        doorsSeen = true
        UnitPlatform.markUsed(Cap.DOORS)
        McuReader.busDoorsWrite(state)
    }

    override fun reverse(on: Boolean) {
        UnitPlatform.markUsed(Cap.REVERSE)
        CarBox.reverseWrite(on)
    }

    override fun headlights(on: Boolean) {
        UnitPlatform.markUsed(Cap.HEADLIGHTS)
        UnitSignals.headlightsWrite(on)
    }

    override fun speedKmh(v: Float?) {
        if (v != null) UnitPlatform.markUsed(Cap.SPEED_RPM)
        _speedKmh.value = v
        busPublisher.put(K.VEHICLE_SPEED, v?.let { SpeedCorrection.corrected(it.roundToInt()) }?.toDouble(), ttlMs = BUS_HOLD_MS)
    }

    override fun rpm(v: Int?) {
        if (v != null) UnitPlatform.markUsed(Cap.SPEED_RPM)
        _rpm.value = v
    }

    override fun volts(v: Double) {
        UnitPlatform.markUsed(Cap.VOLTS)
        UnitSignals.mcuVolts(v)
    }

    override fun outsideTempC(c: Double?) {
        if (c != null) UnitPlatform.markUsed(Cap.OUTSIDE_TEMP)
        UnitSignals.outsideTempWrite(c)
    }

    override fun steeringDeg(d: Float) {
        UnitPlatform.markUsed(Cap.STEERING)
        CarBox.steeringWrite(d)
    }

    override fun raw(key: String, value: String) {
        SignalSources.busValue(key, value)
    }
}
