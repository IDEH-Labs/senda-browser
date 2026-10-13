package org.senda.browser.e2e.test

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** C. The JavaScript setting and GNU LibreJS (priority 1). */
@RunWith(AndroidJUnit4::class)
class JavaScriptTest : E2eTest() {

    private val all3 = listOf("JS-RAN-3", "JS-RAN-3", "JS-RAN-3")
    private val freeOnly = listOf("NOJS", "JS-RAN-3", "NOJS")
    private val none = listOf("NOJS", "NOJS", "NOJS")

    /** Opens nonfree, free and external, each in a new tab, and reads what each one shows. */
    private fun results(): List<String> = listOf("nonfree", "free", "external").map { name ->
        E2e.openLink(LocalWeb.url("/$name.html"))
        E2e.scriptResult(name)
    }

    private fun choose(label: String) {
        E2e.setJavaScriptMode(label)
        E2e.leaveSettings()
    }

    /** C1 — "All JavaScript" (the default). */
    @Test
    fun c1_allJavaScript() {
        E2e.launch()
        assertEquals(all3, results())
    }

    /** C2 — guards b4695a8. "Free only": nonfree and external blocked, free runs. */
    @Test
    fun c2_freeOnly() {
        E2e.launch()
        choose(Es.jsFree)
        assertEquals(freeOnly, results())
    }

    /** C3 — guards b4695a8. "Off": nothing runs, and the menu has no LibreJS entry. */
    @Test
    fun c3_off() {
        E2e.launch()
        choose(Es.jsOff)
        assertEquals(none, results())
        assertFalse("The menu shows LibreJS with JavaScript off", E2e.menuHas("LibreJS"))
    }

    /** C4 — guards b4695a8. Switching between the three without restarting, and after a cold start. */
    @Test
    fun c4_switchingLiveAndAfterColdStart() {
        E2e.launch()
        for ((label, expected) in listOf(Es.jsFree to freeOnly, Es.jsOff to none, Es.jsNormal to all3, Es.jsFree to freeOnly)) {
            choose(label)
            assertEquals("Live, $label", expected, results())
            E2e.backgroundAndKill()
            E2e.launch()
            assertEquals("After a cold start, $label", expected, results())
        }
    }

    /**
     * C4b — found on 2026-10-12: right after a cold start with "free only", is the first nonfree page blocked?
     * Five cold starts from the icon and five straight from a link, each reported, none retried.
     */
    @Test
    fun c4b_freeOnlyRightAfterColdStart() {
        E2e.launch()
        choose(Es.jsFree)
        val fromIcon = (1..5).map {
            E2e.backgroundAndKill()
            E2e.launch()
            E2e.openLink(LocalWeb.url("/nonfree.html"))
            E2e.scriptResult("nonfree")
        }
        val fromLink = (1..5).map {
            E2e.backgroundAndKill()
            E2e.openLink(LocalWeb.url("/nonfree.html"))
            E2e.scriptResult("nonfree")
        }
        assertEquals(
            "nonfree ran its script right after a cold start. From the icon: $fromIcon; from a link: $fromLink",
            List(10) { "NOJS" }, fromIcon + fromLink
        )
    }

    /**
     * C8 — found on 2026-10-12: after a cold start with "free only", the menu offers LibreJS's panel for the page in
     * front (on the home page there is no page for it to report on, so it is not offered there).
     */
    @Test
    fun c8_libreJsMenuAfterColdStart() {
        E2e.launch()
        choose(Es.jsFree)
        E2e.openLink(LocalWeb.url("/nonfree.html"))
        E2e.scriptResult("nonfree")
        assertTrue("Before restarting, the menu does not offer LibreJS", E2e.menuHas("LibreJS"))
        E2e.backgroundAndKill()
        E2e.openLink(LocalWeb.url("/nonfree.html"))
        E2e.scriptResult("nonfree")
        assertTrue("After a cold start, the menu does not offer LibreJS", E2e.menuHas("LibreJS"))
    }

    /** C5 — guards b4695a8. LibreJS's panel shows the page in front; allowing the site runs the script; forgetting blocks it. */
    @Test
    fun c5_libreJsPanel() {
        E2e.launch()
        choose(Es.jsFree)
        E2e.openLink(LocalWeb.url("/nonfree.html"))
        assertEquals("NOJS", E2e.scriptResult("nonfree"))

        E2e.openLibreJsPanel()
        assertTrue("The panel does not show the page in front", E2e.has(By.textContains("127.0.0.1"), 3000))
        E2e.tapText("Whitelist")
        E2e.tapText("Reload")
        assertEquals("After allowing the site", "JS-RAN-3", E2e.scriptResult("nonfree"))

        E2e.openLibreJsPanel()
        E2e.tapText("Forget")
        E2e.tapText("Reload")
        assertEquals("After forgetting the site", "NOJS", E2e.scriptResult("nonfree"))
    }

    /** C6 — guards b4695a8. "Free only" also in private tabs. */
    @Test
    fun c6_freeOnlyInPrivateTab() {
        E2e.launch()
        choose(Es.jsFree)
        E2e.openPrivateTab(LocalWeb.url("/nonfree.html"))
        assertEquals("nonfree, private tab", "NOJS", E2e.scriptResult("nonfree"))
        E2e.typeAddress(LocalWeb.url("/free.html"))
        assertEquals("free, private tab", "JS-RAN-3", E2e.scriptResult("free"))
    }

    /** C7 — guards b4695a8. The Settings summary shows the option just chosen. */
    @Test
    fun c7_settingsSummary() {
        E2e.launch()
        for (label in listOf(Es.jsFree, Es.jsOff, Es.jsNormal)) {
            E2e.setJavaScriptMode(label)
            // Settings is still open: the JavaScript entry's summary must be the label
            var shown = false
            val end = System.currentTimeMillis() + 3000
            while (!shown && System.currentTimeMillis() < end) {
                shown = E2e.find(By.text("JavaScript"), 500)?.parent?.hasObject(By.text(label)) == true
            }
            assertTrue("Summary after choosing $label (waited up to 3 s). Screen: ${E2e.screenTexts()}", shown)
            E2e.leaveSettings()
        }
    }
}
