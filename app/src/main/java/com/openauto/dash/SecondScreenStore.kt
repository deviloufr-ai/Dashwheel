package com.openauto.dash

import android.content.Context
import com.openauto.dash.link.DisplayBrightness
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The second screen's settings ([SecondScreenConfig]), kept in their own preferences. */
object SecondScreenStore {
    private const val PREFS = "second_screen"

    private val _config = MutableStateFlow(SecondScreenConfig())
    val config: StateFlow<SecondScreenConfig> = _config
    @Volatile private var loaded = false

    fun load(context: Context): SecondScreenConfig {
        if (loaded) return _config.value
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = SecondScreenConfig()
        _config.value = SecondScreenConfig(
            mode = enumOr(p.getString("mode", null), d.mode),
            appPackage = p.getString("app", null),
            pages = SecondScreenCodec.decodePages(p.getString("pages", null)),
            page = enumOr(p.getString("page", null), d.page),
            video = p.getBoolean("video", d.video),
            maxHeight = p.getInt("max_height", d.maxHeight).takeIf { it in SecondScreenRules.STREAM_HEIGHTS } ?: d.maxHeight,
            bitrateKbps = p.getInt("bitrate", d.bitrateKbps).coerceIn(500, 8_000),
            rearSeat = p.getBoolean("rear_seat", d.rearSeat),
            pageKeys = SecondScreenCodec.decodeKeys(p.getString("page_keys", null)),
            mediaKeysTurnPages = p.getBoolean("media_keys_pages", d.mediaKeysTurnPages),
            barSwipeTurnsPages = p.getBoolean("bar_swipe_pages", d.barSwipeTurnsPages),
            layouts = SecondScreenCodec.decodeLayouts(p.getString("layouts", null)),
            background = enumOr(p.getString("background", null), d.background),
            followDayNight = p.getBoolean("follow_day_night", d.followDayNight),
            alertsFullScreen = p.getBoolean("alerts_full_screen", d.alertsFullScreen),
            design = enumOr(p.getString("design", null), d.design),
            mapTilted = p.getBoolean("map_tilted", d.mapTilted),
            mapTilt = enumOr(p.getString("map_tilt", null), d.mapTilt),
            mapDistance = enumOr(p.getString("map_distance", null), d.mapDistance),
            mapBuildings = p.getBoolean("map_buildings", d.mapBuildings),
            upsideDown = p.getBoolean("upside_down", d.upsideDown),
            brightnessDay = p.getInt("brightness_day", d.brightnessDay).coerceIn(BRIGHTNESS),
            brightnessNight = p.getInt("brightness_night", d.brightnessNight).coerceIn(BRIGHTNESS),
            brightnessWired = p.getBoolean("brightness_wired", d.brightnessWired)
        )
        loaded = true
        return _config.value
    }

    /** Changes the settings and saves them. */
    @Synchronized
    fun update(context: Context, change: (SecondScreenConfig) -> SecondScreenConfig) {
        load(context)
        val c = change(_config.value).let { if (it.pages.isEmpty()) it.copy(pages = listOf(it.page)) else it }
        if (c == _config.value) return
        _config.value = c
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("mode", c.mode.name)
            .putString("app", c.appPackage)
            .putString("pages", SecondScreenCodec.encodePages(c.pages))
            .putString("page", c.page.name)
            .putBoolean("video", c.video)
            .putInt("max_height", c.maxHeight)
            .putInt("bitrate", c.bitrateKbps)
            .putBoolean("rear_seat", c.rearSeat)
            .putString("page_keys", SecondScreenCodec.encodeKeys(c.pageKeys))
            .putBoolean("media_keys_pages", c.mediaKeysTurnPages)
            .putBoolean("bar_swipe_pages", c.barSwipeTurnsPages)
            .putString("layouts", SecondScreenCodec.encodeLayouts(c.layouts))
            .putString("background", c.background.name)
            .putBoolean("follow_day_night", c.followDayNight)
            .putBoolean("alerts_full_screen", c.alertsFullScreen)
            .putString("design", c.design.name)
            .putBoolean("map_tilted", c.mapTilted)
            .putString("map_tilt", c.mapTilt.name)
            .putString("map_distance", c.mapDistance.name)
            .putBoolean("map_buildings", c.mapBuildings)
            .putBoolean("upside_down", c.upsideDown)
            .putInt("brightness_day", c.brightnessDay)
            .putInt("brightness_night", c.brightnessNight)
            .putBoolean("brightness_wired", c.brightnessWired)
            .apply()
    }

    private val BRIGHTNESS = DisplayBrightness.MIN..DisplayBrightness.MAX

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}
