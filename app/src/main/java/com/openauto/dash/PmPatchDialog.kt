package com.openauto.dash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Advanced → System permissions, while they aren't granted: gets
 * PMPatch3 onto the unit ([PmPatch]) in one tap, then the restart, then, if
 * Android still holds the permissions back, Dashwheel's reinstall.
 */
@Composable
internal fun PmPatchDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Null while Magisk is being looked at.
    var state by remember { mutableStateOf<PmPatch.State?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { PmPatch.state(context) }
            .onSuccess { state = it }
            .onFailure { error = it.message.orEmpty() }
    }

    fun run(action: () -> Result<Unit>, then: PmPatch.State?) {
        busy = true
        error = null
        scope.launch {
            val res = withContext(Dispatchers.IO) { action() }
            busy = false
            res.onSuccess { if (then != null) state = then }
                .onFailure { error = context.getString(R.string.dash_system_failed, it.message.orEmpty()) }
        }
    }

    AlertDialog(
        modifier = Modifier.keepClearOfWindows(),
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = DashColors.Card,
        title = { Text(stringResource(R.string.settings_system_perms), color = DashColors.TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.settings_pmpatch_body),
                    color = DashColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
                val status = when (state) {
                    null -> if (error == null) stringResource(R.string.settings_pmpatch_checking) else null
                    PmPatch.State.NO_MAGISK -> stringResource(R.string.settings_pmpatch_no_magisk)
                    PmPatch.State.OLD_MAGISK -> stringResource(R.string.settings_pmpatch_old_magisk)
                    PmPatch.State.ABSENT -> null
                    PmPatch.State.RESTART -> stringResource(R.string.settings_pmpatch_restart)
                    PmPatch.State.RUNNING -> stringResource(R.string.settings_pmpatch_running)
                }
                status?.let {
                    Text(
                        it,
                        color = if (state == PmPatch.State.RESTART) DashColors.Good else DashColors.Muted,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                error?.let { Text(it, color = DashColors.Warning, style = MaterialTheme.typography.bodyMedium) }
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = DashColors.Accent)
            }
        },
        confirmButton = {
            val (label, action) = when (state) {
                PmPatch.State.ABSENT -> R.string.settings_pmpatch_install to {
                    run({ PmPatch.install(context) }, PmPatch.State.RESTART)
                }
                PmPatch.State.RESTART -> R.string.dash_reboot_now to {
                    run({ SystemInstaller.rebootDevice(context) }, null)
                }
                PmPatch.State.RUNNING -> R.string.settings_pmpatch_reinstall to {
                    run({ PmPatch.reinstallSelf(context) }, null)
                }
                else -> return@AlertDialog
            }
            TextButton(enabled = !busy, onClick = action) {
                Text(stringResource(label), color = DashColors.Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = { if (!busy) onDismiss() }) {
                Text(stringResource(R.string.dash_close), color = DashColors.Muted)
            }
        }
    )
}
