package com.openauto.dash

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val PREFS = "share_eta"
private const val KEY_WHO = "who"

/** The arrival time of the guidance under way ("18:40"), from Dashwheel's own guidance or the navigation app's; null without one. */
internal fun arrivalTime(): String? {
    val own = InAppNav.guidance.value?.remaining?.takeIf { it.isNotBlank() }
    val line = own ?: NavDirections.state.value.eta.takeIf { NavDirections.state.value.active }
    return line?.split('·')?.map { it.trim() }?.lastOrNull { TIME.matches(it) }
}

private val TIME = Regex("""\d{1,2}[:h.]\d{2}(\s?[AaPp][Mm])?""")

/** Texts [number] through the phone that the driver is on the way, with the arrival time when guided. */
internal fun sendArrival(context: Context, scope: CoroutineScope, name: String, number: String) {
    val app = AppLanguage.wrap(context.applicationContext)
    val text = arrivalTime()?.let { app.getString(R.string.widgets_eta_text, it) } ?: app.getString(R.string.widgets_eta_text_plain)
    scope.launch {
        val sent = PhoneLink.sendText(number, text)
        Toast.makeText(
            app,
            when (sent) {
                true -> app.getString(R.string.widgets_eta_sent, name)
                false -> app.getString(R.string.widgets_eta_failed)
                null -> app.getString(R.string.widgets_eta_no_phone)
            },
            Toast.LENGTH_LONG
        ).show()
    }
}

/**
 * Share my arrival: one of the favourite contacts (‹ › to change, kept for
 * next time) and Send, which texts them through the phone that the driver is
 * on the way, with the arrival time when a guidance is under way.
 */
@Composable
internal fun ShareEtaCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val source = rememberQuickDialSource()
    val people = source.favourites.filter { !it.number.isNullOrBlank() }
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var who by remember { mutableIntStateOf(prefs.getInt(KEY_WHO, 0)) }
    val nav by NavDirections.state.collectAsState()
    val guided by InAppNav.guidance.collectAsState()
    val arrival = remember(nav, guided) { arrivalTime() }
    var sending by remember { mutableStateOf(false) }
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(DashSpace.Md), verticalArrangement = Arrangement.SpaceBetween) {
            TileHeader(stringResource(R.string.widgets_eta)) {
                Text(
                    arrival?.let { stringResource(R.string.widgets_eta_at, it) } ?: "",
                    color = DashColors.Accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge
                )
            }
            if (people.isEmpty()) {
                Text(stringResource(source.emptyText), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            val i = who.mod(people.size)
            val person = people[i]
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = { who = (i - 1).mod(people.size); prefs.edit().putInt(KEY_WHO, who).apply() }, modifier = Modifier.size(DashSize.Touch)) {
                    Icon(Icons.Filled.ChevronLeft, contentDescription = stringResource(R.string.widgets_eta_previous), tint = DashColors.TextPrimary)
                }
                Text(
                    person.name, color = DashColors.TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { who = (i + 1).mod(people.size); prefs.edit().putInt(KEY_WHO, who).apply() }, modifier = Modifier.size(DashSize.Touch)) {
                    Icon(Icons.Filled.ChevronRight, contentDescription = stringResource(R.string.widgets_eta_next), tint = DashColors.TextPrimary)
                }
            }
            Button(
                onClick = { sending = true; sendArrival(context, scope, person.name, person.number!!) },
                modifier = Modifier.fillMaxWidth().heightIn(min = DashSize.TouchPrimary),
                shape = DashShape.Medium
            ) {
                Icon(Icons.Filled.Send, contentDescription = null)
                Text("  " + stringResource(R.string.widgets_eta_send), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
internal fun shareEtaFace(): WidgetFace {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val source = rememberQuickDialSource()
    val people = source.favourites.filter { !it.number.isNullOrBlank() }
    val who = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_WHO, 0) }
    val person = people.getOrNull(who.mod(people.size.coerceAtLeast(1)))
    val nav by NavDirections.state.collectAsState()
    val arrival = remember(nav) { arrivalTime() }
    return WidgetFace(
        icon = Icons.Filled.Send,
        title = BuiltinKind.SHARE_ETA.label,
        value = person?.name ?: "",
        textValue = true,
        caption = arrival?.let { stringResource(R.string.widgets_eta_at, it) } ?: "",
        actions = listOfNotNull(person?.let {
            FaceAction(Icons.Filled.Send, stringResource(R.string.widgets_eta_send), onClick = { sendArrival(context, scope, it.name, it.number!!) }, primary = true)
        })
    )
}
