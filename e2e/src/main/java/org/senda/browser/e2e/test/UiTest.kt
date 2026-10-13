package org.senda.browser.e2e.test

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** H. User interface (priority 1): what can be checked without a person looking. */
@RunWith(AndroidJUnit4::class)
class UiTest : E2eTest() {

    private val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density

    private fun appInFront() = E2e.device.currentPackageName == E2e.APP

    /** Back until the e2e app is in front again (an entry may open one of Android's own screens). */
    private fun backToApp(what: String) {
        repeat(4) {
            if (appInFront()) return
            E2e.device.pressBack()
            Thread.sleep(700)
        }
        if (!appInFront()) fail("After \"$what\" the e2e app did not come back to the front")
    }

    /** Text or description of a node or its children; "" if it has none, null if it left the screen meanwhile. */
    private fun label(node: UiObject2): String? = runCatching {
        (listOf(node) + node.findObjects(By.textContains("")) + node.findObjects(By.descContains("")))
            .mapNotNull { it.text?.takeIf(String::isNotBlank) ?: it.contentDescription?.takeIf(String::isNotBlank) }
            .firstOrNull() ?: ""
    }.getOrNull()

    /** H1 — every Settings entry opens and closes with Back without the app closing. */
    @Test
    fun h1_everySettingsEntryOpensAndCloses() {
        E2e.launch()
        val pid = E2e.pid()
        E2e.openSettings()
        val visited = mutableListOf<String>()
        val problems = mutableListOf<String>()
        var sameScreens = 0
        while (sameScreens < 2) {
            val rows = E2e.device.findObjects(By.pkg(E2e.APP).clickable(true))
                .mapNotNull { label(it) }
                .filter { it.isNotBlank() && it !in visited && it != Es.back && it != Es.searchSettings }
            if (rows.isEmpty()) {
                sameScreens++
            } else {
                sameScreens = 0
                for (row in rows) {
                    visited += row
                    val node = E2e.device.findObjects(By.pkg(E2e.APP).clickable(true)).firstOrNull { label(it) == row } ?: continue
                    if (runCatching { node.click() }.isFailure) continue
                    Thread.sleep(1200)
                    backToApp(row)
                    if (!E2e.has(By.desc(Es.searchSettings), 1000)) {
                        E2e.device.pressBack()
                        Thread.sleep(700)
                        backToApp(row)
                    }
                    if (!E2e.has(By.desc(Es.searchSettings), 2000)) {
                        problems += "\"$row\": Back did not return to Settings"
                        E2e.openSettings()
                    }
                    if (E2e.pid() != pid) {
                        problems += "\"$row\": the app process changed (closed or restarted)"
                        break
                    }
                }
            }
            E2e.find(By.pkg(E2e.APP).scrollable(true), 2000)?.scroll(Direction.DOWN, 0.7f)
            Thread.sleep(400)
        }
        assertTrue("Problems: $problems. Entries visited (${visited.size}): $visited", problems.isEmpty() && visited.size > 10)
    }

    /**
     * Clickable elements wholly on screen now: [name or "(no name)"] -> bounds. Elements cut by the edge of a list
     * are left for the next scroll: their visible part is not their size.
     */
    private fun clickables(): List<Pair<String, Rect>> {
        val lists = E2e.device.findObjects(By.pkg(E2e.APP).scrollable(true)).mapNotNull { runCatching { it.visibleBounds }.getOrNull() }
        return E2e.device.findObjects(By.pkg(E2e.APP).clickable(true)).mapNotNull { node ->
            val bounds = runCatching { node.visibleBounds }.getOrNull() ?: return@mapNotNull null
            val name = label(node) ?: return@mapNotNull null
            val cut = lists.any { l -> Rect.intersects(l, bounds) && (bounds.top <= l.top + 1 || bounds.bottom >= l.bottom - 1) }
            if (cut) null else name.ifBlank { "(no name)" } to bounds
        }
    }

