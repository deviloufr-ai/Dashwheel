package com.openauto.dash

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AirlineSeatReclineNormal
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/*
 * The first-run setup: three steps done once, parked, in place of a
 * "grant access" button on every tile. The car (make, model and year, or
 * later), what the launcher may use (each one optional, with a check once
 * allowed; the phone and the OBD adapter among them) and the look (three to
 * start with). Settings → Advanced runs it again, and the bar's "Finish
 * setting up" pill reopens the access step while a tile on some page still
 * lacks what it needs; "Don't remind me" on that step is what turns the pill
 * off, Skip only leaves. Closed by the drive lock before it was done, it
 * comes back at the step it was on once the car is parked again.
 */

internal enum class SetupStep { CAR, ACCESS, LOOK }

/** The step before this one, where the back arrow and the Back key lead; null on the first. */
internal fun SetupStep.previous(): SetupStep? = SetupStep.entries.getOrNull(ordinal - 1)

/** Whether the setup has been seen, whether the driver asked the pill to stop, and whether there is no adapter to wait for. */
object SetupStore {
    private const val PREFS = "setup"
    private const val KEY_DONE = "done"
    private const val KEY_PILL_OFF = "pill_off"
    private const val KEY_NO_OBD = "no_obd"

    fun isDone(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DONE, false)

    fun markDone(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DONE, true).apply()
    }

    /** True once the driver tapped "Don't remind me" on the access step: the pill stays away. */
    fun pillOff(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PILL_OFF, false)

    fun setPillOff(context: Context, off: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PILL_OFF, off).apply()
    }

    /** The driver has no OBD adapter: the car tiles are not something left to set up. */
    fun noObd(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_NO_OBD, false)

    fun setNoObd(context: Context, none: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_NO_OBD, none).apply()
    }
}

/** What a tile may need that the driver has not allowed yet. */
internal enum class AccessNeed(val icon: ImageVector, @StringRes val titleRes: Int, @StringRes val detailRes: Int) {
    NOTIFICATIONS(Icons.Filled.Notifications, R.string.setup_access_notifications, R.string.setup_access_notifications_detail),
    LOCATION(Icons.Filled.LocationOn, R.string.setup_access_location, R.string.setup_access_location_detail),
    CONTACTS(Icons.Filled.Contacts, R.string.setup_access_contacts, R.string.setup_access_contacts_detail),
    CALENDAR(Icons.Filled.Event, R.string.setup_access_calendar, R.string.setup_access_calendar_detail),
    OBD(Icons.Filled.Bluetooth, R.string.setup_access_obd, R.string.setup_access_obd_detail);

    fun granted(context: Context): Boolean = when (this) {
        NOTIFICATIONS -> CarMediaController.hasNotificationAccess(context)
        LOCATION -> has(context, Manifest.permission.ACCESS_FINE_LOCATION)
        CONTACTS -> has(context, Manifest.permission.READ_CONTACTS)
        CALENDAR -> has(context, Manifest.permission.READ_CALENDAR)
        // An adapter to dial, here or through the phone, or none to wait for.
        OBD -> ObdBluetoothManager.canDial() || SetupStore.noObd(context)
    }

    private fun has(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        /** What [kind] needs to show anything. */
        fun of(kind: BuiltinKind): AccessNeed? = when (kind) {
            BuiltinKind.MEDIA, BuiltinKind.NAVIGATION, BuiltinKind.NOTIFICATIONS -> NOTIFICATIONS
            BuiltinKind.NAVMAP, BuiltinKind.WEATHER, BuiltinKind.PARKING, BuiltinKind.COMPASS, BuiltinKind.TRIP,
            BuiltinKind.FUEL_PRICES, BuiltinKind.FUEL_TO_DEST, BuiltinKind.GFORCE -> LOCATION
            BuiltinKind.QUICK_DIAL -> CONTACTS
            BuiltinKind.CALENDAR -> CALENDAR
            BuiltinKind.TELEMETRY, BuiltinKind.OBD_DTC, BuiltinKind.OBD_ALL, BuiltinKind.RANGE, BuiltinKind.SPEED_HUD,
            BuiltinKind.FILTER_CARE, BuiltinKind.WARMUP, BuiltinKind.BATTERY, BuiltinKind.ECO_DRIVE -> OBD
            else -> null
        }

        /** The needs of the tiles on [pages] that are still not allowed. */
        fun pending(context: Context, pages: List<List<DashboardItem>>): Set<AccessNeed> =
            pages.asSequence().flatten()
                .filterIsInstance<DashboardItem.BuiltinWidget>()
                .mapNotNull { of(it.kind) }
                .distinct()
                .filterNot { it.granted(context) }
                .toSet()
    }
}

