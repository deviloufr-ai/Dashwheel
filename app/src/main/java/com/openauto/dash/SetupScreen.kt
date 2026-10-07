package com.openauto.dash

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AirlineSeatReclineNormal
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/*
 * The first-run setup: three steps done once, parked, in place of a
 * "grant access" button on every tile. The car (a preset to confirm), what
 * the launcher may use (each one optional, with a check once allowed) and
 * the look (three to start with). Settings → Advanced runs it again, and the
 * bar's "Finish setting up" pill reopens the access step while a tile on
 * some page still lacks what it needs.
 */

internal enum class SetupStep { CAR, ACCESS, LOOK }

/** The step before this one, where the back arrow and the Back key lead; null on the first. */
internal fun SetupStep.previous(): SetupStep? = SetupStep.entries.getOrNull(ordinal - 1)

/** Whether the setup has been seen, and whether the driver asked the pill to stop. */
object SetupStore {
    private const val PREFS = "setup"
    private const val KEY_DONE = "done"
    private const val KEY_PILL_OFF = "pill_off"

    fun isDone(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DONE, false)

    fun markDone(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DONE, true).apply()
    }

    /** True once the driver skipped the access step: the pill stays away until the setup is run again. */
    fun pillOff(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PILL_OFF, false)

    fun setPillOff(context: Context, off: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PILL_OFF, off).apply()
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
        OBD -> ObdBluetoothManager.savedDeviceAddress() != null
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
 * The three steps. [onCarSettings] opens the car profile sheet, [onPickObd]
 * the adapter picker (with its Bluetooth permission). [onClose] gets whether
 * the driver went through to the end (true) or skipped (false), or null when
 * the Back key left from the first step.
 */
@Composable
internal fun SetupScreen(
    initialStep: SetupStep,
    theme: ThemeState,
    onCarSettings: () -> Unit,
    onPickObd: () -> Unit,
    onClose: (finished: Boolean?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var step by remember(initialStep) { mutableStateOf(initialStep) }
    val tap = rememberTapFeedback()
    // The Back key does what the arrow does: a step back, and out only from the first.
    BackHandler { step.previous()?.let { step = it } ?: onClose(null) }
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
                TextButton(onClick = { tap(); onClose(false) }, modifier = Modifier.heightIn(min = DashSize.Touch)) {
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
                    SetupStep.CAR -> CarStep(onCarSettings = onCarSettings, onNext = { step = SetupStep.ACCESS })
                    SetupStep.ACCESS -> AccessStep(onPickObd = onPickObd, onNext = { step = SetupStep.LOOK })
                    SetupStep.LOOK -> LookStep(theme = theme, onDone = {
                        SetupStore.markDone(context)
                        onClose(true)
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

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    OutlinedButton(
        onClick = { tap(); onClick() },
        colors = ButtonDefaults.outlinedButtonColors(contentColor = DashColors.TextPrimary),
        border = androidx.compose.foundation.BorderStroke(1.dp, DashColors.Line),
        shape = DashShape.Small,
        modifier = Modifier.heightIn(min = DashSize.TouchPrimary)
    ) { Text(text, style = MaterialTheme.typography.bodyLarge) }
}

/** Step 1: the car on file, to keep or change, and which side the driver sits. */
@Composable
private fun CarStep(onCarSettings: () -> Unit, onNext: () -> Unit) {
    val car by CarProfileStore.profile.collectAsState()
    StepTitle(stringResource(R.string.setup_car_title), stringResource(R.string.setup_car_body))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DashShape.Medium)
            .background(DashColors.CardHi.copy(alpha = DashColors.CardHi.alpha * 0.5f))
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.DirectionsCar, contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size(44.dp))
        Spacer(Modifier.width(18.dp))
        Text(car.name, color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
    }
    Spacer(Modifier.height(20.dp))
    Text(stringResource(R.string.setup_driver_side), color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 8.dp))
    SegmentedSwitch(
        options = listOf(false, true),
        chosen = car.driverOnRight,
        icon = { Icons.Filled.AirlineSeatReclineNormal },
        title = { right -> stringResource(if (right) R.string.setup_driver_right else R.string.setup_driver_left) },
        onChoose = { right -> if (right != car.driverOnRight) CarProfileStore.save(car.copy(driverOnRight = right)) }
    )
    Spacer(Modifier.height(24.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PrimaryButton(stringResource(R.string.setup_car_keep), onNext)
        SecondaryButton(stringResource(R.string.setup_car_change), onCarSettings)
    }
}

/** Step 2: one row per thing the launcher can use, a check once allowed. */
@Composable
private fun AccessStep(onPickObd: () -> Unit, onNext: () -> Unit) {
    StepTitle(stringResource(R.string.setup_access_title), stringResource(R.string.setup_access_body))
    AccessRows(onPickObd)
    Spacer(Modifier.height(20.dp))
    PrimaryButton(stringResource(R.string.setup_next), onNext)
}

/**
 * What the launcher may use, one row each: allowed, or the button that asks.
 * In the setup and in Settings, where it can be looked up at any time.
 */
@Composable
internal fun AccessRows(onPickObd: () -> Unit) {
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
    val location = rememberPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    val contacts = rememberPermission(Manifest.permission.READ_CONTACTS)
    val calendar = rememberPermission(Manifest.permission.READ_CALENDAR)
    AccessNeed.entries.forEach { need ->
        val granted = when (need) {
            AccessNeed.LOCATION -> location.granted
            AccessNeed.CONTACTS -> contacts.granted
            AccessNeed.CALENDAR -> calendar.granted
            AccessNeed.OBD -> obdConnection == ObdConnectionState.CONNECTED || remember(generation) { need.granted(context) }
            AccessNeed.NOTIFICATIONS -> remember(generation) { need.granted(context) }
        }
        AccessRow(
            need = need,
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
            }
        )
    }
}

@Composable
private fun AccessRow(need: AccessNeed, granted: Boolean, @StringRes actionRes: Int, onAction: () -> Unit) {
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(need.icon, contentDescription = null, tint = if (granted) DashColors.Good else DashColors.TextSecondary, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(need.titleRes), color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(need.detailRes), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        if (granted) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.widthIn(min = 120.dp)) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = DashColors.Good, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.setup_allowed), color = DashColors.Good, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            OutlinedButton(
                onClick = { tap(); onAction() },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = DashColors.Accent),
                border = androidx.compose.foundation.BorderStroke(1.dp, DashColors.Accent.copy(alpha = 0.6f)),
                shape = DashShape.Small,
                modifier = Modifier.heightIn(min = DashSize.Touch).widthIn(min = 120.dp)
            ) { Text(stringResource(actionRes)) }
        }
    }
}

/** Step 3: three looks to start with; the whole gallery waits in Settings. */
@Composable
private fun LookStep(theme: ThemeState, onDone: () -> Unit) {
    StepTitle(stringResource(R.string.setup_look_title), stringResource(R.string.setup_look_body))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Premium.starterThemes(rememberUnlocked()).forEach { mode ->
            ThemeCard(mode, mode == theme.mode, Modifier.weight(1f)) { theme.onMode(mode) }
        }
    }
    Spacer(Modifier.height(24.dp))
    PrimaryButton(stringResource(R.string.setup_done), onDone)
}

/** The three looks the setup and the gallery lead with: the default and the two most different skins. */
internal val StarterThemes = listOf(DashThemeMode.AUTO, DashThemeMode.COCKPIT, DashThemeMode.ORBIT)
