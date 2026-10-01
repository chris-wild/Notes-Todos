package uk.co.promptbuilt.notestodos.ui.recipes

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.co.promptbuilt.notestodos.data.AppPrefs
import uk.co.promptbuilt.notestodos.store.OpsStore

/**
 * The conversion dialog, iOS's ConvertSheet: the quantities stepper lives here rather than on
 * the viewer, with the credit cost (or the free re-run) stated before anything is spent.
 */
@Composable
fun ConvertDialog(
    cost: Int?,
    balance: Int?,
    working: Boolean,
    onCreate: (multiplier: Int) -> Unit,
    onCancel: () -> Unit,
) {
    var multiplier by remember { mutableIntStateOf(1) }
    AlertDialog(
        onDismissRequest = { if (!working) onCancel() },
        title = { Text("Ingredient list") },
        text = {
            Column {
                Text(
                    "Cooking for more?",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Quantities ×$multiplier",
                        fontWeight = if (multiplier == 1) FontWeight.Normal else FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { multiplier-- }, enabled = multiplier > 1) { Text("−") }
                    TextButton(onClick = { multiplier++ }, enabled = multiplier < 10) { Text("+") }
                }
                Button(
                    onClick = { onCreate(multiplier) },
                    enabled = !working,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                ) {
                    if (working) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp).width(18.dp))
                    }
                    Text(if (working) "Extracting ingredients…" else "Create ingredient list")
                }
                Text(
                    text = if (cost != null) {
                        val have = balance ?: 0
                        "Uses $cost of your $have conversion credit${if (have == 1) "" else "s"}."
                    } else {
                        "This recipe converts free, because its ingredients are already known."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !working) { Text("Cancel") }
        },
    )
}

/** Credit packs, iOS's PaywallSheet. Consumables need no restore button. */
@Composable
fun PaywallDialog(opsStore: OpsStore, onClose: () -> Unit) {
    val state by opsStore.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current.findActivity()
    LaunchedEffect(Unit) { opsStore.onPaywallShown() }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Recipe conversions") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row {
                    Text("Credits remaining", modifier = Modifier.weight(1f))
                    Text(state.balance?.toString() ?: "–", fontWeight = FontWeight.SemiBold)
                }
                Text(
                    "One credit turns one recipe page, either a photograph or a page of a PDF, into a " +
                        "shopping list. New installs start with 5 free credits. Credits never expire, and " +
                        "your balance is restored with your Android backup.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                Text("Buy credits", style = MaterialTheme.typography.titleSmall)
                when {
                    state.products.isEmpty() && !state.productsLoaded -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.width(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Loading packs…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    state.products.isEmpty() -> Text(
                        "The credit packs are not available right now. Check your connection and try again later.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    else -> state.products.forEach { product ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 10.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(product.name.ifBlank { product.productId })
                                OpsStore.perConversion(product)?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Button(
                                onClick = { activity?.let { opsStore.purchase(it, product) } },
                                enabled = !state.purchasing && activity != null,
                            ) {
                                Text(product.oneTimePurchaseOfferDetails?.formattedPrice ?: "Buy")
                            }
                        }
                    }
                }
                state.message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text("Close") }
        },
    )
}

/**
 * Past the daily naming budget, or when naming fails, the photo is already saved under a
 * date-stamped name and this asks for the real one. Cancel keeps the date name.
 */
@Composable
fun NameRecipeDialog(
    dailyLimit: Boolean,
    onSave: (String) -> Unit,
    onBuyCredits: () -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (dailyLimit) "Daily naming limit reached" else "Name this recipe") },
        text = {
            Column {
                Text(
                    if (dailyLimit) {
                        "Automatic naming has reached today's limit of ${AppPrefs.AUTO_NAME_DAILY_LIMIT}. " +
                            "The photo is saved. Type a name now, cancel and rename it later, or buy a " +
                            "credit pack. There is no daily limit while you have purchased credits."
                    } else {
                        "The photo is saved, but automatic naming did not work this time. Type a name for this recipe."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("Recipe name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.End) {
                if (dailyLimit) TextButton(onClick = onBuyCredits) { Text("Buy credits") }
                TextButton(onClick = { onSave(name) }) { Text("Save") }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        },
    )
}

/** The Activity hosting a composition, which Play Billing needs to show its purchase sheet. */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
