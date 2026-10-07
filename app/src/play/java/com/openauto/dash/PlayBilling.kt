package com.openauto.dash

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Dashwheel Pro through Google Play Billing: one non-consumable product,
 * [PRODUCT_ID], created once in the Play Console under Monetise → Products →
 * In-app products, with that exact id.
 *
 * What Play last said is kept in preferences, so a bought Pro is open from
 * the first frame, offline too; every start then asks Play again and takes
 * its answer, so a refund takes Pro back. A purchase is acknowledged as soon
 * as it is seen: Play refunds one left unacknowledged for three days.
 */
internal object PlayBilling : PurchasesUpdatedListener {
    private const val TAG = "PlayBilling"
    const val PRODUCT_ID = "dashwheel_pro"
    private const val PREFS = "play_billing"
    private const val KEY_OWNED = "pro_owned"

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()
    private val _price = MutableStateFlow<String?>(null)
    val price: StateFlow<String?> = _price.asStateFlow()
    private val _purchase = MutableStateFlow(PurchaseState.IDLE)
    val purchase: StateFlow<PurchaseState> = _purchase.asStateFlow()

    private var appContext: Context? = null
    private var client: BillingClient? = null
    private var details: ProductDetails? = null
    /** What waits for the connection: run in order once Play answers. */
    private val whenReady = mutableListOf<() -> Unit>()
    private var connecting = false

    fun start(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        _unlocked.value = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_OWNED, false)
        client = BillingClient.newBuilder(app)
            .setListener(this)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()
        ready {
            refresh()
            withDetails { }
        }
    }

    /** Opens Play's purchase screen for Pro over [activity]; [purchase] says how it went. */
    fun buy(activity: Activity) {
        if (client == null) {
            _purchase.value = PurchaseState.UNAVAILABLE
            return
        }
        _purchase.value = PurchaseState.BUSY
        ready {
            withDetails { product ->
                if (product == null) {
                    _purchase.value = PurchaseState.UNAVAILABLE
                    return@withDetails
                }
                val offer = product.offer()
                val item = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product)
                // Billing 8 lists a one-time product's offers; the flow names the one taken.
                if (offer != null) item.setOfferToken(offer.offerToken)
                val params = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(listOf(item.build()))
                    .build()
                val result = client?.launchBillingFlow(activity, params)
                when (result?.responseCode) {
                    // The rest comes through onPurchasesUpdated.
                    BillingClient.BillingResponseCode.OK -> Unit
                    BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                        _purchase.value = PurchaseState.IDLE
                        refresh()
                    }
                    else -> {
                        Log.w(TAG, "purchase screen refused: ${result?.debugMessage}")
                        _purchase.value = PurchaseState.FAILED
                    }
                }
            }
        }
    }

    /** Runs [action] once the client is connected, connecting first if need be. */
    private fun ready(action: () -> Unit) {
        val c = client ?: return
        if (c.isReady) {
            action()
            return
        }
        synchronized(whenReady) {
            whenReady += action
            if (connecting) return
            connecting = true
        }
        c.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                val queued = synchronized(whenReady) {
                    connecting = false
                    whenReady.toList().also { whenReady.clear() }
                }
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    if (_purchase.value == PurchaseState.UNAVAILABLE) _purchase.value = PurchaseState.IDLE
                    queued.forEach { it() }
                } else {
                    Log.w(TAG, "Play billing not available: ${result.debugMessage}")
                    _purchase.value = PurchaseState.UNAVAILABLE
                }
            }

            override fun onBillingServiceDisconnected() {
                // The next call reconnects; nothing queued is lost, it was run or dropped above.
                synchronized(whenReady) { connecting = false }
            }
        })
    }

    /** Asks Play what this account owns; its answer is the truth. */
    private fun refresh() {
        val c = client ?: return
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        c.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "purchases not read: ${result.debugMessage}")
                return@queryPurchasesAsync
            }
            handle(purchases, fromStore = true)
        }
    }

    /** The product's details (price), fetched once, then handed to [then]. */
    private fun withDetails(then: (ProductDetails?) -> Unit) {
        details?.let { return then(it) }
        val c = client ?: return then(null)
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()
        c.queryProductDetailsAsync(params) { result, found ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "product not read: ${result.debugMessage}")
                return@queryProductDetailsAsync then(null)
            }
            val product = found.productDetailsList.firstOrNull { it.productId == PRODUCT_ID }
            if (product == null) Log.w(TAG, "$PRODUCT_ID is not a product of this app on Play")
            details = product
            _price.value = product?.offer()?.formattedPrice
            then(product)
        }
    }

    /**
     * Pro from a list of purchases: Play's own list ([fromStore]) decides
     * both ways, a purchase just made only opens.
     */
    private fun handle(purchases: List<Purchase>, fromStore: Boolean) {
        val pro = purchases.filter { PRODUCT_ID in it.products }
        val owned = pro.firstOrNull { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        when {
            owned != null -> {
                if (!owned.isAcknowledged) acknowledge(owned)
                setOwned(true)
                _purchase.value = PurchaseState.IDLE
            }
            pro.any { it.purchaseState == Purchase.PurchaseState.PENDING } -> {
                _purchase.value = PurchaseState.PENDING
                if (fromStore) setOwned(false)
            }
            fromStore -> setOwned(false)
        }
    }

    /** The product's one offer (its base price): Billing 8 lists them, a plain one-time product has one. */
    private fun ProductDetails.offer(): ProductDetails.OneTimePurchaseOfferDetails? =
        oneTimePurchaseOfferDetailsList?.firstOrNull()

    private fun acknowledge(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
        client?.acknowledgePurchase(params) { result ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "purchase not acknowledged: ${result.debugMessage}")
            }
        }
    }

    private fun setOwned(owned: Boolean) {
        if (_unlocked.value != owned) Log.i(TAG, if (owned) "Pro is owned" else "Pro is not owned")
        _unlocked.value = owned
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean(KEY_OWNED, owned)?.apply()
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                _purchase.value = PurchaseState.IDLE
                handle(purchases.orEmpty(), fromStore = false)
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> _purchase.value = PurchaseState.IDLE
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                _purchase.value = PurchaseState.IDLE
                refresh()
            }
            else -> {
                Log.w(TAG, "purchase failed: ${result.responseCode} ${result.debugMessage}")
                _purchase.value = PurchaseState.FAILED
            }
        }
    }
}
