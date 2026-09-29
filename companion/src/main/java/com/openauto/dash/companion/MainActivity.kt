package com.openauto.dash.companion

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.openauto.dash.link.DriveSummary
import com.openauto.dash.link.PairingOffer
import com.openauto.dash.link.TypeText
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The companion's only screen: is the car connected, what still needs
 * allowing, and which cars are paired. Pairing is scanning the code the
 * launcher shows (or opening its dashwheel://pair link), then confirming.
 */
/** The companion's own download, which the launcher's pairing screen also shows as a QR code. */
private const val COMPANION_APK = "dashwheel-companion.apk"

class MainActivity : ComponentActivity() {
    /** Bumped on every resume so the checklist re-reads what the driver just allowed. */
    private var resumes by mutableIntStateOf(0)
    private var offer by mutableStateOf<PairingOffer?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        PairedUnits.load(this)
        CarSpot.load(this)
        DriveJournal.load(this)
        takeOffer(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF5B8DEF), secondary = Color(0xFF2DD4BF), tertiary = Color(0xFFF5B942))) {
                CompanionScreen(
                    resumes = resumes,
                    offer = offer,
                    onScanned = { text ->
                        val parsed = PairingOffer.parse(text)
                        if (parsed == null) explainWrongCode(text)
                        offer = parsed
                    },
                    onOfferDone = { offer = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeOffer(intent)
    }

    override fun onResume() {
        super.onResume()
        resumes++
        LinkService.sync(this)
        // Back from the system settings, maybe with contacts or the calendar just allowed.
        PhoneLists.recheck()
    }

    /** The launcher shows two codes; the download one is the easy one to scan by mistake. */
    private fun explainWrongCode(text: String) {
        val message = if (text.contains(COMPANION_APK, ignoreCase = true)) {
            getString(R.string.scanned_download_code)
        } else {
            getString(R.string.invalid_code_read, text.trim().take(60))
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun takeOffer(intent: Intent?) {
        val data = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW) return
        offer = PairingOffer.parse(data.toString())
        if (offer == null) explainWrongCode(data.toString())
        // Handled once: a rotation must not ask again.
        setIntent(Intent(this, MainActivity::class.java))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompanionScreen(resumes: Int, offer: PairingOffer?, onScanned: (String) -> Unit, onOfferDone: () -> Unit) {
    val context = LocalContext.current
    val units by PairedUnits.units.collectAsState()
    val state by LinkServer.state.collectAsState()
    val drives by DriveJournal.drives.collectAsState()
    var enabled by remember { mutableStateOf(PairedUnits.isEnabled(context)) }
    var removing by remember { mutableStateOf<PairedUnit?>(null) }
    val scan = rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let(onScanned) }
    val scanPrompt = stringResource(R.string.pair_hint)
    val startScan = {
        scan.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(scanPrompt)
                .setBeepEnabled(false)
                .setOrientationLocked(false)
        )
    }
    val setup = rememberSetupChecks(resumes)
    var setupOpen by rememberSaveable { mutableStateOf(false) }
    val paired = units.isNotEmpty()
    val connected = state is LinkState.Connected

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                StatusCard(
                    look = when {
                        !paired -> StatusLook.NO_CAR
                        !enabled -> StatusLook.OFF
                        connected -> StatusLook.CONNECTED
                        state == LinkState.Unavailable -> StatusLook.UNAVAILABLE
                        else -> StatusLook.WAITING
                    },
                    unitName = (state as? LinkState.Connected)?.unitName.orEmpty(),
                    enabled = enabled,
                    canToggle = paired,
                    onToggle = {
                        enabled = it
                        PairedUnits.setEnabled(context, it)
                        LinkService.sync(context)
                    },
                    onPair = startScan
                )
            }
            if (connected) item { KeyboardCard() }
            // Something still to allow comes before the drives: it is why the car shows less.
            val setupFirst = setup.missing > 0 || !paired
            if (setupFirst) setupSection(setup, open = true, onToggle = null)
            if (paired) item { CarSpotCard() }
            if (paired) {
                item { SectionTitle(stringResource(R.string.drives_title)) }
                val latest = drives.firstOrNull()
                if (latest == null) {
                    item { HintCard(stringResource(R.string.drives_none)) }
                } else {
                    // "Under way" only while the car is linked: once it is gone, the drive it reported last is simply the last one.
                    item { LatestDriveCard(latest, underWay = latest.ongoing && connected) }
                    items(drives.drop(1).take(PAST_DRIVES_SHOWN), key = { it.startedAt }) { DriveRow(it) }
                }
            }
            if (!setupFirst) setupSection(setup, open = setupOpen, onToggle = { setupOpen = !setupOpen })
            if (paired) {
                item { SectionTitle(stringResource(R.string.cars_title)) }
                items(units, key = { it.id }) { unit ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.DirectionsCar, contentDescription = null)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(unit.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    stringResource(R.string.paired_since, DateFormat.getDateInstance().format(Date(unit.pairedAt))),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            TextButton(onClick = { removing = unit }) { Text(stringResource(R.string.remove)) }
                        }
                    }
                }
                item {
                    OutlinedButton(onClick = startScan, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.pair_another))
                    }
                }
            }
        }
    }

    if (offer != null) {
        AlertDialog(
            onDismissRequest = onOfferDone,
            icon = { Icon(Icons.Filled.DirectionsCar, contentDescription = null) },
            title = { Text(stringResource(R.string.confirm_title, offer.unitName)) },
            text = { Text(stringResource(R.string.confirm_body)) },
            confirmButton = {
                Button(onClick = {
                    PairedUnits.add(context, offer)
                    enabled = true
                    PairedUnits.setEnabled(context, true)
                    LinkService.sync(context)
                    onOfferDone()
                }) { Text(stringResource(R.string.confirm_allow)) }
            },
            dismissButton = { TextButton(onClick = onOfferDone) { Text(stringResource(R.string.cancel)) } }
        )
    }

    removing?.let { unit ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.remove_title, unit.name)) },
            text = { Text(stringResource(R.string.remove_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        PairedUnits.remove(context, unit.id)
                        LinkService.sync(context)
                        removing = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.remove)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

/**
 * The setup checklist: in full while something is left to allow; once all is
 * allowed, one line that opens it ([onToggle]), since there is nothing to do.
 */
private fun LazyListScope.setupSection(setup: SetupChecks, open: Boolean, onToggle: (() -> Unit)?) {
    item {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(stringResource(R.string.setup_title), Modifier.weight(1f))
            if (setup.missing > 0) {
                Text(
                    stringResource(R.string.setup_missing, setup.missing),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
    if (!open && onToggle != null) {
        item {
            Card(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.setup_all_done), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.setup_show), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    } else {
        item { SetupSteps(setup) }
        if (onToggle != null) {
            item {
                TextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.setup_hide))
                    Icon(Icons.Filled.ExpandLess, contentDescription = null)
                }
            }
        }
    }
}

/** What the status card says, from the link and the switch. */
private enum class StatusLook { NO_CAR, OFF, WAITING, UNAVAILABLE, CONNECTED }

/**
 * The link at a glance: an icon in its colour, what is happening and what to
 * do about it, and the sharing switch. With no car paired, the button to pair one.
 */
@Composable
private fun StatusCard(
    look: StatusLook,
    unitName: String,
    enabled: Boolean,
    canToggle: Boolean,
    onToggle: (Boolean) -> Unit,
    onPair: () -> Unit
) {
    // The link's last event, for when the car does not connect (not translated: technical).
    val lastEvent = LinkServer.lastEvent.collectAsState().value
    val scheme = MaterialTheme.colorScheme
    val (icon, tint) = when (look) {
        StatusLook.CONNECTED -> Icons.Filled.Link to scheme.secondary
        StatusLook.WAITING -> Icons.Filled.WifiTethering to scheme.primary
        StatusLook.UNAVAILABLE -> Icons.Filled.ErrorOutline to scheme.error
        StatusLook.OFF -> Icons.Filled.LinkOff to scheme.onSurfaceVariant
        StatusLook.NO_CAR -> Icons.Filled.DirectionsCar to scheme.primary
    }
    val title = when (look) {
        StatusLook.NO_CAR -> stringResource(R.string.status_no_car)
        StatusLook.OFF -> stringResource(R.string.status_off)
        StatusLook.WAITING -> stringResource(R.string.status_waiting)
        StatusLook.UNAVAILABLE -> stringResource(R.string.status_unavailable)
        StatusLook.CONNECTED -> stringResource(R.string.status_connected, unitName)
    }
    val detail = when (look) {
        StatusLook.NO_CAR -> stringResource(R.string.pair_hint)
        StatusLook.OFF -> stringResource(R.string.status_off_detail)
        StatusLook.WAITING -> stringResource(R.string.status_waiting_detail)
        StatusLook.UNAVAILABLE -> stringResource(R.string.status_unavailable_detail)
        StatusLook.CONNECTED -> stringResource(R.string.status_connected_detail)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (look == StatusLook.CONNECTED) CardDefaults.cardColors(containerColor = scheme.primaryContainer) else CardDefaults.cardColors()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = tint.copy(alpha = 0.18f), modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = tint) }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    // What the switch does, over the status it gives.
                    if (canToggle) {
                        Text(
                            stringResource(R.string.share_toggle).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalContentColor.current.copy(alpha = 0.7f)
                        )
                    }
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = 0.8f))
                }
                if (canToggle) {
                    Spacer(Modifier.width(8.dp))
                    // The switch speaks for itself to TalkBack: "Share with the car, on".
                    val label = stringResource(R.string.share_toggle)
                    Switch(
                        checked = enabled,
                        onCheckedChange = onToggle,
                        modifier = Modifier.semantics { contentDescription = label }
                    )
                }
            }
            if ((look == StatusLook.WAITING || look == StatusLook.UNAVAILABLE) && lastEvent != null) {
                Text(
                    lastEvent,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
            if (look == StatusLook.NO_CAR) {
                Button(onClick = onPair, modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.pair_car))
                }
            }
        }
    }
}