    /** The screens the checks of H3 and H4 go through: home, menu, Settings (each scroll) and the tab list. */
    private fun visitMainScreens(check: (screen: String) -> Unit) {
        E2e.openLink(LocalWeb.url("/a.html"))
        E2e.pageShows("e2e-a")
        check("page")
        E2e.openMenu()
        check("menu")
        E2e.device.pressBack()
        E2e.openTabs()
        check("tab list")
        E2e.device.pressBack()
        E2e.openSettings()
        repeat(8) { i ->
            check("Settings ${i + 1}")
            E2e.find(By.pkg(E2e.APP).scrollable(true), 1000)?.scroll(Direction.DOWN, 0.8f)
        }
    }

    /** H3 — every clickable element has a text or a description for screen readers. */
    @Test
    fun h3_everyClickableHasAName() {
        val unnamed = sortedSetOf<String>()
        visitMainScreens { screen ->
            clickables().filter { it.first == "(no name)" }.forEach { unnamed += "$screen at ${it.second.toShortString()}" }
        }
        assertTrue("Clickable elements with no text or description (${unnamed.size}): $unnamed", unnamed.isEmpty())
    }

    /** H4 — touch targets of at least 48 dp; smaller ones are listed with their size. */
    @Test
    fun h4_touchTargetsAtLeast48dp() {
        val min = (48 * density).toInt()
        val small = sortedSetOf<String>()
        visitMainScreens { screen ->
            clickables().filter { (_, r) -> r.width() > 0 && r.height() > 0 && (r.width() < min || r.height() < min) }
                .forEach { (name, r) ->
                    small += "$screen: \"$name\" ${(r.width() / density).toInt()}x${(r.height() / density).toInt()} dp"
                }
        }
        assertTrue("Touch targets under 48 dp (${small.size}): $small", small.isEmpty())
    }

    /**
     * H6 — rotating keeps the tab, its address and an open dialog, without the app closing. The phone's rotation
     * settings are written down before and put back exactly as they were.
     */
    @Test
    fun h6_rotation() {
        val auto = E2e.sh("settings get system accelerometer_rotation").trim()
        val user = E2e.sh("settings get system user_rotation").trim()
        try {
            E2e.openLink(LocalWeb.url("/a.html"))
            assertTrue(E2e.pageShows("e2e-a"))
            val pid = E2e.pid()
            E2e.device.setOrientationLeft()
            Thread.sleep(1500)
            assertTrue("After rotating, the page is gone", E2e.pageShows("e2e-a", 5000))
            E2e.waitForAddress(LocalWeb.url("/a.html").removePrefix("http://"), "the page after rotating")
            E2e.device.setOrientationNatural()
            Thread.sleep(1500)

            E2e.setJavaScriptMode(Es.jsNormal) // leaves the JavaScript dialog closed; open it again and rotate
            E2e.tapTextInList("JavaScript")
            E2e.need(By.text(Es.jsFree), "the JavaScript dialog")
            E2e.device.setOrientationLeft()
            Thread.sleep(1500)
            assertTrue("After rotating, the open dialog is gone", E2e.has(By.text(Es.jsFree), 5000))
            E2e.device.setOrientationNatural()
            Thread.sleep(1000)
            assertEquals("The app process changed while rotating", pid, E2e.pid())
        } finally {
            E2e.device.unfreezeRotation()
            E2e.sh("settings put system accelerometer_rotation $auto")
            if (user != "null") E2e.sh("settings put system user_rotation $user")
        }
    }

    /** H7 — Back, the menu and the tab list work with the bar at the top, at the bottom and floating. */
    @Test
    fun h7_toolbarPositions() {
        val problems = mutableListOf<String>()
        for (position in listOf("TOP", "BOTTOM", "FLOATING")) {
            E2e.reset(testProxyPrefs() + mapOf("toolbar_position" to position))
            E2e.openLink(LocalWeb.url("/a.html"))
            E2e.pageShows("e2e-a")
            E2e.typeAddress(LocalWeb.url("/b.html"))
            if (!E2e.pageShows("e2e-b")) { problems += "$position: typing an address"; continue }
            E2e.device.pressBack()
            if (!E2e.pageShows("e2e-a", 5000)) problems += "$position: Back"
            val before = LocalWeb.directRequests.get()
            runCatching { E2e.openMenu(); E2e.tapDesc(Es.reload) }.onFailure { problems += "$position: menu or reload (${it.message})" }
            Thread.sleep(2000)
            if (LocalWeb.directRequests.get() <= before) problems += "$position: reload did not load the page again"
            runCatching { E2e.openTabs(); E2e.device.pressBack() }.onFailure { problems += "$position: tab list (${it.message})" }
        }
        assertTrue("Problems: $problems", problems.isEmpty())
    }
}

