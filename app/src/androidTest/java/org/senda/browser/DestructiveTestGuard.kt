package org.senda.browser

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue

/**
 * Las pruebas instrumentadas corren sobre los datos reales del teléfono. Las que borran el historial o
 * cambian la contraseña de WebDAV solo corren si se piden expresamente:
 * adb shell am instrument -w -e allowDestructive 1 …
 */
object DestructiveTestGuard {
    fun requireExplicitPermission(what: String) {
        assumeTrue(
            "Omitida: $what. Usa -e allowDestructive 1 en un teléfono de pruebas",
            InstrumentationRegistry.getArguments().getString("allowDestructive") == "1"
        )
    }
}
