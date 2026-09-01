package com.kharcha.ledger.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kharcha.ledger.data.prefs.AppSettings
import com.kharcha.ledger.data.prefs.SettingsRepository
import com.kharcha.ledger.data.repository.LedgerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsState(
    val settings: AppSettings = AppSettings(),
    val messagesRead: Int = 0,
    val messagesIgnored: Int = 0
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val repository: LedgerRepository
) : ViewModel() {

    val state: StateFlow<SettingsState> = combine(
        settingsRepository.settings,
        repository.observeMessageStats()
    ) { settings, stats ->
        SettingsState(settings, stats.first, stats.second)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsState())

    fun setKeepRaw(keep: Boolean) = viewModelScope.launch {
        settingsRepository.setKeepRawMessages(keep)
        // Turning retention off deletes what was already kept, immediately.
        if (!keep) repository.purgeRawMessages()
    }

    fun setBiometric(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setBiometricLock(enabled)
    }

    fun setAutoMerge(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setAutoMergeDuplicates(enabled)
    }

    fun reprocess() = viewModelScope.launch { repository.reprocessRecent() }
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall) }

        item {
            SettingToggle(
                title = "Keep original message text",
                body = "Off by default. Kharcha stores what it extracted and a hash of each " +
                    "message, not the words. Turning this on keeps the SMS text in the " +
                    "encrypted database so you can check a parse.",
                checked = state.settings.keepRawMessages,
                onChange = viewModel::setKeepRaw
            )
        }

        item {
            SettingToggle(
                title = "Require unlock",
                body = "Ask for your fingerprint or device PIN before opening the app.",
                checked = state.settings.biometricLock,
                onChange = viewModel::setBiometric
            )
        }

        item {
            SettingToggle(
                title = "Merge duplicates automatically",
                body = "When a bank, a UPI app and a merchant all report the same payment, " +
                    "keep one transaction. Turn off to review every match yourself.",
                checked = state.settings.autoMergeDuplicates,
                onChange = viewModel::setAutoMerge
            )
        }

        item {
            Column {
                Text("Privacy", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "This app has no internet permission. Nothing it reads can leave the phone: " +
                        "there is no account, no sync and no analytics. ${state.messagesRead} messages " +
                        "have been processed here, ${state.messagesIgnored} of them ignored as OTPs, " +
                        "marketing or balance alerts.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            Button(onClick = { viewModel.reprocess() }) { Text("Re-run reconciliation") }
        }
    }
}

@Composable
private fun SettingToggle(
    title: String,
    body: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
