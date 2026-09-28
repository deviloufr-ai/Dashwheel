package com.openauto.dash

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.UserHandle
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * A launchable app installed on the device.
 *
 * [icon] is the app's launcher icon; [packageName] identifies it for launching.
 */
@Immutable
data class AppEntry(
    val label: String,
    val packageName: String,
    val icon: Drawable
)

/**
 * Reads the list of launchable apps from [PackageManager] and launches them.
 *
 * Enumerating other apps requires the `<queries>` element declared in the
 * manifest (Android 11+ package visibility). Apps are launched fullscreen via
 * their launch [Intent] — Android does not allow a normal app to embed another
 * app's UI inside a view, so "opening" an app hands the whole screen to it.
 */
object AppLauncher {

    /** All launchable apps except this launcher, sorted alphabetically by label. */
    fun loadApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        return pm.queryIntentActivities(intent, 0)
            .mapNotNull { resolveInfo ->
                val pkg = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null // hide ourselves
                runCatching {
                    AppEntry(
                        label = resolveInfo.loadLabel(pm).toString(),
                        packageName = pkg,
                        icon = resolveInfo.loadIcon(pm)
                    )
                }.getOrNull()
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * Calls [onChange] (on the main thread) whenever an app is installed,
     * removed or updated, so a launcher that stays up for days still lists
     * what is really there. Returns the call that stops watching.
     */
    fun watchPackages(context: Context, onChange: () -> Unit): () -> Unit {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return {}
        val callback = object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String, user: UserHandle) = onChange()
            override fun onPackageAdded(packageName: String, user: UserHandle) = onChange()
            override fun onPackageChanged(packageName: String, user: UserHandle) = onChange()
            override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = onChange()
            override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = onChange()
        }
        launcherApps.registerCallback(callback)
        return { launcherApps.unregisterCallback(callback) }
    }

    /** Launches [packageName] fullscreen. Returns false if it has no launch intent. */
    fun launch(context: Context, packageName: String): Boolean {
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return false
        val launched = runCatching { context.startActivity(launchIntent); true }.getOrDefault(false)
        if (launched) AppUsage.opened(context, packageName)
        return launched
    }
}

/**
 * How often each app was opened from the launcher, so the drawer can put the
 * ones the driver really uses first instead of an alphabet to read through.
 */
object AppUsage {
    private const val PREFS = "app_usage"
    private const val KEY = "counts"
    /** Past this, every count is halved: what was used a lot long ago makes room for what is used now. */
    private const val HALVE_AT = 200

    private val _counts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val counts: StateFlow<Map<String, Int>> = _counts.asStateFlow()
    private var loaded = false

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        _counts.value = runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { o.optInt(it) }.filterValues { it > 0 }
        }.getOrDefault(emptyMap())
    }

    @Synchronized
    fun opened(context: Context, packageName: String) {
        load(context)
        val next = counted(_counts.value, packageName)
        _counts.value = next
        val json = JSONObject().apply { next.forEach { (pkg, n) -> put(pkg, n) } }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json.toString()).apply()
    }

    /** [counts] with one more opening of [packageName]. */
    internal fun counted(counts: Map<String, Int>, packageName: String): Map<String, Int> {
        val next = counts + (packageName to (counts[packageName] ?: 0) + 1)
        return if (next.getValue(packageName) < HALVE_AT) next else next.mapValues { it.value / 2 }.filterValues { it > 0 }
    }

    /**
     * The apps opened most among [installed], most used first, [max] at most.
     * Only those opened at least [MIN_OPENINGS] times: one try is not a habit.
     * Empty until at least two qualify, a row of one being no help.
     */
    internal fun mostUsed(installed: List<String>, counts: Map<String, Int>, max: Int = 6): List<String> {
        val used = installed
            .filter { (counts[it] ?: 0) >= MIN_OPENINGS }
            .sortedByDescending { counts.getValue(it) }
            .take(max)
        return if (used.size < 2) emptyList() else used
    }

    const val MIN_OPENINGS = 2
}
