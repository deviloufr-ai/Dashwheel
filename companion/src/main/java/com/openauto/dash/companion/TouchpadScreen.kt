package com.openauto.dash.companion

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The phone as the car's mouse ([CarTouchpad]): one finger moves the pointer
 * on the car's screen, a tap taps there, a long touch holds there, two
 * fingers scroll. Back and Home are the car's own. The phone's screen stays
 * on while it is open.
 */
@Composable
internal fun TouchpadScreen(connected: Boolean, onClose: () -> Unit) {
    val status by CarTouchpad.status.collectAsState()
    val view = LocalView.current
    val haptics = LocalHapticFeedback.current
    // Opened again when the link comes back, so the pointer shows on the car again.
    DisposableEffect(connected) {
        CarTouchpad.open()
        view.keepScreenOn = true
        onDispose {
            CarTouchpad.close()
            view.keepScreenOn = false
        }
    }
    BackHandler(onBack = onClose)

    Column(
        Modifier
            .fillMaxSize()
            .background(CompanionColors.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.touchpad_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        CarTouchpad.message(status)?.let { message ->
            Text(
                stringResource(message), style = MaterialTheme.typography.bodyMedium, color = CompanionColors.Amber,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp)
            )
            // The car can't tap for the phone: one tap turns that on there.
            if (status == CarTouchpad.Status.OFF) {
                Button(onClick = { CarTouchpad.turnOn() }, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.keyboard_turn_on))
                }
            }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 12.dp)
                .background(CompanionColors.Surface, MaterialTheme.shapes.large)
                .border(1.dp, CompanionColors.Line, MaterialTheme.shapes.large)
                .pointerInput(Unit) {
                    pad(
                        onMove = { CarTouchpad.move(it.x / density, it.y / density) },
                        onScroll = { CarTouchpad.scroll(it.x / density, it.y / density) },
                        onTap = { CarTouchpad.tap() },
                        onHold = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            CarTouchpad.hold()
                        }
                    )
                }
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                Icon(Icons.Filled.Mouse, contentDescription = null, tint = CompanionColors.Line, modifier = Modifier.size(48.dp))
                Text(
                    stringResource(R.string.touchpad_hint), style = MaterialTheme.typography.bodyMedium,
                    color = CompanionColors.Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { CarTouchpad.back() }, enabled = connected, modifier = Modifier.weight(1f)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.touchpad_back))
            }
            OutlinedButton(onClick = { CarTouchpad.home() }, enabled = connected, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Home, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.touchpad_home))
            }
        }
    }
}

/**
 * The pad's touches, told apart: a finger that moves past the touch slop
 * moves the pointer (two fingers scroll), one that lifts before then taps,
 * one that stays down past the long-press time holds. Moves in pixels.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.pad(
    onMove: (Offset) -> Unit,
    onScroll: (Offset) -> Unit,
    onTap: () -> Unit,
    onHold: () -> Unit
) {
    val slop = viewConfiguration.touchSlop
    val holdMs = viewConfiguration.longPressTimeoutMillis
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var moved = false
        var twoFingers = false
        var held = false
        var travel = Offset.Zero
        var lastAt = down.uptimeMillis
        while (true) {
            // Still: the hold comes when the long-press time runs out with the finger down.
            val still = !moved && !twoFingers && !held
            val left = holdMs - (lastAt - down.uptimeMillis)
            val event = if (still) {
                if (left > 0) withTimeoutOrNull(left) { awaitPointerEvent() } else null
            } else {
                awaitPointerEvent()
            }
            if (event == null) {
                held = true
                onHold()
                continue
            }
            lastAt = event.changes.maxOf { it.uptimeMillis }
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            if (pressed.size >= 2) twoFingers = true
            // The fingers' average step: two fingers scrolling move together.
            val step = pressed.fold(Offset.Zero) { sum, change -> sum + (change.position - change.previousPosition) } / pressed.size.toFloat()
            event.changes.forEach { it.consume() }
            if (held) continue
            if (!moved) {
                travel += step
                if (travel.getDistance() <= slop) continue
                moved = true
                // The slop's worth of move is not lost: the pointer starts from where the finger did.
                if (twoFingers) onScroll(travel) else onMove(travel)
                continue
            }
            if (twoFingers) onScroll(step) else onMove(step)
        }
        if (!moved && !held && !twoFingers) onTap()
    }
}
