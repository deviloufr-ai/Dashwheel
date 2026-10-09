package com.openauto.dash

import android.content.Context
import com.openauto.dash.link.ClusterState
import com.openauto.dash.link.DisplayWords
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * The second screen's own lines (waiting, no signal, the pairing hint) in
 * Dashwheel's language, sent when a display links and again when the language
 * changes. The display keeps them for its next start ([DisplayWords]).
 */
internal object SecondScreenWords {
    /** How often a language picked meanwhile is noticed (the picker restarts the screens, not this). */
    private const val CHECK_MS = 30_000L

    fun start(scope: CoroutineScope, context: Context) {
        val app = context.applicationContext
        val checks = flow { while (true) { emit(Unit); delay(CHECK_MS) } }
        scope.launch {
            // The units too: "km range" becomes "mi range" when the driver switches.
            combine(DisplayLink.state, checks, Units.current) { link, _, units ->
                if (link is DisplayLinkState.Connected) words(app, units) else null
            }.distinctUntilChanged().collect { words -> words?.let(DisplayLink::send) }
        }
    }

    /** The words the display prints around the cluster's figures, in the driver's language and units. */
    fun labels(context: Context, units: UnitSystem) = ClusterState.Labels(
        // The display puts the names and the time where the %s is.
        open = context.getString(R.string.second_screen_label_open, "%s"),
        nothingPlaying = context.getString(R.string.info_nothing_playing),
        noRoute = context.getString(R.string.info_directions_no_route),
        arrive = context.getString(R.string.second_screen_label_arrive, "%s"),
        rpm = context.getString(R.string.info_unit_rpm),
        coolant = context.getString(R.string.orbit_sat_coolant),
        fuel = context.getString(R.string.second_screen_label_fuel),
        range = context.getString(R.string.second_screen_label_range, units.distanceUnit),
        measuresNone = context.getString(R.string.car_waiting_obd),
        noPosition = context.getString(R.string.second_screen_label_no_position),
        noTiles = context.getString(R.string.second_screen_label_no_tiles)
    )

    /** The lines in the language Dashwheel writes in, a language picked inside Dashwheel included. */
    private fun words(app: Context, units: UnitSystem): DisplayWords {
        val wrapped = AppLanguage.wrap(app)
        val res = wrapped.resources
        return DisplayWords(
            language = res.configuration.locales[0].language,
            waiting = res.getString(R.string.second_screen_pi_waiting),
            noSignal = res.getString(R.string.second_screen_pi_no_signal),
            connected = res.getString(R.string.second_screen_pi_connected),
            scanApp = res.getString(R.string.second_screen_pi_scan),
            labels = labels(wrapped, units)
        )
    }
}
