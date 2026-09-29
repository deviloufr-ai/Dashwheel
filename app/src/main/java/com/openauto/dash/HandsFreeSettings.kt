package com.openauto.dash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The settings of what Dashwheel does without a tap: what it says by itself
 * (Settings, Alerts) and the places a button leads to (Settings, Driving).
 */

/**
 * Everything the car says by itself, in one place: the warnings and tips, the
 * start-up briefing, the phone's messages. Each alert's own voice is with its
 * design, below.
 */
@Composable
internal fun VoiceSettings() {
    val context = LocalContext.current
    var config by remember { mutableStateOf(AiSettings.load(context)) }
    val messages by AlertStyleStore.messages.collectAsState()
    fun save(next: AiConfig) {
        // Read again first: the key or the language may have changed since this pane opened.
        val saved = AiSettings.load(context).copy(speak = next.speak, briefing = next.briefing)
        AiSettings.save(context, saved)
        config = saved
    }
    SettingsSection(stringResource(R.string.voice_section))
    SettingsToggle(
        Icons.Filled.Campaign, stringResource(R.string.voice_warnings),
        stringResource(R.string.voice_warnings_detail), config.speak
    ) { save(config.copy(speak = it)) }
    SettingsToggle(
        Icons.Filled.WbSunny, stringResource(R.string.briefing_setting),
        stringResource(R.string.briefing_setting_detail), config.briefing
    ) { save(config.copy(briefing = it)) }
    SettingsToggle(
        Icons.AutoMirrored.Filled.Message, stringResource(R.string.voice_messages),
        stringResource(R.string.voice_messages_detail), messages
    ) { AlertStyleStore.setMessages(context, it) }
    if (messages) {
        TextButton(onClick = { MessageAlerts.preview(context) }, modifier = Modifier.padding(start = 48.dp)) {
            Text(stringResource(R.string.message_alert_try), color = DashColors.Accent)
        }
    }
    Spacer(Modifier.height(20.dp))
}

/** Settings, Driving: the row that opens the driver's places. */
@Composable
internal fun PlacesRow(onOpen: () -> Unit) {
    val places by PlacesStore.places.collectAsState()
    val set = listOfNotNull(
        places.home?.let { stringResource(R.string.places_home) },
        places.work?.let { stringResource(R.string.places_work) }
    )
    SettingsRow(
        Icons.Filled.Place, stringResource(R.string.places_title),
        if (set.isEmpty()) stringResource(R.string.places_detail_empty) else set.joinToString(", "),
        onOpen
    )
}

/**
 * Home and work: typed once here, then one tap on the map tile or one
 * steering wheel button away. Set from an address, or from where the car is.
 */
@Composable
internal fun PlacesSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val places by PlacesStore.places.collectAsState()
    LaunchedEffect(Unit) { PlacesStore.load(context) }
    SettingsSheet(
        title = stringResource(R.string.places_title),
        onDismiss = onDismiss,
        actions = { SheetButton(stringResource(R.string.ai_done), onClick = onDismiss) }
    ) {
        Text(stringResource(R.string.places_explanation), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
        PlaceEditor(stringResource(R.string.places_home), places.home) { PlacesStore.setHome(context, it) }
        PlaceEditor(stringResource(R.string.places_work), places.work) { PlacesStore.setWork(context, it) }
        if (places.recent.isNotEmpty()) {
            Label(stringResource(R.string.places_recent))
            Text(places.recent.joinToString("\n") { it.name }, color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            SheetButton(stringResource(R.string.places_forget_recent), primary = false) { PlacesStore.forgetRecent(context) }
        }
    }
}

/** One place: where it is now, an address to find it by, the car's own position, and forgetting it. */
@Composable
private fun PlaceEditor(title: String, place: Place?, onSet: (Place?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var address by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val notFound = stringResource(R.string.info_map_address_not_found)
    val offline = stringResource(R.string.places_offline)
    val noPosition = stringResource(R.string.voice_no_position)

    fun find() {
        val q = address.trim()
        if (q.isEmpty() || busy) return
        keyboard?.hide()
        busy = true
        problem = null
        scope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { PlaceSearch.find(q) } }
            busy = false
            val hit = found.getOrNull()
            if (hit == null) {
                problem = if (found.isFailure) offline else notFound
            } else {
                onSet(hit)
                address = ""
            }
        }
    }

    fun here() {
        val at = HandsFree.position(context)
        if (at == null) {
            problem = noPosition
            return
        }
        busy = true
        problem = null
        scope.launch {
            val name = withContext(Dispatchers.IO) { PlaceSearch.nameOf(at.latitude, at.longitude) }
                ?: PlaceSearch.coordinates(at.latitude, at.longitude)
            busy = false
            onSet(Place(name, at.latitude, at.longitude))
        }
    }

    Label(title)
    Text(
        place?.name ?: stringResource(R.string.places_not_set),
        color = if (place != null) DashColors.TextPrimary else DashColors.Muted,
        style = MaterialTheme.typography.bodyLarge
    )
    OutlinedTextField(
        value = address,
        onValueChange = { address = it; problem = null },
        placeholder = { Text(stringResource(R.string.places_address_hint), color = DashColors.Muted) },
        singleLine = true,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
        colors = fieldColors(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { find() }, onDone = { find() }, onGo = { find() })
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        SheetButton(stringResource(R.string.places_find)) { find() }
        SheetButton(stringResource(R.string.places_here), primary = false) { here() }
        if (place != null) SheetButton(stringResource(R.string.places_forget), primary = false) { onSet(null) }
    }
    problem?.let { Text(it, color = DashColors.Warning, style = MaterialTheme.typography.bodySmall) }
    Spacer(Modifier.height(8.dp))
}
