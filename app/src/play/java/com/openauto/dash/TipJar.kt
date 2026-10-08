package com.openauto.dash

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableStateFlow

/*
 * Play edition: a coffee for the author, paid through Google Play (its rules
 * allow no outside donation link). The tips are consumable in-app products,
 * set up in the Play Console with the ids in [TIP_IDS]; each is consumed
 * once paid, so it can be given again. Nothing is unlocked by a tip.
 */

/** The tips' product ids in the Play Console, smallest first. */
private val TIP_IDS = listOf("tip_coffee", "tip_lunch", "tip_dinner")

private object TipStore {
    private const val TAG = "TipJar"

    /** The tips on offer, with Play's own names and local prices; empty until Play answers, or when it has none. */
    val tips = MutableStateFlow<List<ProductDetails>>(emptyList())

    /** A tip went through. */
    val thanked = MutableStateFlow(false)

    private var client: BillingClient? = null

    fun connect(context: Context) {
        if (client != null) return
        val c = BillingClient.newBuilder(context.applicationContext)
            .setListener { result, purchases ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) purchases?.forEach(::settle)
            }
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()
        client = c
        c.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    Log.i(TAG, "billing unavailable: ${result.debugMessage}")
                    return
                }
                loadTips(c)
                // A tip paid while the app was away (a pending one that cleared): consume it now.
                c.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { r, purchases ->
                    if (r.responseCode == BillingClient.BillingResponseCode.OK) purchases.forEach(::settle)
                }
            }

            override fun onBillingServiceDisconnected() {
                client = null
            }
        })
    }

    fun disconnect() {
        client?.endConnection()
        client = null
    }

    private fun loadTips(c: BillingClient) {
        val products = TIP_IDS.map {
            QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.INAPP).build()
        }
        c.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(products).build()) { result, details ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryProductDetailsAsync
            tips.value = details.productDetailsList.sortedBy { TIP_IDS.indexOf(it.productId) }
        }
    }

    fun buy(context: Context, tip: ProductDetails) {
        val c = client ?: return
        val activity = context.activity() ?: return
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(tip).build()))
            .build()
        c.launchBillingFlow(activity, params)
    }

    /** A paid tip: consumed (which also acknowledges it, or Play refunds it after three days), then thanked. */
    private fun settle(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        val c = client ?: return
        c.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()) { result, _ ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) thanked.value = true
            else Log.w(TAG, "tip not consumed: ${result.debugMessage}")
        }
    }
}

/**
 * The About pane's support section in this edition: a button per tip with
 * its local price, or the plain "free" line while Play has none to offer
 * (no Play Store, products not set up yet, offline).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TipJar(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        TipStore.connect(context)
        onDispose { TipStore.disconnect() }
    }
    val tips by TipStore.tips.collectAsState()
    val thanked by TipStore.thanked.collectAsState()
    if (tips.isEmpty()) {
        Text(
            stringResource(R.string.about_free_open_source),
            color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium,
            modifier = modifier
        )
        return
    }
    SettingsSection(stringResource(R.string.about_support_section))
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.about_support_text), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tips.forEach { tip ->
                val price = tip.oneTimePurchaseOfferDetails?.formattedPrice.orEmpty()
                SheetButton(if (price.isEmpty()) tip.name else "${tip.name}  $price", primary = tip == tips.first()) {
                    TipStore.buy(context, tip)
                }
            }
        }
        if (thanked) {
            Text(stringResource(R.string.about_tip_thanks), color = DashColors.Accent, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
