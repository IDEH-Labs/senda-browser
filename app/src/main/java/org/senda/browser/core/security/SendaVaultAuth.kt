package org.senda.browser.core.security

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Asks for fingerprint or PIN so the vault key becomes available ([SendaVaultManager.AUTH_VALIDITY_SECONDS]). */
object SendaVaultAuth {
    fun request(context: Context, title: String, callback: (Boolean) -> Unit) {
        val activity = context as? FragmentActivity
        if (activity == null) {
            callback(true)
            return
        }
        // Fingerprint or the phone's PIN/pattern: with no enrolled fingerprint the vault was inaccessible
        // The vault key is only unlocked with a "strong" fingerprint or PIN (Android 11+)
        val authenticators = (if (Build.VERSION.SDK_INT >= 30)
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        else
            BiometricManager.Authenticators.BIOMETRIC_WEAK) or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(activity).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            // With no lock at all on the phone there is nothing to verify with
            callback(true)
            return
        }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    callback(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    callback(false)
                }
            }
        )
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(promptInfo)
    }
}
