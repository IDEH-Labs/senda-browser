package org.senda.browser.e2e.test

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** G. Engine security (priority 1). G1 is a script on the computer: scripts/check_geckoview.py. */
@RunWith(AndroidJUnit4::class)
class EngineTest : E2eTest() {

    /**
     * G2 — no DRM: a page asking for Widevine, as a streaming site does, is refused by Gecko itself (EME off). Before,
     * it was refused only because Senda never answered Gecko's request for approval, which a Gecko update could change.
     */
    @Test
    fun g2_noDrm() {
        E2e.openLink(LocalWeb.url("/drm.html"))
        assertTrue(E2e.pageShows("drm"))
        val blocked = E2e.has(By.text("DRM-BLOCKED EME has been preffed off"), 8000)
        assertTrue("The page was not refused with EME off. Screen: ${E2e.screenTexts()}", blocked)
    }
}
