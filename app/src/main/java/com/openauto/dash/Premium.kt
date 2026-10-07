package com.openauto.dash

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow

/*
 * Dashwheel Pro: the part of the catalogue a Google Play install keeps for
 * one purchase. The GitHub build (free software, GPL) has everything open;
 * on Play, where the app cannot take a donation, the four most worked
 * skins and a dozen extra widgets pay for the work instead. What is locked
 * is decided here, once; which store the build is from, and whether Pro is
 * bought, is the flavour's [Edition] (src/github and src/play).
 */

/** How a purchase stands: what the unlock dialog says under its button. */
enum class PurchaseState {
    IDLE,
    /** The store's purchase screen is up, or on its way. */
    BUSY,
    /** Bought, but the payment is still going through (a slow payment method): Pro opens once it does. */
    PENDING,
    /** The store cannot be reached (offline, not signed in, or no Play on this unit). */
    UNAVAILABLE,
    /** The purchase was refused. */
    FAILED
}

/**
 * What the store a build comes from decides. One object named `Edition`
 * implements it per flavour, so the code in the main source set never asks
 * which build it is in beyond these few facts.
 */
internal interface StoreEdition {
    /** Installed from Google Play: updates come from Play, no donation link, Pro is for sale. */
    val playStore: Boolean
    /** The whole catalogue is open: always on GitHub, on Play once Pro is bought. */
    val unlocked: StateFlow<Boolean>
    /** The Pro unlock's price in the store's own words, once it has answered. */
    val price: StateFlow<String?>
    val purchase: StateFlow<PurchaseState>
    /** Called once at start, before the first screen: reads what is owned. */
    fun start(context: Context)
    /** Opens the store's purchase screen for Pro. */
    fun buy(activity: Activity)
    /** "Buy me a coffee": the open-source build's support link; none on Play. */
    val donationUrl: String?
    /** Where the phone companion app is downloaded from (the pairing dialog's QR code). */
    val companionUrl: String
    /** Updates are looked for on GitHub Releases and installed in place; a Play build updates through Play. */
    val updatesFromGitHub: Boolean
}

object Premium {
    /** The whole-design skins kept for Pro; Orbit stays free as the taste of what a skin is. */
    val LOCKED_THEMES: Set<DashThemeMode> = setOf(
        DashThemeMode.COCKPIT, DashThemeMode.HORIZON, DashThemeMode.TAPE_DECK, DashThemeMode.CANVAS
    )

    /**
     * The widgets kept for Pro: the ones for the keen driver and the tinkerer
     * (timers, G-force, speed cameras, logs, graphs, the unit's monitor), never
     * the ones a dashboard is made of (map, music, car data, the alerts).
     */
    val LOCKED_KINDS: Set<BuiltinKind> = setOf(
        BuiltinKind.PERF_TIMER, BuiltinKind.GFORCE, BuiltinKind.ECO_DRIVE, BuiltinKind.SPEED_CAMERAS,
        BuiltinKind.FUEL_LOG, BuiltinKind.ENGINE_TEMPS, BuiltinKind.HEAD_UNIT, BuiltinKind.CAN_MON,
        BuiltinKind.WEATHER_ALERTS, BuiltinKind.COMMUTE, BuiltinKind.SHARE_ETA, BuiltinKind.VOICE_NOTES,
        BuiltinKind.RADIO_PRESETS
    )

    /** The theme shown in place of a locked one (a layout restored from a backup, a refund). */
    val FALLBACK_THEME = DashThemeMode.AUTO

    /** The setup's three looks while Pro is not bought: the default, the free skin, and a glass theme. */
    val FREE_STARTER_THEMES = listOf(DashThemeMode.AUTO, DashThemeMode.ORBIT, DashThemeMode.DARK_GLASS)

    fun locked(mode: DashThemeMode): Boolean = mode in LOCKED_THEMES && !Edition.unlocked.value

    fun locked(kind: BuiltinKind): Boolean = kind in LOCKED_KINDS && !Edition.unlocked.value

    /** The kinds a template must leave off right now. */
    fun lockedKinds(): Set<BuiltinKind> = if (Edition.unlocked.value) emptySet() else LOCKED_KINDS

    /** [mode] as it can be shown: itself, or [FALLBACK_THEME] while it is locked. */
    fun allowed(mode: DashThemeMode): DashThemeMode = if (locked(mode)) FALLBACK_THEME else mode

    /** The looks the setup and the gallery lead with, none of them locked. */
    fun starterThemes(unlocked: Boolean = Edition.unlocked.value): List<DashThemeMode> =
        if (unlocked || StarterThemes.none { it in LOCKED_THEMES }) StarterThemes else FREE_STARTER_THEMES