/**
 * J1 — stress (priority 2). Instead of Android's monkey, whose random touches can reach the status bar and the phone's
 * quick settings, random taps only on elements of the e2e app, with a fixed seed. Entries that would leave the app or
 * change the network (Settings, proxy, share, print, cast, home screen shortcut) are never tapped, and the run stops if
 * anything else comes to the front. The test proxy is down, so nothing reaches the internet.
 */
@RunWith(AndroidJUnit4::class)
class StressTest : E2eTest() {

    override fun startPrefs(): Map<String, Any> = testProxyPrefs(port = LocalWeb.deadPort())

    private val neverTap = listOf(
        Es.settings, Es.menuTorProxy, "Compartir", "Imprimir", "Transmitir a la TV", "Añadir a pantalla de inicio",
        "Agregar a pantalla de inicio", "Descargas", "Copiar enlace"
    )

    @Test
    fun j1_randomTapsInsideTheApp() {
        val launchers = E2e.sh("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
            .lines().map { it.trim() }.filter { it.contains('/') }.map { it.substringBefore('/') }.toSet()
        val seed = 20261012L
        val steps = 400
        val random = java.util.Random(seed)
        E2e.openLink(LocalWeb.url("/a.html"))
        E2e.pageShows("e2e-a")
        val pid = E2e.pid()
        val exitsBefore = abnormalExits()
        val log = mutableListOf<String>()
        for (step in 1..steps) {
            if (E2e.device.currentPackageName != E2e.APP) {
                if (E2e.device.currentPackageName in launchers) {
                    // Back on the first page leaves Senda for the home screen, as it should: open it again
                    log += "$step reopen"
                    E2e.launch()
                } else {
                    E2e.device.pressBack()
                    Thread.sleep(800)
                }
                if (E2e.device.currentPackageName != E2e.APP) {
                    fail("Step $step (seed $seed): ${E2e.device.currentPackageName} is in front; stopped. Last: ${log.takeLast(5)}")
                }
            }
            val targets = E2e.device.findObjects(By.pkg(E2e.APP).clickable(true)).filter { node ->
                val name = runCatching {
                    (listOf(node) + node.findObjects(By.textContains("")) + node.findObjects(By.descContains("")))
                        .mapNotNull { it.text ?: it.contentDescription }.joinToString(" ")
                }.getOrNull() ?: return@filter false
                neverTap.none { name.contains(it) }
            }
            if (targets.isEmpty() || random.nextInt(10) == 0) {
                log += "$step back"
                E2e.device.pressBack()
            } else {
                val node = targets[random.nextInt(targets.size)]
                log += "$step tap ${runCatching { node.visibleBounds.toShortString() }.getOrDefault("?")}"
                runCatching { node.click() }
            }
            Thread.sleep(300)
            if (E2e.pid() == null) {
                // Back can close the app normally: open it again and go on, unless it crashed (checked at the end)
                E2e.launch()
            }
        }
        val crashes = abnormalExits() - exitsBefore
        assertTrue("Crashes or freezes during $steps steps (seed $seed): $crashes. pid at start $pid", crashes.isEmpty())
    }

    /** Crashes and freezes Android recorded for the e2e app (its own record, not the system log): "time pid reason". */
    private fun abnormalExits(): Set<String> {
        val lines = E2e.sh("dumpsys activity exit-info ${E2e.APP}").lines().map { it.trim() }
        return lines.indices.filter { lines[it].startsWith("process=") && (lines[it].contains("CRASH") || lines[it].contains("ANR")) }
            .map { i -> lines.getOrNull(i - 1).orEmpty().substringBefore(" realUid") + " " + lines[i].substringBefore(" status=") }
            .toSet()
    }
}
