package com.openauto.dash

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.openauto.dash.link.CallState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.max

/*
 * How Dashwheel's own alerts look, chosen per alert in Settings, Look: the
 * phone's calls ([PhoneCallOverlay]) and the open doors ([DoorAlertOverlay]).
 * Each shows in an overlay window of its own ([AlertWindow]) over whatever app
 * fills the screen, or inside the launcher without "display over other apps"
 * ([AlertPopup]).
 */

/** The designs an alert can take, from the least in the way to the most. */
enum class AlertStyle(@StringRes val title: Int, @StringRes val detail: Int) {
    PILL(R.string.alert_style_pill, R.string.alert_style_pill_detail),
    CARD(R.string.alert_style_card, R.string.alert_style_card_detail),
    BANNER(R.string.alert_style_banner, R.string.alert_style_banner_detail),
    PANEL(R.string.alert_style_panel, R.string.alert_style_panel_detail),
    FULL(R.string.alert_style_full, R.string.alert_style_full_detail)
}

/**
 * The alerts whose design can be chosen, each with the designs that suit it
 * (the radar never covers the reversing camera) and whether it can be said
 * out loud ([AlertVoice]).
 */
enum class AlertKind(val key: String, val styles: List<AlertStyle>, val speakable: Boolean, val cardAt: CardAt) {
    CALL("call", AlertStyle.entries, speakable = true, cardAt = CardAt.TOP),
    DOORS("doors", AlertStyle.entries, speakable = true, cardAt = CardAt.TOP_END),
    RADAR("radar", listOf(AlertStyle.PILL, AlertStyle.CARD, AlertStyle.PANEL), speakable = false, cardAt = CardAt.END),
    AC("ac", listOf(AlertStyle.PILL, AlertStyle.CARD, AlertStyle.BANNER, AlertStyle.PANEL), speakable = false, cardAt = CardAt.BOTTOM),
    TYRES("tyres", AlertStyle.entries, speakable = true, cardAt = CardAt.TOP_END),
    // Shown while driving: never anything that covers the screen.
    BELT("belt", listOf(AlertStyle.PILL, AlertStyle.CARD, AlertStyle.BANNER), speakable = true, cardAt = CardAt.TOP)
}

/**
 * Where an alert's card sits: the call at the top, the doors in the corner,
 * the radar at the side of the reversing camera's picture, the climate at the
 * bottom like the car's own bar.
 */
enum class CardAt(val gravity: Int, val alignment: Alignment) {
    TOP(Gravity.TOP or Gravity.CENTER_HORIZONTAL, Alignment.TopCenter),
    TOP_END(Gravity.TOP or Gravity.END, Alignment.TopEnd),
    END(Gravity.END or Gravity.CENTER_VERTICAL, Alignment.CenterEnd),
    BOTTOM(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, Alignment.BottomCenter)
}

object AlertStyleStore {
    private const val PREFS = "alert_styles"

    private val _styles = MutableStateFlow<Map<AlertKind, AlertStyle>>(emptyMap())
    val styles: StateFlow<Map<AlertKind, AlertStyle>> = _styles

    private val _spoken = MutableStateFlow(setOf(AlertKind.DOORS, AlertKind.TYRES, AlertKind.BELT))
    /** The alerts also said out loud ([AlertVoice]); the doors until the driver says otherwise. */
    val spoken: StateFlow<Set<AlertKind>> = _spoken

    private val _messages = MutableStateFlow(true)
    /** Whether a message arriving on the phone is said: who it is from, never what it says ([AlertVoice]). */
    val messages: StateFlow<Boolean> = _messages

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _styles.value = AlertKind.entries.mapNotNull { kind ->
            p.getString(kind.key, null)?.let { name -> AlertStyle.entries.firstOrNull { it.name == name } }?.let { kind to it }
        }.toMap()
        _spoken.value = AlertKind.entries.filter { p.getBoolean("speak_${it.key}", it == AlertKind.DOORS || it == AlertKind.TYRES || it == AlertKind.BELT) }.toSet()
        _messages.value = p.getBoolean(KEY_MESSAGES, true)
    }

    private const val KEY_MESSAGES = "speak_messages"

    fun setMessages(context: Context, on: Boolean) {
        _messages.value = on
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_MESSAGES, on).apply()
    }

    fun set(context: Context, kind: AlertKind, style: AlertStyle) {
        _styles.update { it + (kind to style) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(kind.key, style.name).apply()
    }

    fun setSpoken(context: Context, kind: AlertKind, on: Boolean) {
        _spoken.update { if (on) it + kind else it - kind }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("speak_${kind.key}", on).apply()
    }
}

/** [kind]'s chosen design; the card until one is chosen (or when the chosen one doesn't suit it). */
fun Map<AlertKind, AlertStyle>.of(kind: AlertKind): AlertStyle = this[kind]?.takeIf { it in kind.styles } ?: AlertStyle.CARD

/**
 * "Try it" in the style picker: a made-up call or open doors for a few
 * seconds, in the chosen design, over whatever is on screen, and said out
 * loud when that alert is spoken.
 */
