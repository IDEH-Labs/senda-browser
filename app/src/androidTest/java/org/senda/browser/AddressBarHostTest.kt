package org.senda.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.senda.browser.ui.components.fitHostFromEnd

/** The address bar domain is trimmed from the left: you always see how it ends. */
@RunWith(AndroidJUnit4::class)
class AddressBarHostTest {

    // Each character is 10 px wide
    private fun fit(host: String, maxPx: Int) = fitHostFromEnd(host, maxPx) { it.length * 10 }

    @Test
    fun wholeHostWhenItFits() = assertEquals("es.wikipedia.org", fit("es.wikipedia.org", 160))

    @Test
    fun dropsSubdomainsFirst() = assertEquals("…wikipedia.org", fit("es.wikipedia.org", 150))

    @Test
    fun lookalikeShowsTheRealEnding() {
        // Before it showed "paypal.com.ev…"; now it shows the end, which is the real owner of the site
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
