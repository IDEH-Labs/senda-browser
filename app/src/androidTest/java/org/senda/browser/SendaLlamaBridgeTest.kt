package org.senda.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.senda.browser.core.ai.llama.SendaLlamaBridge
import org.senda.browser.core.cast.SendaUnifiedCast

@RunWith(AndroidJUnit4::class)
class SendaLlamaBridgeTest {

    @Test
    fun testNativeLlamaLibraryLoadsAndInitializesOnDevice() = runBlocking {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertNotNull("Target context must not be null", appContext)

        // Initialize SendaLlamaBridge
        SendaLlamaBridge.initialize(appContext)

        // Allow up to 3 seconds for async initialization
        var attempts = 0
        while (!SendaLlamaBridge.isAvailable && attempts < 30) {
            delay(100)
            attempts++
        }

        assertTrue("Native senda-llama bridge must be loaded on ARM64 hardware", SendaLlamaBridge.isAvailable)
        val status = SendaLlamaBridge.status.value
        assertTrue("Status must be Ready or ModelLoaded, but was: $status", status is SendaLlamaBridge.EngineStatus.Ready || status is SendaLlamaBridge.EngineStatus.ModelLoaded)
    }

    @Test
    fun testUnifiedCastSubsystemInitializesWithoutErrors() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertNotNull(appContext)

        SendaUnifiedCast.initialize(appContext)
        // Verify devices collection is available
        assertNotNull(SendaUnifiedCast.devices)
    }
}
