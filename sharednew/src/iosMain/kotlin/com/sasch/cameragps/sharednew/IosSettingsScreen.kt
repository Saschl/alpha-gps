package com.sasch.cameragps.sharednew

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.app_controls
import cameragps.sharednew.generated.resources.arrow_back_24px
import cameragps.sharednew.generated.resources.auto_scan
import cameragps.sharednew.generated.resources.auto_scan_description
import cameragps.sharednew.generated.resources.back
import cameragps.sharednew.generated.resources.enable_app
import cameragps.sharednew.generated.resources.enable_app_description
import cameragps.sharednew.generated.resources.settings
import cameragps.sharednew.generated.resources.tip_jar
import cameragps.sharednew.generated.resources.tip_jar_description
import cameragps.sharednew.generated.resources.tip_jar_dismiss
import cameragps.sharednew.generated.resources.tip_jar_error_prefix
import cameragps.sharednew.generated.resources.tip_jar_loading
import cameragps.sharednew.generated.resources.tip_jar_thank_you
import cameragps.sharednew.generated.resources.tip_jar_unavailable
import com.sasch.cameragps.sharednew.ui.settings.SharedSettingsCard
import com.sasch.cameragps.sharednew.ui.settings.SharedSettingsScreen
import com.sasch.cameragps.sharednew.ui.settings.SharedToggleRow
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun IosSettingsScreen(
    isAppEnabled: Boolean,
    autoScanEnabled: Boolean,
    onBackClick: () -> Unit,
    onOpenHelp: () -> Unit,
    onAppEnabledChange: (Boolean) -> Unit,
    onAutoScanEnabledChange: (Boolean) -> Unit,
    onShowWelcomeAgain: () -> Unit,
) {
    SharedSettingsScreen(
        title = stringResource(Res.string.settings),
        onBackClick = onBackClick,
        onTitleClick = {},
        navigationIcon = {
            Icon(
                painterResource(Res.drawable.arrow_back_24px),
                contentDescription = stringResource(Res.string.back)
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item {
                SharedSettingsCard(title = stringResource(Res.string.app_controls)) {
                    SharedToggleRow(
                        title = stringResource(Res.string.enable_app),
                        description = stringResource(Res.string.enable_app_description),
                        checked = isAppEnabled,
                        onCheckedChange = onAppEnabledChange,
                    )
                    SharedToggleRow(
                        title = stringResource(Res.string.auto_scan),
                        description = stringResource(Res.string.auto_scan_description),
                        checked = autoScanEnabled,
                        onCheckedChange = onAutoScanEnabledChange,
                    )
                    /* SharedToggleRow(
                         title = stringResource(Res.string.reset_welcome),
                         description = stringResource(Res.string.show_welcome_again_description),
                         checked = false,
                         onCheckedChange = { if (it) onShowWelcomeAgain() },
                         trailing = {
                             TextButton(onClick = onShowWelcomeAgain) {
                                 Text(stringResource(Res.string.welcome_get_started_button))
                             }
                         },
                     )*/
                }
            }
            /* item {
                 SharedSettingsCard(title = stringResource(Res.string.help_menu_item)) {
                     TextButton(onClick = onOpenHelp) {
                         Text(stringResource(Res.string.help_faq_title))
                     }
                 }
             }*/
            /*item {
                SharedSettingsCard(title = stringResource(Res.string.is_there_documenation)) {
                    Text(
                        text = stringResource(Res.string.is_there_documenation_answer),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }*/
            item {
                IosTipJarCard()
            }
        }
    }
}

@Composable
private fun IosTipJarCard() {
    val products by IosTipJarController.products.collectAsState()
    val purchaseState by IosTipJarController.purchaseState.collectAsState()
    val isLoadingProducts by IosTipJarController.isLoadingProducts.collectAsState()

    LaunchedEffect(Unit) {
        if (products.isEmpty() && !isLoadingProducts) {
            IosTipJarController.fetchProducts()
        }
    }

    SharedSettingsCard(title = stringResource(Res.string.tip_jar)) {
        Text(
            text = stringResource(Res.string.tip_jar_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(12.dp))

        when {
            // ── Purchase succeeded ──────────────────────────────────────────
            purchaseState is TipPurchaseState.Success -> {
                Text(
                    text = stringResource(Res.string.tip_jar_thank_you),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
                TextButton(onClick = { IosTipJarController.resetPurchaseState() }) {
                    Text(stringResource(Res.string.tip_jar_dismiss))
                }
            }

            // ── Purchase error ──────────────────────────────────────────────
            purchaseState is TipPurchaseState.Error -> {
                val msg = (purchaseState as TipPurchaseState.Error).message
                Text(
                    text = "${stringResource(Res.string.tip_jar_error_prefix)} $msg",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = { IosTipJarController.resetPurchaseState() }) {
                    Text(stringResource(Res.string.tip_jar_dismiss))
                }
            }

            // ── Purchase in progress ────────────────────────────────────────
            purchaseState is TipPurchaseState.Loading -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(Res.string.tip_jar_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ── Fetching products ───────────────────────────────────────────
            isLoadingProducts -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(Res.string.tip_jar_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ── Products unavailable ────────────────────────────────────────
            products.isEmpty() || !IosTipJarController.canMakePurchases() -> {
                Text(
                    text = stringResource(Res.string.tip_jar_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── Tip buttons ─────────────────────────────────────────────────
            else -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    products.forEach { product ->
                        OutlinedButton(
                            onClick = { IosTipJarController.purchase(product) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary,
                            ),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = product.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = product.formattedPrice,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
