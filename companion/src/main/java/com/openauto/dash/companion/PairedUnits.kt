package com.openauto.dash.companion

import android.content.Context
import com.openauto.dash.link.PairingOffer
import com.openauto.dash.link.PairingStorage
import com.openauto.dash.link.StoredPairing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A head unit this phone agreed to share with. */
data class PairedUnit(val id: String, val name: String, val secret: ByteArray, val pairedAt: Long)

/**
 * The head units the driver allowed, with the secret each one proves on every
 * connection. Kept in private preferences, left out of backups and transfers.
 */
object PairedUnits {
    private const val PREFS = "paired_units"
    private const val KEY = "units"
    private const val KEY_ENABLED = "enabled"

    private val _units = MutableStateFlow<List<PairedUnit>>(emptyList())
    val units: StateFlow<List<PairedUnit>> = _units
    private var loaded = false

    @Synchronized
    fun load(context: Context): List<PairedUnit> {
        if (!loaded) {
            _units.value = read(context)
            loaded = true
        }
        return _units.value
    }

    fun secretFor(context: Context, id: String): ByteArray? = load(context).firstOrNull { it.id == id }?.secret

    fun nameOf(context: Context, id: String): String? = load(context).firstOrNull { it.id == id }?.name

    @Synchronized
    fun add(context: Context, offer: PairingOffer) {
        val unit = PairedUnit(offer.id, offer.unitName, offer.secret, System.currentTimeMillis())
        write(context, load(context).filter { it.id != offer.id } + unit)
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        write(context, load(context).filter { it.id != id })
        LinkServer.forget(id)
    }

    /** Whether the driver has sharing switched on (on by default once paired). */
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun read(context: Context): List<PairedUnit> =
        PairingStorage.decode(prefs(context).getString(KEY, null)).map { PairedUnit(it.id, it.name, it.secret, it.pairedAt) }

    private fun write(context: Context, units: List<PairedUnit>) {
        val stored = units.map { StoredPairing(it.id, it.secret, name = it.name, pairedAt = it.pairedAt) }
        prefs(context).edit().putString(KEY, PairingStorage.encode(stored)).apply()
        _units.value = units
    }
}
