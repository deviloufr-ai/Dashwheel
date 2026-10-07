package com.openauto.dash

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openauto.dash.link.FuelFill
import com.openauto.dash.link.FuelFills
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** What the car burns, in money: the consumption at the last price paid; null until both are known. */
internal fun costPer100(fills: List<FuelFill>): Double? {
    val l100 = FuelFills.litersPer100(fills) ?: return null
    val price = fills.firstNotNullOfOrNull { it.pricePerL } ?: return null
    return l100 * price
}

private fun money(v: Double, currency: String?): String = String.format(Locale.getDefault(), "%.2f %s", v, currency ?: "€").trim()

/**
 * The refuels: the last one, what the car really uses and what 100 km cost.
 * Refuels log themselves from the fuel gauge; Add fill covers the ones it
 * missed, with two steppers (litres, price per litre) rather than a keyboard.
 */
@Composable
internal fun FuelLogCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val fills by FuelLog.fills.collectAsState()
    val units by Units.current.collectAsState()
    var adding by remember { mutableStateOf(false) }
    // The steppers are an entry task: parked only, and put away as soon as the car moves.
    val lock = LocalDriveLock.current
    if (adding) ParkedOnly { adding = false }
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(DashSpace.Lg), verticalArrangement = Arrangement.SpaceBetween) {
            TileHeader(stringResource(R.string.widgets_fuel)) {
                IconButton(onClick = { if (adding) adding = false else lock.whenParked { adding = true } }) {
                    Icon(
                        if (adding) Icons.Filled.Close else Icons.Filled.Add,
                        contentDescription = stringResource(R.string.widgets_fuel_add), tint = DashColors.Accent
                    )
                }
            }
            if (adding) {
                AddFill(lastPrice = FuelLog.lastPrice() ?: CarProfileStore.current.fuelPrice) { liters, price ->
                    FuelLog.addManual(context, liters, price)
                    adding = false
                }
                return@Column
            }
            val last = fills.firstOrNull()
            if (last == null) {
                Text(stringResource(R.string.widgets_fuel_none), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            Text(
                stringResource(
                    R.string.widgets_fuel_last,
                    String.format(Locale.getDefault(), "%.1f", last.liters),
                    DateFormat.getDateInstance(DateFormat.SHORT).format(Date(last.at))
                ),
                color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(units.economyUnit, color = DashColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
                    Text(
                        FuelFills.litersPer100(fills)?.let { units.economy(it) }?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "--",
                        color = DashColors.TextPrimary, fontWeight = FontWeight.Black, style = MaterialTheme.typography.headlineSmall
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(stringResource(R.string.widgets_fuel_per100), color = DashColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
                    Text(
                        costPer100(fills)?.let { money(it, last.currency) } ?: "--",
                        color = DashColors.TextPrimary, fontWeight = FontWeight.Black, style = MaterialTheme.typography.headlineSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun AddFill(lastPrice: Double, onSave: (Double, Double) -> Unit) {
    var liters by remember { mutableIntStateOf(40) }
    var cents by remember { mutableDoubleStateOf(Math.round(lastPrice * 100).toDouble()) }
    Column(verticalArrangement = Arrangement.spacedBy(DashSpace.Sm)) {
        Stepper("$liters L", onMinus = { liters = (liters - 5).coerceAtLeast(5) }, onPlus = { liters = (liters + 5).coerceAtMost(120) })
        Stepper(
            String.format(Locale.getDefault(), "%.2f / L", cents / 100),
            onMinus = { cents = (cents - 1).coerceAtLeast(50.0) }, onPlus = { cents = cents + 1 }
        )
        StepButton(stringResource(R.string.widgets_fuel_save), Modifier.fillMaxWidth(), primary = true) { onSave(liters.toDouble(), cents / 100) }
    }
}

@Composable
private fun Stepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DashSpace.Sm)) {
        StepButton("-", Modifier.width(DashSize.Touch), onClick = onMinus)
        Text(value, color = DashColors.TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        StepButton("+", Modifier.width(DashSize.Touch), onClick = onPlus)
    }
}

@Composable
private fun StepButton(label: String, modifier: Modifier, primary: Boolean = false, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    Box(
        modifier = modifier.height(40.dp).clip(DashShape.Medium).background(if (primary) DashColors.Accent else DashColors.CardHi)
            .clickable(role = Role.Button) { tap(); onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (primary) DashColors.OnAccent else DashColors.TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
internal fun fuelLogFace(): WidgetFace {
    val fills by FuelLog.fills.collectAsState()
    val units by Units.current.collectAsState()
    val last = fills.firstOrNull()
    return WidgetFace(
        icon = Icons.Filled.LocalGasStation,
        title = BuiltinKind.FUEL_LOG.label,
        value = FuelFills.litersPer100(fills)?.let { units.economy(it) }?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "--",
        unit = units.economyUnit,
        caption = last?.let {
            stringResource(R.string.widgets_fuel_last, String.format(Locale.getDefault(), "%.1f", it.liters), DateFormat.getDateInstance(DateFormat.SHORT).format(Date(it.at)))
        } ?: stringResource(R.string.widgets_fuel_none),
        stats = listOfNotNull(costPer100(fills)?.let { FaceStat(stringResource(R.string.widgets_fuel_per100), money(it, last?.currency)) })
    )
}