/**
 * The phone as the car's keyboard ([CarKeyboard]): the text field selected on
 * the car's screen follows this one as it is typed, and Enter runs it there.
 */
@Composable
private fun KeyboardCard() {
    val context = LocalContext.current
    var field by remember { mutableStateOf(TextFieldValue("")) }
    // Only the answers to what was sent from here: not a share from earlier.
    var sentHere by remember { mutableStateOf(false) }
    val answer by CarKeyboard.answer.collectAsState()

    fun change(value: TextFieldValue) {
        val changed = value.text != field.text
        field = value
        if (changed) {
            sentHere = true
            CarKeyboard.send(value.text, TypeText.Mode.REPLACE)
        }
    }

    fun enter() {
        sentHere = true
        CarKeyboard.send(field.text, TypeText.Mode.REPLACE, enter = true)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Keyboard, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.keyboard_title), fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.keyboard_detail), style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedTextField(
                value = field,
                onValueChange = ::change,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                placeholder = { Text(stringResource(R.string.keyboard_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { enter() }),
                maxLines = 4
            )
            answer?.takeIf { sentHere }?.let { a ->
                val good = a.status == CarKeyboard.Status.TYPED || a.status == CarKeyboard.Status.TYPING_ON
                Text(
                    stringResource(CarKeyboard.message(a.status)),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (good) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
                // The car can't see its fields: one tap turns that on there.
                if (a.status == CarKeyboard.Status.TYPING_OFF) {
                    Button(onClick = { CarKeyboard.turnOn() }, modifier = Modifier.padding(top = 8.dp)) {
                        Text(stringResource(R.string.keyboard_turn_on))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    // The phone's clipboard, at the cursor: the car's field follows.
                    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                    val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                    if (!text.isNullOrEmpty()) {
                        val from = minOf(field.selection.start, field.selection.end)
                        val to = maxOf(field.selection.start, field.selection.end)
                        val joined = field.text.substring(0, from) + text + field.text.substring(to)
                        change(TextFieldValue(joined, TextRange(from + text.length)))
                    }
                }) {
                    Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.keyboard_paste))
                }
                OutlinedButton(onClick = { change(TextFieldValue("")) }) { Text(stringResource(R.string.keyboard_clear)) }
                Spacer(Modifier.weight(1f))
                Button(onClick = ::enter) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardReturn, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.keyboard_enter))
                }
            }
        }
    }
}

