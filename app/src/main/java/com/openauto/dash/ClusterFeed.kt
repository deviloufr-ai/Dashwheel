package com.openauto.dash

import android.content.Context
import androidx.compose.ui.graphics.toArgb
import com.openauto.dash.link.ClusterState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The second screen's readings, for when it draws the cluster itself
 * (SecondScreenOutput.DATA): the same feeds the dashboard's widgets read,
 * sent over DisplayLink whenever something shown changes (see [ClusterThrottle]).
 */
internal object ClusterFeed {
    private const val SAMPLE_MS = 200L

    fun run(context: Context, scope: CoroutineScope, page: StateFlow<ClusterPage>): Job = scope.launch {
        val throttle = ClusterThrottle()
        while (isActive) {
            val state = snapshot(context, page.value)
            // The clock and the playing position move on their own: they don't make a change.
            val key = state.copy(clock = 0, media = state.media?.copy(positionMs = 0))
            if (throttle.shouldSend(key, System.currentTimeMillis())) DisplayLink.send(state)
            delay(SAMPLE_MS)
        }
    }

    private fun snapshot(context: Context, page: ClusterPage): ClusterState {
        val obdLive = ObdBluetoothManager.connectionState.value == ObdConnectionState.CONNECTED || DemoMode.isOn
        val obd = ObdBluetoothManager.data.value
        // The OBD speed comes corrected already (ObdBluetoothManager), and the correction is for the car's
        // figure, not the GPS: corrected again here, a stopped car's 1 km/h of GPS drift read 9.
        val speed = if (obdLive) obd.speedKmh else LocationFeed.freshSpeedKmh.value
        val doors = McuReader.doorState.value
        val open = if (doors == null) emptyList() else listOfNotNull(
            R.string.vehicle_door_front_left.takeIf { doors.frontLeft },
            R.string.vehicle_door_front_right.takeIf { doors.frontRight },
            R.string.vehicle_door_rear_left.takeIf { doors.rearLeft },
            R.string.vehicle_door_rear_right.takeIf { doors.rearRight },
            R.string.vehicle_door_tailgate.takeIf { doors.tailgate },
            R.string.vehicle_door_bonnet.takeIf { doors.bonnet }
        ).map { context.getString(it) }

        val controller = CarMediaController.shared(context)
        val media = if (DemoMode.isOn) DemoMode.media.value else controller.mediaState.value
        val nav = NavDirections.state.value
        val units = Units.current.value
        return ClusterState(
            clock = System.currentTimeMillis(),
            page = page.name,
            speedKmh = speed,
            rpm = obd.rpm.takeIf { obdLive },
            coolantC = obd.coolantTempC.takeIf { obdLive && it != 0 },
            fuelPct = McuReader.fuelPercent.value ?: obd.fuelLevelPct.takeIf { obdLive && it > 0 },
            rangeKm = McuReader.rangeKm.value,
            open = open,
            obdConnected = obdLive,
            imperial = units.imperial,
            speedUnit = units.speedUnit,
            fahrenheit = units.fahrenheit,
            clock12 = !units.clock24,
            // The display draws in its own colours: the second screen's background, not the dashboard's look.
            night = SecondScreenRules.background(SecondScreenStore.config.value, day = DashColors.Light) != ClusterBackground.LIGHT,
            accent = DashColors.Accent.toArgb().toLong() and 0xFFFFFFFFL,
            media = media.takeIf { it.hasMedia && it.title.isNotBlank() }?.let {
                ClusterState.Media(
                    title = it.title,
                    artist = it.artist,
                    playing = it.isPlaying,
                    positionMs = controller.positionMs(),
                    durationMs = it.durationMs
                )
            },
            nav = nav.takeIf { it.active && it.instruction.isNotBlank() }?.let {
                ClusterState.Nav(instruction = it.instruction, distance = it.distance, eta = it.eta)
            },
            labels = labels(context, units)
        )
    }

    /** The words the display prints around these figures, in the driver's language like the door names above. */
    private fun labels(context: Context, units: UnitSystem) = ClusterState.Labels(
        // The display puts the names and the time where the %s is.
        open = context.getString(R.string.second_screen_label_open, "%s"),
        nothingPlaying = context.getString(R.string.info_nothing_playing),
        noRoute = context.getString(R.string.info_directions_no_route),
        arrive = context.getString(R.string.second_screen_label_arrive, "%s"),
        rpm = context.getString(R.string.info_unit_rpm),
        coolant = context.getString(R.string.orbit_sat_coolant),
        fuel = context.getString(R.string.second_screen_label_fuel),
        range = context.getString(R.string.second_screen_label_range, units.distanceUnit)
    )
}
