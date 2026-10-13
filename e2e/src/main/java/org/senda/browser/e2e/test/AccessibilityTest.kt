package org.senda.browser.e2e.test

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** H3 (part): what accessibility services are told matches what is on screen. */
@RunWith(AndroidJUnit4::class)
class AccessibilityTest : E2eTest() {

    /**
     * Found on 2026-10-12: GeckoView placed the page's elements for accessibility as if the page started at the top
     * of the screen (192 px too high below Senda's bar), so touch exploration and taps by position hit the wrong
     * element. A button tapped where accessibility says it is must be pressed.
     */
    @Test
    fun h3b_pageElementWhereAccessibilitySaysItIs() {
        E2e.openLink(LocalWeb.url("/button.html"))
        val button = E2e.need(By.text("PRESS-ME"), "the page's button")
        val bounds = button.visibleBounds.toShortString()
        button.click()
        assertTrue(
            "Tapping the button where accessibility places it ($bounds) did not press it. Screen: ${E2e.screenTexts()}",
            E2e.has(By.text("PRESSED"), 3000)
        )
    }
}
