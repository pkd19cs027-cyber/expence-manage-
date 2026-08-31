package com.kharcha.ledger.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.item
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kharcha.ledger.data.repository.LedgerRepository
import com.kharcha.ledger.engine.merchant.Categories
import com.kharcha.ledger.engine.review.ReviewAction
import com.kharcha.ledger.engine.review.ReviewItem
import com.kharcha.ledger.ui.components.SectionCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val repository: LedgerRepository
) : ViewModel() {

    val items: StateFlow<List<ReviewItem>> = repository.observeOpenReviews()
        .map { list -> list.sortedByDescending { it.createdAt } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun resolve(item: ReviewItem, action: ReviewAction, value: String? = null) {
        viewModelScope.launch { repository.resolveReview(item.id, action, value) }
    }
}

/**
 * One queue for everything the engine was not sure about, instead of a
 * notification every time a message is ambiguous. Each card states the question
 * in plain language and offers the two or three answers that make sense.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReviewScreen(viewModel: ReviewViewModel = hiltViewModel()) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    var categoryFor by remember { mutableStateOf<ReviewItem?>(null) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                if (items.isEmpty()) "Nothing to review" else "Needs review · ${items.size}",
                style = MaterialTheme.typography.headlineSmall
            )
        }
        if (items.isEmpty()) {
            item {
                Text(
                    "Everything Kharcha read was clear enough to file on its own.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        items(items, key = { it.id }) { reviewItem ->
            SectionCard(title = reviewItem.reason.title) {
                Text(reviewItem.summary, style = MaterialTheme.typography.bodyMedium)
                reviewItem.detail?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (reviewItem.signals.isNotEmpty()) {
                    Text(
                        "Why: " + reviewItem.signals.joinToString(", "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    reviewItem.actions.forEach { action ->
                        Button(
                            onClick = {
                                if (action == ReviewAction.SET_CATEGORY) {
                                    categoryFor = reviewItem
                                } else {
                                    viewModel.resolve(reviewItem, action)
                                }
                            }
                        ) {
                            Text(action.label())
                        }
                    }
                }
            }
        }
    }

    categoryFor?.let { pending ->
        AlertDialog(
            onDismissRequest = { categoryFor = null },
            title = { Text("Pick a category") },
            text = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Categories.all.forEach { category ->
                        AssistChip(
                            onClick = {
                                viewModel.resolve(pending, ReviewAction.SET_CATEGORY, category)
                                categoryFor = null
                            },
                            label = { Text(category, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { categoryFor = null }) { Text("Cancel") }
            }
        )
    }
}

private fun ReviewAction.label(): String = when (this) {
    ReviewAction.MERGE -> "Merge"
    ReviewAction.KEEP_SEPARATE -> "Keep separate"
    ReviewAction.MARK_TRANSFER -> "It's a transfer"
    ReviewAction.MARK_EXPENSE -> "It's an expense"
    ReviewAction.MARK_INVESTMENT -> "It's an investment"
    ReviewAction.MARK_INCOME -> "It's income"
    ReviewAction.SET_CATEGORY -> "Set category"
    ReviewAction.SET_MERCHANT -> "Name merchant"
    ReviewAction.CONFIRM_ACCOUNT -> "Pick account"
    ReviewAction.IGNORE -> "Ignore"
}
