package org.senda.browser.e2e.test

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A. Tabs and restarts (priority 1). */
@RunWith(AndroidJUnit4::class)
class TabsTest : E2eTest() {

    private fun shown(path: String) = LocalWeb.url(path).removePrefix("http://")

    /** A1 — guards 5c1b1e5 and 2de2a69. */
    @Test
    fun a1_twoTabsSurviveTwoRestarts() {
        E2e.openLink(LocalWeb.url("/a.html"))
        assertTrue(E2e.pageShows("e2e-a"))
        E2e.openLink(LocalWeb.url("/b.html"))
        assertTrue(E2e.pageShows("e2e-b"))
        val expected = listOf(shown("/a.html"), shown("/b.html"))
        E2e.assertSameAddresses(expected, E2e.normalTabAddresses(), "before restarting")

        var lastPid = E2e.pid()
        repeat(2) { round ->
            E2e.backgroundAndKill()
            E2e.launch()
            val pid = E2e.pid()
            assertNotEquals("Restart ${round + 1} did not start a new process", lastPid, pid)
            lastPid = pid
            E2e.assertSameAddresses(expected, E2e.normalTabAddresses(), "after restart ${round + 1}")
        }
    }

    /**
     * A2 — guards 2de2a69. A restored tab whose page is still loading shows its address within a second, and is
     * still there if the app is stopped one second later.
     */
    @Test
    fun a2_restoredLoadingTabKeepsItsAddress() {
        E2e.launch()
        E2e.startupResume()
        E2e.openLink(LocalWeb.url("/slow.html"))
        E2e.waitForAddress(shown("/slow.html"), "the slow tab, first time")
        E2e.backgroundAndKill()

        // Counted from the moment Senda's window is in front, which is what a person perceives: the cold start of
        // the engine before that is measured by I1, not here
        E2e.launch()
        val opened = System.currentTimeMillis()
        val shownAfter = E2e.msUntilAddress(shown("/slow.html"), opened)
        assertTrue(
            "The restored slow tab showed its address after ${shownAfter ?: "more than 20000"} ms (limit 1000). Screen: ${E2e.screenTexts()}",
            shownAfter != null && shownAfter <= 1000
        )
        Thread.sleep((1000 - shownAfter!!).coerceAtLeast(0))
        E2e.backgroundAndKill()

        E2e.launch()
        assertTrue(
            "The slow tab was lost after stopping the app while it loaded",
            E2e.normalTabAddresses().contains(shown("/slow.html"))
        )
    }

    /** A3 — each startup mode does what its text in Settings says. */
    @Test
    fun a3_startupModes() {
        E2e.openLink(LocalWeb.url("/a.html"))
        assertTrue(E2e.pageShows("e2e-a"))
        E2e.openLink(LocalWeb.url("/b.html"))
        assertTrue(E2e.pageShows("e2e-b"))

        // Clean home page in front, previous tabs kept in the list
        E2e.setStartupMode(Es.startupHome)
        E2e.backgroundAndKill()
        E2e.launch()
        assertTrue("HOME: the page in front is not the home page", E2e.homeInFront())
        E2e.assertSameAddresses(listOf(shown("/a.html"), shown("/b.html")), E2e.normalTabAddresses(), "with HOME")

        // Continue in the last tab
        E2e.openLink(LocalWeb.url("/c.html"))
        assertTrue(E2e.pageShows("e2e-c"))
        E2e.setStartupMode(Es.startupResume)
        E2e.backgroundAndKill()
        E2e.launch()
        assertTrue("RESUME: the last tab is not in front", E2e.pageShows("e2e-c"))

        // Start without the previous tabs
        E2e.setStartupMode(Es.startupClean)
        E2e.backgroundAndKill()
        E2e.launch()
        assertEquals("CLEAN: previous tabs are still there", emptyList<String>(), E2e.normalTabAddresses())
    }

    /** A4 — private tabs are never saved. */
    @Test
    fun a4_privateTabsAreNotSaved() {
        E2e.launch()
        E2e.openPrivateTab(LocalWeb.url("/a.html"))
        assertTrue(E2e.pageShows("e2e-a"))
        assertEquals(1, E2e.privateTabCount())
        E2e.backgroundAndKill()
        E2e.launch()
        assertEquals("A private tab survived a restart", 0, E2e.privateTabCount())
    }

    /**
     * A5 — seen on 2026-10-11 with Tor: a NEW tab opened from another app while its page loads shows its address,
     * not the home page, and survives a stop one second later.
     */
    @Test
    fun a5_newTabFromLinkWhileLoading() {
        E2e.launch()
        // Counted from the moment Android has handed the link to Senda (am start -W returns), as A2 counts from the
        // window in front
        E2e.openLink(LocalWeb.url("/slow.html"))
        val opened = System.currentTimeMillis()
        val shownAfter = E2e.msUntilAddress(shown("/slow.html"), opened)
        assertTrue(
            "The new slow tab showed its address after ${shownAfter ?: "more than 20000"} ms (limit 1000). Screen: ${E2e.screenTexts()}",
            shownAfter != null && shownAfter <= 1000
        )
        Thread.sleep((1000 - shownAfter!!).coerceAtLeast(0))
        E2e.backgroundAndKill()
        E2e.launch()
        assertTrue(
            "The new slow tab was lost after stopping the app while it loaded",
            E2e.normalTabAddresses().contains(shown("/slow.html"))
        )
    }
}
