package com.kharcha.ledger.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kharcha.ledger.data.db.entity.AccountEntity
import com.kharcha.ledger.data.repository.LedgerRepository
import com.kharcha.ledger.engine.model.AccountType
import com.kharcha.ledger.engine.model.Money
import com.kharcha.ledger.ui.components.SectionCard
import com.kharcha.ledger.ui.components.relativeAge
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val repository: LedgerRepository
) : ViewModel() {

    val accounts: StateFlow<List<AccountEntity>> = repository.observeAccounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun confirm(account: AccountEntity, isMine: Boolean) {
        viewModelScope.launch { repository.confirmAccount(account.accountKey, isMine) }
    }
}

@Composable
fun AccountsScreen(viewModel: AccountsViewModel = hiltViewModel()) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val mine = accounts.filter { it.verifiedByUser }
    val pending = accounts.filter { !it.verifiedByUser && !it.hiddenByUser }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (pending.isNotEmpty()) {
            item {
                SectionCard(
                    title = "We found these accounts",
                    subtitle = "Confirming them lets Kharcha tell a transfer apart from spending"
                ) {
                    pending.forEach { account ->
                        Column(Modifier.fillMaxWidth()) {
                            Text(account.displayName, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Seen in ${account.occurrences} messages",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { viewModel.confirm(account, true) }) { Text("Mine") }
                                OutlinedButton(onClick = { viewModel.confirm(account, false) }) { Text("Not mine") }
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }
            }
        }

        item { Text("Your accounts", style = MaterialTheme.typography.headlineSmall) }

        items(mine, key = { it.accountKey }) { account ->
            SectionCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(account.displayName, style = MaterialTheme.typography.titleMedium)
                        account.balanceAt?.let {
                            Text(
                                "Last known balance · ${Instant.ofEpochMilli(it).relativeAge()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        account.balancePaise?.let { Money(it).format(withPaise = false) } ?: "—",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                if (account.accountType == AccountType.CREDIT_CARD.name) {
                    Spacer(Modifier.height(8.dp))
                    account.statementDuePaise?.let {
                        Text("Statement due ${Money(it).format(withPaise = false)}", style = MaterialTheme.typography.bodyMedium)
                    }
                    account.minimumDuePaise?.let {
                        Text("Minimum due ${Money(it).format(withPaise = false)}", style = MaterialTheme.typography.bodyMedium)
                    }
                    account.dueDate?.let {
                        Text("Due date $it", style = MaterialTheme.typography.bodyMedium)
                    }
                    account.availableLimitPaise?.let {
                        Text(
                            "Available limit ${Money(it).format(withPaise = false)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
