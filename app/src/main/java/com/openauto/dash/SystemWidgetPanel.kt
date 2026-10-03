package com.openauto.dash

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap

private const val HOST_ID = 1024

/**
 * Process-wide [AppWidgetHost]. Every hosted widget shares ONE host (a single
 * host id): two hosts with the same id conflict, and hosting widgets in more
 * than one place at once requires the same host. Listening is ref-counted.
 */
object WidgetHostHolder {
    private var host: AppWidgetHost? = null
    private var active = 0

    /** The shared host, without starting it listening (see [acquire]). */
    fun host(context: Context): AppWidgetHost =
        host ?: AppWidgetHost(context.applicationContext, HOST_ID).also { host = it }

    fun acquire(context: Context): AppWidgetHost {
        val h = host(context)
        if (active == 0) runCatching { h.startListening() }
        active++
        return h
    }

    fun release() {
        active--
        if (active <= 0) {
            active = 0
            runCatching { host?.stopListening() }
        }
    }

    fun delete(context: Context, appWidgetId: Int) {
        runCatching { host(context).deleteAppWidgetId(appWidgetId) }
    }

    private const val PREFS = "widget_host"
    private const val KEY_RETIRED = "retired"

    private fun retired(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_RETIRED, null).orEmpty()

    /**
     * A widget taken off a page: its id is let go of later, by [sweep], once
     * nothing can show it again. Deleted on the spot, Undo brought the tile
     * back as "Widget unavailable", and the same widget on the other
     * arrangement (the pages beside the Maps dock) died with it. Written
     * down, so an id still waiting when the launcher stops isn't left behind.
     */
    fun retire(context: Context, appWidgetId: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_RETIRED, retired(context) + appWidgetId.toString()).apply()
    }

    /**
     * Lets go of the retired ids that are on no page any more: [inUse] is every
     * id the pages shown, Undo's and the saved arrangements still hold, asked
     * for only when an id is waiting; null (it can't be told) leaves them all.
     */
    fun sweep(context: Context, inUse: () -> Set<Int>?) {
        val waiting = retired(context)
        if (waiting.isEmpty()) return
        val kept = inUse() ?: return
        val gone = waiting.filter { it.toIntOrNull() !in kept }
        if (gone.isEmpty()) return
        gone.forEach { id -> id.toIntOrNull()?.let { delete(context, it) } }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_RETIRED, waiting - gone.toSet()).apply()
    }
}

/**
 * Renders a bound Android app-widget by its [appWidgetId] inside the shared host.
 */
@Composable
fun HostedSystemWidget(appWidgetId: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val host = remember { WidgetHostHolder.host(context) }
    val manager = remember { AppWidgetManager.getInstance(context) }

    // Listening is taken and given back by the effect, so a composition that is
    // thrown away before it lands never leaves the count raised.
    DisposableEffect(Unit) {
        WidgetHostHolder.acquire(context)
        onDispose { WidgetHostHolder.release() }
    }

    val info = remember(appWidgetId) { manager.getAppWidgetInfo(appWidgetId) }
    if (info == null) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.dash_widget_unavailable),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        return
    }

    // A new id gets a new view: the factory only runs once per view.
    key(appWidgetId) {
        AndroidView(
            factory = { ctx -> host.createView(ctx, appWidgetId, info) },
            modifier = modifier.fillMaxSize().padding(8.dp),
            update = { }
        )
    }
}

/**
 * Returns a launcher lambda that shows OUR OWN widget picker (built from the full
 * [AppWidgetManager.installedProviders] list — the stock system picker hides many
 * widgets, e.g. Google Maps), binds the chosen provider, runs any configure step,
 * and calls [onAdded] with the ready widget id.
 */
/**
 * Two ways to add a hosted system widget: [pickFromList] opens our full picker,
 * and [addPackage] binds the first widget a given app declares (one tap, used to
 * drop the Google Maps widget straight onto the dashboard). [addPackage] returns
 * false if that app declares no widget.
 */
class SystemWidgetAdder(
    val pickFromList: () -> Unit,
    val addPackage: (String) -> Boolean
)

