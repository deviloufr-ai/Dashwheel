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
        val speed = (if (obdLive) obd.speedKmh else LocationFeed.freshSpeedKmh.value)?.let(SpeedCorrection::corrected)
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
            night = !DashColors.Light,
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
            }
        )
    }
}
