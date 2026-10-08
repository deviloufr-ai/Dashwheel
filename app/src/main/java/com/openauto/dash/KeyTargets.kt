package com.openauto.dash

import android.annotation.SuppressLint
import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * Which app the head unit's own NAVI, voice and custom keys open, on the QF
 * firmware (ROCO K706). The firmware reads the choice from system properties
 * its car settings write (worked out from the firmware): the package name,
 * plus the activity for the custom key; "nothing" leaves it to the unit (its
 * app picker for NAVI and the custom key, its own assistant for voice).
 * Written through the privileged shell, the only way to set persist.sys.*.
 */
object KeyTargets {

    enum class Key(val prop: String, val classProp: String?, @StringRes val labelRes: Int) {
        NAVI("persist.sys.maps", null, R.string.keys_navi),
        VOICE("persist.sys.voice.app", null, R.string.keys_voice),
        CUSTOM("persist.sys.custom.app", "persist.sys.custom.app.class", R.string.keys_custom)
    }

    /** What the firmware stores for "the unit decides". */
    private const val UNSET = "nothing"

    private val _targets = MutableStateFlow<Map<Key, String?>>(emptyMap())
    /** Each key's app (a package name), or null when the unit decides. */
    val targets: StateFlow<Map<Key, String?>> = _targets

    /** Whether this is the QF firmware: its car settings always set the NAVI key's property. */
    fun available(): Boolean = !systemProperty(Key.NAVI.prop).isNullOrBlank()

    fun refresh() {
        _targets.value = Key.entries.associateWith { key ->
            systemProperty(key.prop)?.takeIf { it.isNotBlank() && it != UNSET }
        }
    }

    /**
     * Makes [key] open [pkg] (null: the unit decides). The custom key also
     * needs the app's own start activity. True when the unit took it.
     */
    suspend fun set(context: Context, key: Key, pkg: String?): Boolean = runCatching {
        val value = pkg ?: UNSET
        DockShell.shell(context, "setprop ${key.prop} $value")
        key.classProp?.let { classProp ->
            val cls = pkg?.let { context.packageManager.getLaunchIntentForPackage(it)?.component?.className } ?: UNSET
            DockShell.shell(context, "setprop $classProp $cls")
        }
        val now = systemProperty(key.prop)
        check(now == value) { "${key.prop} is $now" }
        _targets.update { it + (key to pkg) }
    }.isSuccess

    @SuppressLint("PrivateApi")
    private fun systemProperty(name: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String
    }.getOrNull()
}

/**
 * Settings, Driving, under Buttons: which app the unit's NAVI, voice and
 * custom keys open. Only the QF firmware has the keys and only a privileged
 * shell can write the choice: other units have no row, and a QF unit
 * without a shell counts them in the pane's [RootLockCard].
 */
@Composable
internal fun KeyTargetRows() {
    // The choice is written through the shell: the Play edition has no row for it.
    if (Edition.play) return
    val context = LocalContext.current
    // Not on this firmware: nothing to list. Without a shell the Driving pane's root card names it.
    val available = remember { KeyTargets.available() }
    if (!available || !shellAccess().shell) return
    LaunchedEffect(Unit) { KeyTargets.refresh() }
    val targets by KeyTargets.targets.collectAsState()
    var picking by remember { mutableStateOf<KeyTargets.Key?>(null) }
    val scope = rememberCoroutineScope()
    val unitChoice = stringResource(R.string.keys_unit_choice)

    KeyTargets.Key.entries.forEach { key ->
        val pkg = targets[key]
        val label = pkg?.let { appLabel(context, it) } ?: unitChoice
        SettingsRow(Icons.Filled.Keyboard, stringResource(key.labelRes), label) { picking = key }
    }

    picking?.let { key ->
        val apps = remember {
            // Dashwheel first: the NAVI key opening the dashboard's own map is the usual wish.
            listOf(AppEntry(context.getString(R.string.app_name), context.packageName, context.packageManager.getApplicationIcon(context.packageName))) +
                AppLauncher.loadApps(context)
        }
        fun choose(pkg: String?) {
            picking = null
            scope.launch {
                if (!KeyTargets.set(context, key, pkg)) Toast.makeText(context, R.string.settings_rom_needs_root, Toast.LENGTH_SHORT).show()
            }
        }
        AppPickerDialog(
            apps = apps,
            title = stringResource(key.labelRes),
            onPick = { choose(it.packageName) },
            onDismiss = { picking = null },
            neutral = { TextButton(onClick = { choose(null) }) { Text(unitChoice, color = DashColors.Accent) } }
        )
    }
}

private fun appLabel(context: Context, pkg: String): String = runCatching {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)
