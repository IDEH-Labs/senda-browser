package org.senda.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.senda.browser.core.cast.SendaUnifiedCast

@RunWith(AndroidJUnit4::class)
class SendaUnifiedCastTest {

    @Test
    fun testUnifiedCastSubsystemInitializesWithoutErrors() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertNotNull(appContext)

        SendaUnifiedCast.initialize(appContext)
        // Verify devices collection is available
        assertNotNull(SendaUnifiedCast.devices)
    }
}
