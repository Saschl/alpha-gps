package com.sasch.cameragps.sharednew

import com.diamondedge.logging.logging
import com.sasch.cameragps.sharednew.IosTipJarController.fetchProducts
import com.sasch.cameragps.sharednew.IosTipJarController.products
import com.sasch.cameragps.sharednew.IosTipJarController.purchase
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import platform.Foundation.NSError
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterCurrencyStyle
import platform.StoreKit.SKPayment
import platform.StoreKit.SKPaymentQueue
import platform.StoreKit.SKPaymentTransaction
import platform.StoreKit.SKPaymentTransactionObserverProtocol
import platform.StoreKit.SKPaymentTransactionState
import platform.StoreKit.SKProduct
import platform.StoreKit.SKProductsRequest
import platform.StoreKit.SKProductsRequestDelegateProtocol
import platform.StoreKit.SKProductsResponse
import platform.StoreKit.SKRequest
import platform.darwin.NSObject

internal data class TipProduct(
    val productId: String,
    val title: String,
    val formattedPrice: String,
    val skProduct: SKProduct,
)

internal sealed class TipPurchaseState {
    data object Idle : TipPurchaseState()
    data object Loading : TipPurchaseState()
    data object Success : TipPurchaseState()
    data class Error(val message: String) : TipPurchaseState()
}

/**
 * Singleton that manages in-app purchase products used as voluntary tips.
 * The revenue covers the Apple Developer Program fee ($99/year).
 *
 * Call [fetchProducts] once to populate [products] from App Store Connect.
 * Use [purchase] to initiate a tip payment.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal object IosTipJarController {

    private val logging = logging()

    // These product IDs must exist in App Store Connect as consumable IAPs.
    private val tipProductIds = listOf(
        "com.saschl.cameragps.tip.small",
        "com.saschl.cameragps.tip.medium",
        "com.saschl.cameragps.tip.large"
    )

    private val _products = MutableStateFlow<List<TipProduct>>(emptyList())
    val products: StateFlow<List<TipProduct>> = _products

    private val _purchaseState = MutableStateFlow<TipPurchaseState>(TipPurchaseState.Idle)
    val purchaseState: StateFlow<TipPurchaseState> = _purchaseState

    private val _isLoadingProducts = MutableStateFlow(false)
    val isLoadingProducts: StateFlow<Boolean> = _isLoadingProducts

    // Held strongly so ARC doesn't release them before callbacks fire.
    private var requestDelegate: NSObject? = null
    private var transactionObserver: NSObject? = null

    // Cache of fetched SKProduct objects for use during purchase.
    private val skProductMap = mutableMapOf<String, SKProduct>()

    init {
        registerTransactionObserver()
    }

    private fun registerTransactionObserver() {
        val observer = object : NSObject(), SKPaymentTransactionObserverProtocol {
            @ObjCSignatureOverride
            override fun paymentQueue(queue: SKPaymentQueue, updatedTransactions: List<*>) {
                updatedTransactions.forEach { any ->
                    val tx = any as? SKPaymentTransaction ?: return@forEach
                    val productId = tx.payment.productIdentifier
                    /*  NSLog(
                          "TipJar: transaction update state=%@ product=%@ error=%@",
                          tx.transactionState.toString(),
                          productId,
                          tx.error?.localizedDescription ?: "none",
                      )*/
                    when (tx.transactionState) {
                        SKPaymentTransactionState.SKPaymentTransactionStatePurchased,
                        SKPaymentTransactionState.SKPaymentTransactionStateRestored -> {
                            // NSLog("TipJar: purchase success for %@", productId)
                            queue.finishTransaction(tx)
                            _purchaseState.update { TipPurchaseState.Success }
                        }

                        SKPaymentTransactionState.SKPaymentTransactionStateFailed -> {
                            queue.finishTransaction(tx)
                            // SKErrorPaymentCancelled = 2 — treat user cancellation as a no-op.
                            val wasCancelled = tx.error!!.code == 2L
                            if (wasCancelled) {
                                // NSLog("TipJar: purchase cancelled by user for %@", productId)
                            } else {
                                /* NSLog(
                                     "TipJar: purchase failed for %@ - %@",
                                     productId,
                                     tx.error!!.localizedDescription,
                                 )*/
                            }
                            _purchaseState.update {
                                if (wasCancelled) {
                                    TipPurchaseState.Idle
                                } else {
                                    TipPurchaseState.Error(tx.error!!.localizedDescription)
                                }
                            }
                        }

                        // .purchasing / .deferred — wait for the next callback.
                        else -> {
                            // NSLog("TipJar: waiting for terminal state for %@", productId)
                        }
                    }
                }
            }
        }
        transactionObserver = observer
        SKPaymentQueue.defaultQueue().addTransactionObserver(observer)
    }

    /** Fetches product info (title + price) from App Store Connect. Safe to call multiple times. */
    fun fetchProducts() {
        if (_isLoadingProducts.value) return
        _isLoadingProducts.update { true }

        val request = SKProductsRequest(productIdentifiers = tipProductIds.toSet())

        val delegate = object : NSObject(), SKProductsRequestDelegateProtocol {
            override fun productsRequest(
                request: SKProductsRequest,
                didReceiveResponse: SKProductsResponse,
            ) {

                val formatter = NSNumberFormatter().apply {
                    numberStyle = NSNumberFormatterCurrencyStyle
                }
                val tipProducts = didReceiveResponse.products.mapNotNull { any ->

                    val product = any as? SKProduct ?: return@mapNotNull null
                    formatter.locale = product.priceLocale
                    val priceString = formatter.stringFromNumber(product.price)
                        ?: product.price.stringValue
                    skProductMap[product.productIdentifier] = product
                    TipProduct(
                        productId = product.productIdentifier,
                        title = product.localizedTitle,
                        formattedPrice = priceString,
                        skProduct = product,
                    )
                }.sortedBy { tipProductIds.indexOf(it.productId) }

                _products.update { tipProducts }
                _isLoadingProducts.update { false }
                logging.d(msg = { "TipJar: loaded ${tipProducts.size} products" })
            }

            @ObjCSignatureOverride
            override fun request(request: SKRequest, didFailWithError: NSError) {
                _isLoadingProducts.update { false }
                logging.i { "TipJar: product fetch failed — ${didFailWithError.localizedDescription}" }
            }
        }

        // Keep a strong reference so ARC doesn't collect the delegate before the callback.
        requestDelegate = delegate
        request.delegate = delegate
        request.start()
    }

    /** Initiates a tip purchase for [tipProduct]. */
    fun purchase(tipProduct: TipProduct) {
        // Prefer the cached reference; fall back to the one on the model.
        val product = skProductMap[tipProduct.productId] ?: tipProduct.skProduct
        _purchaseState.update { TipPurchaseState.Loading }
        val payment = SKPayment.paymentWithProduct(product)
        SKPaymentQueue.defaultQueue().addPayment(payment)
    }

    /** Resets the purchase state back to [TipPurchaseState.Idle]. */
    fun resetPurchaseState() {
        _purchaseState.update { TipPurchaseState.Idle }
    }

    /** Returns `false` if parental controls or other restrictions prevent purchases. */
    fun canMakePurchases(): Boolean = SKPaymentQueue.canMakePayments()
}









