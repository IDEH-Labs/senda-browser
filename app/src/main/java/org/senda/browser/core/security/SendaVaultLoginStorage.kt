package org.senda.browser.core.security

import android.content.Context
import org.mozilla.geckoview.Autocomplete
import org.mozilla.geckoview.GeckoResult

/**
 * Offers GeckoView the vault accounts for sign-in forms.
 *
 * It only hands over username and origin, never the password: the vault key requires fingerprint or PIN, so
 * decrypting on every page load is neither possible nor desirable. The password is decrypted when the user
 * picks the account in the selector (SendaPrompt.LoginSelect), after authenticating.
 */
class SendaVaultLoginStorage(context: Context) : Autocomplete.StorageDelegate {
    companion object {
        const val TAG = "SendaLogin"

        // Chosen account whose fill is still pending: the fingerprint prompt takes focus away from the page and
        // GeckoView cancels the request; tapping the field again (key still available) fills it automatically
        private const val PENDING_MS = 25_000L
        @Volatile private var pendingGuid: String? = null
        @Volatile private var pendingAt = 0L

        fun setPending(guid: String) {
            pendingGuid = guid
            pendingAt = System.currentTimeMillis()
        }

        /** Returns and consumes the pending account if it is still valid and among the offered [guids]. */
        fun takePending(guids: Collection<String?>): String? {
            val guid = pendingGuid ?: return null
            if (System.currentTimeMillis() - pendingAt > PENDING_MS) {
                pendingGuid = null
                return null
            }
            if (guid !in guids) return null
            pendingGuid = null
            return guid
        }

        /**
         * The same account with the password decrypted, ready to confirm GeckoView's selector.
         * Throws [VaultLockedException] if authentication is needed; null if the account no longer exists.
         */
        fun filledOption(context: Context, option: Autocomplete.LoginSelectOption): Autocomplete.LoginSelectOption? {
            val entry = option.value
            val cred = SendaVaultManager.getCredentials(context).firstOrNull { it.id == entry.guid } ?: return null
            val chars = SendaVaultManager.decryptPassword(cred.encryptedPasswordBase64, cred.ivBase64)
            val filled = Autocomplete.LoginEntry.Builder()
                .guid(entry.guid)
                .origin(entry.origin)
                .formActionOrigin(entry.formActionOrigin ?: "")
                .username(entry.username)
                .password(String(chars))
                .build()
            SendaVaultManager.wipe(chars)
            return Autocomplete.LoginSelectOption(filled, option.hint)
        }
    }

    private val appContext = context.applicationContext

    // GeckoView asks by base domain (eTLD+1 resolved with the Public Suffix List),
    // and then filters strictly by origin (signon.includeOtherSubdomainsInLookup disabled)
    override fun onLoginFetch(domain: String): GeckoResult<Array<Autocomplete.LoginEntry>> {
        val baseDomain = SendaVaultManager.extractCanonicalDomain(domain, appContext)
        val entries = try {
            SendaVaultManager.getCredentials(appContext)
                .filter { it.domain.equals(baseDomain, ignoreCase = true) }
                .map { cred ->
                    Autocomplete.LoginEntry.Builder()
                        .guid(cred.id)
                        .origin(originOf(cred))
                        .formActionOrigin("")
                        .username(cred.username)
                        .password("")
                        .build()
                }
        } catch (_: Exception) {
            emptyList()
        }
        android.util.Log.i(TAG, "onLoginFetch: ${entries.size} cuenta(s) para el dominio base")
        return GeckoResult.fromValue(entries.toTypedArray())
    }

    private fun originOf(cred: VaultCredential): String {
        val uri = try { java.net.URI(cred.originUrl.trim()) } catch (_: Exception) { null }
        val scheme = uri?.scheme?.lowercase()
        val host = uri?.host
        return if ((scheme == "https" || scheme == "http") && !host.isNullOrBlank()) {
            if (uri.port > 0) "$scheme://$host:${uri.port}" else "$scheme://$host"
        } else {
            "https://${cred.domain}"
        }
    }
}
