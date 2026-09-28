package com.openauto.dash

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.openauto.dash.link.CallCommand
import com.openauto.dash.link.CallState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/*
 * The phone's calls on the head unit, from the companion or the head unit's
 * own Bluetooth ([PhoneCallOverlay.call]), in the design the driver chose
 * ([AlertStyle]): who is calling with Answer / Decline, then the call's
 * duration and Hang up. The side panel and the full screen are for the
 * ringing only: an answered call shrinks to the card's slim bar, so a long
 * call never hides the map. While reversing it's the pill, above the
 * reversing camera. It is its own overlay window, so it shows over
 * whatever app fills the screen, and keeps running while the launcher is in
 * the background. Without "display over other apps" it falls back to a popup
 * inside the launcher ([PhoneCallHost]).
 */

object PhoneCallOverlay {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * The call to show: the style picker's made-up one while it is tried,
     * the companion's (it knows the contact's photo), else the head unit's own
     * Bluetooth call once the driver chose Dashwheel's card over the ROM's
     * ([RomPopups]). The head unit answers when the companion may not.
     */
    val call: StateFlow<PhoneCall?> =
        combine(PhoneLink.call, HeadUnitPhone.call, RomPopups.replaced, AlertPreview.call, UnitSignals.projectionOnScreen) { link, unit, replaced, preview, projected ->
            val own = unit?.takeIf { RomPopups.Kind.CALL in replaced }
            preview ?: when {
                // CarPlay / Android Auto on screen shows the call itself: no second card over it.
                projected -> null
                link == null -> own
                !link.canControl && own != null -> link.copy(canControl = true, viaHeadUnit = true)
                else -> link
            }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    /** The design the call shows in now: null when there's no call. */
    val style: StateFlow<AlertStyle?> =
        combine(call, AlertStyleStore.styles, CarBox.reversing) { call, styles, reversing ->
            call?.let { callStyle(it, styles.of(AlertKind.CALL), reversing) }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * The call a button acts on with no card to press (a learned steering
     * wheel button): the one shown, else the head unit's own, which the ROM's
     * pop-up shows when the driver kept it.
     */
    private fun callToControl(): PhoneCall? = call.value ?: HeadUnitPhone.call.value

    /** Answers the call that rings, the way its card's button does. */
    fun answerCall() {
        val c = callToControl()
        if (c != null) answer(c) else PhoneLink.callCommand(CallCommand.Action.ANSWER)
    }

    fun declineCall() {
        val c = callToControl()
        if (c != null) decline(c) else PhoneLink.callCommand(CallCommand.Action.DECLINE)
    }

    fun hangUpCall() {
        val c = callToControl()
        if (c != null) hangUp(c) else PhoneLink.callCommand(CallCommand.Action.HANG_UP)
    }

    private var started = false
    private var window: AlertWindow? = null

    private val _showing = MutableStateFlow(false)
    /** True while the overlay window is up (the launcher's popup then stays away). */
    val showing: StateFlow<Boolean> = _showing

    fun start(context: Context) {
        if (started) return
        started = true
        // Strings in the language picked in the launcher, not the system's.
        val app = AppLanguage.wrap(context.applicationContext)
        scope.launch {
            // Again when reverse is engaged: the same design then moves above the camera.
            combine(style, CarBox.reversing) { style, _ -> style }.collect { style ->
                if (style != null && !Settings.canDrawOverlays(app)) PipAnchor.grantOverlayPermission(app)
                val w = window ?: AlertWindow(app, "call", AlertKind.CALL.cardAt.gravity, aboveCamera = { CarBox.reversing.value }).also { window = it }
                if (style != null && w.canShow()) {
                    _showing.value = w.show(style) { CallAlertContent(style) }
                } else {
                    window?.hide()
                    _showing.value = false
                }
            }
        }
    }
}

/**
 * The design a call shows in: the pill while reversing, so the camera stays
 * in view; the side panel and the full screen only while it rings, then the
 * slim bar.
 */
internal fun callStyle(call: PhoneCall, chosen: AlertStyle, reversing: Boolean): AlertStyle = when {
    reversing -> AlertStyle.PILL
    call.phase == CallState.Phase.RINGING || chosen != AlertStyle.PANEL && chosen != AlertStyle.FULL -> chosen
    else -> AlertStyle.CARD
}

/** The call in [style], following [PhoneCallOverlay.call]; the last call stays while it animates out. */
@Composable
private fun CallAlertContent(style: AlertStyle) {
    val live by PhoneCallOverlay.call.collectAsState()
    val call = rememberLast(live) ?: return
    CallAlert(call, style)
}

@Composable
private fun CallAlert(call: PhoneCall, style: AlertStyle) = when (style) {
    AlertStyle.PILL -> CallPill(call)
    AlertStyle.CARD -> CallCard(call)
    AlertStyle.BANNER -> CallBanner(call)
    AlertStyle.PANEL -> CallPanel(call)
    AlertStyle.FULL -> CallFullScreen(call)
}

/** The launcher's own call popup, for when the overlay window isn't allowed. */
@Composable
internal fun PhoneCallHost() {
    val call by PhoneCallOverlay.call.collectAsState()
    val style by PhoneCallOverlay.style.collectAsState()
    val overlay by PhoneCallOverlay.showing.collectAsState()
    val c = call ?: return
    val s = style ?: return
    if (overlay) return
    AlertPopup(s, AlertKind.CALL.cardAt.alignment) { CallAlert(c, s) }
}

/**
 * Keeps the overlay's composition running on its own: tied to the launcher's
 * activity it would pause whenever another app is in front, exactly when a
 * call card is needed most.
 */
internal class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    init {
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}

private val Answer = Color(0xFF2E9D4F)
private val HangUp = Color(0xFFD93A3A)

private val PhoneCall.ringing: Boolean get() = phase == CallState.Phase.RINGING
private val PhoneCall.title: String?
    get() = name ?: number

private fun answer(call: PhoneCall) {
    when {
        call.preview -> AlertPreview.stop()
        call.viaHeadUnit -> HeadUnitPhone.accept()
        else -> PhoneLink.callCommand(CallCommand.Action.ANSWER)
    }
}

private fun decline(call: PhoneCall) {
    when {
        call.preview -> AlertPreview.stop()
        call.viaHeadUnit -> HeadUnitPhone.decline()
        else -> PhoneLink.callCommand(CallCommand.Action.DECLINE)
    }
}

private fun hangUp(call: PhoneCall) {
    when {
        call.preview -> AlertPreview.stop()
        call.viaHeadUnit -> HeadUnitPhone.hangUp()
        else -> PhoneLink.callCommand(CallCommand.Action.HANG_UP)
    }
}

/** Decline and Answer while it rings, Hang up once it's on; nothing when the companion may not. */
@Composable
private fun CallButtons(call: PhoneCall, size: Dp, gap: Dp, labels: Boolean = false) {
    if (!call.canControl) return
    Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.Top) {
        if (call.ringing) {
            LabeledCallButton(Icons.Filled.CallEnd, HangUp, stringResource(R.string.phone_call_decline), size, labels) { decline(call) }
            LabeledCallButton(Icons.Filled.Call, Answer, stringResource(R.string.phone_call_answer), size, labels) { answer(call) }
        } else {
            LabeledCallButton(Icons.Filled.CallEnd, HangUp, stringResource(R.string.phone_call_hang_up), size, labels) { hangUp(call) }
        }
    }
}

