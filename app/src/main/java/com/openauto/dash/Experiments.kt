package com.openauto.dash

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings, Lab, Experimental features: one switch for what is built but not
 * yet proven in a car. Off, none of it runs: the 3D car showing the lights
 * and the open doors, the dusk city on the 3D map, the drawn road in Road
 * watch on the phone (told to it with the car's telemetry).
 */
internal object Experiments {
    private val FILE = PrefFile("experiments")
    private val ON = FILE.bool("on", default = false)
    private val _on = MutableStateFlow(false)
    @Volatile private var loaded = false

    val on: StateFlow<Boolean> = _on.asStateFlow()

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        _on.value = FILE.open(context)[ON]
    }

    fun setOn(context: Context, value: Boolean) {
        _on.value = value
        FILE[context, ON] = value
    }

    @Composable
    fun active(): Boolean {
        load(LocalContext.current)
        val on by on.collectAsState()
        return on
    }
}
