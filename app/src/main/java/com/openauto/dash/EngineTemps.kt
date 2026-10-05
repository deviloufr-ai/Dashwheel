package com.openauto.dash

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** One reading: coolant and intake air in °C, null where the car gave none. */
internal data class TempSample(val at: Long, val coolantC: Int?, val intakeC: Int?)

/**
 * Coolant and intake air over the last [WINDOW_MS], a reading every
 * [EVERY_MS]: how fast the engine warms up, whether it runs hot in a jam.
 * Kept from the start so the graph has a history the moment it is shown.
 */
internal object EngineTemps {
    const val WINDOW_MS = 20 * 60_000L
    private const val EVERY_MS = 10_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    private val _samples = MutableStateFlow<List<TempSample>>(emptyList())
    val samples: StateFlow<List<TempSample>> = _samples

    fun start() {
        if (started) return
        started = true
        scope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                val sample = if (DemoMode.isOn) demo(now) else {
                    val connected = ObdBluetoothManager.connectionState.value == ObdConnectionState.CONNECTED
                    val d = ObdBluetoothManager.data.value
                    TempSample(now, d.coolantTempC.takeIf { connected && it != 0 }, d.intakeTempC.takeIf { connected && it != 0 })
                }
                _samples.value = (_samples.value + sample).filter { now - it.at <= WINDOW_MS }
                delay(EVERY_MS)
            }
        }
    }

    /** A cold start warming to 90 °C. */
    private fun demo(now: Long): TempSample {
        val t = (_samples.value.size * EVERY_MS / 1000.0)
        return TempSample(now, (20 + 70 * (1 - Math.exp(-t / 400))).toInt(), (18 + 10 * (1 - Math.exp(-t / 600))).toInt())
    }
}

private val CoolantColor = Color(0xFFFF7043)
private val IntakeColor = Color(0xFF4FC3F7)

@Composable
internal fun EngineTempsCard(modifier: Modifier = Modifier) {
    val samples by EngineTemps.samples.collectAsState()
    val units by Units.current.collectAsState()
    val last = samples.lastOrNull()
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(DashSpace.Lg), verticalArrangement = Arrangement.spacedBy(DashSpace.Sm)) {
            TileHeader(stringResource(R.string.widgets_temps))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DashSpace.Lg)) {
                Text(
                    stringResource(R.string.widgets_temps_coolant, last?.coolantC?.let { units.tempText(it) } ?: "--"),
                    color = CoolantColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall
                )
                Text(
                    stringResource(R.string.widgets_temps_intake, last?.intakeC?.let { units.tempText(it) } ?: "--"),
                    color = IntakeColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall
                )
            }
            if (samples.none { it.coolantC != null || it.intakeC != null }) {
                Text(stringResource(R.string.widgets_temps_none), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            val grid = DashColors.Muted.copy(alpha = 0.3f)
            Canvas(modifier = Modifier.fillMaxWidth().weight(1f)) {
                val now = samples.last().at
                val all = samples.flatMap { listOfNotNull(it.coolantC, it.intakeC) }
                val lo = minOf(0, all.min()).toFloat()
                val hi = maxOf(110, all.max()).toFloat()
                fun x(at: Long) = size.width * (1f - (now - at).toFloat() / EngineTemps.WINDOW_MS)
                fun y(c: Int) = size.height * (1f - (c - lo) / (hi - lo))
                // 90 °C, where a warm engine sits.
                drawLine(grid, Offset(0f, y(90)), Offset(size.width, y(90)), strokeWidth = 2f)
                fun line(pick: (TempSample) -> Int?, color: Color) {
                    val path = Path()
                    var open = false
                    samples.forEach { s ->
                        val v = pick(s)
                        if (v == null) { open = false; return@forEach }
                        if (open) path.lineTo(x(s.at), y(v)) else path.moveTo(x(s.at), y(v))
                        open = true
                    }
                    drawPath(path, color, style = Stroke(width = 5f, cap = StrokeCap.Round))
                }
                line({ it.intakeC }, IntakeColor)
                line({ it.coolantC }, CoolantColor)
            }
        }
    }
}

@Composable
internal fun engineTempsFace(): WidgetFace {
    val samples by EngineTemps.samples.collectAsState()
    val units by Units.current.collectAsState()
    val last = samples.lastOrNull()
    val coolant = last?.coolantC
    return WidgetFace(
        icon = Icons.Filled.Thermostat,
        title = BuiltinKind.ENGINE_TEMPS.label,
        value = coolant?.let { units.temp(it).toString() } ?: "--",
        unit = units.tempUnit,
        caption = stringResource(R.string.widgets_temps_intake, last?.intakeC?.let { units.tempText(it) } ?: "--"),
        fraction = coolant?.let { (it / 120f).coerceIn(0f, 1f) },
        alert = coolant != null && coolant >= 105
    )
}
