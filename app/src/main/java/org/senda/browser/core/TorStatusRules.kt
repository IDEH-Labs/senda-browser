package org.senda.browser.core

/**
 * What a status broadcast from tor-android's TorService means for Senda. Kept apart from [SendaTorManager] (and from
 * TorService, which loads the native library) so the sequences seen on the phone can be tested on the PC.
 *
 * In tor-android 0.4.9.13 the order depends on who stops Tor:
 * - Tor ends by itself or fails: the tor thread sends STOPPING, then onDestroy sends OFF.
 * - Android stops the service (app idle in the background): onDestroy sends OFF while the old Tor is still shutting
 *   down, and its thread sends STOPPING afterwards. That late STOPPING must not change the state of a new Tor.
 */
internal object TorStatusRules {
    const val STARTING = "STARTING"
    const val ON = "ON"
    const val STOPPING = "STOPPING"
    const val OFF = "OFF"

    /**
     * [state]: the new state. [restart]: Tor was running, Senda still wants it and must start it again.
     * [oldTorStillClosing]: the old Tor has not finished yet; a new one must wait for its STOPPING.
     * [oldTorFinished]: the STOPPING of that old Tor arrived.
     */
    data class Result(
        val state: TorState,
        val restart: Boolean = false,
        val oldTorStillClosing: Boolean = false,
        val oldTorFinished: Boolean = false
    )

    /** Returns null when the broadcast changes nothing (repeated or late). */
    fun onStatus(current: TorState, status: String, wanted: Boolean, oldTorClosing: Boolean): Result? = when (status) {
        STARTING -> if (current == TorState.STARTING) null else Result(TorState.STARTING)
        ON -> if (current == TorState.CONNECTED) null else Result(TorState.CONNECTED)
        STOPPING -> when {
            oldTorClosing -> Result(current, oldTorFinished = true)
            current == TorState.CONNECTED -> Result(TorState.STOPPING)
            // A new Tor that fails sends STOPPING too, but OFF always follows: it decides
            else -> null
        }
        OFF -> when (current) {
            // Android stopped a running Tor: start it again (only possible with Senda in front, otherwise on return)
            TorState.CONNECTED -> Result(TorState.STOPPED, restart = wanted, oldTorStillClosing = true)
            TorState.STOPPING -> Result(TorState.STOPPED, restart = wanted)
            // The new Tor closed before connecting: error (pages fail instead of going out directly); retried when
            // Senda comes back to the front
            TorState.STARTING -> Result(TorState.ERROR)
            TorState.STOPPED, TorState.ERROR -> null
        }
        else -> null
    }
}
