package com.openauto.dash

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Units and clock ([Units]): one switch each for speed and distance,
 * temperature, fuel economy and the clock. Auto is the region's and the
 * unit's own choice, which the hint spells out.
 */
@Composable
internal fun UnitsSetting(heading: Boolean = true) {
    val context = LocalContext.current
    val chosen by Units.choices.collectAsState()
    val auto = Units.auto()
    val autoTitle = stringResource(R.string.units_auto)
    if (heading) SettingsSection(stringResource(R.string.units_title))

    UnitLabel(stringResource(R.string.units_speed))
    SegmentedSwitch(
        options = DistanceChoice.entries,
        chosen = chosen.distance,
        icon = { autoOr(it == DistanceChoice.AUTO, Icons.Filled.Speed) },
        title = {
            when (it) {
                DistanceChoice.AUTO -> autoTitle
                DistanceChoice.METRIC -> "km/h"
                DistanceChoice.IMPERIAL -> "mph"
            }
        },
        onChoose = { Units.save(context, chosen.copy(distance = it)) }
    )

    UnitLabel(stringResource(R.string.units_temp))
    SegmentedSwitch(
        options = TempChoice.entries,
        chosen = chosen.temp,
        icon = { autoOr(it == TempChoice.AUTO, Icons.Filled.Thermostat) },
        title = {
            when (it) {
                TempChoice.AUTO -> autoTitle
                TempChoice.CELSIUS -> "°C"
                TempChoice.FAHRENHEIT -> "°F"
            }
        },
        onChoose = { Units.save(context, chosen.copy(temp = it)) }
    )

    UnitLabel(stringResource(R.string.units_economy))
    SegmentedSwitch(
        options = EconomyChoice.entries,
        chosen = chosen.economy,
        icon = { autoOr(it == EconomyChoice.AUTO, Icons.Filled.LocalGasStation) },
        title = {
            when (it) {
                EconomyChoice.AUTO -> autoTitle
                EconomyChoice.L_PER_100KM -> "L/100 km"
                EconomyChoice.MPG_UK -> "mpg UK"
                EconomyChoice.MPG_US -> "mpg US"
                EconomyChoice.KM_PER_L -> "km/L"
            }
        },
        onChoose = { Units.save(context, chosen.copy(economy = it)) }
    )

    UnitLabel(stringResource(R.string.units_clock))
    SegmentedSwitch(
        options = ClockChoice.entries,
        chosen = chosen.clock,
        icon = { autoOr(it == ClockChoice.AUTO, Icons.Filled.Schedule) },
        title = {
            when (it) {
                ClockChoice.AUTO -> autoTitle
                ClockChoice.H24 -> "24 h"
                ClockChoice.H12 -> "12 h"
            }
        },
        onChoose = { Units.save(context, chosen.copy(clock = it)) }
    )
    SwitchHint(stringResource(R.string.units_auto_hint, unitsSummary(auto)))
    Spacer(Modifier.height(20.dp))
}

private fun autoOr(auto: Boolean, icon: ImageVector): ImageVector = if (auto) Icons.Filled.Public else icon

/** "km/h, °C, L/100 km, 24 h": the units in [auto], as Auto gives them or as chosen. */
internal fun unitsSummary(auto: UnitSystem): String {
    val economy = when (auto.economy) {
        Economy.MPG_UK -> "mpg UK"
        Economy.MPG_US -> "mpg US"
        else -> auto.economyUnit
    }
    return listOf(auto.speedUnit, auto.tempUnit, economy, if (auto.clock24) "24 h" else "12 h").joinToString(", ")
}

/** What the switch under it sets. */
@Composable
private fun UnitLabel(text: String) {
    Spacer(Modifier.height(8.dp))
    Text(
        text,
        color = DashColors.TextSecondary,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(start = 12.dp, bottom = 6.dp)
    )
}
