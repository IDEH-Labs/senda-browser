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
}
