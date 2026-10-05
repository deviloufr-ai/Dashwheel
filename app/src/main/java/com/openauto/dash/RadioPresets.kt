package com.openauto.dash

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** A stored station: [freq] in hundredths of a MHz (9850 = 98.5 MHz). */
internal data class RadioStation(val freq: Int, val name: String) {
    val mhz: String get() = String.format(Locale.ROOT, "%.1f", freq / 100.0)
}

/**
 * The K706's FM radio (com.android.fmradio.ext) from a tile: its stored
 * stations (its open station list), the one playing (its broadcast), and a
 * tap to tune. The radio only takes orders while its screen is alive, so
 * when it isn't, it is started first and the dashboard brought back.
 */
internal object RadioPresets {
    private const val TAG = "RadioPresets"
    const val PACKAGE = "com.android.fmradio.ext"
    private val ACTIVITY = ComponentName(PACKAGE, "com.android.fmradio.FmMainActivity")
    private val STATIONS = Uri.parse("content://$PACKAGE/station")
    private const val UPDATE = "com.qf.radio.update_action"
    private const val SET_FREQ = "ailit.set.radio.frequency"
    private const val NEXT = "/customize/radio/next"
    private const val PREVIOUS = "/customize/radio/pre"
    private const val START_WAIT_MS = 1_800L
    const val SHOWN = 6

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var users = 0

    private val _stations = MutableStateFlow<List<RadioStation>>(emptyList())
    val stations: StateFlow<List<RadioStation>> = _stations
    private val _playing = MutableStateFlow<RadioStation?>(null)
    val playing: StateFlow<RadioStation?> = _playing

