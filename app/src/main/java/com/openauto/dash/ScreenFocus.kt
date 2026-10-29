package com.openauto.dash

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * Which screen the wheel's dashboard buttons move: the unit's own (1), or the
 * second screen's cluster (2). A wheel button set to "Switch screen focus"
 * goes from one to the other; the bar's dashboard chip carries a 1 or a 2.
 */

internal object ScreenFocus {
    private val _second = MutableStateFlow(false)
    /** The driver chose the second screen. It counts only while that screen shows the cluster ([focused]). */
    val second: StateFlow<Boolean> = _second.asStateFlow()

    /** The screen the dashboard buttons move: 2 while the second screen is chosen and shows the cluster, else 1. */
    fun focused(chosenSecond: Boolean, output: SecondScreenOutput): Int =
        if (chosenSecond && clusterShown(output)) 2 else 1

    /** The second screen shows the cluster, whose pages the buttons can turn. */
    fun clusterShown(output: SecondScreenOutput): Boolean =
        output == SecondScreenOutput.VIDEO_CLUSTER || output == SecondScreenOutput.DATA

    val onSecond: Boolean
        get() = focused(_second.value, SecondScreenController.status.value.output) == 2

    /** From 1 to 2 and back, said out loud; with no cluster on a second screen it stays on 1 and says so. */
    fun toggle(context: Context) {
        if (onSecond) {
            _second.value = false
            HandsFree.say(context, R.string.wheel_focus_screen_1)
        } else if (!clusterShown(SecondScreenController.status.value.output)) {
            _second.value = false
            HandsFree.say(context, R.string.wheel_focus_no_second)
        } else {
            _second.value = true
            HandsFree.say(context, R.string.wheel_focus_screen_2)
        }
    }

    /** A dashboard button pressed: on the second screen while it has the focus. False when it is the unit's own to move. */
    fun step(step: Int): Boolean {
        if (!onSecond) return false
        SecondScreenController.turnPage(step)
        return true
    }
}

/**
 * The focused screen's number in a small square, for the bars' dashboard
 * chip: lit on 2, plain on 1. Only once a wheel button switches the focus,
 * so a bar without one isn't cluttered with it.
 */
@Composable
internal fun ScreenFocusBadge(modifier: Modifier = Modifier) {
    val buttons by SteeringWheelStore.buttons.collectAsState()
    if (buttons.none { it.assignment == WheelAssignment.Preset(SteeringWheelAction.SCREEN_FOCUS) }) return
    val chosen by ScreenFocus.second.collectAsState()
    val status by SecondScreenController.status.collectAsState()
    val screen = ScreenFocus.focused(chosen, status.output)
    val label = stringResource(R.string.wheel_focus_badge, screen)
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(DashShape.Small)
            .background(if (screen == 2) DashColors.Accent else DashColors.CardHi)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Text(
            screen.toString(),
            color = if (screen == 2) DashColors.OnAccent else DashColors.TextPrimary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1
        )
    }
}
