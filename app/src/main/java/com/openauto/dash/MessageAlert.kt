package com.openauto.dash

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/*
 * A message arriving on the phone, shown as well as said: a card at the top
 * with who it's from and its first lines, for a glance at a red light, and
 * the choice to hear it, answer it or put it away. It goes by itself after a
 * few seconds; the same switch as the spoken alert turns it on and off.
 */
internal object MessageAlerts {
    private const val SHOW_MS = 8_000L

    private val _shown = MutableStateFlow<NotifItem?>(null)
    val shown: StateFlow<NotifItem?> = _shown.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var hiding: Job? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            NotificationFeed.arrived.collect { item ->
                if (!AlertStyleStore.messages.value) return@collect
                // CarPlay / Android Auto shows its own.
                if (UnitSignals.projectionOnScreen.value) return@collect
                show(item)
            }
        }
    }

    private fun show(item: NotifItem) {
        _shown.value = item
        hiding?.cancel()
        hiding = scope.launch {
            delay(SHOW_MS)
            _shown.value = null
        }
    }

    /** Settings' "Try it": a made-up message, shown and said as a real one would be. */
    fun preview(context: Context) {
        val res = AppLanguage.wrap(context.applicationContext).resources
        val item = NotifItem(
            key = "preview", packageName = "", appLabel = "Messages", title = "Alex",
            text = res.getString(R.string.message_alert_sample), postedAt = System.currentTimeMillis(),
            icon = null, contentIntent = null
        )
        show(item)
        AlertVoice.previewMessage(context, item)
    }

    fun dismiss() {
        hiding?.cancel()
        _shown.value = null
    }

    /** Reads it out, whatever the voice switches: the driver asked. */
    fun readOut(context: Context, item: NotifItem) {
        val app = context.applicationContext
        CarVoice.setContext(app)
        CarVoice.speak(spokenMessage(item), AppLanguage.wrap(app).resources.configuration.locales[0])
    }
}

/** The message card at the top of the screen, over the dashboard. */
@Composable
internal fun MessageAlertHost() {
    val item by MessageAlerts.shown.collectAsState()
    val context = LocalContext.current
    var replying by remember { mutableStateOf<NotifItem?>(null) }
    replying?.let { PhoneMessageSheet(it) { replying = null } }
    val n = item ?: return
    AlertPopup(AlertStyle.CARD, Alignment.TopCenter) {
        Row(
            modifier = Modifier
                .widthIn(min = 360.dp, max = 620.dp)
                // A solid card under the glass: over the dashboard it must read on its own.
                .clip(DashShape.Large)
                .background(DashColors.Card.copy(alpha = 1f))
                .then(glassPanel(DashShape.Large))
                .clickable {
                    MessageAlerts.dismiss()
                    replying = n
                }
                .padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val icon = remember(n.icon) { n.icon?.asImageBitmap() }
            if (icon != null) {
                Image(icon, contentDescription = null, modifier = Modifier.size(40.dp).clip(CircleShape))
            } else {
                Icon(Icons.Filled.Chat, contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    n.title.ifEmpty { n.appLabel }, color = DashColors.TextPrimary, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                val body = n.messages.lastOrNull()?.text ?: n.text
                if (body.isNotBlank()) {
                    Text(body, color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(n.appLabel, color = DashColors.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                IconButton(onClick = { MessageAlerts.readOut(context, n); MessageAlerts.dismiss() }) {
                    Icon(Icons.Filled.VolumeUp, contentDescription = stringResource(R.string.message_alert_read), tint = DashColors.Accent)
                }
                IconButton(onClick = { MessageAlerts.dismiss() }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.dash_close), tint = DashColors.TextSecondary)
                }
            }
        }
    }
}
