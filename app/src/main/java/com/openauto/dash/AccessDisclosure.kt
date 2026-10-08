package com.openauto.dash

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton

/**
 * The Play edition's prominent disclosure before a system permission page:
 * what Dashwheel does with the accessibility service, or with notification
 * access, said in the app before Android's own page opens. Google Play asks
 * for it, and asks that the service is never turned on by the app itself.
 * A small dialog activity, so it can come from anywhere: a tile, Settings,
 * or the phone's keyboard card asking over the link, with no activity at hand.
 * The GitHub edition opens the system page straight away, as before.
 */
object AccessDisclosure {
    enum class Kind { ACCESSIBILITY, NOTIFICATIONS }

    /** Shows the disclosure for [kind]; only its "Open settings" opens the system page. */
    fun show(context: Context, kind: Kind) {
        context.startActivity(
            Intent(context, AccessDisclosureActivity::class.java)
                .putExtra(EXTRA_KIND, kind.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    /** The system page where the driver turns the access of [kind] on. */
    fun openSystemPage(context: Context, kind: Kind) {
        val action = when (kind) {
            Kind.ACCESSIBILITY -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            Kind.NOTIFICATIONS -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
        }
        context.launchSafely(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    internal const val EXTRA_KIND = "kind"
}

/** See [AccessDisclosure]: a see-through activity holding the one dialog. */
class AccessDisclosureActivity : ComponentActivity() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = runCatching { AccessDisclosure.Kind.valueOf(intent.getStringExtra(AccessDisclosure.EXTRA_KIND).orEmpty()) }
            .getOrDefault(AccessDisclosure.Kind.ACCESSIBILITY)
        val title = when (kind) {
            AccessDisclosure.Kind.ACCESSIBILITY -> R.string.access_a11y_title
            AccessDisclosure.Kind.NOTIFICATIONS -> R.string.access_notif_title
        }
        val body = when (kind) {
            AccessDisclosure.Kind.ACCESSIBILITY -> R.string.access_a11y_body
            AccessDisclosure.Kind.NOTIFICATIONS -> R.string.access_notif_body
        }
        setContent {
            OpenAutoDashTheme {
                AlertDialog(
                    onDismissRequest = { finish() },
                    containerColor = DashColors.Card,
                    title = { Text(getString(title), color = DashColors.TextPrimary) },
                    text = { Text(getString(body), color = DashColors.Muted, style = MaterialTheme.typography.bodyMedium) },
                    confirmButton = {
                        TextButton(onClick = {
                            AccessDisclosure.openSystemPage(this, kind)
                            finish()
                        }) { Text(getString(R.string.access_open_settings), color = DashColors.TextPrimary) }
                    },
                    dismissButton = {
                        TextButton(onClick = { finish() }) { Text(getString(R.string.access_not_now), color = DashColors.Muted) }
                    }
                )
            }
        }
    }
}
