package org.senda.browser.e2e.test

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * H2 and H5 (priority 1): screens in every language, theme and with large system text, saved for a person to review.
 * The automatic part only checks that nothing closes. Screenshots show the e2e app only: the status bar (the person's
 * notifications) and the navigation bar are cut off before saving.
 */
@RunWith(AndroidJUnit4::class)
class ScreensTest : E2eTest() {

    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screens").apply { mkdirs() }

    private fun capture(name: String) {
        Thread.sleep(800)
        val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val res = InstrumentationRegistry.getInstrumentation().targetContext.resources
        fun dimen(id: String) = res.getIdentifier(id, "dimen", "android").takeIf { it != 0 }?.let { res.getDimensionPixelSize(it) } ?: 0
        val top = dimen("status_bar_height")
        val bottom = dimen("navigation_bar_height")
        val cropped = Bitmap.createBitmap(shot, 0, top, shot.width, shot.height - top - bottom)
        File(dir, "$name.png").outputStream().use { cropped.compress(Bitmap.CompressFormat.PNG, 90, it) }
    }

    /** Main screens: home, the menu, Settings and the tab list. [menu] and [settings] are the texts in that language. */
    private fun walk(prefix: String, menu: String, settings: String) {
        E2e.launch()
        val pid = E2e.pid()
        capture("$prefix-1-home")
        E2e.openLink(LocalWeb.url("/a.html"))
        E2e.pageShows("e2e-a")
        E2e.tapDesc(menu)
        capture("$prefix-2-menu")
        E2e.tapTextInList(settings)
        capture("$prefix-3-settings")
        E2e.device.pressBack()
        Thread.sleep(500)
        E2e.tapTabCounter()
        Thread.sleep(1000)
        capture("$prefix-4-tabs")
        E2e.device.pressBack()
        assertEquals("The app closed or restarted ($prefix)", pid, E2e.pid())
    }

    /** H2 — the 8 languages. */
    @Test
    fun h2_languages() {
        val languages = listOf(
            Triple("DE", "Hauptmenü", "Einstellungen"), Triple("EN", "Main menu", "Settings"),
            Triple("ES", "Menú principal", "Ajustes"), Triple("FR", "Menu principal", "Paramètres"),
            Triple("PT", "Menu principal", "Configurações"), Triple("IT", "Menu principale", "Impostazioni"),
            Triple("JA", "メインメニュー", "設定"), Triple("ZH", "主菜单", "设置")
        )
        for ((code, menu, settings) in languages) {
            E2e.reset(testProxyPrefs() + mapOf("app_language" to code))
            walk("h2-$code", menu, settings)
        }
        assertTrue(dir.listFiles()!!.count { it.name.startsWith("h2-") } >= 32)
    }

    /**
     * H5 — light and dark theme, and the system text at 130 %. The phone's text size is written down before and put
     * back exactly as it was.
     */
    @Test
    fun h5_themesAndLargeText() {
        for (theme in listOf("LIGHT", "DARK")) {
            E2e.reset(testProxyPrefs() + mapOf("theme_mode" to theme))
            walk("h5-$theme", Es.mainMenu, Es.settings)
        }
        val scale = E2e.sh("settings get system font_scale").trim()
        try {
            E2e.sh("settings put system font_scale 1.3")
            E2e.reset(testProxyPrefs())
            walk("h5-text130", Es.mainMenu, Es.settings)
        } finally {
            if (scale == "null" || scale.isBlank()) E2e.sh("settings delete system font_scale")
            else E2e.sh("settings put system font_scale $scale")
        }
        assertEquals("The phone's text size was not put back", scale, E2e.sh("settings get system font_scale").trim())
    }

    /**
     * Every Settings entry and the main menu with its bookmarks submenu, for a person to review that the whole interface
     * looks finished. Only the e2e app is captured: an entry that opens one of Android's own screens is not.
     */
    @Test
    fun h9_everyDialog() {
        E2e.reset(testProxyPrefs() + mapOf("user_bookmarks" to
            "[{\"id\":\"e2e1\",\"title\":\"e2e-bookmark-one\",\"url\":\"${LocalWeb.url("/a.html")}\",\"timestamp\":1}]"))
        E2e.openLink(LocalWeb.url("/a.html"))
        E2e.pageShows("e2e-a")
        E2e.openMenu()
        capture("h9-00-menu")
        E2e.tapText(Es.bookmarks)
        capture("h9-01-menu-bookmarks")
        E2e.device.pressBack()
        Thread.sleep(500)
        E2e.openSettings()
        val seen = mutableListOf<String>()
        var index = 2
        var quiet = 0
        while (quiet < 2) {
            val rows = E2e.device.findObjects(androidx.test.uiautomator.By.pkg(E2e.APP).clickable(true)).mapNotNull { n ->
                runCatching { n.findObjects(androidx.test.uiautomator.By.textContains("")).firstOrNull { !it.text.isNullOrBlank() }?.text }.getOrNull()
            }.filter { it !in seen }
            if (rows.isEmpty()) quiet++ else quiet = 0
            for (row in rows) {
                seen += row
                val node = E2e.device.findObjects(androidx.test.uiautomator.By.pkg(E2e.APP).clickable(true))
                    .firstOrNull { n -> runCatching { n.hasObject(androidx.test.uiautomator.By.text(row)) }.getOrDefault(false) } ?: continue
                if (runCatching { node.click() }.isFailure) continue
                Thread.sleep(1200)
                if (E2e.device.currentPackageName == E2e.APP) {
                    capture("h9-%02d-%s".format(index++, row.take(30).replace(Regex("[^\\p{L}\\p{N}]+"), "_")))
                }
                repeat(3) {
                    if (E2e.device.currentPackageName != E2e.APP || !E2e.has(androidx.test.uiautomator.By.desc(Es.searchSettings), 500)) {
                        E2e.device.pressBack(); Thread.sleep(600)
                    }
                }
            }
            E2e.find(androidx.test.uiautomator.By.pkg(E2e.APP).scrollable(true), 1000)?.scroll(androidx.test.uiautomator.Direction.DOWN, 0.7f)
            Thread.sleep(400)
        }
        assertTrue("Few entries captured: $seen", index > 20)
    }
}
