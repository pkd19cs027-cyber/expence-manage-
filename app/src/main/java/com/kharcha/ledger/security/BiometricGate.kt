package com.kharcha.ledger.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import java.util.concurrent.Executor

/**
 * Optional lock in front of the ledger.
 *
 * The database is encrypted regardless; this is about the person standing next
 * to you, not about the phone being stolen.
 */
object BiometricGate {

    private val ALLOWED = BiometricManager.Authenticators.BIOMETRIC_WEAK or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun isAvailable(activity: FragmentActivity): Boolean =
        BiometricManager.from(activity).canAuthenticate(ALLOWED) == BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(
        activity: FragmentActivity,
        executor: Executor,
        onSuccess: () -> Unit,
        onFailure: () -> Unit
    ) {
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFailure()
            }
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Kharcha")
                .setSubtitle("Your ledger is encrypted on this device")
                .setAllowedAuthenticators(ALLOWED)
                .build()
        )
    }
}
