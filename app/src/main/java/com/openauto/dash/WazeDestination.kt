package com.openauto.dash

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * Where Waze is guiding to, read from Waze's own files with root: Waze shows
 * no destination anywhere Dashwheel can see it (its one notification only
 * says it runs, and the K706 firmware's navigation feed only listens to
 * AMap), but it keeps every place it guides to in `user.db` (RECENTS, newest
 * `access_time` first, PLACES in millionths of a degree) and the trip under way
 * in its `session` file (`Destination.Position: lon,lat`). The exact position
 * goes to [NavHandoff], so the second screen's shadow route ([ShadowRoute])
 * follows a trip typed into Waze, not only one started from Dashwheel.
 *
 * Only while Waze runs and a second screen is linked, and only with root (the
 * GitHub edition). A place counts once chosen while Waze runs: an old trip
 * left in the files is not taken for the one under way.
 */
internal object WazeDestination {
    private const val TAG = "WazeDestination"
    private const val DIR = "/data/data/${NavHandoff.WAZE}"
    private const val POLL_MS = 15_000L
    /** A place chosen this long before Waze was seen running still counts (Waze was starting). */
    private const val EARLY_MS = 60_000L
    /** Two positions closer than this are the same place. */
    private const val SAME_PLACE_DEG = 0.0005

    internal data class Read(val name: String, val lat: Double, val lng: Double, val atMs: Long)

    fun start(scope: CoroutineScope, context: Context) {
        if (!Edition.full) return
        val app = context.applicationContext
        scope.launch {
            combine(NavDirections.running, DisplayLink.state) { running, link ->
                running == NavHandoff.WAZE && link is DisplayLinkState.Connected
            }
                .distinctUntilChanged()
                .collectLatest { on -> if (on) watch(app) }
        }
    }

    private suspend fun watch(context: Context) {
        if (!withContext(Dispatchers.IO) { SystemInstaller.isRootAvailable() }) return
        val since = System.currentTimeMillis() - EARLY_MS
        var sessionAtStart: Read? = null
        var firstRead = true
        var dbStamp: String? = null
        var recent: Read? = null
        var taken: Read? = null
        while (true) {
            val files = withContext(Dispatchers.IO) {
                runCatching { readFiles(context, dbStamp) }.onFailure { Log.w(TAG, "can't read Waze's files: ${it.message}") }.getOrNull()
            }
            if (files != null) {
                if (files.dbStamp != dbStamp) {
                    dbStamp = files.dbStamp
                    recent = files.recent
                    Log.i(TAG, "Waze's last place: $recent")
                }
                if (firstRead) {
                    sessionAtStart = files.session
                    firstRead = false
                    Log.i(TAG, "Waze's trip at start: $sessionAtStart")
                }
                val found = pick(recent, files.session, sessionAtStart, since)
                if (found != null && (taken == null || !samePlace(found, taken))) {
                    taken = found
                    Log.i(TAG, "Waze is going to $found")
                    NavHandoff.fromApp(found.name, found.lat, found.lng)
                }
            }
            delay(POLL_MS)
        }
    }

    /**
     * The destination of the trip under way: Waze's newest recent place when it
     * was chosen since [sinceMs], or the session's trip when it changed since
     * Waze was first read; the later of the two.
     */
    internal fun pick(recent: Read?, session: Read?, sessionAtStart: Read?, sinceMs: Long): Read? {
        val fromRecent = recent?.takeIf { it.atMs >= sinceMs }
        val fromSession = session?.takeIf { sessionAtStart == null || !samePlace(it, sessionAtStart) }
        return listOfNotNull(fromRecent, fromSession).maxByOrNull { it.atMs }
    }

    internal fun samePlace(a: Read, b: Read): Boolean =
        abs(a.lat - b.lat) < SAME_PLACE_DEG && abs(a.lng - b.lng) < SAME_PLACE_DEG

