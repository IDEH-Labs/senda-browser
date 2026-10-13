package org.senda.browser.e2e.test

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * I. Performance (priority 2, catches large regressions). The numbers are written to perf.txt in the test app's
 * folder. The e2e app is debuggable, which makes it slower than a release, so the reference is the e2e app itself:
 * 2026-10-12/13, GeckoView 158, on the maintainer's phone: cold start median 1302–1552 ms, main process 405–431 MB.
 * (A release, 0.2.0-beta.1 on 2026-10-09: 361–406 ms and 281 MB; comparing the e2e app with it failed every time.)
 */
@RunWith(AndroidJUnit4::class)
class PerformanceTest : E2eTest() {

    private val report = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "perf.txt")

    private fun write(line: String) = report.appendText(line + "\n")

    /** I1 — cold start, 10 times (am start -W, TotalTime). */
    @Test
    fun i1_coldStart() {
        E2e.launch() // first start after clearing data does extra work: not counted
        val times = (1..10).map {
            E2e.backgroundAndKill()
            val out = E2e.sh("am start -W -n ${E2e.APP}/org.senda.browser.MainActivity -a android.intent.action.MAIN -c android.intent.category.LAUNCHER")
            Thread.sleep(1500)
            out.lineSequence().firstOrNull { it.startsWith("TotalTime:") }?.substringAfter(':')?.trim()?.toInt() ?: -1
        }
        val sorted = times.sorted()
        val median = (sorted[4] + sorted[5]) / 2
        write("I1 cold start ms: $times; median $median, min ${sorted.first()}, max ${sorted.last()}")
        assertTrue("A start time could not be read: $times", times.none { it < 0 })
        // Alert from the plan: median more than 25 % above the reference (1552 ms, upper end)
        assertTrue("Median cold start $median ms is more than 25 % above 1552 ms. Times: $times", median <= 1940)
    }

    /** I2 — memory at rest after 3 local pages (dumpsys meminfo, PSS per process). */
    @Test
    fun i2_memoryAtRest() {
        listOf("/a.html", "/b.html", "/c.html").forEach { E2e.openLink(LocalWeb.url(it)) }
        E2e.pageShows("e2e-c")
        E2e.device.pressHome()
        Thread.sleep(10_000)
        val pids = E2e.sh("pidof ${E2e.APP}").trim()
        val processes = E2e.sh("ps -A -o PID,NAME").lines().filter { it.contains(E2e.APP) && !it.contains(".test") }
        val lines = processes.map { p ->
            val pid = p.trim().substringBefore(' ')
            val total = E2e.sh("dumpsys meminfo $pid").lineSequence().firstOrNull { it.trim().startsWith("TOTAL PSS:") || it.trim().startsWith("TOTAL ") }
            "${p.trim()}: ${total?.trim()}"
        }
        write("I2 memory at rest (main pid $pids):\n  " + lines.joinToString("\n  "))
        assertTrue("No process of the e2e app found", processes.isNotEmpty())
        // "<pid> org.senda.browser.e2e: TOTAL…": the main process (the others are "…e2e:tab…")
        val mainPss = lines.firstOrNull { it.contains("${E2e.APP}: ") }
        val kb = Regex("TOTAL(?: PSS:)?\\s+(\\d+)").find(mainPss.orEmpty())?.groupValues?.get(1)?.toInt()
        write("I2 main process PSS: ${kb?.div(1024)} MB")
        assertTrue("Main process ${kb?.div(1024)} MB is more than 25 % above 431 MB", kb == null || kb / 1024 <= 539)
    }
}