/** Where the car was left (see [CarSpot]), with the map and walking directions to it. */
@Composable
private fun CarSpotCard() {
    val context = LocalContext.current
    val spot by CarSpot.spot.collectAsState()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.LocalParking, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.car_spot_title), fontWeight = FontWeight.SemiBold)
                    val s = spot
                    Text(
                        when {
                            s == null -> stringResource(R.string.car_spot_none)
                            s.saved -> stringResource(R.string.car_spot_saved, whenText(s.at))
                            else -> stringResource(R.string.car_spot_left, whenText(s.at))
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            spot?.let { s ->
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { open(context, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${s.lat},${s.lng}"))) }) {
                        Icon(Icons.Filled.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.car_spot_map))
                    }
                    Button(onClick = { open(context, Intent(Intent.ACTION_VIEW, walkingDirections(s))) }) {
                        Icon(Icons.Filled.DirectionsWalk, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.car_spot_walk))
                    }
                }
            }
        }
    }
}

/** Past drives listed under the latest one; older ones stay in [DriveJournal]. */
private const val PAST_DRIVES_SHOWN = 30

@Composable
private fun HintCard(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
    }
}

/** The latest drive in full (see [DriveJournal]): the trip computer's figures, then the eco-driving card's. */
@Composable
private fun LatestDriveCard(d: DriveSummary, underWay: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Route, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(if (underWay) R.string.drive_under_way else R.string.drive_last), fontWeight = FontWeight.SemiBold)
                    Text(dateText(d.startedAt), style = MaterialTheme.typography.bodySmall)
                }
                EcoBadge(d.ecoScore)
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Figure(kmText(d.distanceKm), stringResource(R.string.drive_km))
                Figure(durationText(d.elapsedMs), stringResource(R.string.drive_time))
                Figure(d.avgSpeedKmh.toString(), stringResource(R.string.drive_average))
                Figure(d.maxSpeedKmh.toString(), stringResource(R.string.drive_top))
            }
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val score = d.ecoScore
                if (score == null) {
                    Text(stringResource(R.string.drive_eco_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(
                        stringResource(R.string.drive_eco_score, score) + " · " + stringResource(ecoCall(score)),
                        style = MaterialTheme.typography.bodyMedium, color = ecoTint(score), fontWeight = FontWeight.SemiBold
                    )
                    Text(stringResource(R.string.drive_hard, d.hardAccel, d.hardBrake), style = MaterialTheme.typography.bodySmall)
                    d.sweetPercent?.let { Text(stringResource(R.string.drive_band, it), style = MaterialTheme.typography.bodySmall) }
                    if (d.clutchHolds > 0) Text(stringResource(R.string.drive_clutch, d.clutchHolds), style = MaterialTheme.typography.bodySmall)
                }
                val liters = d.fuelLiters
                val cost = d.fuelCost
                if (liters != null && cost != null) {
                    Text(stringResource(R.string.drive_fuel, decimal(liters, 1), decimal(cost, 2), d.currency.orEmpty()), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** One past drive on a line: when, how far, how long, how fast, and its score. */
@Composable
private fun DriveRow(d: DriveSummary) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(dateText(d.startedAt), fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.drive_row, kmText(d.distanceKm), durationText(d.elapsedMs), d.avgSpeedKmh),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.width(8.dp))
            EcoBadge(d.ecoScore)
        }
    }
}

