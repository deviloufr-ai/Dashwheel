package com.openauto.dash

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Traffic
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openauto.dash.link.CarTelemetry
import com.openauto.dash.link.KeepClip
import com.openauto.dash.link.RoadScene
import com.openauto.dash.roadwatch.RoadRules
import com.openauto.dash.roadwatch.RoadWarning
import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.Vehicle
import com.openauto.dash.vehicle.speedKmhFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * Road watch on the car: the phone behind the windscreen sends what its
 * camera reads of the road ahead ([RoadScene], from the companion), and the
 * car, which knows its own speed and its indicators, decides what to warn
 * about ([RoadRules]): the car ahead coming up fast, a wheel on the lane's
 * line, following too close, the traffic moving off. Shown in the driver's
 * chosen design ([AlertKind.ROAD]; never covering the screen, it is shown
 * while driving), with a tone for the urgent ones and a few words for the
 * others when the driver wants them said.
 */
object RoadWatch {
    private const val PREFS = "road_watch"
    private const val KEY_LANES = "lanes"
    private const val KEY_MOVED_OFF = "moved_off"
    /** No scene for this long: the phone stopped watching (closed, dropped, the link gone). */
    private const val STALE_MS = 1_500L
    /** The car's own word back to the phone this often, for its recording of the road. */
    private const val TELEMETRY_MS = 500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val rules = RoadRules()
    private var started = false
    private var sceneAt = 0L
    private var telemetryAt = 0L
    private var carKmh: Int? = null

    private val _live = MutableStateFlow(false)
    /** The phone is watching the road and sending what it sees. */
    val live: StateFlow<Boolean> = _live

    private val _warning = MutableStateFlow<RoadWarning?>(null)
    val warning: StateFlow<RoadWarning?> = _warning

    private val _lanes = MutableStateFlow(true)
    /** The lane warnings: some drivers want only the car ahead watched. */
    val lanes: StateFlow<Boolean> = _lanes

    private val _movedOff = MutableStateFlow(true)
    /** The word when the traffic ahead moves off. */
    val movedOff: StateFlow<Boolean> = _movedOff

    fun start(context: Context) {
        if (started) return
        started = true
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _lanes.value = p.getBoolean(KEY_LANES, true)
        _movedOff.value = p.getBoolean(KEY_MOVED_OFF, true)
        rules.lanes = _lanes.value
        rules.movedOff = _movedOff.value
        scope.launch { carSpeed().collect { carKmh = it } }
        // The hazard lights going on while the phone watches: a moment for its recorder to keep.
        scope.launch {
            Vehicle.bus.value(K.LIGHTS).map { CarLight.HAZARD in it.orEmpty() }.distinctUntilChanged().collect { on ->
                if (on && _live.value) PhoneLink.send(KeepClip("hazard"))
            }
        }
        // The scenes stop coming when the phone stops: so do the warnings.
        scope.launch {
            _live.collectLatest { on ->
                while (on) {
                    delay(500)
                    if (SystemClock.elapsedRealtime() - sceneAt > STALE_MS) {
                        rules.clear()
                        _warning.value = null
                        _live.value = false
                    }
                }
            }
        }
    }

    /** A scene from the phone, on the link's thread. */
    fun feed(scene: RoadScene) {
        scope.launch {
            val now = SystemClock.elapsedRealtime()
            sceneAt = now
            _live.value = true
            scene.signKmh?.let { SpeedLimit.sign(it) }
            if (scene.signEnd) SpeedLimit.endSign()
            // The car's own speed when it gives one (the OBD, the CAN box), else the phone's GPS in the scene.
            _warning.value = rules.update(scene, carKmh?.toFloat(), indicating(), now)
            // And the car's word back to the phone, for the picture and the notes of its recording.
            if (now - telemetryAt >= TELEMETRY_MS) {
                telemetryAt = now
                val lights = Vehicle.bus.now(K.LIGHTS).orEmpty()
                val turn = Vehicle.bus.now(K.INDICATORS)
                PhoneLink.send(
                    CarTelemetry(
                        speedKmh = carKmh,
                        rpm = Vehicle.bus.now(K.RPM)?.roundToInt(),
                        turnLeft = turn?.left == true,
                        turnRight = turn?.right == true,
                        hazard = CarLight.HAZARD in lights,
                        lights = CarLight.DIPPED in lights || CarLight.MAIN_BEAM in lights
                    )
                )
            }
        }
    }

    fun setLanes(context: Context, on: Boolean) {
        _lanes.value = on
        rules.lanes = on
        save(context, KEY_LANES, on)
    }

    fun setMovedOff(context: Context, on: Boolean) {
        _movedOff.value = on
        rules.movedOff = on
        save(context, KEY_MOVED_OFF, on)
    }

    private fun save(context: Context, key: String, on: Boolean) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(key, on).apply()

    /** An indicator, or the hazards, on: from the CAN box, which says so on the QF units. */
    private fun indicating(): Boolean =
        Vehicle.bus.now(K.INDICATORS)?.let { it.left || it.right } == true || CarLight.HAZARD in Vehicle.bus.now(K.LIGHTS).orEmpty()

    /** The car's own speed, null when nothing in the car says it (the GPS is the phone's job here). */
    private fun carSpeed(): Flow<Int?> = speedKmhFlow(K.VEHICLE_SPEED)
}

