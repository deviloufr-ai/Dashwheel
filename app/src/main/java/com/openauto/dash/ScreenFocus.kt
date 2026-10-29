package com.openauto.dash

import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * The steering wheel's mode 1 and mode 2. A wheel button set to "Mode 1 / 2"
 * goes from one to the other, without a word. In mode 2 each button does its
 * second action (WheelButton.second); one with none does its own, and its
 * page buttons then move the second screen's cluster: the focus goes there.
 * The screen in focus carries a dot in its corner, and a frame lights round
 * it for a moment as it gets it; with one screen, the dot means mode 2.
 */

internal object ScreenFocus {
    /** How long the frame shows round the screen that just got the focus. */
    const val FLASH_MS = 700L

    private val _second = MutableStateFlow(false)
    /** The driver chose the second screen. It counts only while that screen shows the cluster ([focused]). */
    val second: StateFlow<Boolean> = _second.asStateFlow()

    private val _switchedAt = MutableStateFlow(0L)
    /** When the focus last moved ([SystemClock.elapsedRealtime]); 0: not yet. */
    val switchedAt: StateFlow<Long> = _switchedAt.asStateFlow()

    /** The screen the dashboard buttons move: 2 while the second screen is chosen and shows the cluster, else 1. */
    fun focused(chosenSecond: Boolean, output: SecondScreenOutput): Int =
        if (chosenSecond && clusterShown(output)) 2 else 1

    /** The second screen shows the cluster, whose pages the buttons can turn. */
    fun clusterShown(output: SecondScreenOutput): Boolean =
        output == SecondScreenOutput.VIDEO_CLUSTER || output == SecondScreenOutput.DATA

    /**
     * The screen to mark, or null when none is: only with a wheel button that
     * switches the mode. With a second screen, the one in focus; with one
     * screen, the unit's own, in mode 2 only.
     */
    fun mark(bound: Boolean, chosenSecond: Boolean, output: SecondScreenOutput): Int? = when {
        !bound -> null
        clusterShown(output) -> focused(chosenSecond, output)
        chosenSecond -> 1
        else -> null
    }

    /** [mark] as things stand, for the second screen's feed. */
    fun marked(): Int? = mark(bound(SteeringWheelStore.buttons.value), _second.value, SecondScreenController.status.value.output)

    /** The frame is still up round the screen that just got the focus. */
    fun flashing(now: Long = SystemClock.elapsedRealtime()): Boolean =
        _switchedAt.value > 0 && now - _switchedAt.value < FLASH_MS

    fun bound(buttons: List<WheelButton>): Boolean =
        buttons.any { it.switchesMode }

    val onSecond: Boolean
        get() = focused(_second.value, SecondScreenController.status.value.output) == 2

    /**
     * From mode 1 to mode 2 and back. With neither a cluster on a second
     * screen nor a button with a second action, mode 2 would do nothing: it
     * stays on 1, and a short note says so.
     */
    fun toggle(context: Context) {
        val next = !_second.value
        if (next && !clusterShown(SecondScreenController.status.value.output) && SteeringWheelStore.buttons.value.none { it.second != null }) {
            _second.value = false
            val app = context.applicationContext
            Toast.makeText(app, AppLanguage.wrap(app).resources.getString(R.string.wheel_focus_no_second), Toast.LENGTH_SHORT).show()
            return
        }
        _second.value = next
        _switchedAt.value = SystemClock.elapsedRealtime()
    }

    /**
     * A second screen page button pressed: the unit's dashboards move instead
     * while a wheel button switches the focus and screen 1 has it. Without
     * one, the button keeps to the second screen as it always did.
     */
    fun unitFocused(): Boolean = pageTarget(bound(SteeringWheelStore.buttons.value), onSecond) == 1

    /** The screen a page button moves, whichever screen it names: the focused one once a button switches the focus, else null (its own). */
    fun pageTarget(bound: Boolean, onSecond: Boolean): Int? = if (!bound) null else if (onSecond) 2 else 1

    /** A dashboard button pressed: on the second screen while it has the focus. False when it is the unit's own to move. */
    fun step(step: Int): Boolean {
        if (!onSecond) return false
        SecondScreenController.turnPage(step)
        return true
    }
}

/**
 * Over the whole of [screen] (1: the dashboard, 2: the streamed cluster):
 * while it has the wheel's focus, a dot in its top right corner, and a frame
 * in [accent] round it for a moment when the focus has just come to it.
 * Nothing at all with one screen in use. Takes no touches.
 */
@Composable
internal fun ScreenFocusMark(screen: Int, accent: Color = DashColors.Accent) {
    val buttons by SteeringWheelStore.buttons.collectAsState()
    val chosen by ScreenFocus.second.collectAsState()
    val status by SecondScreenController.status.collectAsState()
    val switchedAt by ScreenFocus.switchedAt.collectAsState()
    val marked = ScreenFocus.mark(ScreenFocus.bound(buttons), chosen, status.output)
    val here by rememberUpdatedState(marked == screen)
    val flash = remember { Animatable(0f) }
    LaunchedEffect(switchedAt) {
        if (switchedAt == 0L || !here) return@LaunchedEffect
        flash.snapTo(1f)
        flash.animateTo(0f, tween(ScreenFocus.FLASH_MS.toInt()))
    }
    if (marked == null) return
    Box(
        Modifier.fillMaxSize().drawBehind {
            // Read while drawing: the fade redraws the frame, not the screen under it.
            val a = flash.value
            if (a <= 0f) return@drawBehind
            val w = 4.dp.toPx()
            drawRoundRect(
                accent.copy(alpha = a), Offset(w / 2, w / 2), Size(size.width - w, size.height - w),
                CornerRadius(12.dp.toPx()), style = Stroke(w)
            )
        }
    ) {
        if (marked == screen) {
            Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(10.dp).clip(CircleShape).background(accent))
        }
    }
}