@Composable
private fun Figure(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The eco-driving score as a small tinted chip; "--" without one. */
@Composable
private fun EcoBadge(score: Int?) {
    val tint = if (score == null) MaterialTheme.colorScheme.onSurfaceVariant else ecoTint(score)
    Surface(shape = RoundedCornerShape(8.dp), color = tint.copy(alpha = 0.18f)) {
        Text(
            score?.toString() ?: "--", color = tint, fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/** The same verdict as the head unit's eco-driving card. */
private fun ecoCall(score: Int): Int = when {
    score >= 80 -> R.string.drive_eco_smooth
    score >= 60 -> R.string.drive_eco_fair
    else -> R.string.drive_eco_harsh
}

@Composable
private fun ecoTint(score: Int): Color = when {
    score >= 80 -> MaterialTheme.colorScheme.secondary
    score >= 60 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.error
}

private fun kmText(km: Double): String =
    if (km < 100) String.format(Locale.getDefault(), "%.1f", km) else km.roundToInt().toString()

private fun decimal(value: Double, digits: Int): String = String.format(Locale.getDefault(), "%.${digits}f", value)

/** "1 h 05" / "25 min". */
@Composable
private fun durationText(ms: Long): String {
    val minutes = (ms / 60_000L).toInt()
    return if (minutes >= 60) stringResource(R.string.drive_duration_hm, minutes / 60, minutes % 60)
    else stringResource(R.string.drive_duration_m, minutes.coerceAtLeast(1))
}

/** Weekday, date and time, abbreviated: "Tue, 24 Sep, 08:12". */
@Composable
private fun dateText(at: Long): String = DateUtils.formatDateTime(
    LocalContext.current, at,
    DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL
)

/** The time alone today, the date on other days. */
private fun whenText(at: Long): String =
    DateUtils.formatSameDayTime(at, System.currentTimeMillis(), DateFormat.MEDIUM, DateFormat.SHORT).toString()

/** Walking directions in Google Maps (the app when installed, else the browser). */
private fun walkingDirections(s: CarSpotInfo): Uri =
    Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${s.lat},${s.lng}&travelmode=walking")

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = modifier.padding(top = 8.dp))
}

/** What the setup checklist found, and how to fix each; re-read on every resume. */
private class SetupChecks(
    val listener: Boolean,
    /** Null below Android 13, where there is nothing to allow. */
    val post: Boolean?,
    val calls: Boolean,
    val agenda: Boolean,
    val battery: Boolean,
    val askPost: () -> Unit,
    val askCalls: () -> Unit,
    val askAgenda: () -> Unit
) {
    /** The steps that can be checked from here and are not done (the hotspot can't be). */
    val missing: Int get() = listOf(listener, post ?: true, calls, agenda, battery).count { !it }
}

@Composable
private fun rememberSetupChecks(resumes: Int): SetupChecks {
    val context = LocalContext.current
    // Re-read on every resume: the driver comes back from the system settings.
    val listener = remember(resumes) {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
    val battery = remember(resumes) {
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
    var postGranted by remember(resumes) { mutableStateOf(canPostNotifications(context)) }
    val askPost = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { postGranted = it }
    var callsGranted by remember(resumes) { mutableStateOf(CALL_PERMISSIONS.all { granted(context, it) }) }
    val askCalls = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        callsGranted = CALL_PERMISSIONS.all { granted(context, it) }
        PhoneLists.recheck()
    }
    var agendaGranted by remember(resumes) { mutableStateOf(granted(context, Manifest.permission.READ_CALENDAR)) }
    val askAgenda = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        agendaGranted = it
        PhoneLists.recheck()
    }
    return SetupChecks(
        listener = listener,
        post = if (Build.VERSION.SDK_INT >= 33) postGranted else null,
        calls = callsGranted,
        agenda = agendaGranted,
        battery = battery,
        askPost = { ask(context, arrayOf(Manifest.permission.POST_NOTIFICATIONS)) { askPost.launch(it.single()) } },
        askCalls = { ask(context, CALL_PERMISSIONS) { askCalls.launch(it) } },
        askAgenda = { ask(context, arrayOf(Manifest.permission.READ_CALENDAR)) { askAgenda.launch(it.single()) } }
    )
}

/**
 * Asks for [permissions] with [launch]; unless Android no longer asks (refused
 * twice), where the Allow button would silently do nothing: App info is
 * opened instead, where they can still be given. "Asked before" is kept, as
 * Android gives no rationale either before the first request.
 */
private fun ask(context: Context, permissions: Array<String>, launch: (Array<String>) -> Unit) {
    val activity = context as? Activity
    val missing = permissions.filterNot { granted(context, it) }
    val asked = context.getSharedPreferences(PERMISSION_PREFS, Context.MODE_PRIVATE)
    val refusedForGood = activity != null && missing.isNotEmpty() &&
        missing.all { asked.getBoolean(it, false) && !activity.shouldShowRequestPermissionRationale(it) }
    if (refusedForGood) {
        Toast.makeText(context, R.string.permission_in_settings, Toast.LENGTH_LONG).show()
        open(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        return
    }
    asked.edit().apply { missing.forEach { putBoolean(it, true) } }.apply()
    launch(permissions)
}

private const val PERMISSION_PREFS = "permissions_asked"

@Composable
private fun SetupSteps(setup: SetupChecks) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Step(
            Icons.Filled.Notifications, stringResource(R.string.step_notifications),
            stringResource(R.string.step_notifications_detail), done = setup.listener
        ) {
            open(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        if (!setup.listener) {
            // Android 13+ greys out Notification access for apps installed outside a store.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.step_notifications_restricted), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    open(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }) { Text(stringResource(R.string.step_app_info)) }
            }
        }
        setup.post?.let { post ->
            Step(
                Icons.Filled.NotificationsActive, stringResource(R.string.step_post),
                stringResource(R.string.step_post_detail), done = post, onFix = setup.askPost
            )
        }
        Step(
            Icons.Filled.Call, stringResource(R.string.step_calls),
            stringResource(R.string.step_calls_detail), done = setup.calls, onFix = setup.askCalls
        )
        Step(
            Icons.Filled.Event, stringResource(R.string.step_agenda),
            stringResource(R.string.step_agenda_detail), done = setup.agenda, onFix = setup.askAgenda
        )
        Step(
            Icons.Filled.BatteryFull, stringResource(R.string.step_battery),
            stringResource(R.string.step_battery_detail), done = setup.battery
        ) {
            open(context, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
        }
        Step(
            Icons.Filled.Wifi, stringResource(R.string.step_hotspot),
            stringResource(R.string.step_hotspot_detail), done = null
        ) {
            if (!open(context, Intent("android.settings.TETHER_SETTINGS"))) open(context, Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }
    }
}

/** One thing to set up. [done] null: can't be checked from here, always offers to open it. */
@Composable
private fun Step(icon: ImageVector, title: String, detail: String, done: Boolean?, onFix: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = if (done == false) MaterialTheme.colorScheme.tertiary else LocalContentColor.current)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(8.dp))
            when (done) {
                true -> Icon(Icons.Filled.CheckCircle, contentDescription = stringResource(R.string.done), tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(28.dp))
                false -> Button(onClick = onFix) { Text(stringResource(R.string.action_allow)) }
                null -> OutlinedButton(onClick = onFix) { Text(stringResource(R.string.step_open_settings)) }
            }
        }
    }
}

private fun canPostNotifications(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS)

/**
 * Who is calling (state, number, contact), answering / hanging up from the
 * car, and its Quick dial: the favourites, the last calls, calling one.
 */
private val CALL_PERMISSIONS = arrayOf(
    Manifest.permission.READ_PHONE_STATE,
    Manifest.permission.READ_CALL_LOG,
    Manifest.permission.READ_CONTACTS,
    Manifest.permission.ANSWER_PHONE_CALLS,
    Manifest.permission.CALL_PHONE
)

private fun granted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun open(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent)
    true
} catch (e: ActivityNotFoundException) {
    false
} catch (e: SecurityException) {
    false
}
