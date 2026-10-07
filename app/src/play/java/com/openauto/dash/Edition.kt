package com.openauto.dash

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/**
 * The Google Play build: updated by Play, no donation link (a Play app may
 * only take money through Play's own billing), and Dashwheel Pro, the one
 * in-app purchase, opening the skins and widgets of [Premium].
 */
internal object Edition : StoreEdition {
    override val playStore: Boolean = true
    override val unlocked: StateFlow<Boolean> get() = PlayBilling.unlocked
    override val price: StateFlow<String?> get() = PlayBilling.price
    override val purchase: StateFlow<PurchaseState> get() = PlayBilling.purchase
    override fun start(context: Context) = PlayBilling.start(context)
    override fun buy(activity: Activity) = PlayBilling.buy(activity)
    override val donationUrl: String? = null
    /** The companion's own listing on Play (same developer account). */
    override val companionUrl: String = "https://play.google.com/store/apps/details?id=com.openauto.dash.companion"
    override val updatesFromGitHub: Boolean = false
}
