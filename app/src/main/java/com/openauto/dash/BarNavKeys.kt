package com.openauto.dash

import android.accessibilityservice.AccessibilityService
import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Back at the bar's left end and Recent apps at its right (TopBar), for a unit
 * with no such keys on its bezel. Off until the driver turns it on.
 */
internal object BarNavKeys {
    private const val PREFS = "dash_bar"
    private const val KEY = "nav_keys"

    private val _on = MutableStateFlow(false)
    val on: StateFlow<Boolean> = _on.asStateFlow()
    private var loaded = false

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        _on.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    }

    fun save(context: Context, on: Boolean) {
        _on.value = on
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }

    /** The wheel's Back: an app in a tile steps back on its tile, else the system's. False without the accessibility service. */
    fun back(): Boolean = EmbeddedApp.backInTile() || SplitAccessibilityService.globalAction(AccessibilityService.GLOBAL_ACTION_BACK)

    fun recents(): Boolean = SplitAccessibilityService.globalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
}

/** Settings, Look, under the bar's heading: the Back and Recent apps buttons. */
@Composable
internal fun BarNavKeysSetting() {
    val context = LocalContext.current
    BarNavKeys.load(context)
    val on by BarNavKeys.on.collectAsState()
    SettingsToggle(
        icon = Icons.AutoMirrored.Filled.ArrowBack,
        title = stringResource(R.string.dash_bar_nav_keys),
        detail = stringResource(R.string.dash_bar_nav_keys_detail),
        checked = on
    ) { BarNavKeys.save(context, it) }
}
