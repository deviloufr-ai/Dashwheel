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
    private const val WORDS_MS = 30_000L

    fun run(context: Context, scope: CoroutineScope, page: StateFlow<ClusterPage>): Job = scope.launch {
        val throttle = ClusterThrottle()
        // The words in Dashwheel's language, one picked inside it included (the app's own
        // context stays in the unit's); looked up again now and then for a new choice.
        var words = AppLanguage.wrap(context)
        var wordsAt = System.currentTimeMillis()
        while (isActive) {
            if (System.currentTimeMillis() - wordsAt > WORDS_MS) {
                words = AppLanguage.wrap(context)
                wordsAt = System.currentTimeMillis()
            }
            val state = snapshot(words, page.value)
            // The clock and the playing position move on their own: they don't make a change.
            val key = state.copy(clock = 0, media = state.media?.copy(positionMs = 0))
            if (throttle.shouldSend(key, System.currentTimeMillis())) DisplayLink.send(state)
            delay(SAMPLE_MS)
        }
    }

    private fun snapshot(context: Context, page: ClusterPage): ClusterState {
        val display = (DisplayLink.state.value as? DisplayLinkState.Connected)?.display
        // A display that reads the words sent once (SecondScreenWords) needn't get them five times a second.
        val wordsOnce = display != null && SecondScreenRules.readsWordsOnce(display.protocol)
        val config = SecondScreenStore.config.value
        val obdLive = ObdBluetoothManager.connectionState.value == ObdConnectionState.CONNECTED || DemoMode.isOn
        val obd = ObdBluetoothManager.data.value
        // The OBD speed comes corrected already (ObdBluetoothManager), and the correction is for the car's
        // figure, not the GPS: corrected again here, a stopped car's 1 km/h of GPS drift read 9.
        val speed = if (obdLive) obd.speedKmh else LocationFeed.freshSpeedKmh.value
        val fuel = carFuelInfo(McuReader.fuelPercent.value, if (obdLive) obd.fuelLevelPct else 0, McuReader.rangeKm.value)
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
        // The navigation app's own turn; else the one on the shadow route (ShadowRoute), when there is one.
        val nav = NavDirections.state.value.takeIf { it.active && it.instruction.isNotBlank() } ?: ShadowRoute.turn.value ?: NavState()
        val units = Units.current.value
        return ClusterState(
            clock = System.currentTimeMillis(),
            page = page.name,
            speedKmh = speed,
            rpm = obd.rpm.takeIf { obdLive },
            coolantC = obd.coolantTempC.takeIf { obdLive && it != 0 },
            // As the dashboard's bar reads it (carFuelInfo): a car that sends 0 % with a range
            // left sends no level at all, and the level is worked out from the range.
            fuelPct = fuel?.percent,
            rangeKm = fuel?.rangeKm ?: McuReader.rangeKm.value,
            open = open,
            obdConnected = obdLive,
            imperial = units.imperial,
            alert = SecondScreenAlerts.dataAlert(context),
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
            labels = if (wordsOnce) null else SecondScreenWords.labels(context, units),
            design = config.design.name,
            // Only for the page that shows them: five times a second, the rest of the time, for nothing.
            measures = if (page == ClusterPage.OBD && obdLive) measures(context, obd, units) else emptyList(),
            // The turn as the navigation knows it (in-app), so the display needn't read the words.
            maneuver = nav.maneuver,
            // The page as laid out on the board, so the display draws the same widgets in the same places.
            layout = SecondScreenRules.layoutMessage(config.layoutFor(page)),
            // Where the car is, for the display's own map: rounded so a parked car sends nothing new.
            position = LocationFeed.location.value?.let { l ->
                ClusterState.Position(
                    Math.round(l.latitude * 1e5) / 1e5, Math.round(l.longitude * 1e5) / 1e5,
                    LocationFeed.headingDeg.value?.let { Math.round(it) }
                )
            },
            // How the display's own map is seen.
            mapView = ClusterState.MapView(config.mapTilted, config.mapTilt.name, config.mapDistance.name, config.mapBuildings)
        )
    }

    /** The Measures page's readings: those of the dashboard's "All readings" tile, in its words and units. */
    private fun measures(context: Context, d: ObdData, u: UnitSystem): List<ClusterState.Measure> = listOfNotNull(
        ClusterState.Measure(context.getString(R.string.info_unit_rpm), d.rpm.toString()),
        ClusterState.Measure(context.getString(R.string.vehicle_speed), u.speedText(d.speedKmh)),
        ClusterState.Measure(context.getString(R.string.vehicle_coolant), u.tempText(d.coolantTempC)),
        ClusterState.Measure(context.getString(R.string.vehicle_intake_air), u.tempText(d.intakeTempC)),
        ClusterState.Measure(context.getString(R.string.vehicle_throttle), "${d.throttlePct} %"),
        ClusterState.Measure(context.getString(R.string.vehicle_engine_load), "${d.engineLoadPct} %"),
        ClusterState.Measure(context.getString(R.string.vehicle_battery), String.format(java.util.Locale.getDefault(), "%.1f V", d.voltage)),
        d.fuelLevelPct.takeIf { it > 0 }?.let { ClusterState.Measure(context.getString(R.string.vehicle_fuel_level), "$it %") }
    )

}