    fun installed(context: Context): Boolean = isPackageInstalled(context, PACKAGE)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val freq = intent.getIntExtra("${UPDATE}_freq_key", 0).takeIf { it > 0 } ?: return
            val name = intent.getStringExtra("${UPDATE}_name_key").orEmpty().trim()
            _playing.value = RadioStation(freq, name)
        }
    }

    fun start(context: Context) {
        val app = context.applicationContext
        if (users++ == 0) ContextCompat.registerReceiver(app, receiver, IntentFilter(UPDATE), ContextCompat.RECEIVER_EXPORTED)
        scope.launch { _stations.value = withContext(Dispatchers.IO) { read(app) } }
        if (_playing.value == null) {
            systemProperty("sys.qf.station_freq")?.toIntOrNull()?.takeIf { it > 0 }?.let { _playing.value = RadioStation(it, "") }
        }
    }

    fun stop(context: Context) {
        if (users == 0 || --users > 0) return
        runCatching { context.applicationContext.unregisterReceiver(receiver) }
    }

    /** The stored stations, favourites and presets first, at most [SHOWN]. */
    private fun read(context: Context): List<RadioStation> = runCatching {
        val out = ArrayList<Triple<RadioStation, Int, Boolean>>()
        context.contentResolver.query(STATIONS, null, null, null, null)?.use { c ->
            val f = c.getColumnIndex("frequency")
            val n = c.getColumnIndex("station_name")
            val ps = c.getColumnIndex("program_service")
            val p = c.getColumnIndex("preset")
            val fav = c.getColumnIndex("is_favorite")
            val band = c.getColumnIndex("radio_band")
            while (c.moveToNext()) {
                if (f < 0) break
                if (band >= 0 && c.getInt(band) != 0) continue
                val freq = c.getInt(f).takeIf { it > 0 } ?: continue
                val name = listOf(n, ps).firstNotNullOfOrNull { i -> if (i >= 0) c.getString(i)?.trim()?.takeIf { it.isNotEmpty() } else null }.orEmpty()
                out += Triple(RadioStation(freq, name), if (p >= 0) c.getInt(p) else 0, fav >= 0 && c.getInt(fav) != 0)
            }
        }
        out.sortedWith(compareByDescending<Triple<RadioStation, Int, Boolean>> { it.third }.thenBy { if (it.second > 0) it.second else Int.MAX_VALUE })
            .map { it.first }.distinctBy { it.freq }.take(SHOWN)
    }.onFailure { Log.w(TAG, "stations unread: ${it.message}") }.getOrDefault(emptyList())

    fun tune(context: Context, station: RadioStation) = command(context) {
        it.sendBroadcast(Intent(SET_FREQ).putExtra("band", "fm").putExtra("freq", station.freq / 100f))
        _playing.value = station
    }

    fun next(context: Context) = command(context) { it.sendBroadcast(Intent(NEXT)) }
    fun previous(context: Context) = command(context) { it.sendBroadcast(Intent(PREVIOUS)) }

    /** [send] once the radio's screen is alive: started first when it isn't, and the dashboard brought back over it. */
    private fun command(context: Context, send: (Context) -> Unit) {
        val app = context.applicationContext
        scope.launch {
            if (systemProperty("sys.qf.radio.status") != "true") {
                runCatching { app.startActivity(Intent().setComponent(ACTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { Log.w(TAG, "can't start the radio", it); return@launch }
                delay(START_WAIT_MS)
                send(app)
                runCatching {
                    app.startActivity(
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(app.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    )
                }
            } else {
                send(app)
            }
        }
    }

    @SuppressLint("PrivateApi")
    private fun systemProperty(name: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String
    }.getOrNull()
}

@Composable
internal fun RadioPresetsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    DisposableEffect(Unit) { RadioPresets.start(context); onDispose { RadioPresets.stop(context) } }
    val stations by RadioPresets.stations.collectAsState()
    val playing by RadioPresets.playing.collectAsState()
    val tap = rememberTapFeedback()
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(DashSpace.Md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Radio, contentDescription = null, tint = DashColors.Accent)
                Text(
                    "  " + (playing?.let { p -> listOf(p.mhz, p.name).filter { it.isNotEmpty() }.joinToString("  ") } ?: stringResource(R.string.widgets_radio)),
                    color = DashColors.TextPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { tap(); RadioPresets.previous(context) }, modifier = Modifier.size(DashSize.Touch)) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.widgets_radio_previous), tint = DashColors.TextPrimary)
                }
                IconButton(onClick = { tap(); RadioPresets.next(context) }, modifier = Modifier.size(DashSize.Touch)) {
                    Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.widgets_radio_next), tint = DashColors.TextPrimary)
                }
            }
            if (!RadioPresets.installed(context)) {
                Text(stringResource(R.string.widgets_radio_none), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            if (stations.isEmpty()) {
                Text(stringResource(R.string.widgets_radio_empty), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            // Two rows of three: big enough for a finger at the wheel.
            Column(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DashSpace.Sm)) {
                stations.chunked(3).forEach { row ->
                    Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DashSpace.Sm)) {
                        row.forEach { s ->
                            val on = playing?.freq == s.freq
                            Box(
                                modifier = Modifier.weight(1f).fillMaxHeight().clip(DashShape.Medium)
                                    .background(if (on) DashColors.Accent.copy(alpha = 0.25f) else DashColors.CardHi)
                                    .clickable(role = Role.Button) { tap(); RadioPresets.tune(context, s) },
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(s.mhz, color = DashColors.TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                    if (s.name.isNotEmpty()) Text(
                                        s.name, color = DashColors.TextSecondary, style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                        repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun radioPresetsFace(): WidgetFace {
    val context = LocalContext.current
    DisposableEffect(Unit) { RadioPresets.start(context); onDispose { RadioPresets.stop(context) } }
    val playing by RadioPresets.playing.collectAsState()
    return WidgetFace(
        icon = Icons.Filled.Radio,
        title = BuiltinKind.RADIO_PRESETS.label,
        value = playing?.mhz ?: "",
        unit = if (playing != null) "MHz" else "",
        caption = playing?.name.orEmpty(),
        actions = listOf(
            FaceAction(Icons.Filled.SkipPrevious, stringResource(R.string.widgets_radio_previous), onClick = { RadioPresets.previous(context) }),
            FaceAction(Icons.Filled.SkipNext, stringResource(R.string.widgets_radio_next), onClick = { RadioPresets.next(context) })
        )
    )
}
