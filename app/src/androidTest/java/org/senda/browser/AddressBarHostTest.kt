package org.senda.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.senda.browser.ui.components.fitHostFromEnd

/** El dominio de la barra se recorta por la izquierda: siempre se ve cómo termina. */
@RunWith(AndroidJUnit4::class)
class AddressBarHostTest {

    // Cada carácter mide 10 px
    private fun fit(host: String, maxPx: Int) = fitHostFromEnd(host, maxPx) { it.length * 10 }

    @Test
    fun wholeHostWhenItFits() = assertEquals("es.wikipedia.org", fit("es.wikipedia.org", 160))

    @Test
    fun dropsSubdomainsFirst() = assertEquals("…wikipedia.org", fit("es.wikipedia.org", 150))

    @Test
    fun lookalikeShowsTheRealEnding() {
        // Antes se veía «paypal.com.ev…»; ahora el final, que es el dueño real del sitio
        val shown = fit("paypal.com.evil-phish.net", 150)
        assertEquals("…evil-phish.net", shown)
    }

    @Test
    fun veryNarrowKeepsTheEnd() {
        val shown = fit("es.wikipedia.org", 60)
        assertEquals("…a.org", shown)
        assertTrue(shown.length * 10 <= 60)
    }
}