    /** Opens the build's own page on Google Play (the Play app, else the browser). */
    fun openStoreListing(context: Context) {
        val id = context.packageName
        if (!context.launchSafely(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id")))) {
            context.launchSafely(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$id")))
        }
    }
}

/** Whether the whole catalogue is open, as a state: a purchase opens every locked card at once. */
@Composable
internal fun rememberUnlocked(): Boolean = Edition.unlocked.collectAsState().value

@Composable
internal fun lockedNow(mode: DashThemeMode): Boolean = mode in Premium.LOCKED_THEMES && !rememberUnlocked()

@Composable
internal fun lockedNow(kind: BuiltinKind): Boolean = kind in Premium.LOCKED_KINDS && !rememberUnlocked()

/** The "Pro" mark on a locked card: a small pill with a lock. */
@Composable
internal fun LockBadge(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(DashShape.Pill)
            .background(DashColors.AccentBrush)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = DashColors.OnAccent, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            stringResource(R.string.pro_badge), color = DashColors.OnAccent, fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelSmall, maxLines = 1
        )
    }
}

/**
 * What a locked widget's tile shows (a layout from a backup, a template made
 * before a refund): its name and the way to Pro, in place of its reading.
 */
@Composable
internal fun LockedTile(kind: BuiltinKind) {
    var unlock by remember { mutableStateOf(false) }
    if (unlock) UnlockDialog { unlock = false }
    val tap = rememberTapFeedback()
    Card(modifier = Modifier.fillMaxSize().clickable { tap(); unlock = true }) {
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size(36.dp))
            Spacer(Modifier.height(8.dp))
            Text(kind.label, color = DashColors.TextSecondary, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                stringResource(R.string.pro_tile_hint), color = DashColors.Muted,
                style = MaterialTheme.typography.labelSmall, maxLines = 2
            )
        }
    }
}

/**
 * The one way to Pro: what it adds, the price, and the store's purchase
 * screen behind the button. Closes by itself once the purchase is through.
 */
@Composable
internal fun UnlockDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val unlocked = rememberUnlocked()
    val price by Edition.price.collectAsState()
    val purchase by Edition.purchase.collectAsState()
    LaunchedEffect(unlocked) { if (unlocked) onDismiss() }
    val tap = rememberTapFeedback()
    AlertDialog(
        modifier = Modifier.keepClearOfWindows(),
        onDismissRequest = onDismiss,
        containerColor = DashColors.Card,
        icon = { Icon(Icons.Filled.WorkspacePremium, contentDescription = null, tint = DashColors.Accent) },
        title = { Text(stringResource(R.string.pro_dialog_title), color = DashColors.TextPrimary) },
        text = {
            Column {
                Text(
                    stringResource(R.string.pro_dialog_body, Premium.LOCKED_KINDS.size),
                    color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium
                )
                val note = when (purchase) {
                    PurchaseState.PENDING -> R.string.pro_dialog_pending
                    PurchaseState.UNAVAILABLE -> R.string.pro_dialog_unavailable
                    PurchaseState.FAILED -> R.string.pro_dialog_failed
                    PurchaseState.IDLE, PurchaseState.BUSY -> null
                }
                if (note != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(note),
                        color = if (purchase == PurchaseState.PENDING) DashColors.TextSecondary else DashColors.Warning,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = purchase != PurchaseState.BUSY && purchase != PurchaseState.PENDING,
                onClick = {
                    tap()
                    val activity = context.activity()
                    if (activity != null) Edition.buy(activity)
                }
            ) {
                val label = price?.let { stringResource(R.string.pro_dialog_buy, it) } ?: stringResource(R.string.pro_dialog_buy_no_price)
                Text(label, color = DashColors.Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.pro_dialog_later), color = DashColors.Muted) }
        }
    )
}

/** Settings → About on Play: Pro, bought or on offer, in place of the donation link. */
@Composable
internal fun ProSection() {
    val unlocked = rememberUnlocked()
    val price by Edition.price.collectAsState()
    var dialog by remember { mutableStateOf(false) }
    if (dialog) UnlockDialog { dialog = false }
    SettingsSection(stringResource(R.string.pro_name))
    if (unlocked) {
        SettingsRow(Icons.Filled.WorkspacePremium, stringResource(R.string.pro_name), stringResource(R.string.pro_settings_unlocked)) {}
    } else {
        val title = price?.let { stringResource(R.string.pro_dialog_buy, it) } ?: stringResource(R.string.pro_settings_row)
        SettingsRow(Icons.Filled.WorkspacePremium, title, stringResource(R.string.pro_settings_locked_detail, Premium.LOCKED_KINDS.size)) {
            dialog = true
        }
    }
}

/** Settings → About on Play: where updates come from, in place of the GitHub updater's row. */
@Composable
internal fun PlayUpdateRow() {
    val context = LocalContext.current
    SettingsRow(Icons.Filled.SystemUpdate, stringResource(R.string.about_play_updates), stringResource(R.string.about_play_updates_detail)) {
        Premium.openStoreListing(context)
    }
}