object AlertPreview {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    val call = MutableStateFlow<PhoneCall?>(null)
    val doors = MutableStateFlow<McuReader.DoorState?>(null)
    val radar = MutableStateFlow<Radar?>(null)
    val climate = MutableStateFlow<Climate?>(null)
    val tyres = MutableStateFlow<Map<TyrePos, Tyre>?>(null)
    val belt = MutableStateFlow(false)

    private const val SHOW_MS = 8_000L

    fun show(context: Context, kind: AlertKind) {
        stop()
        when (kind) {
            AlertKind.CALL -> PhoneCall(
                CallState.Phase.RINGING, number = "06 12 34 56 78", name = "Alex Martin", photo = null,
                answeredAt = 0, canControl = true, preview = true
            ).let {
                call.value = it
                AlertVoice.sayCall(context, it, force = true)
            }
            AlertKind.DOORS -> McuReader.DoorState(frontLeft = true, tailgate = true).let {
                doors.value = it
                AlertVoice.sayDoors(context, it.openNames(), force = true)
            }
            AlertKind.RADAR -> radar.value = CarBox.sampleRadar()
            AlertKind.AC -> climate.value = CarBox.sampleClimate()
            AlertKind.TYRES -> Tyres.sample().let {
                tyres.value = it
                AlertVoice.sayTyre(context, TyrePos.FRONT_LEFT, TyreProblem.LOW, force = true)
            }
            AlertKind.BELT -> {
                belt.value = true
                AlertVoice.sayBelt(context, force = true)
            }
        }
        job = scope.launch {
            delay(SHOW_MS)
            clear()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        clear()
    }

    private fun clear() {
        call.value = null
        doors.value = null
        radar.value = null
        climate.value = null
        tyres.value = null
        belt.value = false
    }
}

/** The side panel's width: a good third of the screen, never narrower than a phone. */
internal fun panelWidthDp(screenWidthDp: Int): Dp = max(380f, screenWidthDp * 0.36f).dp

/**
 * One alert's overlay window. Its size and place follow the design; showing
 * and hiding animate, and the window goes once the alert has animated out.
 */
internal class AlertWindow(
    private val context: Context,
    private val name: String,
    /** Where the card design sits: the call card top centre, the door card in the corner. */
    private val cardGravity: Int,
    /**
     * Whether to draw it through the accessibility service when that's on:
     * the only window Dashwheel can put above the ROM's reversing camera (an
     * app overlay sits under it). Asked at each [show]: the radar always, a
     * call while reversing. Such a window needs no "display over other apps".
     */
    private val aboveCamera: () -> Boolean = { false }
) {
    /** The shown window is the accessibility service's. */
    private var above = false
    private var wm: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null
    private var style: AlertStyle? = null
    private var visible: MutableTransitionState<Boolean>? = null

    /** Shows [content] in [style]'s window (a new one when the design changed); false when it can't be added. */
    fun show(style: AlertStyle, content: @Composable () -> Unit): Boolean {
        val host = if (aboveCamera()) SplitAccessibilityService.overlayHost() else null
        if (view != null && this.style == style && above == (host != null)) {
            visible?.targetState = true
            return true
        }
        removeNow()
        val hostContext = host ?: context
        wm = hostContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val type = if (host != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        // Set to show before the view is attached: its first composition may run inside addView.
        val state = MutableTransitionState(false).apply { targetState = true }
        val o = OverlayOwner()
        val v = ComposeView(hostContext)
        v.setViewTreeLifecycleOwner(o)
        v.setViewTreeSavedStateRegistryOwner(o)
        v.setContent {
            OpenAutoDashTheme {
                AlertMotion(style, state, onGone = { v.post { if (view === v) removeNow() } }, content = content)
            }
        }
        return runCatching { wm.addView(v, params(style, type)) }
            .onSuccess { view = v; owner = o; this.style = style; visible = state; above = host != null }
            .onFailure { Log.w(TAG, "could not add the $name window", it); o.destroy() }
            .isSuccess
    }

    /** Animates the alert out; the window goes after. */
    fun hide() {
        if (view == null) return
        visible?.targetState = false
    }

    fun removeNow() {
        val v = view ?: return
        view = null
        style = null
        visible = null
        runCatching { wm.removeViewImmediate(v) }.onFailure { Log.w(TAG, "remove failed", it) }
        owner?.destroy()
        owner = null
    }

    /** Whether [show] can put a window up at all: over other apps, or through the accessibility service. */
    fun canShow(): Boolean =
        aboveCamera() && SplitAccessibilityService.overlayHost() != null || android.provider.Settings.canDrawOverlays(context)

    private fun params(style: AlertStyle, type: Int): WindowManager.LayoutParams {
        val dm = context.resources.displayMetrics
        val margin = (24 * dm.density).toInt()
        val wrap = WindowManager.LayoutParams.WRAP_CONTENT
        val match = WindowManager.LayoutParams.MATCH_PARENT
        val panel = (panelWidthDp((dm.widthPixels / dm.density).toInt()).value * dm.density).toInt()
        val (w, h) = when (style) {
            AlertStyle.PILL, AlertStyle.CARD -> wrap to wrap
            AlertStyle.BANNER -> match to wrap
            AlertStyle.PANEL -> panel to match
            AlertStyle.FULL -> match to match
        }
        return WindowManager.LayoutParams(
            w, h,
            type,
            // Touchable (the buttons), but never takes the keyboard or the back key.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = when (style) {
                AlertStyle.CARD -> cardGravity
                AlertStyle.PILL, AlertStyle.BANNER -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                AlertStyle.PANEL -> Gravity.TOP or Gravity.END
                AlertStyle.FULL -> Gravity.TOP or Gravity.START
            }
            when (style) {
                AlertStyle.CARD -> {
                    if (cardGravity and Gravity.HORIZONTAL_GRAVITY_MASK != Gravity.CENTER_HORIZONTAL) x = margin
                    when (cardGravity and Gravity.VERTICAL_GRAVITY_MASK) {
                        Gravity.TOP -> y = margin
                        // Clear of the dashboard's bottom bar.
                        Gravity.BOTTOM -> y = margin * 4
                    }
                }
                AlertStyle.PILL -> y = margin / 2
                else -> Unit
            }
            title = "Dashwheel $name"
        }
    }

    private companion object {
        const val TAG = "AlertWindow"
    }
}

/** The design's way in and out: panels slide from the side, strips from the top, cards pop. */
@Composable
internal fun AlertMotion(style: AlertStyle, state: MutableTransitionState<Boolean>, onGone: () -> Unit, content: @Composable () -> Unit) {
    AnimatedVisibility(visibleState = state, enter = enterOf(style), exit = exitOf(style)) { content() }
    if (state.isIdle && !state.currentState && !state.targetState) LaunchedEffect(Unit) { onGone() }
}

private fun enterOf(style: AlertStyle): EnterTransition = when (style) {
    AlertStyle.CARD -> fadeIn(tween(180)) + scaleIn(tween(220), initialScale = 0.9f)
    AlertStyle.PILL, AlertStyle.BANNER -> slideInVertically(tween(260, easing = FastOutSlowInEasing)) { -it } + fadeIn(tween(200))
    AlertStyle.PANEL -> slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { it }
    AlertStyle.FULL -> fadeIn(tween(220)) + scaleIn(tween(260), initialScale = 1.04f)
}

private fun exitOf(style: AlertStyle): ExitTransition = when (style) {
    AlertStyle.CARD -> fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.9f)
    AlertStyle.PILL, AlertStyle.BANNER -> slideOutVertically(tween(220)) { -it } + fadeOut(tween(180))
    AlertStyle.PANEL -> slideOutHorizontally(tween(260)) { it }
    AlertStyle.FULL -> fadeOut(tween(200))
}

