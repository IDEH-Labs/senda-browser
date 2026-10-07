package org.senda.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.senda.browser.core.security.SendaVaultManager
import java.security.KeyStore

/**
 * Comprobación de la clave de la Bóveda sin tocar nada: no crea, no borra y no descifra.
 * La anulación real (quitar el bloqueo de pantalla) no se puede provocar desde una prueba.
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
        // En un teléfono con bloqueo de pantalla la clave es válida: no debe ofrecerse borrar la Bóveda
        assertFalse(SendaVaultManager.isVaultKeyInvalidated())
    }
}
