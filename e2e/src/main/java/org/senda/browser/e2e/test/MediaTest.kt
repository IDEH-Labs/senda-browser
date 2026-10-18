package org.senda.browser.e2e.test

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * L. Videos detected for the TV. Opening "Cast to the TV" looks for TVs on the Wi-Fi (an SSDP search that stays on
 * the local network); no TV is needed: the dialog says whether the page has a video to send.
 */
@RunWith(AndroidJUnit4::class)
class MediaTest : E2eTest() {

    private fun waitForVideo(path: String) =
        E2e.waitFor("the page to request $path") { (LocalWeb.videoRequests[path]?.get() ?: 0) > 0 }

    /** Whether "Cast to the TV" offers a video for the tab in front. */
    private fun castOffersVideo(): Boolean {
        if (E2e.has(By.pkg(E2e.APP).desc(Es.cast), 1500)) E2e.tapDesc(Es.cast)
        else {
            E2e.openMenu()
            E2e.tapTextInList(Es.cast)
        }
        E2e.need(By.text(Es.cast), "the cast dialog")
        Thread.sleep(1000)
        val offers = !E2e.has(By.text(Es.castNoVideo), 500)
        E2e.tapText(Es.close)
        Thread.sleep(500)
        return offers
    }

    private fun closeAllPrivateTabs() {
        E2e.openTabs()
        E2e.tapText(E2e.device.findObject(By.textStartsWith(Es.tabsPrivate + " (")).text)
        E2e.tapDesc(Es.tabsMoreOptions)
        E2e.tapText(Es.closeAllPrivate)
        // The confirmation dialog repeats the text on its button
        E2e.waitFor("the confirmation") { E2e.device.findObjects(By.text(Es.closeAllPrivate)).isNotEmpty() }
        E2e.device.findObjects(By.text(Es.closeAllPrivate)).last().click()
        Thread.sleep(500)
        if (E2e.has(By.textStartsWith(Es.tabsPrivate + " ("), 1000)) E2e.device.pressBack()
    }

    /** L1 — a private tab's video is forgotten when the last private tab closes; a normal tab's video stays. */
    @Test
    fun l1_privateVideoForgottenWhenPrivateTabsClose() {
        // On a freshly cleared app the built-in extensions install after the first page starts: a video that page
        // loads goes unseen (measured 2026-10-17). A first page and a moment give them time
        E2e.openLink(LocalWeb.url("/a.html"))
        assertTrue(E2e.pageShows("e2e-a"))
        Thread.sleep(3000)

        E2e.openLink(LocalWeb.url("/vnormal.html"))
        assertTrue(E2e.pageShows("e2e-vnormal"))
        waitForVideo("/v2.mp4")
        assertTrue("The normal tab's video was not detected. Screen: ${E2e.screenTexts()}", castOffersVideo())

        E2e.openPrivateTab(LocalWeb.url("/vpriv.html"))
        assertTrue(E2e.pageShows("e2e-vpriv"))
        waitForVideo("/v1.mp4")
        assertTrue("The private tab's video was not detected. Screen: ${E2e.screenTexts()}", castOffersVideo())

        LocalWeb.privateVideo = false
        closeAllPrivateTabs()
        assertEquals("Private tabs left", 0, E2e.privateTabCount())

        assertTrue(E2e.pageShows("e2e-vnormal"))
        assertTrue("The normal tab's video was forgotten too", castOffersVideo())

        // The same page as the private tab, now without its video: only a remembered private video could be offered
        val before = LocalWeb.videoRequests["/v1.mp4"]?.get() ?: 0
        E2e.openLink(LocalWeb.url("/vpriv.html"))
        assertTrue(E2e.pageShows("e2e-vpriv"))
        Thread.sleep(2000)
        assertEquals("The page requested its old video again", before, LocalWeb.videoRequests["/v1.mp4"]?.get() ?: 0)
        assertFalse("The private tab's video is still offered after closing every private tab", castOffersVideo())
    }
}
