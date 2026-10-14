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

    /**
     * The SOCKS port in Tor's answer to GETINFO net/listeners/socks: quoted addresses separated by spaces, such as
     * "127.0.0.1:38451". Only a port on the phone itself (127.0.0.1 or ::1) and outside the reserved range; null
     * otherwise, so Senda never points its requests elsewhere.
     */
    fun socksPortFrom(info: String?): Int? {
        if (info == null) return null
        for (listener in info.split(' ').map { it.trim().trim('"') }) {
            val host = listener.substringBeforeLast(':', "")
            val port = listener.substringAfterLast(':').toIntOrNull() ?: continue
            if ((host == "127.0.0.1" || host == "[::1]") && port in 1024..65535) return port
        }
        return null
    }

    /**
     * Tor's bootstrap progress, 0 to 100, from its answer to GETINFO status/bootstrap-phase: its last bootstrap event,
     * such as `NOTICE BOOTSTRAP PROGRESS=50 TAG=loading_descriptors SUMMARY="Loading relay descriptors"`
     * (control-spec). Null when there is no answer yet.
     */
    fun bootstrapProgress(info: String?): Int? =
        info?.let { Regex("""\bBOOTSTRAP\b.*?\bPROGRESS=(\d{1,3})\b""").find(it) }?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..100 }

    /**
     * Whether Tor reports that it cannot connect: a WARN bootstrap event whose RECOMMENDATION is "warn" (control-spec:
     * "ignore" means Tor expects to recover by itself). Tor Browser shows its connection error on the same events.
     */
    fun bootstrapFailed(info: String?): Boolean =
        info != null && Regex("""^\s*WARN\s+BOOTSTRAP\b""").containsMatchIn(info) &&
            Regex("""\bRECOMMENDATION=(\w+)""").find(info)?.groupValues?.get(1)?.equals("ignore", ignoreCase = true) == false

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
