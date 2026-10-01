package uk.co.promptbuilt.notestodos.store

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import java.text.NumberFormat
import java.util.Currency
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/** What the paywall and Settings show. */
data class OpsState(
    val products: List<ProductDetails> = emptyList(),
    val productsLoaded: Boolean = false,
    val balance: Int? = null,
    val purchased: Boolean = false,
    val purchasing: Boolean = false,
    val message: String? = null,
    /** Google Play's own reason when no packs load, shown under the paywall's notice. */
    val unavailableReason: String? = null,
) {
    /** Accounts holding purchased credits name photographs without the daily cap. */
    val namingExempt: Boolean get() = purchased && (balance ?: 0) > 0
}

/**
 * Google Play consumable credit packs. The crediting contract mirrors iOS's OpsStore: a purchase
 * is consumed ONLY after the Worker has answered 2xx (idempotent by order id, so a lost reply or
 * a replay can never double-credit). Anything unconsumed is resubmitted at launch, so no purchase
 * is lost. Google refunds a purchase that is never acknowledged within three days, and consuming
 * acknowledges, so a submission that fails is retried at every launch and paywall visit.
 */
class OpsStore(
    context: Context,
    private val account: OpsAccount,
    private val api: OpsWorkerApi,
) : PurchasesUpdatedListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(OpsState())
    val state: StateFlow<OpsState> = _state.asStateFlow()

    private val billing: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    private var started = false

    /** Idempotent; called once at app start. */
    fun start() {
        if (started) return
        started = true
        scope.launch { refreshBalance() }
        billing.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    scope.launch {
                        loadProducts()
                        drainUnconsumed()
                    }
                } else {
                    Log.w(TAG, "billing setup failed: ${result.responseCode} ${result.debugMessage}")
                    _state.update {
                        it.copy(productsLoaded = true, unavailableReason = "Google Play billing setup: code ${result.responseCode}")
                    }
                }
            }

            override fun onBillingServiceDisconnected() = Unit // enableAutoServiceReconnection
        })
    }

    /** Called when the paywall opens: a launch-time load can race Play or the network. */
    fun onPaywallShown() {
        scope.launch {
            refreshBalance()
            if (_state.value.products.isEmpty()) loadProducts()
            drainUnconsumed()
        }
    }

    suspend fun refreshBalance() {
        try {
            val reply = api.balance(account.token())
            _state.update { it.copy(balance = reply.balance, purchased = reply.purchased) }
        } catch (e: Exception) {
            // Offline is normal; keep the last known balance rather than alarming anyone.
            Log.i(TAG, "balance refresh failed: ${e.message}")
        }
    }

    suspend fun loadProducts() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                PACK_CREDITS.keys.map { id ->
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(id)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                },
            )
            .build()
        val (loaded, reason) = suspendCancellableCoroutine { cont ->
            billing.queryProductDetailsAsync(params) { result, details ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    Log.w(TAG, "product query failed: ${result.responseCode} ${result.debugMessage}")
                }
                val unfetched = details.unfetchedProductList.joinToString { "${it.productId.substringAfterLast('.')}=${it.statusCode}" }
                val reason = "Google Play code ${result.responseCode}" + if (unfetched.isEmpty()) "" else ", unavailable: $unfetched"
                cont.resume(details.productDetailsList to reason)
            }
        }
        Log.i(TAG, "products: requested ${PACK_CREDITS.size}, got ${loaded.size} ${loaded.map { it.productId }}; $reason")
        _state.update {
            it.copy(
                products = loaded.sortedBy { p -> p.oneTimePurchaseOfferDetails?.priceAmountMicros ?: 0 },
                productsLoaded = true,
                unavailableReason = if (loaded.isEmpty()) reason else null,
            )
        }
    }

    fun purchase(activity: Activity, product: ProductDetails) {
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product).build()),
            )
            .setObfuscatedAccountId(account.token())
            .build()
        _state.update { it.copy(purchasing = true, message = null) }
        val result = billing.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            _state.update { it.copy(purchasing = false, message = "Purchase could not start: ${result.debugMessage}") }
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases.orEmpty().forEach { scope.launch { submit(it) } }
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            else -> _state.update { it.copy(message = "Purchase failed: ${result.debugMessage}") }
        }
        _state.update { it.copy(purchasing = false) }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private suspend fun drainUnconsumed() {
        val owned = suspendCancellableCoroutine { cont ->
            billing.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
            ) { _, list -> cont.resume(list) }
        }
        owned.forEach { submit(it) }
    }

    /** Credit the pack with the Worker, then consume it. Never consume before the credit lands. */
    private suspend fun submit(purchase: Purchase) {
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PENDING -> {
                _state.update { it.copy(message = "Waiting for payment approval. The credits arrive once it is approved.") }
                return
            }
            Purchase.PurchaseState.PURCHASED -> Unit
            else -> return
        }
        val bearer = purchase.accountIdentifiers?.obfuscatedAccountId ?: account.token()
        try {
            var balance: Int? = null
            for (productId in purchase.products) {
                balance = api.submitGooglePurchase(bearer, productId, purchase.purchaseToken)
            }
            consume(purchase.purchaseToken)
            if (bearer == account.token()) {
                _state.update { it.copy(balance = balance ?: it.balance, purchased = true, message = null) }
            }
            refreshBalance()
        } catch (e: OpsWorkerApi.WorkerException) {
            if (e.status != 409) {
                _state.update { it.copy(message = "Your purchase is safe but not yet credited. HobPad will try again shortly.") }
            }
            Log.w(TAG, "purchase submission failed: ${e.message}")
        } catch (e: Exception) {
            _state.update { it.copy(message = "Your purchase is safe but not yet credited. HobPad will try again shortly.") }
            Log.w(TAG, "purchase submission failed: ${e.message}")
        }
    }

    private suspend fun consume(purchaseToken: String) {
        suspendCancellableCoroutine { cont ->
            billing.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(purchaseToken).build()) { result, _ ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    Log.w(TAG, "consume failed: ${result.responseCode} ${result.debugMessage}")
                }
                cont.resume(Unit)
            }
        }
    }

    companion object {
        private const val TAG = "OpsStore"

        /**
         * Sale copy for each pack. The Worker's PRODUCT_CREDITS map (backend/ops/wrangler.toml)
         * is the authority for what a pack actually credits; keep the two identical.
         */
        val PACK_CREDITS = linkedMapOf(
            "uk.co.promptbuilt.hobpad.ops50" to 50,
            "uk.co.promptbuilt.hobpad.ops100" to 100,
            "uk.co.promptbuilt.hobpad.ops500" to 500,
        )

        /** "£0.04 per conversion", in the buyer's own currency. */
        fun perConversion(product: ProductDetails): String? {
            val offer = product.oneTimePurchaseOfferDetails ?: return null
            val credits = PACK_CREDITS[product.productId] ?: return null
            val format = NumberFormat.getCurrencyInstance().apply {
                currency = Currency.getInstance(offer.priceCurrencyCode)
            }
            return "${format.format(offer.priceAmountMicros / 1_000_000.0 / credits)} per conversion"
        }
    }
}
