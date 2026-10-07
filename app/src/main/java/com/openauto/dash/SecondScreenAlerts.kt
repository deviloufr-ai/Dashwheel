package com.openauto.dash

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.openauto.dash.link.CallState
import com.openauto.dash.link.ClusterState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/*
 * The alert up on the dashboard, full screen on the second screen as well
 * (Settings, Second screen, "Alerts full screen"): the streamed cluster draws
 * it in Dashwheel's own design ([SecondScreenAlertLayer]), and a display that
 * draws its pages itself gets its words ([dataAlert]). One at a time, the most
 * urgent ([AlertKind.rank]). Not the volume bar, which comes up at every turn
 * of the knob, nor Gemini Live, a conversation; a call only while it rings.
 */
internal object SecondScreenAlerts {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The alerts that never take the second screen. */
    private val NEVER = setOf(AlertKind.VOLUME, AlertKind.GEMINI)

    /** The alert the second screen shows now, or null. */
    val shown: StateFlow<AlertKind?> =
        combine(AlertArbiter.wanted, PhoneCallOverlay.call, SecondScreenStore.config) { up, call, config ->
            if (!config.alertsFullScreen) return@combine null
            up.keys
                .filter { it !in NEVER }
                .filter { it != AlertKind.CALL || call?.phase == CallState.Phase.RINGING }
                .minByOrNull { it.rank }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * [kind]'s biggest design: its full screen, else its side panel, which then
     * fills the second screen, else its card in the middle of it.
     */
    fun styleFor(kind: AlertKind): AlertStyle = when {
        AlertStyle.FULL in kind.styles -> AlertStyle.FULL
        AlertStyle.PANEL in kind.styles -> AlertStyle.PANEL
        else -> AlertStyle.CARD
    }

    /** The shown alert in words, for a display that draws its pages itself. */
    fun dataAlert(context: Context): ClusterState.Alert? {
        val kind = shown.value ?: return null
        fun s(res: Int) = context.getString(res)
        return when (kind) {
            AlertKind.DOORS -> ClusterState.Alert(
                s(R.string.alert_kind_doors),
                DoorAlertOverlay.alert.value?.let { d ->
                    listOfNotNull(
                        R.string.vehicle_door_front_left.takeIf { d.frontLeft },
                        R.string.vehicle_door_front_right.takeIf { d.frontRight },
                        R.string.vehicle_door_rear_left.takeIf { d.rearLeft },
                        R.string.vehicle_door_rear_right.takeIf { d.rearRight },
                        R.string.vehicle_door_tailgate.takeIf { d.tailgate },
                        R.string.vehicle_door_bonnet.takeIf { d.bonnet }
                    ).joinToString(", ") { s(it) }
                }.orEmpty(),
                ClusterState.Alert.WARN
            )
            AlertKind.CALL -> ClusterState.Alert(
                s(R.string.alert_kind_call),
                PhoneCallOverlay.call.value?.let { it.name?.takeIf(String::isNotBlank) ?: it.number }.orEmpty()
            )
            AlertKind.BELT -> ClusterState.Alert(s(R.string.alert_kind_belt), s(R.string.car_belt_fasten), ClusterState.Alert.CRITICAL)
            AlertKind.TYRES -> ClusterState.Alert(
                s(R.string.alert_kind_tyres),
                Tyres.problems.value.entries.joinToString(", ") { (pos, problem) -> "${s(pos.labelRes)}: ${s(problem.labelRes)}" },
                ClusterState.Alert.CRITICAL
            )
            AlertKind.RADAR -> ClusterState.Alert(s(R.string.alert_kind_radar), level = ClusterState.Alert.WARN)
            AlertKind.AC -> ClusterState.Alert(s(R.string.alert_kind_ac))
            AlertKind.VOLUME, AlertKind.GEMINI -> null
        }
    }
}

/** Over the streamed cluster ([ClusterScreen]): the shown alert, full screen, fading in and out. */
@Composable
internal fun SecondScreenAlertLayer() {
    val live by SecondScreenAlerts.shown.collectAsState()
    val kind = rememberLast(live) ?: return
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = live != null
    AnimatedVisibility(visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
        val style = SecondScreenAlerts.styleFor(kind)
        // The full-screen design dims what is behind it itself; the others get the same dimming.
        Box(
            Modifier.fillMaxSize().then(
                if (style == AlertStyle.FULL) Modifier
                else Modifier.background(DashColors.Background.copy(alpha = if (DashColors.Light) 0.72f else 0.66f))
            ),
            contentAlignment = Alignment.Center
        ) {
            when (kind) {
                AlertKind.DOORS -> DoorAlertContent(style)
                AlertKind.CALL -> CallAlertContent(style)
                AlertKind.BELT -> BeltAlert(style)
                AlertKind.TYRES -> TyreAlertContent(style)
                AlertKind.RADAR -> RadarAlertContent(style)
                AlertKind.AC -> ClimateAlertContent(style)
                AlertKind.VOLUME, AlertKind.GEMINI -> Unit
            }
        }
    }
}
