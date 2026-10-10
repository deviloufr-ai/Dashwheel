package com.openauto.dash

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ViewQuilt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/*
 * The first-run tour: six tips drawn over the real dashboard, one idea each.
 * The thing a tip is about is lit up through a hole in the dimmed screen (its
 * place comes from [tourTarget]); the card sits beside it. Read aloud by
 * default, Skip on every card, parked only (the drive lock closes it).
 */

internal enum class TourStep { WELCOME, SWIPE, ARRANGE, ADD, LOOK, OBD, HANDS_FREE, DONE }

/** What a tip lights up. Each is marked where it is drawn, in whichever bar or skin is on screen. */
internal enum class TourTarget { PAGES, TILE, ADD, MENU, OBD }

private val TourStep.target: TourTarget?
    get() = when (this) {
        TourStep.SWIPE -> TourTarget.PAGES
        TourStep.ARRANGE -> TourTarget.TILE
        TourStep.ADD -> TourTarget.ADD
        TourStep.LOOK -> TourTarget.MENU
        TourStep.OBD -> TourTarget.OBD
        else -> null
    }

/** These tips light up something on the bottom bar: it must not hide itself under them (BarAutoHide.kt). */
internal val TourStep.onBar: Boolean get() = target == TourTarget.MENU || target == TourTarget.OBD

/**
 * The steps in order. Right after the setup ([afterSetup]) the look tip is
 * left out: the setup's last step just asked for a look.
 */
private fun tourOrder(afterSetup: Boolean): List<TourStep> =
    TourStep.entries.filter { !(afterSetup && it == TourStep.LOOK) }

/** The tips between the welcome and the end. */
private fun List<TourStep>.tips(): List<TourStep> = filter { it != TourStep.WELCOME && it != TourStep.DONE }

/** Arranging is shown while these tips are up: they are about the edit bar and the tiles in it. */
internal val TourStep.wantsEditing: Boolean get() = this == TourStep.ARRANGE || this == TourStep.ADD

object TourStore {
    private const val PREFS = "tour"
    private const val KEY_PENDING = "pending"
    private const val KEY_SEEN = "seen"
    private const val KEY_SPEAK = "speak"
    private const val KEY_DECLINED = "declined"

    /** True from the end of the first setup until the tour is finished or skipped. */
    fun isPending(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PENDING, false)

    /** The setup closed: the tour is offered next, unless it was already seen. */
    fun offer(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_SEEN, false)) prefs.edit().putBoolean(KEY_PENDING, true).apply()
    }

    /** Finished or skipped: not offered again (Settings, About still starts it). */
    fun markSeen(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PENDING, false).putBoolean(KEY_SEEN, true).apply()
    }

    /** "Not now" on the welcome: still pending, so the next parked start offers it once more; a second "Not now" counts as seen. */
    fun notNow(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val declined = prefs.getInt(KEY_DECLINED, 0) + 1
        if (declined >= 2) markSeen(context) else prefs.edit().putInt(KEY_DECLINED, declined).apply()
    }

    fun readAloud(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SPEAK, true)

    fun setReadAloud(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SPEAK, on).apply()
    }
}

/** Where each [TourTarget] is on screen, in root coordinates. Several may report (pager pages): the biggest visible one wins. */
internal object TourTargets {
    private val places = mutableStateMapOf<Pair<TourTarget, Any>, Rect>()

    fun put(target: TourTarget, owner: Any, bounds: Rect) {
        if (places[target to owner] != bounds) places[target to owner] = bounds
    }

    fun remove(target: TourTarget, owner: Any) {
        places.remove(target to owner)
    }

    fun boundsOf(target: TourTarget): Rect? =
        places.filterKeys { it.first == target }.values
            .filter { it.width > 1f && it.height > 1f }
            .maxByOrNull { it.width * it.height }
}

/** Marks what [target] lights up during the tour. Pager pages off screen are clipped to nothing and so never win. */
internal fun Modifier.tourTarget(target: TourTarget): Modifier = composed {
    val owner = remember { Any() }
    DisposableEffect(owner) { onDispose { TourTargets.remove(target, owner) } }
    onGloballyPositioned { TourTargets.put(target, owner, it.boundsInRoot()) }
}

/** Title and body of a step, also what is read aloud. */
private class TourText(@StringRes val title: Int, @StringRes val body: Int, @StringRes val hint: Int? = null)