@Composable
private fun LabeledCallButton(icon: ImageVector, color: Color, label: String, size: Dp, showLabel: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CallButton(icon, color, label, size, onClick)
        if (showLabel) {
            Spacer(Modifier.height(8.dp))
            Text(label, color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * "Incoming call · +33…" for a phone call, "WhatsApp · Incoming call" for an
 * app's; "Calling" for one the driver placed; then the duration once it's on
 * ("WhatsApp · 2:14").
 */
@Composable
private fun CallStatusLine(call: PhoneCall, withNumber: Boolean = true, align: TextAlign? = null) {
    when {
        call.dialing -> Text(
            listOfNotNull(call.app, stringResource(R.string.phone_call_outgoing)).joinToString(" · "),
            color = DashColors.TextSecondary, textAlign = align, style = MaterialTheme.typography.bodyMedium
        )
        call.ringing -> Text(
            listOfNotNull(
                call.app,
                stringResource(R.string.phone_call_incoming),
                call.number.takeIf { withNumber && call.name != null }
            ).joinToString(" · "),
            color = DashColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = align,
            style = MaterialTheme.typography.bodyMedium
        )
        else -> Row(verticalAlignment = Alignment.CenterVertically) {
            if (call.app != null) {
                Text(
                    "${call.app} · ", color = DashColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            CallDuration(call.answeredAt)
        }
    }
}

@Composable
private fun NoControlLine(call: PhoneCall, align: TextAlign? = null) {
    // The Calls permission is for the phone's own calls; an app's call without buttons is just shown.
    if (!call.canControl && call.app == null) {
        Text(stringResource(R.string.phone_call_no_control), color = DashColors.Warning, textAlign = align, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun callTitle(call: PhoneCall): String = call.title ?: stringResource(R.string.phone_call_unknown)

@Composable
internal fun CallCard(call: PhoneCall) {
    val ringing = call.ringing
    SolidCard {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = if (ringing) 16.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CallerAvatar(call, if (ringing) 64.dp else 44.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.widthIn(min = 160.dp, max = 340.dp)) {
                Text(
                    callTitle(call), color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = if (ringing) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium
                )
                CallStatusLine(call)
                NoControlLine(call)
            }
            if (call.canControl) {
                Spacer(Modifier.width(16.dp))
                CallButtons(call, if (ringing) 64.dp else 52.dp, 14.dp)
            }
        }
    }
}

/** A capsule at the top: the least in the way. */
@Composable
private fun CallPill(call: PhoneCall) {
    Surface(color = DashColors.Card.copy(alpha = 1f), shape = DashShape.Pill, shadowElevation = 6.dp, modifier = Modifier.border(1.dp, DashColors.Line, DashShape.Pill)) {
        Row(Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            CallerAvatar(call, 36.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.widthIn(max = 220.dp)) {
                Text(callTitle(call), color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                if (!call.ringing) CallStatusLine(call)
            }
            if (call.canControl) {
                Spacer(Modifier.width(12.dp))
                CallButtons(call, 40.dp, 8.dp)
            }
        }
    }
}

/** A strip across the whole top of the screen. */
@Composable
private fun CallBanner(call: PhoneCall) {
    Surface(
        color = DashColors.Card.copy(alpha = 1f),
        shape = DashShape.Medium,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).border(1.dp, DashColors.Line, DashShape.Medium)
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            CallerAvatar(call, 52.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(callTitle(call), color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
                CallStatusLine(call)
                NoControlLine(call)
            }
            CallButtons(call, 56.dp, 14.dp)
        }
    }
}

/** A full-height panel on the right: the caller large, the buttons large at the bottom. */
@Composable
private fun CallPanel(call: PhoneCall) {
    Surface(color = DashColors.Card.copy(alpha = 1f), shadowElevation = 12.dp, modifier = Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(1.dp).fillMaxSize().background(DashColors.Line))
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    listOfNotNull(call.app, stringResource(R.string.phone_call_incoming)).joinToString(" · ").uppercase(),
                    color = DashColors.Accent, letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge
                )
                Spacer(Modifier.weight(1f))
                CallerAvatar(call, 148.dp)
                Spacer(Modifier.height(22.dp))
                Text(
                    callTitle(call), color = DashColors.TextPrimary, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.headlineMedium
                )
                if (call.name != null && call.number != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(call.number, color = DashColors.TextSecondary, style = MaterialTheme.typography.titleMedium)
                }
                NoControlLine(call, TextAlign.Center)
                Spacer(Modifier.weight(1f))
                CallButtons(call, 84.dp, 56.dp, labels = true)
            }
        }
    }
}

/** The whole screen dimmed, the caller large in a card in the middle, until answered or declined. */
@Composable
private fun CallFullScreen(call: PhoneCall) {
    // Touches outside the card are held: nothing behind is pressed by mistake.
    FullScreenModal(onTap = {}) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 48.dp, vertical = 32.dp)) {
            CallerAvatar(call, 140.dp)
            Spacer(Modifier.height(20.dp))
            Text(
                callTitle(call), color = DashColors.TextPrimary, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.displaySmall
            )
            Spacer(Modifier.height(8.dp))
            CallStatusLine(call, align = TextAlign.Center)
            NoControlLine(call, TextAlign.Center)
            Spacer(Modifier.height(32.dp))
            CallButtons(call, 88.dp, 96.dp, labels = true)
        }
    }
}

@Composable
private fun CallerAvatar(call: PhoneCall, size: Dp) {
    val photo = call.photo
    if (photo != null) {
        Image(photo.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(CircleShape))
    } else {
        val initial = call.name?.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()
        Box(Modifier.size(size).clip(CircleShape).background(DashColors.CardHi), contentAlignment = Alignment.Center) {
            if (initial != null) Text(initial.toString(), color = DashColors.TextPrimary, fontSize = (size.value * 0.42f).sp)
            else Icon(Icons.Filled.Call, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(size / 2))
        }
    }
}

/** m:ss (h:mm:ss past an hour) since [answeredAt], ticking. */
@Composable
private fun CallDuration(answeredAt: Long) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(answeredAt) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    val total = ((now - answeredAt) / 1000).coerceAtLeast(0)
    val text = if (total >= 3600) "%d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60)
    else "%d:%02d".format(total / 60, total % 60)
    Text(text, color = DashColors.Good, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun CallButton(icon: ImageVector, color: Color, label: String, size: Dp, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .clickable(role = Role.Button, onClickLabel = label) { tap(); onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(size * 0.45f))
    }
}
