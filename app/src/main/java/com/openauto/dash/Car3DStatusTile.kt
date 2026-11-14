package com.openauto.dash

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The 3D car widget: the driver's own car in 3D with the car's problems on
 * it, the whole tile given to the car. The same problems, numbered the same
 * way, as the Car problems tile ([ObdDtcCard], [numberedMapProblems]); it
 * turns to the most serious one, a chip turns it to another, a finger turns
 * it while parked. A pill in the corner says the verdict and opens the full
 * Car problems sheet ([ObdDialog]). Without a 3D model (no car pack with one,
 * or no GPU for it) the car's pictures stand in ([CarProblemsMap]), else a
 * word on where to get one.
 */
@Composable
internal fun Car3DStatusCard(
    connection: ObdConnectionState,
    onConnect: () -> Unit,
    onPickDevice: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val ai by AiMechanic.state.collectAsState()
    val codes = ai.codes
    val diagnosis = ai.diagnosis
    // As on the Car problems tile: the engine's own warning says no more than its codes once they are read.
    val carMessages = rememberCarMessages().let { all ->
        if (codes.isNullOrEmpty()) all else all.filter { it.code !in PsaWarnings.engineLamp }
    }
    val messageTexts = carMessages.map { carMessageText(it.code) }
    val codeSerious = if (diagnosis?.severity == Severity.STOP) 2 else 1
    val codeNames = codes.orEmpty().map { c ->
        (diagnosis?.codes?.firstOrNull { it.code == c }?.meaning?.ifBlank { null } ?: ObdCodes.describe(c).localizedTitle()) + " · " + c
    }
    val problems = remember(carMessages, messageTexts, codes, diagnosis, codeNames) {
        numberedMapProblems(carMessages, messageTexts, codes.orEmpty(), codeNames, codeSerious)
    }
    val lamp by ObdBluetoothManager.lamp.collectAsState()
    val verdict = carVerdict(carMessages.map { it.level }, codes, diagnosis?.severity, lamp?.on == true)
    val count = carMessages.count { it.level != PsaWarnings.Level.INFO } + codes.orEmpty().size

    val car by CarProfileStore.profile.collectAsState()
    val model = rememberCar3DModel()
    // A finger turns the car only while parked, as on the Car problems tile.
    val lockWhileMoving = remember(context) { DriveLockStore.load(context) }
    val moving by rememberMoving(lockWhileMoving, DemoMode.isOn)
    // The problem the car is turned to, by number (0: the whole car); at first the most serious one.
    var picked by remember { mutableStateOf<Int?>(null) }
    val turnedTo = picked?.takeIf { p -> p == 0 || problems.any { it.number == p } }
        ?: problems.maxByOrNull { it.serious }?.number ?: 0
    var sheet by remember { mutableStateOf(false) }

    Card(modifier = modifier) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // The chips down the side and the pill need some room; a small tile is the car alone.
            val roomy = maxWidth >= 220.dp && maxHeight >= 160.dp
            when {
                model != null -> {
                    val in3d = remember(problems, car.driverOnRight) {
                        problems.filter { it.number > 0 }
                            .map { Problem3D(it.number, Car3DSpots.of(it.spot, car.driverOnRight), it.label, it.serious) }
                    }
                    Car3DProblems(
                        model, in3d, in3d.firstOrNull { it.number == turnedTo },
                        interactive = !moving,
                        onPick = { picked = it ?: 0 },
                        modifier = Modifier.fillMaxSize(),
                        chips = roomy && in3d.isNotEmpty()
                    )
                }
                problems.isNotEmpty() -> CarProblemsMap(
                    problems,
                    Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = if (roomy) 56.dp else 12.dp),
                    compact = !roomy
                )
                else -> NoCarModel(Modifier.fillMaxSize().padding(16.dp))
            }
            if (roomy) {
                VerdictPill(
                    verdict, count,
                    Modifier.align(Alignment.BottomStart).padding(8.dp).widthIn(max = maxWidth * 0.62f)
                ) { sheet = true }
            }
        }
    }
    if (sheet) ObdDialog(connection, onConnect, onPickDevice) { sheet = false }
}

/** The verdict in a corner of the car: how many problems and whether to drive on; a tap opens the full list. */
@Composable
private fun VerdictPill(verdict: CarVerdict, count: Int, modifier: Modifier, onOpen: () -> Unit) {
    val colour = verdict.colour()
    Row(
        modifier
            .clip(DashShape.Small)
            .background(DashColors.Card.copy(alpha = 0.85f))
            .border(1.dp, colour.copy(alpha = 0.6f), DashShape.Small)
            .clickable(onClick = onOpen)
            .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        if (count > 0) {
            Text(count.toString(), color = colour, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            stringResource(verdict.titleRes),
            color = DashColors.TextPrimary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Icon(Icons.Filled.ChevronRight, contentDescription = stringResource(R.string.car3d_details), tint = DashColors.Accent, modifier = Modifier.size(20.dp))
    }
}

/** No 3D car and no problem with a place on the car: say where the 3D car comes from. */
@Composable
private fun NoCarModel(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.ViewInAr, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(36.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.car3d_tile_no_model, stringResource(R.string.mycar_title)),
                color = DashColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }
    }
}
