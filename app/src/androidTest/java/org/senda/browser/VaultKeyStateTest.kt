package org.senda.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.senda.browser.core.security.SendaVaultManager
import java.security.KeyStore

/**
 * Checks the vault key without changing anything: it does not create, delete or decrypt.
 * Real invalidation (removing the screen lock) cannot be triggered from a test.
 */
@RunWith(AndroidJUnit4::class)
class VaultKeyStateTest {

    private fun keyExists(): Boolean =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(SendaVaultManager.VAULT_KEY_ALIAS)

    @Test
    fun probeDoesNotCreateOrDeleteTheKey() {
        val before = keyExists()
        SendaVaultManager.isVaultKeyInvalidated()
        assertEquals("la comprobación no debe crear ni borrar la clave", before, keyExists())
    }

    @Test
    fun validKeyIsNotReportedAsInvalidated() {
        // On a phone with a screen lock the key is valid: deleting the vault must not be offered
        assertFalse(SendaVaultManager.isVaultKeyInvalidated())
    }
}