@Composable
fun rememberSystemWidgetAdder(onAdded: (Int) -> Unit): SystemWidgetAdder {
    val context = LocalContext.current
    val host = remember { WidgetHostHolder.host(context) }
    val manager = remember { AppWidgetManager.getInstance(context) }

    DisposableEffect(Unit) {
        WidgetHostHolder.acquire(context)
        onDispose { WidgetHostHolder.release() }
    }

    var showPicker by remember { mutableStateOf(false) }
    var pendingId by remember { mutableIntStateOf(-1) }

    val configureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val id = pendingId
        pendingId = -1
        if (result.resultCode == Activity.RESULT_OK && id != -1) onAdded(id)
        else if (id != -1) host.deleteAppWidgetId(id)
    }

    fun finishBound(id: Int) {
        val info = manager.getAppWidgetInfo(id)
        if (info?.configure != null) {
            pendingId = id
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                component = info.configure
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            }
            runCatching { configureLauncher.launch(intent) }
                .onFailure { host.deleteAppWidgetId(id) }
        } else {
            onAdded(id)
        }
    }

    val bindLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val id = pendingId
        pendingId = -1
        if (result.resultCode == Activity.RESULT_OK && id != -1) finishBound(id)
        else if (id != -1) host.deleteAppWidgetId(id)
    }

    fun pick(info: AppWidgetProviderInfo) {
        val id = host.allocateAppWidgetId()
        val allowed = runCatching { manager.bindAppWidgetIdIfAllowed(id, info.provider) }.getOrDefault(false)
        if (allowed) {
            finishBound(id)
        } else {
            pendingId = id
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            }
            runCatching { bindLauncher.launch(intent) }.onFailure { host.deleteAppWidgetId(id) }
        }
    }

    if (showPicker) {
        // Every installed provider (labels + icons) is enumerated on IO so
        // opening the picker doesn't stall the UI thread.
        var providers by remember { mutableStateOf<List<PickerProvider>>(emptyList()) }
        LaunchedEffect(Unit) {
            providers = withContext(Dispatchers.IO) { collectProviders(manager, context) }
        }
        SystemWidgetPickerDialog(
            providers = providers,
            onPick = { showPicker = false; pick(it) },
            onDismiss = { showPicker = false }
        )
    }

    fun addPackage(pkg: String): Boolean {
        val info = runCatching { manager.getInstalledProvidersForPackage(pkg, null) }
            .getOrNull()?.firstOrNull() ?: return false
        pick(info)
        return true
    }

    return SystemWidgetAdder(pickFromList = { showPicker = true }, addPackage = ::addPackage)
}

/** A provider as the picker lists it, label and icon loaded up front (off the main thread). */
private class PickerProvider(val info: AppWidgetProviderInfo, val label: String, val icon: ImageBitmap?)

/**
 * Build the picker's provider list. [AppWidgetManager.installedProviders] only
 * returns providers in the HOME_SCREEN category, which silently drops widgets
 * some apps (notably **Google Maps**) declare under a different category — so the
 * unit's native launcher shows a live Maps widget but ours never listed it. We
 * merge in each of those apps' providers explicitly via
 * [AppWidgetManager.getInstalledProvidersForPackage], dedupe by provider
 * component, and float them to the top so Maps is easy to find.
 */
private fun collectProviders(
    manager: AppWidgetManager,
    context: Context
): List<PickerProvider> {
    val pm = context.packageManager
    val byComponent = LinkedHashMap<String, AppWidgetProviderInfo>()

    // Priority apps whose widgets the default query tends to hide.
    val priorityPackages = listOf(
        "com.google.android.apps.maps",
        "com.waze",
        "com.google.android.apps.mapslite"
    )
    for (pkg in priorityPackages) {
        runCatching { manager.getInstalledProvidersForPackage(pkg, null) }
            .getOrNull()
            ?.forEach { info -> byComponent[info.provider.flattenToString()] = info }
    }

    val priorityCount = byComponent.size

    manager.installedProviders.forEach { info ->
        byComponent.putIfAbsent(info.provider.flattenToString(), info)
    }

    // Labels and icons are loaded here, on IO, once each: the list rows only show them.
    val all = byComponent.values.map { info ->
        PickerProvider(
            info,
            runCatching { info.loadLabel(pm) }.getOrDefault(info.provider.packageName),
            runCatching {
                (info.loadIcon(context, 0) ?: pm.getApplicationIcon(info.provider.packageName))
                    .toBitmap(width = 96, height = 96).asImageBitmap()
            }.getOrNull()
        )
    }
    val priority = all.take(priorityCount)
    val rest = all.drop(priorityCount).sortedBy { it.label.lowercase() }
    return priority + rest
}

/** Our full widget picker: every installed AppWidget provider, icon + label. */
@Composable
private fun SystemWidgetPickerDialog(
    providers: List<PickerProvider>,
    onPick: (AppWidgetProviderInfo) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        modifier = Modifier.keepClearOfWindows(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dash_choose_widget)) },
        text = {
            if (providers.isEmpty()) {
                Text(stringResource(R.string.dash_no_widgets_found), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().height(380.dp)) {
                    items(providers) { p ->
                        val info = p.info
                        val iconBitmap = p.icon
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(info) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (iconBitmap != null) {
                                Image(bitmap = iconBitmap, contentDescription = null, modifier = Modifier.size(36.dp))
                            } else {
                                Spacer(Modifier.size(36.dp))
                            }
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(p.label, color = MaterialTheme.colorScheme.onSurface)
                                Text(
                                    info.provider.packageName,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dash_cancel)) }
        }
    )
}
