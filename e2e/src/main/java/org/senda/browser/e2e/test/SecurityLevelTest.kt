package org.senda.browser.e2e.test

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * D. Security levels as in Tor Browser (STANDARD, SAFER, SAFEST). The engine part is set when Gecko starts, so each
 * test starts the e2e app with the level already chosen. The page reads what it can use: WebGL, WebAssembly,
 * MathML and SVG. Each pref was measured on the phone on 2026-10-14 to remove exactly that.
 */
@RunWith(AndroidJUnit4::class)
class SecurityLevelTest : E2eTest() {

    private var level = "STANDARD"

    override fun startPrefs(): Map<String, Any> = mapOf("security_level" to level)

    /** What the page at /levels.html reports ("webgl=… wasm=… math=… svg=…"), once its script ran. */
    private fun report(chosen: String): String {
        level = chosen
        E2e.reset(testProxyPrefs() + startPrefs())
        E2e.launch()
        E2e.openLink(LocalWeb.url("/levels.html"))
        var seen = ""
        E2e.waitFor("the report of /levels.html at $chosen") {
            seen = Regex("""webgl=\w+ wasm=\w+ math=\w+ svg=\w+""").find(E2e.screenTexts())?.value.orEmpty()
            seen.isNotEmpty()
        }
        return seen
    }

    /** D1 — STANDARD (the default): nothing is removed. */
    @Test
    fun d1_standardRemovesNothing() {
        assertEquals("webgl=yes wasm=yes math=yes svg=yes", report("STANDARD"))
    }

    /** D2 — SAFER: no WebGL, WebAssembly or MathML; SVG stays, as in Tor Browser. */
    @Test
    fun d2_saferRemovesWebglWasmMathml() {
        assertEquals("webgl=no wasm=no math=no svg=yes", report("SAFER"))
    }

    /** D3 — SAFEST: also no SVG. */
    @Test
    fun d3_safestAlsoRemovesSvg() {
        assertEquals("webgl=no wasm=no math=no svg=no", report("SAFEST"))
    }
}
