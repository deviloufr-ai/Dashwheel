package com.openauto.dash

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/*
 * The "about to act" step. A tap that places a call, sends a text or replaces
 * the route does not act at once: a strip on the tile says what is about to
 * happen, with the name, beside a big Cancel, and the action runs about three
 * seconds later. A finger that landed on the wrong row at the wheel can take
 * it back.
 */

/** How long the strip waits before acting. */
internal const val PENDING_ACTION_MS = 3_000L

/**
 * The whole seconds the strip still shows, [armedAt] and [now] on the same
 * clock: 3, 2, 1, then 0 once the action is due.
 */
internal fun pendingSecondsLeft(armedAt: Long, now: Long, delayMs: Long = PENDING_ACTION_MS): Int {
    val left = (delayMs - (now - armedAt)).coerceIn(0L, delayMs)
    return ((left + 999L) / 1000L).toInt()
}

/** What a tile is about to do: [label] says it ("Calling Alex"), [run] does it. */
internal class PendingAction(val label: String, val run: () -> Unit)

/** The one action a tile holds back, shown by its [PendingActionStrip]. */
internal class PendingActionState {
    var pending by mutableStateOf<PendingAction?>(null)
        private set

    /** A tap on another row while one waits replaces it: the last name tapped is the one meant. */
    fun arm(label: String, run: () -> Unit) {
        pending = PendingAction(label, run)
    }

    fun cancel() {
        pending = null
    }

    /** The countdown of [action] ran out: it runs, unless it was cancelled or replaced meanwhile. */
    fun fire(action: PendingAction) {
        if (pending !== action) return
        pending = null
        action.run()
    }
}

@Composable
internal fun rememberPendingAction(): PendingActionState = remember { PendingActionState() }

/**
 * The strip itself, laid over the bottom of the tile while [state] holds an
 * action: seconds left, what is about to happen, Cancel. Nothing otherwise.
 * A tile that leaves the screen takes its countdown with it: nothing is
 * called or sent from a page the driver no longer sees.
 */
@Composable
internal fun PendingActionStrip(state: PendingActionState, modifier: Modifier = Modifier) {
    val action = state.pending ?: return
    var left by remember(action) { mutableIntStateOf(pendingSecondsLeft(0L, 0L)) }
    LaunchedEffect(action) {
        val armedAt = SystemClock.elapsedRealtime()
        while (true) {
            left = pendingSecondsLeft(armedAt, SystemClock.elapsedRealtime())
            if (left == 0) break
            delay(100)
        }
        state.fire(action)
    }
    val tap = rememberTapFeedback()
    val shape = DashShape.Medium
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            // Opaque on every theme: it covers the rows under it.
            .background(DashColors.Card.copy(alpha = 1f))
            .border(1.dp, DashColors.Accent, shape)
            // A second tap on the same spot lands here, not on the row underneath.
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(left.toString(), color = DashColors.Accent, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.width(12.dp))
        Text(
            action.label, color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = { tap(); state.cancel() },
            colors = ButtonDefaults.buttonColors(containerColor = DashColors.CardHi, contentColor = DashColors.TextPrimary),
            shape = DashShape.Medium,
            modifier = Modifier.heightIn(min = DashSize.TouchPrimary)
        ) { Text(stringResource(R.string.dash_cancel), maxLines = 1) }
    }
}
