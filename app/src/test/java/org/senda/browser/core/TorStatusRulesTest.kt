package org.senda.browser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.senda.browser.core.TorStatusRules.OFF
import org.senda.browser.core.TorStatusRules.ON
import org.senda.browser.core.TorStatusRules.STARTING
import org.senda.browser.core.TorStatusRules.STOPPING

/**
 * Status sequences from tor-android 0.4.9.13, including the one recorded on the phone on 2026-10-11 15:23:08: Android
 * stopped Tor while Senda was in the background, Senda came back to the front still "connected", then OFF arrived
 * and finally the late STOPPING of the old Tor. Before, Tor was never started again and every page failed.
 */
class TorStatusRulesTest {
    /** Applies the broadcasts like SendaTorManager does; a restart sets STARTING as start() does. */
    private class Simulation(var state: TorState, val wanted: Boolean = true) {
        var oldTorClosing = false
        var restarts = 0

        fun receive(status: String) {
            val result = TorStatusRules.onStatus(state, status, wanted, oldTorClosing) ?: return
            if (result.oldTorStillClosing) oldTorClosing = true
            if (result.oldTorFinished) {
                oldTorClosing = false
                return
            }
            state = result.state
            if (result.restart) {
                restarts++
                state = TorState.STARTING
            }
        }
    }

    @Test
    fun androidStopsTorInTheBackground_isStartedAgainAndConnects() {
        val tor = Simulation(TorState.CONNECTED)
        tor.receive(OFF)
        assertEquals(1, tor.restarts)
        assertEquals(TorState.STARTING, tor.state)
        assertTrue("the new Tor must wait for the old one", tor.oldTorClosing)

        tor.receive(STOPPING) // late, from the old Tor's thread
        assertEquals(TorState.STARTING, tor.state)
        assertFalse(tor.oldTorClosing)

        tor.receive(STARTING)
        tor.receive(ON)
        assertEquals(TorState.CONNECTED, tor.state)
        assertEquals(1, tor.restarts)
    }

    @Test
    fun torEndingByItself_isStartedAgainWithoutWaitingForAnOldTor() {
        val tor = Simulation(TorState.CONNECTED)
        tor.receive(STOPPING)
        assertEquals(TorState.STOPPING, tor.state)
        tor.receive(OFF)
        assertEquals(1, tor.restarts)
        assertFalse(tor.oldTorClosing)
    }

    @Test
    fun newTorFailingBeforeConnecting_isAnErrorNotARestartLoop() {
        val tor = Simulation(TorState.STARTING)
        tor.receive(STOPPING)
        assertEquals(TorState.STARTING, tor.state)
        tor.receive(OFF)
        assertEquals(TorState.ERROR, tor.state)
        assertEquals(0, tor.restarts)
    }

    @Test
    fun notStartedAgainWhenSendaNoLongerWantsTor() {
        val tor = Simulation(TorState.CONNECTED, wanted = false)
        tor.receive(OFF)
        assertEquals(TorState.STOPPED, tor.state)
        assertEquals(0, tor.restarts)
    }

    @Test
    fun repeatedBroadcastsChangeNothing() {
        assertNull(TorStatusRules.onStatus(TorState.STOPPED, OFF, wanted = true, oldTorClosing = false))
        assertNull(TorStatusRules.onStatus(TorState.ERROR, OFF, wanted = true, oldTorClosing = false))
        assertNull(TorStatusRules.onStatus(TorState.CONNECTED, ON, wanted = true, oldTorClosing = false))
        assertNull(TorStatusRules.onStatus(TorState.STARTING, STARTING, wanted = true, oldTorClosing = false))
        assertNull(TorStatusRules.onStatus(TorState.STOPPED, "UNKNOWN", wanted = true, oldTorClosing = false))
    }

    @Test
    fun torConnectingLateAfterTheTimeout_isUsed() {
        val tor = Simulation(TorState.ERROR)
        tor.receive(ON)
        assertEquals(TorState.CONNECTED, tor.state)
    }

    @Test
    fun theSocksPortComesFromTorsOwnAnswer() {
        assertEquals(38451, TorStatusRules.socksPortFrom("\"127.0.0.1:38451\""))
        assertEquals(41000, TorStatusRules.socksPortFrom("\"[::1]:41000\" \"127.0.0.1:42000\""))
        // Senda's Tor also lists its socket for pages: only the TCP port is taken
        assertEquals(38451, TorStatusRules.socksPortFrom("\"unix:/data/user/0/org.senda.browser/files/tor-ipc/socks\" \"127.0.0.1:38451\""))
        // No SOCKS port, a missing answer, another address or a reserved port: no port, so requests fail on the phone
        assertNull(TorStatusRules.socksPortFrom(""))
        assertNull(TorStatusRules.socksPortFrom(null))
        assertNull(TorStatusRules.socksPortFrom("\"0.0.0.0:9050\""))
        assertNull(TorStatusRules.socksPortFrom("\"127.0.0.1:80\""))
    }

    @Test
    fun bootstrapProgressComesFromTorsLastEvent() {
        assertEquals(50, TorStatusRules.bootstrapProgress("NOTICE BOOTSTRAP PROGRESS=50 TAG=loading_descriptors SUMMARY=\"Loading relay descriptors\""))
        assertEquals(10, TorStatusRules.bootstrapProgress("WARN BOOTSTRAP PROGRESS=10 TAG=conn_done SUMMARY=\"Connected to a relay\" WARNING=\"Connection refused\" REASON=CONNECTREFUSED COUNT=3 RECOMMENDATION=ignore"))
        assertEquals(100, TorStatusRules.bootstrapProgress("NOTICE BOOTSTRAP PROGRESS=100 TAG=done SUMMARY=\"Done\""))
        // No answer yet, or not a bootstrap event: no progress
        assertNull(TorStatusRules.bootstrapProgress(null))
        assertNull(TorStatusRules.bootstrapProgress(""))
        assertNull(TorStatusRules.bootstrapProgress("NOTICE CIRCUIT_ESTABLISHED"))
    }

    @Test
    fun torFailsOnlyWhenTorSaysSo() {
        assertTrue(TorStatusRules.bootstrapFailed("WARN BOOTSTRAP PROGRESS=10 TAG=conn_done SUMMARY=\"Connected to a relay\" WARNING=\"No route to host\" REASON=NOROUTE COUNT=5 RECOMMENDATION=warn"))
        // Tor expects to recover, still making progress, or no answer yet: keep waiting
        assertFalse(TorStatusRules.bootstrapFailed("WARN BOOTSTRAP PROGRESS=10 TAG=conn_done SUMMARY=\"Connected to a relay\" WARNING=\"Connection refused\" REASON=CONNECTREFUSED COUNT=1 RECOMMENDATION=ignore"))
        assertFalse(TorStatusRules.bootstrapFailed("NOTICE BOOTSTRAP PROGRESS=51 TAG=loading_descriptors SUMMARY=\"Loading relay descriptors\""))
        assertFalse(TorStatusRules.bootstrapFailed(null))
    }
}