/**
 * The three steps. [onPickObd] opens the adapter picker (with its Bluetooth
 * permission). [onAccessChanged] says something the pill looks at changed
 * here (no adapter, a phone paired); [onPillOff] is "Don't remind me".
 * [onStep] follows the step on screen, so the drive lock can bring the setup
 * back where it was. [onClose] is every way out: Done, Skip, or the Back key
 * from the first step.
 */
@Composable
internal fun SetupScreen(
    initialStep: SetupStep,
    theme: ThemeState,
    onPickObd: () -> Unit,
    onAccessChanged: () -> Unit,
    pillOff: Boolean,
    onPillOff: () -> Unit,
    onStep: (SetupStep) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var step by remember(initialStep) { mutableStateOf(initialStep) }
    LaunchedEffect(step) { onStep(step) }
    val tap = rememberTapFeedback()
    // The Back key does what the arrow does: a step back, and out only from the first.
    BackHandler { step.previous()?.let { step = it } ?: onClose() }
    SolidCard(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 18.dp)) {
            // Header: where we are, and the way out.
            Row(verticalAlignment = Alignment.CenterVertically) {
                step.previous()?.let { before ->
                    Box(
                        modifier = Modifier
                            .size(DashSize.TouchPrimary)
                            .clip(DashShape.Medium)
                            .background(DashColors.CardHi)
                            .clickable { tap(); step = before },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.setup_back), tint = DashColors.TextPrimary)
                    }
                    Spacer(Modifier.width(14.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.setup_title),
                        color = DashColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        stringResource(R.string.setup_step, step.ordinal + 1),
                        color = DashColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                StepDots(step)
                Spacer(Modifier.width(16.dp))
                TextButton(onClick = { tap(); onClose() }, modifier = Modifier.heightIn(min = DashSize.Touch)) {
                    Text(stringResource(R.string.setup_skip), color = DashColors.TextSecondary)
                }
            }
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                when (step) {
                    SetupStep.CAR -> CarStep(onNext = { step = SetupStep.ACCESS })
                    SetupStep.ACCESS -> AccessStep(
                        onPickObd = onPickObd, onAccessChanged = onAccessChanged, pillOff = pillOff, onPillOff = onPillOff,
                        onNext = { step = SetupStep.LOOK }
                    )
                    SetupStep.LOOK -> LookStep(theme = theme, onDone = {
                        SetupStore.markDone(context)
                        onClose()
                    })
                }
            }
        }
    }
}

/** Three dots, the current step filled. */
@Composable
private fun StepDots(step: SetupStep) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        SetupStep.entries.forEach { s ->
            val on = s == step
            Box(
                Modifier
                    .size(if (on) 12.dp else 8.dp)
                    .clip(CircleShape)
                    .background(if (on) DashColors.Accent else DashColors.Muted.copy(alpha = 0.5f))
            )
        }
    }
}

