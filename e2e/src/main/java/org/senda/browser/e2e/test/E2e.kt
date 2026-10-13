package org.senda.browser.e2e.test

import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.util.regex.Pattern

/**
 * Drives the e2e app the way a person would. Every command that names an app takes [APP], and [guard] refuses to go
 * on unless it is the separate test app: these tests never touch the real Senda or its data.
 */
object E2e {
    const val APP = "org.senda.browser.e2e"
    private const val ACTIVITY = "org.senda.browser.MainActivity"
    const val TIMEOUT = 15_000L

    val device: UiDevice by lazy { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()) }

    /** Rule 1 and rule 4 of docs/automated-tests.md, checked before every test. */
    fun guard() {
        check(APP.endsWith(".e2e")) { "Refusing to run: the target is not the e2e app" }
        assertTrue("The e2e app is not installed", sh("pm path $APP").startsWith("package:"))
        val callState = sh("dumpsys telephony.registry").lineSequence()
            .map { it.trim() }.filter { it.startsWith("mCallState=") }.toList()
        assertTrue("A call is in progress: stopping", callState.all { it == "mCallState=0" })
    }

    /** One command, split on spaces (no shell): no quotes, pipes or redirections. */
    fun sh(command: String): String = device.executeShellCommand(command)

    /** A shell script run inside the e2e app's data folder (run-as works because the e2e app is debuggable). */
    fun appScript(script: String): String {
        check(Build.VERSION.SDK_INT >= 31) { "run-as with a script needs Android 12 or later" }
        val fds = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommandRw("run-as $APP sh")
        ParcelFileDescriptor.AutoCloseOutputStream(fds[1]).use { it.write(script.toByteArray()) }
        return ParcelFileDescriptor.AutoCloseInputStream(fds[0]).use { it.readBytes().decodeToString() }
    }

    // ---- The app ----

    fun stop() {
        sh("am force-stop $APP")
        waitFor("the e2e app to stop") { pid() == null }
    }

    /** Main process id, or null when it is not running. */
    fun pid(): String? = sh("pidof $APP").trim().split(' ').firstOrNull { it.isNotBlank() }

    /** Back to a freshly installed e2e app, with [prefs] already saved, as if the person had chosen them. */
    fun reset(prefs: Map<String, Any> = emptyMap()) {
        stop()
        assertTrue("Could not clear the e2e app", sh("pm clear $APP").contains("Success"))
        if (prefs.isNotEmpty()) writePrefs(prefs)
    }

    private fun writePrefs(prefs: Map<String, Any>) {
        val entries = prefs.entries.joinToString("\n") { (key, value) ->
            when (value) {
                is Boolean -> "<boolean name=\"$key\" value=\"$value\" />"
                is Int -> "<int name=\"$key\" value=\"$value\" />"
                else -> "<string name=\"$key\">$value</string>"
            }
        }
        val xml = "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\" ?>\n<map>\n$entries\n</map>\n"
        check(!xml.contains('\'')) { "Settings values must not contain single quotes" }
        // No here-document: the shell cannot create its temporary file inside the app. Private permissions, as the app's own
        appScript("umask 077\nmkdir -p shared_prefs\nprintf '%s' '$xml' > shared_prefs/senda_preferences.xml\n")
        assertTrue("Settings were not written", appScript("cat shared_prefs/senda_preferences.xml").contains("<map>"))
    }

    /** Opens the e2e app from its icon. */
    fun launch() {
        sh("am start -W -n $APP/$ACTIVITY -a android.intent.action.MAIN -c android.intent.category.LAUNCHER")
        waitForApp()
    }

    /** Opens [url] in the e2e app as a link from another app would. */
    fun openLink(url: String) {
        sh("am start -W -n $APP/$ACTIVITY -a android.intent.action.VIEW -d $url")
        waitForApp()
    }

    fun waitForApp() {
        assertTrue("The e2e app did not come to the front", device.wait(Until.hasObject(By.pkg(APP).depth(0)), TIMEOUT))
    }

    /** Sends the e2e app to the background and stops it, as Android does with an app in the background. */
    fun backgroundAndKill() {
        device.pressHome()
        Thread.sleep(1500)
        stop()
    }

    // ---- The screen ----

    fun find(selector: BySelector, timeout: Long = TIMEOUT): UiObject2? = device.wait(Until.findObject(selector), timeout)

    fun has(selector: BySelector, timeout: Long = TIMEOUT) = find(selector, timeout) != null

    fun need(selector: BySelector, what: String, timeout: Long = TIMEOUT): UiObject2 =
        find(selector, timeout) ?: fail("Not on screen: $what. Screen: ${screenTexts()}").let { error("unreachable") }

    fun tapText(text: String) = need(By.text(text), "\"$text\"").click()

    fun tapDesc(desc: String) = need(By.desc(desc), "button \"$desc\"").click()

    /** Taps a text that may be further down a list, scrolling to it first. */
    fun tapTextInList(text: String) {
        repeat(25) {
            find(By.pkg(APP).text(text), 700)?.let { it.click(); return }
            val list = find(By.pkg(APP).scrollable(true), 1000) ?: fail("No list to scroll looking for \"$text\"")
            (list as UiObject2).scroll(Direction.DOWN, 0.5f)
        }
        fail("\"$text\" not found after scrolling. Screen: ${screenTexts()}")
    }

    /** Texts and descriptions the e2e app shows right now: the evidence a failure message carries. */
    fun screenTexts(): String = runCatching {
        device.findObjects(By.pkg(APP)).mapNotNull { o ->
            listOfNotNull(o.text, o.contentDescription).filter { it.isNotBlank() }.joinToString("/").ifBlank { null }
        }.distinct().joinToString(" | ").take(1500)
    }.getOrDefault("?")

    fun waitFor(what: String, timeoutMs: Long = TIMEOUT, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(250)
        }
        fail("Timed out waiting for $what")
    }

    /** Text of the page in front, which GeckoView offers to accessibility services such as UI Automator. */
    fun pageShows(text: String, timeout: Long = TIMEOUT) = has(By.text(text), timeout)

    /**
     * Result of a JavaScript fixture: "JS-RAN-3" once the script ran, "NOJS" if it still has not after
     * [settleMs] (a blocked script never changes the page, so its result can only be read after waiting).
     */
    fun scriptResult(title: String, settleMs: Long = 4000): String {
        need(By.text(title), "the page \"$title\"")
        val end = System.currentTimeMillis() + settleMs
        while (System.currentTimeMillis() < end) {
            if (has(By.text("JS-RAN-3"), 250)) return "JS-RAN-3"
        }
        return if (has(By.text("NOJS"), 1000)) "NOJS" else "neither JS-RAN-3 nor NOJS"
    }

    fun openMenu() = tapDesc(Es.mainMenu)

    fun openSettings() {
        openMenu()
        tapText(Es.settings)
        need(By.desc(Es.searchSettings), "Settings", TIMEOUT)
    }

    /** Opens the tab list from the counter button (it has no description, only the number of tabs). */
    fun openTabs() {
        val counter = device.findObjects(By.pkg(APP).text(Pattern.compile("\\d{1,2}|∞")).clickable(false))
            .plus(device.findObjects(By.pkg(APP).text(Pattern.compile("\\d{1,2}|∞"))))
            .firstOrNull() ?: fail("No tab counter on screen").let { error("unreachable") }
        // The number is inside the button: tap the button around it
        (counter.parent?.takeIf { it.isClickable } ?: counter.parent?.parent?.takeIf { it.isClickable } ?: counter).click()
        need(By.textStartsWith(Es.tabsNormal + " ("), "the tab list")
    }

    /** Addresses of the normal tabs, as the tab list shows them. */
    fun normalTabAddresses(): List<String> {
        openTabs()
        tapText(device.findObject(By.textStartsWith(Es.tabsNormal + " (")).text)
        Thread.sleep(500)
        val shown = device.findObjects(By.pkg(APP).textContains(LocalWeb.host)).map { it.text }
        device.pressBack()
        return shown
    }

    fun privateTabCount(): Int {
        openTabs()
        val label = need(By.textStartsWith(Es.tabsPrivate + " ("), "the private tabs section").text
        device.pressBack()
        return label.substringAfter('(').substringBefore(')').toInt()
    }

    /** The address bar shows the host of the page in front (not the home page). */
    /** Milliseconds until the address bar shows the host of [shown], measured from [since]; null after [limitMs]. */
    fun msUntilAddress(shown: String, since: Long, limitMs: Long = 20_000): Long? {
        val hostShown = shown.substringBefore(':').substringBefore('/')
        while (System.currentTimeMillis() - since < limitMs) {
            if (device.hasObject(By.pkg(APP).text(hostShown))) return System.currentTimeMillis() - since
            Thread.sleep(50)
        }
        return null
    }

    fun waitForAddress(shown: String, what: String, timeoutMs: Long = TIMEOUT) {
        val hostShown = shown.substringBefore(':').substringBefore('/')
        assertTrue(
            "The address bar does not show $what within $timeoutMs ms. Screen: ${screenTexts()}",
            has(By.pkg(APP).textContains(hostShown), timeoutMs)
        )
    }

    /** The home page is in front: no test page and no test address on screen. */
    fun homeInFront(): Boolean {
        Thread.sleep(1500)
        return !has(By.pkg(APP).textContains(LocalWeb.host), 500) && !has(By.textStartsWith("e2e-"), 500)
    }

    /** Settings > Tabs > startup option [label]. */
    fun setStartupMode(label: String) {
        openSettings()
        tapTextInList(Es.tabsTitle)
        tapText(label)
        device.pressBack() // the dialog
        device.pressBack() // Settings
        Thread.sleep(500)
    }

    fun startupResume() = setStartupMode(Es.startupResume)

    /** Types [url] in the address bar of the tab in front and goes there. */
    fun typeAddress(url: String) {
        val editing = By.pkg(APP).clazz("android.widget.EditText").focused(true)
        val field = find(editing, 1000)
            ?: run {
                // Not editing yet: tap the address bar, which shows the host or the search hint
                val bar = find(By.pkg(APP).text(Es.addressHint), 2000)
                    ?: need(By.pkg(APP).text(LocalWeb.host), "the address bar")
                bar.click()
                need(editing, "the address field")
            }
        field.text = url
        device.pressEnter()
    }

    /** Opens a private tab from the tab list and loads [url] in it. */
    fun openPrivateTab(url: String) {
        openTabs()
        tapText(device.findObject(By.textStartsWith(Es.tabsPrivate + " (")).text)
        tapDesc(Es.newPrivateTab)
        // The new tab is created with the tab list still open: back to the tab
        if (has(By.textStartsWith(Es.tabsPrivate + " ("), 1000)) device.pressBack()
        typeAddress(url)
    }

    /** Settings > JavaScript > [label]; returns with Settings still open so its summary can be read. */
    fun setJavaScriptMode(label: String) {
        openSettings()
        tapTextInList("JavaScript")
        tapText(label)
        // The first time "free only" is chosen, LibreJS is installed: give it a moment
        Thread.sleep(if (label == Es.jsFree) 3000 else 500)
        tapText(Es.ok)
    }

    fun leaveSettings() {
        device.pressBack()
        Thread.sleep(500)
    }

    /** Menu > Tor and proxy, the same dialog as in Settings. */
    fun openProxyDialog() {
        openMenu()
        tapText(Es.menuTorProxy)
        need(By.text(Es.torTitle), "the proxy dialog")
    }

    fun chooseDirect() {
        openProxyDialog()
        tapText(Es.proxyOff)
        tapText(Es.save)
    }

    fun chooseHttpProxy(host: String, port: Int) {
        openProxyDialog()
        tapText(Es.proxyHttp)
        fill(Es.proxyHost, host)
        fill(Es.proxyPort, port.toString())
        tapText(Es.save)
    }

    /** Text field whose label is [label]. */
    private fun fill(label: String, value: String) {
        val field = device.findObjects(By.clazz("android.widget.EditText"))
            .firstOrNull { it.hasObject(By.text(label)) }
            ?: fail("No field \"$label\"").let { error("unreachable") }
        field.text = value
    }

    /** The panel of the uBlock Origin button, from Settings > uBlock Origin > shield, for the tab in front. */
    fun openUBlockPanel() {
        openSettings()
        tapTextInList("uBlock Origin")
        tapText(Es.ublockShield)
    }

    /** LibreJS's panel from the menu (only there while JavaScript is set to "free only"). */
    fun openLibreJsPanel() {
        openMenu()
        tapTextInList("LibreJS")
        need(By.text("Whitelist"), "the LibreJS panel")
    }

    fun menuHas(text: String): Boolean {
        openMenu()
        val found = has(By.text(text), 1500) || runCatching {
            UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView(text)
        }.getOrDefault(false)
        device.pressBack()
        return found
    }

    fun assertSameAddresses(expected: List<String>, actual: List<String>, step: String) =
        assertEquals("Tabs $step", expected.sorted(), actual.sorted())
}