    private class Files(val dbStamp: String?, val recent: Read?, val session: Read?)

    /** One root call lists the database's stamp and the session's lines; the database is copied out only when it changed. */
    private fun readFiles(context: Context, lastStamp: String?): Files {
        val out = RootShell.su(
            "cd $DIR && stat -c '%n %Y %s' user.db user.db-wal 2>/dev/null; echo $SPLIT; stat -c %Y session 2>/dev/null; " +
                "grep -E '^ *(Destination|Navigation)\\.' session 2>/dev/null",
            8
        ).out
        val (stampPart, sessionPart) = out.split(SPLIT).let { it[0].trim() to it.getOrElse(1) { "" } }
        val stamp = stampPart.ifBlank { null }
        val recent = if (stamp != null && stamp != lastStamp) copyAndQuery(context) else null
        val sessionLines = sessionPart.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val mtimeS = sessionLines.firstOrNull()?.toLongOrNull()
        val session = parseSession(sessionLines.drop(if (mtimeS != null) 1 else 0), (mtimeS ?: 0L) * 1000)
        return Files(stamp, recent, session)
    }

    private fun copyAndQuery(context: Context): Read? {
        val dir = File(context.cacheDir, "waze").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val to = dir.absolutePath
        RootShell.su(
            "cd $DIR && cat user.db > $to/user.db && { [ -f user.db-wal ] && cat user.db-wal > $to/user.db-wal; true; } && chmod 666 $to/*",
            8
        )
        val db = File(dir, "user.db")
        if (!db.canRead()) return null
        return SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READWRITE).use { sql ->
            sql.rawQuery(RECENT_SQL, null).use { c ->
                if (!c.moveToFirst()) return@use null
                val at = c.getLong(0)
                val name = listOf(c.getString(1), c.getString(2), listOfNotNull(c.getString(3), c.getString(4)).joinToString(" "))
                    .firstOrNull { !it.isNullOrBlank() }.orEmpty()
                position(c.getLong(6), c.getLong(5))?.let { (lat, lng) -> Read(name, lat, lng, if (at > 100_000_000_000L) at else at * 1000) }
            }
        }
    }

    /**
     * The trip in Waze's `session` lines ("Destination.Position: lon,lat" in
     * millionths of a degree, "Navigation.Last dest name: …"); null without a
     * destination position.
     */
    internal fun parseSession(lines: List<String>, atMs: Long): Read? {
        val values = lines.mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.substring(0, colon).trim() to line.substring(colon + 1).trim()
        }.toMap()
        val raw = values["Destination.Position"]?.split(',')?.map { it.trim().toLongOrNull() } ?: return null
        val lon = raw.getOrNull(0) ?: return null
        val lat = raw.getOrNull(1) ?: return null
        val (la, lo) = position(lon, lat) ?: return null
        val name = listOf("Navigation.Last dest venue name", "Navigation.Last dest name")
            .firstNotNullOfOrNull { values[it]?.takeIf(String::isNotBlank) }
            ?: listOfNotNull(values["Navigation.Last dest number"], values["Navigation.Last dest street"], values["Navigation.Last dest city"])
                .filter { it.isNotBlank() }.joinToString(" ")
        return Read(name, la, lo, atMs)
    }

    /** Millionths of a degree to (lat, lng); null for nothing (0,0) or out of range. */
    private fun position(lonE6: Long, latE6: Long): Pair<Double, Double>? {
        val lat = latE6 / 1e6
        val lng = lonE6 / 1e6
        if (lat == 0.0 && lng == 0.0) return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return lat to lng
    }

    private const val SPLIT = "--dw-session--"
    private const val RECENT_SQL =
        "SELECT R.access_time, R.name, P.name, P.street, P.city, P.latitude, P.longitude " +
            "FROM RECENTS R JOIN PLACES P ON R.place_id = P.id ORDER BY R.access_time DESC LIMIT 1"
}