/**
 * An alert inside the launcher, for when the overlay window isn't allowed:
 * the same design, in the same place, over the dashboard only.
 */
@Composable
internal fun AlertPopup(style: AlertStyle, cardAlignment: Alignment, content: @Composable () -> Unit) {
    val margin = with(LocalDensity.current) { 24.dp.roundToPx() }
    val (alignment, offset) = when (style) {
        AlertStyle.CARD -> cardAlignment to when (cardAlignment) {
            Alignment.TopEnd -> IntOffset(-margin, margin)
            Alignment.CenterEnd -> IntOffset(-margin, 0)
            Alignment.BottomCenter -> IntOffset(0, -margin * 4)
            else -> IntOffset(0, margin)
        }
        AlertStyle.PILL -> Alignment.TopCenter to IntOffset(0, margin / 2)
        AlertStyle.BANNER -> Alignment.TopCenter to IntOffset.Zero
        AlertStyle.PANEL -> Alignment.TopEnd to IntOffset.Zero
        AlertStyle.FULL -> Alignment.Center to IntOffset.Zero
    }
    val panelWidth = panelWidthDp(LocalConfiguration.current.screenWidthDp)
    Popup(alignment = alignment, offset = offset, properties = PopupProperties(focusable = false)) {
        val size = when (style) {
            AlertStyle.PANEL -> Modifier.fillMaxHeight().width(panelWidth)
            AlertStyle.FULL -> Modifier.fillMaxSize()
            AlertStyle.BANNER -> Modifier.fillMaxWidth()
            else -> Modifier
        }
        Box(size) { content() }
    }
}

/**
 * The full-screen design: everything behind dimmed, [content] in a solid card
 * in the middle. A tap outside the card runs [onTap] (and never reaches what's behind).
 */
@Composable
internal fun FullScreenModal(onTap: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = DashColors.Card.copy(alpha = 1f),
            shape = DashShape.Large,
            shadowElevation = 16.dp,
            modifier = Modifier.fillMaxWidth(0.72f).fillMaxHeight(0.78f).border(1.dp, DashColors.Line, DashShape.Large)
        ) {
            Box(contentAlignment = Alignment.Center) { content() }
        }
    }
}

/** [value], or the last one it had while the alert animates out after it went null. */
@Composable
internal fun <T : Any> rememberLast(value: T?): T? {
    val last = remember { mutableStateOf(value) }
    if (value != null && last.value != value) last.value = value
    return value ?: last.value
}
