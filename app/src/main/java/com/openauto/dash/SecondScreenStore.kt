package com.openauto.dash

import android.content.Context
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
            mediaKeysTurnPages = p.getBoolean("media_keys_pages", d.mediaKeysTurnPages)
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
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}
