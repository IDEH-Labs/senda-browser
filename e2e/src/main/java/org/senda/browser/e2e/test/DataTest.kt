package org.senda.browser.e2e.test

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** E. Data that must survive (priority 2). */
@RunWith(AndroidJUnit4::class)
class DataTest : E2eTest() {

    // Two made-up bookmarks, as if the person had saved them; the history comes from visiting the pages
    override fun startPrefs(): Map<String, Any> = mapOf(
        "user_bookmarks" to
            "[{\"id\":\"e2e1\",\"title\":\"e2e-bookmark-one\",\"url\":\"${LocalWeb.url("/a.html")}\",\"timestamp\":1}," +
            "{\"id\":\"e2e2\",\"title\":\"e2e-bookmark-two\",\"url\":\"${LocalWeb.url("/b.html")}\",\"timestamp\":2}]"
    )

    private fun listShows(menuEntry: String, vararg texts: String): List<String> {
        E2e.openMenu()
        E2e.tapTextInList(menuEntry)
        val missing = texts.filterNot { E2e.has(By.textContains(it), 5000) }
        E2e.device.pressBack()
        Thread.sleep(500)
        return missing
    }

    private fun assertDataThere(step: String) {
        val bookmarks = listShows(Es.bookmarks, "e2e-bookmark-one", "e2e-bookmark-two")
        assertTrue("Bookmarks missing $step: $bookmarks", bookmarks.isEmpty())
        val history = listShows(Es.history, "e2e-a", "e2e-b")
        assertTrue("History missing $step: $history", history.isEmpty())
    }

    /** E1 — bookmarks and history survive a restart and an update (the same e2e build installed over itself). */
    @Test
    fun e1_bookmarksAndHistorySurvive() {
        E2e.openLink(LocalWeb.url("/a.html"))
        assertTrue(E2e.pageShows("e2e-a"))
        E2e.openLink(LocalWeb.url("/b.html"))
        assertTrue(E2e.pageShows("e2e-b"))
        assertDataThere("before restarting")

        E2e.backgroundAndKill()
        E2e.launch()
        assertDataThere("after a restart")

        E2e.backgroundAndKill()
        E2e.updateInPlace()
        E2e.launch()
        assertDataThere("after an update")
    }
}