@Composable
private fun StepTitle(title: String, body: String) {
    Text(title, color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
    Text(body, color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    Button(
        onClick = { tap(); onClick() },
        colors = ButtonDefaults.buttonColors(containerColor = DashColors.Accent, contentColor = DashColors.OnAccent),
        shape = DashShape.Small,
        modifier = Modifier.heightIn(min = DashSize.TouchPrimary).widthIn(min = 180.dp)
    ) { Text(text, style = MaterialTheme.typography.bodyLarge) }
}

/**
 * Step 1: the car in a few words. Make, model and year (the spec sheet in
 * Settings, Car has everything else), the fuel and gearbox the tiles read,
 * and which side the driver sits. Later leaves it all for another time.
 */
@Composable
private fun CarStep(onNext: () -> Unit) {
    val context = LocalContext.current
    val saved by CarProfileStore.profile.collectAsState()
    var make by remember { mutableStateOf(saved.make) }
    var model by remember { mutableStateOf(saved.model) }
    var year by remember { mutableStateOf(saved.year?.toString().orEmpty()) }
    var fuel by remember { mutableStateOf(saved.fuel) }
    var gearbox by remember { mutableStateOf(saved.gearbox) }
    var driverOnRight by remember { mutableStateOf(saved.driverOnRight) }
    val tap = rememberTapFeedback()

    /** Keeps what was typed; the free name follows make, model and year until the sheet gives it another. */
    fun save() {
        val y = year.trim().toIntOrNull()
        val carChanged = make.trim() != saved.make || model.trim() != saved.model || y != saved.year
        val name = if (carChanged || saved.name.isBlank()) CarProfile.composeName(make, model, y).ifBlank { saved.name } else saved.name
        val next = saved.copy(name = name, make = make.trim(), model = model.trim(), year = y, fuel = fuel, gearbox = gearbox, driverOnRight = driverOnRight)
        if (next != saved) {
            CarProfileStore.save(next.copy(source = SpecSource.USER, updatedAt = System.currentTimeMillis()))
            // The mechanic's answers are about the car: redo them for the new one.
            AiMechanic.refresh()
        }
    }

    StepTitle(stringResource(R.string.setup_car_title), stringResource(R.string.setup_car_body))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        CarField(make, stringResource(R.string.setup_car_make), Modifier.weight(1f)) { make = it }
        CarField(model, stringResource(R.string.setup_car_model), Modifier.weight(1f)) { model = it }
        CarField(year, stringResource(R.string.setup_car_year), Modifier.weight(0.5f), number = true) { year = it.filter(Char::isDigit).take(4) }
    }
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(3f)) {
            FieldTitle(stringResource(R.string.setup_car_fuel))
            ChoiceRow(FuelType.entries, fuel, { context.getString(it.labelRes) }) { fuel = it }
        }
        Column(Modifier.weight(2f)) {
            FieldTitle(stringResource(R.string.setup_car_gearbox))
            // Two chips: manual, or any kind of automatic (the sheet tells them apart).
            ChoiceRow(
                listOf(false, true), gearbox != GearboxType.MANUAL,
                { automatic -> context.getString(if (automatic) R.string.car_gearbox_automatic else R.string.car_gearbox_manual) }
            ) { automatic ->
                gearbox = when {
                    !automatic -> GearboxType.MANUAL
                    gearbox == GearboxType.MANUAL -> GearboxType.AUTOMATIC
                    else -> gearbox
                }
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    FieldTitle(stringResource(R.string.setup_driver_side))
    SegmentedSwitch(
        options = listOf(false, true),
        chosen = driverOnRight,
        icon = { Icons.Filled.AirlineSeatReclineNormal },
        title = { right -> stringResource(if (right) R.string.setup_driver_right else R.string.setup_driver_left) },
        onChoose = { driverOnRight = it }
    )
    Spacer(Modifier.height(24.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        PrimaryButton(stringResource(R.string.setup_next)) { save(); onNext() }
        TextButton(onClick = { tap(); onNext() }, modifier = Modifier.heightIn(min = DashSize.TouchPrimary)) {
            Text(stringResource(R.string.setup_later), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun FieldTitle(text: String) {
    Text(text, color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun CarField(value: String, label: String, modifier: Modifier, number: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        modifier = modifier,
        colors = fieldColors()
    )
}

/** Step 2: one row per thing the launcher can use, a check once allowed; "Don't remind me" rests the bar's pill. */
@Composable
private fun AccessStep(onPickObd: () -> Unit, onAccessChanged: () -> Unit, pillOff: Boolean, onPillOff: () -> Unit, onNext: () -> Unit) {
    val tap = rememberTapFeedback()
    StepTitle(stringResource(R.string.setup_access_title), stringResource(R.string.setup_access_body))
    AccessRows(onPickObd, onAccessChanged)
    Spacer(Modifier.height(20.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        PrimaryButton(stringResource(R.string.setup_next), onNext)
        if (!pillOff) {
            TextButton(onClick = { tap(); onPillOff() }, modifier = Modifier.heightIn(min = DashSize.TouchPrimary)) {
                Text(stringResource(R.string.setup_no_reminder), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/**
 * What the launcher may use, one row each: allowed, or the button that asks.
 * The OBD row also takes "I don't have one", the phone row pairs the
 * companion. In the setup and in Settings, where it can be looked up at any
 * time. [onChanged]: something the bar's pill looks at was settled here.
 */
@Composable
internal fun AccessRows(onPickObd: () -> Unit, onChanged: () -> Unit = {}) {
    val context = LocalContext.current
    // The system screens grant on their side: read again each time the launcher comes back.
    var generation by remember { mutableStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) generation++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val obdConnection by ObdBluetoothManager.connectionState.collectAsState()
    // Read again when the link changes too: a pick in the adapter dialog starts a connection.
    val obdUsable = obdConnection == ObdConnectionState.CONNECTED || remember(generation, obdConnection) { ObdBluetoothManager.canDial() }
    var noObd by remember { mutableStateOf(SetupStore.noObd(context)) }
    val location = rememberPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    val contacts = rememberPermission(Manifest.permission.READ_CONTACTS)
    val calendar = rememberPermission(Manifest.permission.READ_CALENDAR)
    AccessNeed.entries.forEach { need ->
        val granted = when (need) {
            AccessNeed.LOCATION -> location.granted
            AccessNeed.CONTACTS -> contacts.granted
            AccessNeed.CALENDAR -> calendar.granted
            AccessNeed.OBD -> obdUsable
            AccessNeed.NOTIFICATIONS -> remember(generation) { need.granted(context) }
        }
        AccessRow(
            icon = need.icon,
            title = stringResource(need.titleRes),
            detail = stringResource(need.detailRes),
            granted = granted,
            actionRes = if (need == AccessNeed.OBD) R.string.setup_choose else R.string.setup_allow,
            onAction = {
                when (need) {
                    AccessNeed.NOTIFICATIONS -> CarMediaController.openNotificationAccessSettings(context)
                    AccessNeed.LOCATION -> location.request()
                    AccessNeed.CONTACTS -> contacts.request()
                    AccessNeed.CALENDAR -> calendar.request()
                    AccessNeed.OBD -> onPickObd()
                }
            },
            // No adapter: said once, and the car tiles stop counting as left to set up.
            note = if (need == AccessNeed.OBD && noObd) stringResource(R.string.setup_obd_no_adapter) else null,
            secondary = if (need == AccessNeed.OBD && !noObd) R.string.setup_obd_none to {
                SetupStore.setNoObd(context, true)
                noObd = true
                onChanged()
            } else null
        )
    }
    // The phone: messages, calls and the agenda come over its link, not from an Android permission.
    val phones by PhoneLink.phones.collectAsState()
    var pairing by remember { mutableStateOf(false) }
    AccessRow(
        icon = Icons.Filled.PhoneAndroid,
        title = stringResource(R.string.setup_access_phone),
        detail = stringResource(R.string.setup_access_phone_detail),
        granted = phones.any { !it.forgotten },
        grantedRes = R.string.setup_paired,
        actionRes = R.string.setup_pair,
        onAction = { pairing = true }
    )
    if (pairing) PhonePairingDialog(onDismiss = { pairing = false; onChanged() })
}

/**
 * One row: what it is, then the check once [granted], or the button that
 * asks. [note] stands in for the check when there is nothing to grant;
 * [secondary] is a text button under the main one.
 */
@Composable
private fun AccessRow(
    icon: ImageVector,
    title: String,
    detail: String,
    granted: Boolean,
    @StringRes actionRes: Int,
    onAction: () -> Unit,
    @StringRes grantedRes: Int = R.string.setup_allowed,
    note: String? = null,
    secondary: Pair<Int, () -> Unit>? = null
) {
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = if (granted) DashColors.Good else DashColors.TextSecondary, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
            Text(detail, color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        if (granted) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.widthIn(min = 120.dp)) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = DashColors.Good, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(grantedRes), color = DashColors.Good, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            if (note != null) {
                Text(note, color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(12.dp))
            }
            Column(horizontalAlignment = Alignment.End) {
                OutlinedButton(
                    onClick = { tap(); onAction() },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = DashColors.Accent),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DashColors.Accent.copy(alpha = 0.6f)),
                    shape = DashShape.Small,
                    modifier = Modifier.heightIn(min = DashSize.Touch).widthIn(min = 120.dp)
                ) { Text(stringResource(actionRes)) }
                secondary?.let { (labelRes, onSecondary) ->
                    TextButton(onClick = { tap(); onSecondary() }, modifier = Modifier.heightIn(min = DashSize.Touch)) {
                        Text(stringResource(labelRes), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/** Step 3: three looks to start with; the whole gallery waits in Settings. */
@Composable
private fun LookStep(theme: ThemeState, onDone: () -> Unit) {
    StepTitle(stringResource(R.string.setup_look_title), stringResource(R.string.setup_look_body))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        StarterThemes.forEach { mode ->
            ThemeCard(mode, mode == theme.mode, Modifier.weight(1f)) { theme.onMode(mode) }
        }
    }
    Spacer(Modifier.height(24.dp))
    PrimaryButton(stringResource(R.string.setup_done), onDone)
}

/** The three looks the setup and the gallery lead with: the default and the two most different skins. */
internal val StarterThemes = listOf(DashThemeMode.AUTO, DashThemeMode.COCKPIT, DashThemeMode.ORBIT)