object RoadAlertOverlay {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var window: AlertWindow? = null
    private var tones: ToneGenerator? = null

    /** The warning on screen: the real one, or "Try it"'s. */
    val shown: StateFlow<RoadWarning?> =
        combine(RoadWatch.warning, AlertPreview.road) { real, preview -> preview ?: real }
            .stateIn(scope, SharingStarted.Eagerly, null)

    val style: StateFlow<AlertStyle?> =
        combine(shown, AlertStyleStore.styles, UnitSignals.projectionOnScreen) { warning, styles, projected ->
            if (warning == null) null else if (projected) AlertStyle.PILL else styles.of(AlertKind.ROAD)
        }.arbitrated(AlertKind.ROAD).stateIn(scope, SharingStarted.Eagerly, null)

    fun start(context: Context) {
        if (started) return
        started = true
        val app = AppLanguage.wrap(context.applicationContext)
        RoadWatch.start(app)
        scope.launch {
            var before: RoadWarning? = null
            RoadWatch.warning.collect { warning ->
                if (warning != null && warning != before) sound(app, warning)
                before = warning
            }
        }
        scope.launch {
            style.collect { style ->
                val w = window ?: AlertWindow(app, "road", AlertKind.ROAD.cardAt.gravity, rank = AlertKind.ROAD.rank).also { window = it }
                if (style != null && w.canShow()) w.show(style) { RoadAlertContent(style) } else window?.hide()
            }
        }
    }

    /**
     * How a warning is heard: a tone for what can't wait (the car ahead, the
     * lane), words for the rest when the driver has them said.
     */
    fun sound(context: Context, warning: RoadWarning, force: Boolean = false) {
        when (warning) {
            RoadWarning.COLLISION -> tone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1_200)
            RoadWarning.LANE_LEFT, RoadWarning.LANE_RIGHT -> tone(ToneGenerator.TONE_PROP_BEEP2, 300)
            RoadWarning.TOO_CLOSE, RoadWarning.MOVED_OFF ->
                if (!AlertVoice.sayRoad(context, warning, force)) tone(ToneGenerator.TONE_PROP_ACK, 250)
        }
    }

    private fun tone(type: Int, ms: Int) {
        val t = tones ?: runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 100) }.getOrNull()?.also { tones = it } ?: return
        runCatching { t.startTone(type, ms) }
    }
}

/** What each warning says on screen, and out loud. */
@get:StringRes
internal val RoadWarning.text: Int
    get() = when (this) {
        RoadWarning.COLLISION -> R.string.road_warn_collision
        RoadWarning.LANE_LEFT -> R.string.road_warn_lane_left
        RoadWarning.LANE_RIGHT -> R.string.road_warn_lane_right
        RoadWarning.TOO_CLOSE -> R.string.road_warn_close
        RoadWarning.MOVED_OFF -> R.string.road_warn_moved_off
    }

private val RoadWarning.icon: ImageVector
    get() = when (this) {
        RoadWarning.COLLISION -> Icons.Filled.Warning
        RoadWarning.LANE_LEFT, RoadWarning.LANE_RIGHT -> Icons.Filled.SwapHoriz
        RoadWarning.TOO_CLOSE -> Icons.Filled.DirectionsCar
        RoadWarning.MOVED_OFF -> Icons.Filled.Traffic
    }

/** The collision in red, the rest amber: the driver sees at a glance which one must be acted on now. */
@Composable
private fun RoadWarning.tone(): Color = if (this == RoadWarning.COLLISION) DashColors.Critical else DashColors.Warning

/** The warning up now, in [style]; the last one stays drawn while it animates out. */
@Composable
internal fun RoadAlertContent(style: AlertStyle) {
    val live by RoadAlertOverlay.shown.collectAsState()
    val warning = rememberLast(live) ?: return
    RoadAlert(style, warning)
}

@Composable
internal fun RoadAlert(style: AlertStyle, warning: RoadWarning) {
    val text = stringResource(warning.text)
    val tint = warning.tone()
    when (style) {
        AlertStyle.BANNER -> AlertSurface(AlertStyle.BANNER, tone = tint) {
            Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                AlertStripe(tint)
                Spacer(Modifier.width(14.dp))
                Icon(warning.icon, contentDescription = null, tint = tint, modifier = Modifier.size(34.dp))
                Spacer(Modifier.width(14.dp))
                Text(text, color = DashColors.TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
            }
        }
        AlertStyle.CARD -> AlertSurface(AlertStyle.CARD, tone = tint) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(warning.icon, contentDescription = null, tint = tint, modifier = Modifier.size(44.dp))
                Spacer(Modifier.width(14.dp))
                Text(text, color = DashColors.TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
            }
        }
        else -> AlertSurface(AlertStyle.PILL, tone = tint) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(warning.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Text(text, color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** The launcher's own Road watch popup, for when the overlay window isn't allowed. */
@Composable
internal fun RoadAlertHost() {
    val style by RoadAlertOverlay.style.collectAsState()
    val s = style ?: return
    if (android.provider.Settings.canDrawOverlays(androidx.compose.ui.platform.LocalContext.current)) return
    AlertPopup(s, AlertKind.ROAD.cardAt.alignment) { RoadAlertContent(s) }
}
