package org.senda.browser.core.security

import android.content.Context
import org.mozilla.geckoview.Autocomplete
import org.mozilla.geckoview.GeckoResult

/**
 * Ofrece a GeckoView las cuentas de la Bóveda para los formularios de inicio de sesión.
 *
 * Solo entrega usuario y origen, nunca la contraseña: la clave de la Bóveda exige huella o PIN, así que
 * descifrar al cargar cada página no es posible ni deseable. La contraseña se descifra cuando el usuario
 * elige la cuenta en el selector (SendaPrompt.LoginSelect), tras identificarse.
 */
class SendaVaultLoginStorage(context: Context) : Autocomplete.StorageDelegate {
    companion object {
        const val TAG = "SendaLogin"

        // Cuenta elegida cuyo relleno quedó pendiente: el aviso de huella le quita el foco a la página y
        // GeckoView cancela la petición; al volver a tocar el campo (clave aún disponible) se rellena sola
        private const val PENDING_MS = 25_000L
        @Volatile private var pendingGuid: String? = null
        @Volatile private var pendingAt = 0L

        fun setPending(guid: String) {
            pendingGuid = guid
            pendingAt = System.currentTimeMillis()
        }

        /** Devuelve y consume la cuenta pendiente si sigue vigente y está entre las [guids] ofrecidas. */
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
         * La misma cuenta con la contraseña descifrada, lista para confirmar el selector de GeckoView.
         * Lanza [VaultLockedException] si hace falta identificarse; null si la cuenta ya no existe.
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

    // GeckoView pide por dominio base (eTLD+1 resuelto con la Public Suffix List),
    // y luego filtra estrictamente por origen (signon.includeOtherSubdomainsInLookup deshabilitado)
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
