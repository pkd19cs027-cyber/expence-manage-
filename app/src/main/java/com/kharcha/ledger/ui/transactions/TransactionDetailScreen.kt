package com.kharcha.ledger.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.item
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kharcha.ledger.engine.merchant.Categories
import com.kharcha.ledger.engine.model.TimestampSource
import com.kharcha.ledger.ui.components.SectionCard
import com.kharcha.ledger.ui.components.formatFull
import com.kharcha.ledger.ui.components.signedAmount
import com.kharcha.ledger.ui.components.tintFor

@Composable
fun TransactionDetailScreen(
    transactionId: String,
    viewModel: TransactionDetailViewModel = hiltViewModel()
) {
    LaunchedEffect(transactionId) { viewModel.load(transactionId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val transaction = state.transaction ?: return

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text(
                    signedAmount(transaction),
                    style = MaterialTheme.typography.displaySmall,
                    color = tintFor(transaction)
                )
                Text(
                    transaction.merchant ?: transaction.merchantRaw ?: "Payment",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    transaction.timestamp.formatFull() +
                        if (transaction.timestampSource == TimestampSource.MESSAGE_RECEIVED_TIME) " (time approximate)" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            SectionCard(title = "Details") {
                DetailRow("Status", transaction.status.name.lowercase().replace('_', ' '))
                DetailRow("Type", transaction.kind.name.lowercase().replace('_', ' '))
                DetailRow("Method", transaction.method.name.lowercase())
                transaction.account?.let { DetailRow("Account", "${it.institutionId} •${it.last4}") }
                transaction.counterAccount?.let { DetailRow("To", "${it.institutionId} •${it.last4}") }
                transaction.channelApp?.let { DetailRow("Paid via", it) }
                transaction.counterpartyVpa?.let { DetailRow("UPI ID", it) }
                transaction.reference?.let { DetailRow(transaction.referenceKind.name.replace('_', ' '), it) }
                transaction.balanceAfter?.let { DetailRow("Balance after", it.format()) }
                if (transaction.refundedAmount.isPositive()) {
                    DetailRow("Refunded", transaction.refundedAmount.format())
                    DetailRow("Net impact", transaction.netImpact.format())
                }
                DetailRow("Confidence", "${(transaction.confidence * 100).toInt()}%")
            }
        }

        item {
            SectionCard(
                title = "Evidence",
                subtitle = "Confirmed from ${transaction.sourceCount} " +
                    if (transaction.sourceCount == 1) "message" else "messages"
            ) {
                transaction.sources.forEach { source ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text("${source.sender} · ${source.sourceType.name}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "hash ${source.messageHash.take(12)}… · parser v${source.parserVersion}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Message text is not stored unless you turn that on in Settings.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (state.links.isNotEmpty()) {
            item {
                SectionCard(title = "Related") {
                    state.links.forEach { link ->
                        val otherId = if (link.fromTransactionId == transaction.id) link.toTransactionId else link.fromTransactionId
                        val other = state.related[otherId]
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                viewModel.relationLabel(link, transaction.id),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                other?.amount?.format() ?: "—",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        link.explanation?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        item {
            SectionCard(title = "Category", subtitle = "Your choice is remembered for this merchant") {
                Column {
                    Categories.all.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { category ->
                                AssistChip(
                                    onClick = { viewModel.setCategory(category) },
                                    label = { Text(category, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
