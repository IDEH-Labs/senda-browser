package org.senda.browser

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue

/**
 * Instrumented tests run on the phone's real data. Those that clear the history or
 * change the WebDAV password only run when explicitly requested:
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