private val TourStep.text: TourText
    get() = when (this) {
        TourStep.WELCOME -> TourText(R.string.tour_welcome_title, R.string.tour_welcome_body)
        TourStep.SWIPE -> TourText(R.string.tour_swipe_title, R.string.tour_swipe_body, R.string.tour_try_swipe)
        TourStep.ARRANGE -> TourText(R.string.tour_arrange_title, R.string.tour_arrange_body, R.string.tour_arrange_hint)
        TourStep.ADD -> TourText(R.string.tour_add_title, R.string.tour_add_body, R.string.tour_add_hint)
        TourStep.LOOK -> TourText(R.string.tour_look_title, R.string.tour_look_body)
        TourStep.OBD -> TourText(R.string.tour_obd_title, R.string.tour_obd_body)
        TourStep.HANDS_FREE -> TourText(R.string.tour_hands_title, R.string.tour_hands_body, R.string.tour_hands_hint)
        TourStep.DONE -> TourText(R.string.tour_done_title, R.string.tour_done_body)
    }

/**
 * The tour over the whole dashboard. Touches outside the lit-up hole are
 * held back; inside it they reach the dashboard, so the driver can try the
 * gesture (a swipe on the pages moves the tour on, see the caller).
 */
@Composable
internal fun TourOverlay(
    step: TourStep,
    obd: ObdConnectionState,
    onStep: (TourStep) -> Unit,
    /** [finished]: the last card was reached, rather than skipped. */
    onClose: (finished: Boolean) -> Unit,
    /** Offered by itself after the first setup, rather than replayed from Settings. */
    afterSetup: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val order = remember(afterSetup) { tourOrder(afterSetup) }
    val density = LocalDensity.current
    var readAloud by remember { mutableStateOf(TourStore.readAloud(context)) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val target = step.target?.let { TourTargets.boundsOf(it) }
    val padPx = with(density) { 8.dp.toPx() }
    val hole = target?.translate(-origin.x, -origin.y)?.inflate(padPx)

    // Read aloud: the title and the body, in the app's language.
    val text = step.text
    LaunchedEffect(step, readAloud) {
        // The tip before stops there: the voice never runs behind the screen.
        CarVoice.stop()
        if (!readAloud) return@LaunchedEffect
        val res = AppLanguage.wrap(context.applicationContext).resources
        CarVoice.speak(res.getString(text.title) + ". " + res.getString(text.body), res.configuration.locales[0])
    }

    // An auto-hiding bar stays up under the tips that point at it, and goes back to its wait after them.
    DisposableEffect(step.onBar) {
        BarAutoHide.heldByTour = step.onBar
        onDispose { BarAutoHide.heldByTour = false }
    }

    val at = order.indexOf(step)
    val next = order.getOrNull(at + 1)
    val prev = order.getOrNull(at - 1)?.takeIf { it != TourStep.WELCOME }

    BoxWithConstraints(modifier = modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        Dim(hole)
        // Hold back touches everywhere but the hole.
        Blockers(hole, w, h)
        when (step) {
            TourStep.SWIPE -> if (hole != null) SwipeHint(hole)
            TourStep.ARRANGE -> if (hole != null) HoldHint(hole)
            else -> {}
        }
        CardPlacer(hole) {
            TourCard(
                step = step,
                tips = order.tips(),
                readAloud = readAloud,
                onReadAloud = {
                    readAloud = !readAloud
                    TourStore.setReadAloud(context, readAloud)
                },
                obd = obd,
                onNext = { if (next != null) onStep(next) else onClose(true) },
                onBack = prev?.let { { onStep(it) } },
                onSkip = { onClose(step == TourStep.DONE) }
            )
        }
    }
}

private val DIM = Color(0xFF030508).copy(alpha = 0.74f)
private val CARD_MAX_WIDTH = 560.dp

/** The dimmed screen, with the hole cut out and a ring pulsing round it. */
@Composable
private fun Dim(hole: Rect?) {
    val accent = DashColors.Accent
    val pulse = rememberInfiniteTransition(label = "tourPulse")
    val grow by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "grow")
    val density = LocalDensity.current
    val corner = with(density) { 22.dp.toPx() }
    val ringW = with(density) { 3.dp.toPx() }
    val spread = with(density) { 16.dp.toPx() }
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    ) {
        drawRect(DIM)
        if (hole != null) {
            drawRoundRect(Color.Transparent, hole.topLeft, hole.size, CornerRadius(corner), blendMode = BlendMode.Clear)
            drawRoundRect(accent, hole.topLeft, hole.size, CornerRadius(corner), style = Stroke(ringW))
            val g = spread * grow
            drawRoundRect(
                accent.copy(alpha = 1f - grow), Offset(hole.left - g, hole.top - g), Size(hole.width + 2 * g, hole.height + 2 * g),
                CornerRadius(corner + g), style = Stroke(ringW)
            )
        }
    }
}

