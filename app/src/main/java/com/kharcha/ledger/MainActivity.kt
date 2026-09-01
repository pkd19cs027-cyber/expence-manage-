package com.kharcha.ledger

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kharcha.ledger.data.prefs.SettingsRepository
import com.kharcha.ledger.data.repository.LedgerRepository
import com.kharcha.ledger.security.BiometricGate
import com.kharcha.ledger.ui.navigation.KharchaNavHost
import com.kharcha.ledger.ui.onboarding.OnboardingScreen
import com.kharcha.ledger.ui.theme.KharchaTheme
import com.kharcha.ledger.work.WorkScheduler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * A FragmentActivity because BiometricPrompt needs one; everything above it is
 * Compose.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var settings: SettingsRepository

    @Inject lateinit var repository: LedgerRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            KharchaTheme {
                val appSettings by settings.settings.collectAsStateWithLifecycle(initialValue = null)
                val reviewCount by repository.observeOpenReviewCount()
                    .collectAsStateWithLifecycle(initialValue = 0)

                var unlocked by remember { mutableStateOf(false) }
                val locked = appSettings?.biometricLock == true && !unlocked

                LaunchedEffect(appSettings?.onboardingComplete) {
                    if (appSettings?.onboardingComplete == true) {
                        // Catch up on anything that arrived while the app was closed.
                        WorkScheduler.enqueueIncrementalImport(this@MainActivity)
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when {
                        appSettings == null -> Unit
                        locked -> LockScreen(
                            onUnlock = {
                                BiometricGate.prompt(
                                    activity = this@MainActivity,
                                    executor = ContextCompat.getMainExecutor(this@MainActivity),
                                    onSuccess = { unlocked = true },
                                    onFailure = {}
                                )
                            }
                        )

                        appSettings?.onboardingComplete != true -> OnboardingScreen(onDone = {})

                        else -> KharchaNavHost(reviewCount = reviewCount)
                    }
                }
            }
        }
    }
}

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    LaunchedEffect(Unit) { onUnlock() }
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Kharcha is locked", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Your ledger is encrypted on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = onUnlock, modifier = Modifier.padding(top = 16.dp)) { Text("Unlock") }
    }
}
