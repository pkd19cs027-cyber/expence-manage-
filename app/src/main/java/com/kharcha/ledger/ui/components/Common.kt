package com.kharcha.ledger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kharcha.ledger.engine.model.CanonicalTransaction
import com.kharcha.ledger.engine.model.Direction
import com.kharcha.ledger.engine.model.Money
import com.kharcha.ledger.engine.model.TransactionKind
import com.kharcha.ledger.engine.model.TransactionStatus
import com.kharcha.ledger.ui.theme.IncomeGreen
import com.kharcha.ledger.ui.theme.NeutralBlue
import com.kharcha.ledger.ui.theme.SpendRed

@Composable
fun SectionCard(
    title: String? = null,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            content()
        }
    }
}

@Composable
fun StatTile(label: String, value: String, tint: Color = MaterialTheme.colorScheme.onSurface, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.headlineSmall, color = tint)
    }
}

/**
 * Colour carries meaning here: red is money gone, green is money in, blue is
 * money that only moved between the user's own pockets and must never read as
 * either.
 */
fun tintFor(transaction: CanonicalTransaction): Color = when {
    !transaction.status.hasFinancialImpact -> Color.Gray
    transaction.kind == TransactionKind.TRANSFER ||
        transaction.kind == TransactionKind.CARD_PAYMENT ||
        transaction.kind == TransactionKind.CASH_WITHDRAWAL -> NeutralBlue
    transaction.direction == Direction.CREDIT -> IncomeGreen
    else -> SpendRed
}

fun signedAmount(transaction: CanonicalTransaction): String {
    val prefix = when {
        !transaction.status.hasFinancialImpact -> ""
        transaction.kind == TransactionKind.TRANSFER || transaction.kind == TransactionKind.CARD_PAYMENT -> ""
        transaction.direction == Direction.CREDIT -> "+"
        else -> "−"
    }
    return prefix + transaction.amount.format()
}

@Composable
fun TransactionRow(
    transaction: CanonicalTransaction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = (transaction.merchant ?: transaction.category ?: "?").take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = transaction.merchant ?: transaction.category ?: "Payment",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1
            )
            Text(
                text = subtitleFor(transaction),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = signedAmount(transaction),
                style = MaterialTheme.typography.titleMedium,
                color = tintFor(transaction),
                fontWeight = FontWeight.SemiBold
            )
            StatusChip(transaction)
        }
    }
}

private fun subtitleFor(transaction: CanonicalTransaction): String {
    val parts = mutableListOf<String>()
    parts += transaction.timestamp.formatTime()
    transaction.account?.let { parts += "${it.institutionId} •${it.last4}" }
    transaction.channelApp?.let { parts += it }
    if (transaction.sourceCount > 1) parts += "${transaction.sourceCount} messages"
    return parts.joinToString(" · ")
}

@Composable
private fun StatusChip(transaction: CanonicalTransaction) {
    val label = when (transaction.status) {
        TransactionStatus.FAILED -> "Failed"
        TransactionStatus.REVERSED -> "Reversed · net ₹0"
        TransactionStatus.REFUNDED -> "Refunded"
        TransactionStatus.PARTIALLY_REFUNDED -> "Net ${transaction.netImpact.format()}"
        TransactionStatus.TRANSFER -> "Transfer"
        TransactionStatus.IGNORED -> "Ignored"
        else -> when (transaction.kind) {
            TransactionKind.CARD_PAYMENT -> "Card bill"
            TransactionKind.CASH_WITHDRAWAL -> "To cash"
            TransactionKind.INVESTMENT -> "Invested"
            TransactionKind.EMI -> "EMI"
            else -> return
        }
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
fun CategoryBar(label: String, amount: Money, share: Double, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(amount.format(withPaise = false), style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { share.toFloat().coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
        )
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), color = Color.Transparent) {
        Column(
            Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
