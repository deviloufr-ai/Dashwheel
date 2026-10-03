package com.openauto.dash

import android.os.SystemClock
import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AirlineSeatReclineNormal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/*
 * The seat belt reminder, from the car's own belt switch (the CAN box,
 * [CarBox]): the driver's belt unbuckled while the car moves. A small alert
 * in the chosen design ([AlertStyle]; never covering the screen while
 * driving), and said once each time it starts. Gone as soon as the belt is
 * buckled or the car stops.
 */

object BeltAlertOverlay {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var window: AlertWindow? = null

    /** The driver's belt is open while moving, as the car box tells it. */
    private val _unbuckledMoving = MutableStateFlow(false)

    val alert: StateFlow<Boolean> =
        combine(RomPopups.replaced, _unbuckledMoving, AlertPreview.belt) { replaced, unbuckled, preview ->
            preview || RomPopups.Kind.BELT in replaced && unbuckled
        }.stateIn(scope, SharingStarted.Eagerly, false)

    val style: StateFlow<AlertStyle?> =
        combine(alert, AlertStyleStore.styles, UnitSignals.projectionOnScreen) { on, styles, projected ->
            if (!on) null else if (projected) AlertStyle.PILL else styles.of(AlertKind.BELT)
        }.stateIn(scope, SharingStarted.Eagerly, null)

    fun start(context: Context) {
        if (started) return
        started = true
        val app = AppLanguage.wrap(context.applicationContext)
        scope.launch {
            var wasMoving = false
            CarBox.body.collect { body ->
                // The car box's own speed: it comes with the belt, from the same frames.
                val moving = isMoving(body?.speedKmh, wasMoving)
                wasMoving = moving
                _unbuckledMoving.value = beltReminder(body?.driverBeltUnfastened == true, moving)
            }
        }
        // The car box gone quiet mid-drive (its app stopped, the unit slept on):
        // nothing would ever say "buckled" or "stopped", and the alert stood for good.
        scope.launch {
            _unbuckledMoving.collectLatest { on ->
                if (!on) return@collectLatest
                while (SystemClock.elapsedRealtime() - CarBox.bodyAt < BODY_SILENT_MS) delay(5_000)
                _unbuckledMoving.value = false
            }
        }
        scope.launch {
            alert.collect { on ->
                if (on && AlertPreview.belt.value.not()) AlertVoice.sayBelt(app)
            }
        }
        scope.launch {
            style.collect { style ->
                val w = window ?: AlertWindow(app, "belt", AlertKind.BELT.cardAt.gravity).also { window = it }
                if (style != null && w.canShow()) w.show(style) { BeltAlert(style) } else window?.hide()
            }
        }
    }
}

/** No word from the car box for this long, moving: what it last said about the belt no longer holds. */
private const val BODY_SILENT_MS = 30_000L

/** Remind only on the move: buckling up while parked is nobody's business yet. */
internal fun beltReminder(driverUnbuckled: Boolean, moving: Boolean): Boolean = driverUnbuckled && moving

@Composable
private fun BeltAlert(style: AlertStyle) {
    val text = stringResource(R.string.car_belt_fasten)
    when (style) {
        AlertStyle.BANNER -> AlertSurface(AlertStyle.BANNER, tone = DashColors.Critical) {
            Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                AlertStripe(DashColors.Critical)
                Spacer(Modifier.width(14.dp))
                Icon(Icons.Filled.AirlineSeatReclineNormal, contentDescription = null, tint = DashColors.Critical, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(14.dp))
                Text(text, color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            }
        }
        AlertStyle.CARD -> AlertSurface(AlertStyle.CARD, tone = DashColors.Critical) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AirlineSeatReclineNormal, contentDescription = null, tint = DashColors.Critical, modifier = Modifier.size(40.dp))
                Spacer(Modifier.width(14.dp))
                Text(text, color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleLarge)
            }
        }
        else -> AlertSurface(AlertStyle.PILL, tone = DashColors.Critical) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AirlineSeatReclineNormal, contentDescription = null, tint = DashColors.Critical, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(text, color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** The launcher's own belt popup, for when the overlay window isn't allowed. */
@Composable
internal fun BeltAlertHost() {
    val style by BeltAlertOverlay.style.collectAsState()
    val s = style ?: return
    if (android.provider.Settings.canDrawOverlays(androidx.compose.ui.platform.LocalContext.current)) return
    AlertPopup(s, AlertKind.BELT.cardAt.alignment) { BeltAlert(s) }
}
