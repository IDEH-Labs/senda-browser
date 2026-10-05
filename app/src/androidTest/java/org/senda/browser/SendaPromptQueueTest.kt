package org.senda.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.senda.browser.ui.model.BrowserTab
import org.senda.browser.ui.model.SendaPrompt

/**
 * Cola de diálogos de una pestaña: dos descargas seguidas no se pisan y cerrar la pestaña resuelve todo.
 * Pestaña privada en about:blank, sin preferencias: no toca datos del usuario.
 */
@RunWith(AndroidJUnit4::class)
class SendaPromptQueueTest {

    @Test
    fun promptsWaitTheirTurnAndAllAreResolved() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val tab = BrowserTab(isPrivate = true)
            val decisions = mutableListOf<String>()
            val first = SendaPrompt.Download("a.pdf", "ejemplo.org", 10) { decisions += "a=$it" }
            val second = SendaPrompt.Download("b.pdf", "ejemplo.org", 10) { decisions += "b=$it" }
            val third = SendaPrompt.SlowScript("ejemplo.org") { decisions += "script=$it" }

            tab.activePrompt = first
            tab.activePrompt = second
            tab.activePrompt = third
            assertSame("La primera sigue a la vista", first, tab.activePrompt)
            assertEquals("Nada resuelto aún", emptyList<String>(), decisions)

            tab.dismissActivePrompt()
            assertEquals(listOf("a=false"), decisions)
            assertSame("Pasa la segunda", second, tab.activePrompt)

            tab.close()
            assertEquals("Cerrar la pestaña resuelve las que esperaban", listOf("a=false", "b=false", "script=true"), decisions)
            assertNull(tab.activePrompt)
        }
    }
}
