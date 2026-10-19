package org.senda.browser.e2e.test

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Temporary probe (not to be committed): what the address bar shows when searching Startpage through Tor. */
@RunWith(AndroidJUnit4::class)
class StartpageProbe : E2eTest() {

    override fun startPrefs(): Map<String, Any> = mapOf(
        "proxy_mode" to "TOR_ORBOT",
        "security_level" to "SAFEST",
        "search_engine_name" to "Startpage",
        "search_engine_url" to "https://www.startpage.com/sp/search?query="
    )

    private val out = InstrumentationRegistry.getInstrumentation().context.getExternalFilesDir(null)!!

    private fun report(key: String, value: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString(key, value) })
    }

    @Test
    fun probe() {
        // Right after a cold start, before Tor has connected: search from the home page's address bar
        E2e.launch()
        report("tor", "searching right after start")
        E2e.need(By.pkg(E2e.APP).text(Es.addressHint), "the address bar").click()
        val field = E2e.need(By.pkg(E2e.APP).clazz("android.widget.EditText").focused(true), "the address field")
        field.text = "gnu"
        E2e.device.pressEnter()
        val start = System.currentTimeMillis()
        for (s in listOf(2, 5, 10, 20, 40, 60, 90, 130, 180)) {
            Thread.sleep(maxOf(0L, start + s * 1000L - System.currentTimeMillis()))
            report("t$s", E2e.screenTexts().take(600))
            E2e.device.dumpWindowHierarchy(File(out, "sp_$s.xml"))
            if (s in listOf(5, 20, 60, 130, 180)) E2e.device.takeScreenshot(File(out, "sp_$s.png"))
        }
    }
}