/** Four touch catchers round the hole (one over everything when there is none). */
@Composable
private fun Blockers(hole: Rect?, w: Float, h: Float) {
    val parts = if (hole == null) listOf(Rect(0f, 0f, w, h)) else listOf(
        Rect(0f, 0f, w, hole.top.coerceAtLeast(0f)),
        Rect(0f, hole.bottom.coerceAtMost(h), w, h),
        Rect(0f, hole.top, hole.left.coerceAtLeast(0f), hole.bottom),
        Rect(hole.right.coerceAtMost(w), hole.top, w, hole.bottom)
    )
    parts.filter { it.width > 0f && it.height > 0f }.forEach { r -> Catcher(r) }
}

@Composable
private fun Catcher(r: Rect) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
            .size(with(density) { r.width.toDp() }, with(density) { r.height.toDp() })
            .pointerInput(Unit) { detectTapGestures { } }
    )
}

/** A finger crossing the pages, right to left. */
@Composable
private fun SwipeHint(hole: Rect) {
    val density = LocalDensity.current
    val t = rememberInfiniteTransition(label = "swipe")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart), label = "p")
    val travel = with(density) { 160.dp.toPx() }
    val x = hole.center.x + travel * (1f - 2f * (p / 0.75f).coerceAtMost(1f))
    val y = hole.top + hole.height * 0.28f
    val alpha = when {
        p < 0.15f -> p / 0.15f
        p > 0.75f -> 1f - (p - 0.75f) / 0.25f
        else -> 1f
    }
    Finger(Offset(x, y), alpha)
}

/** A finger holding the tile while a ring fills round it, like the long press it stands for. */
@Composable
private fun HoldHint(hole: Rect) {
    val density = LocalDensity.current
    val accent = DashColors.Accent
    val t = rememberInfiniteTransition(label = "hold")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart), label = "p")
    val at = Offset(hole.left + hole.width * 0.3f, hole.top + hole.height * 0.7f)
    val r = with(density) { 48.dp.toPx() }
    val ringW = with(density) { 6.dp.toPx() }
    Canvas(Modifier.fillMaxSize()) {
        drawArc(
            accent, -90f, 360f * (p / 0.7f).coerceAtMost(1f), false,
            Offset(at.x - r, at.y - r), Size(2 * r, 2 * r), style = Stroke(ringW, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        )
    }
    Finger(at, 1f)
}

@Composable
private fun Finger(at: Offset, alpha: Float) {
    val density = LocalDensity.current
    val size = 56.dp
    val half = with(density) { (size / 2).toPx() }
    Box(
        modifier = Modifier
            .offset { IntOffset((at.x - half).roundToInt(), (at.y - half).roundToInt()) }
            .graphicsLayer { this.alpha = alpha }
            .size(size)
            .border(10.dp, Color.White.copy(alpha = 0.18f), CircleShape)
            .padding(4.dp)
            .background(Color.White.copy(alpha = 0.9f), CircleShape)
    )
}

/**
 * Puts the card beside the hole where it fits (right or left, then above or
 * below), else over the middle of the screen.
 */
@Composable
private fun CardPlacer(hole: Rect?, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    Layout(content = content, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
        val gap = with(density) { 20.dp.roundToPx() }
        val edge = with(density) { 16.dp.roundToPx() }
        val maxW = with(density) { CARD_MAX_WIDTH.roundToPx() }.coerceAtMost(constraints.maxWidth - 2 * edge)
        val card = measurables.first().measure(Constraints(maxWidth = maxW, maxHeight = constraints.maxHeight - 2 * edge))
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        fun clampX(x: Float) = x.roundToInt().coerceIn(edge, (w - card.width - edge).coerceAtLeast(edge))
        fun clampY(y: Float) = y.roundToInt().coerceIn(edge, (h - card.height - edge).coerceAtLeast(edge))
        val centre = IntOffset((w - card.width) / 2, (h - card.height) / 2)
        val at = when {
            hole == null -> centre
            w - hole.right >= card.width + gap + edge && w - hole.right >= hole.left ->
                IntOffset(clampX(hole.right + gap), clampY(hole.center.y - card.height / 2f))
            hole.left >= card.width + gap + edge ->
                IntOffset(clampX(hole.left - gap - card.width), clampY(hole.center.y - card.height / 2f))
            hole.top >= card.height + gap + edge ->
                IntOffset(clampX(hole.center.x - card.width / 2f), (hole.top - gap - card.height).roundToInt())
            h - hole.bottom >= card.height + gap + edge ->
                IntOffset(clampX(hole.center.x - card.width / 2f), (hole.bottom + gap).roundToInt())
            else -> centre
        }
        layout(w, h) { card.place(at) }
    }
}

@Composable
private fun TourCard(
    step: TourStep,
    tips: List<TourStep>,
    readAloud: Boolean,
    onReadAloud: () -> Unit,
    obd: ObdConnectionState,
    onNext: () -> Unit,
    onBack: (() -> Unit)?,
    onSkip: () -> Unit
) {
    val text = step.text
    Column(
        modifier = Modifier
            .keepClearOfWindows()
            .widthIn(max = CARD_MAX_WIDTH)
            .clip(DashShape.Large)
            // Solid: a glass card would let the tiles' text show through.
            .background(DashColors.Card.compositeOver(DashColors.Background).copy(alpha = 1f))
            .border(1.dp, DashColors.TextPrimary.copy(alpha = 0.10f), DashShape.Large)
            // The card takes its own taps: none fall through to the dashboard.
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(horizontal = 26.dp, vertical = 22.dp)
    ) {
        CardHead(step, tips, readAloud, onReadAloud)
        if (step == TourStep.DONE) {
            Box(
                Modifier
                    .padding(top = 4.dp)
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(DashColors.Accent)
                    .align(Alignment.CenterHorizontally),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.DirectionsCar, null, tint = DashColors.OnAccent, modifier = Modifier.size(34.dp)) }
        }
        Text(
            stringResource(text.title),
            color = DashColors.TextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = if (step == TourStep.WELCOME || step == TourStep.DONE) 34.sp else 28.sp,
            lineHeight = 36.sp,
            modifier = Modifier.padding(top = 10.dp, bottom = 8.dp)
        )
        Text(stringResource(text.body), color = DashColors.TextSecondary, fontSize = 19.sp, lineHeight = 27.sp)
        when (step) {
            TourStep.WELCOME -> Topics(tips)
            TourStep.OBD -> ObdStates(obd)
            TourStep.HANDS_FREE -> WheelJobs()
            else -> {}
        }
        text.hint?.let {
            Text(
                stringResource(it),
                color = if (step == TourStep.SWIPE) DashColors.Good else DashColors.Muted,
                fontSize = 16.sp,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
        CardFoot(step, onNext, onBack, onSkip)
    }
}

@Composable
private fun CardHead(step: TourStep, tips: List<TourStep>, readAloud: Boolean, onReadAloud: () -> Unit) {
    val tap = rememberTapFeedback()
    Row(verticalAlignment = Alignment.CenterVertically) {
        val tip = tips.indexOf(step)
        val kicker = when {
            step == TourStep.WELCOME -> stringResource(R.string.tour_welcome_kicker)
            tip >= 0 -> stringResource(R.string.tour_tip, tip + 1, tips.size)
            else -> ""
        }
        Text(kicker.uppercase(), color = DashColors.AccentInk, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 2.sp)
        if (tip >= 0) {
            Spacer(Modifier.width(10.dp))
            tips.forEachIndexed { i, _ ->
                Box(
                    Modifier
                        .padding(end = 6.dp)
                        .size(width = if (i == tip) 24.dp else 8.dp, height = 8.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                i == tip -> DashColors.Accent
                                i < tip -> DashColors.Accent.copy(alpha = 0.5f)
                                else -> DashColors.TextPrimary.copy(alpha = 0.18f)
                            }
                        )
                )
            }
        }
        Spacer(Modifier.weight(1f))
        val label = stringResource(R.string.tour_read_aloud)
        Box(
            modifier = Modifier
                .size(DashSize.Touch)
                .clip(CircleShape)
                .background(if (readAloud) DashColors.Accent.copy(alpha = 0.18f) else DashColors.CardHi)
                .clickable(role = Role.Switch) { tap(); onReadAloud() }
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (readAloud) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff, null,
                tint = if (readAloud) DashColors.Accent else DashColors.Muted, modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun CardFoot(step: TourStep, onNext: () -> Unit, onBack: (() -> Unit)?, onSkip: () -> Unit) {
    val tap = rememberTapFeedback()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp)
    ) {
        if (step != TourStep.DONE) {
            Text(
                stringResource(if (step == TourStep.WELCOME) R.string.tour_not_now else R.string.tour_skip),
                color = DashColors.Muted,
                fontSize = 18.sp,
                modifier = Modifier
                    .clip(DashShape.Small)
                    .clickable(role = Role.Button) { tap(); onSkip() }
                    .heightIn(min = DashSize.Touch)
                    .padding(horizontal = 6.dp, vertical = 12.dp)
            )
        }
        Spacer(Modifier.weight(1f))
        if (onBack != null) {
            val back = stringResource(R.string.tour_back)
            Box(
                modifier = Modifier
                    .size(DashSize.TouchPrimary)
                    .clip(CircleShape)
                    .background(DashColors.CardHi)
                    .clickable(role = Role.Button) { tap(); onBack() }
                    .semantics { contentDescription = back },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = DashColors.TextPrimary) }
        }
        val label = stringResource(
            when (step) {
                TourStep.WELCOME -> R.string.tour_show_me
                TourStep.HANDS_FREE -> R.string.tour_finish
                TourStep.DONE -> R.string.tour_start_driving
                else -> R.string.tour_next
            }
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = DashSize.TouchPrimary)
                .clip(DashShape.Pill)
                .background(DashColors.Accent)
                .clickable(role = Role.Button) { tap(); onNext() }
                .padding(horizontal = 26.dp)
        ) {
            Text(label, color = DashColors.OnAccent, fontWeight = FontWeight.Bold, fontSize = 19.sp)
            if (step != TourStep.DONE) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = DashColors.OnAccent, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** The welcome card's list of what is coming: one chip per tip in [tips]. */
@Composable
private fun Topics(tips: List<TourStep>) {
    val topics = tips.mapNotNull { tip ->
        when (tip) {
            TourStep.SWIPE -> Icons.Filled.SwapHoriz to R.string.tour_topic_swipe
            TourStep.ARRANGE -> Icons.Filled.OpenWith to R.string.tour_topic_arrange
            TourStep.ADD -> Icons.Filled.Add to R.string.tour_topic_add
            TourStep.LOOK -> Icons.Filled.Palette to R.string.tour_topic_look
            TourStep.OBD -> Icons.Filled.DirectionsCar to R.string.tour_topic_obd
            TourStep.HANDS_FREE -> Icons.Filled.Mic to R.string.tour_topic_hands
            else -> null
        }
    }
    Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        topics.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (icon, label) -> IconChip(icon, stringResource(label), Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun IconChip(icon: ImageVector, label: String, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(DashShape.Medium)
            .background(DashColors.CardHi)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(DashColors.Accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = DashColors.Accent, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(10.dp))
        Text(label, color = DashColors.TextPrimary, fontSize = 16.sp, lineHeight = 19.sp)
    }
}

/** The OBD pill's three faces, the one it shows now outlined. */
@Composable
private fun ObdStates(obd: ObdConnectionState) {
    val states = listOf(
        ObdConnectionState.DISCONNECTED to R.string.tour_obd_off,
        ObdConnectionState.CONNECTING to R.string.tour_obd_looking,
        ObdConnectionState.CONNECTED to R.string.tour_obd_connected
    )
    val now = if (obd == ObdConnectionState.ERROR) ObdConnectionState.DISCONNECTED else obd
    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        states.forEach { (state, label) ->
            val color = if (state == ObdConnectionState.DISCONNECTED) DashColors.Muted else obdStatusColor(state)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .clip(DashShape.Pill)
                    .background(color.copy(alpha = 0.12f))
                    .then(if (state == now) Modifier.border(2.dp, color, DashShape.Pill) else Modifier)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(label), color = if (state == now) DashColors.TextPrimary else DashColors.TextSecondary, fontSize = 15.sp, maxLines = 1)
            }
        }
    }
}

/** What the wheel's buttons and the voice can do, one line each. */
@Composable
private fun WheelJobs() {
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconChip(Icons.Filled.ViewQuilt, stringResource(R.string.tour_wheel_dashboard), Modifier.weight(1f))
            IconChip(Icons.Filled.MusicNote, stringResource(R.string.tour_wheel_music), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconChip(Icons.Filled.Mic, stringResource(R.string.tour_wheel_voice), Modifier.weight(1f))
            IconChip(Icons.Filled.NotificationsActive, stringResource(R.string.tour_wheel_alerts), Modifier.weight(1f))
        }
    }
}
