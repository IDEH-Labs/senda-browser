@file:Suppress("DEPRECATION")
package org.senda.browser.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.*
import org.torproject.jni.TorService
import java.io.File

enum class TorState {
    STOPPED,
    STARTING,
    CONNECTED,
    STOPPING,
    ERROR
}

object SendaTorManager {
    private const val TAG = "SendaTor"

    var state by mutableStateOf(TorState.STOPPED)
        private set

    var statusMessage by mutableStateOf("Tor apagado")
        private set

    private const val TOR_SOCKS_PORT = 9050

    var socksPort by mutableIntStateOf(TOR_SOCKS_PORT)
        private set

    /** Called on the main thread whenever [state] changes: the proxy extension needs to know when Tor is ready. */
    var onStateChanged: (() -> Unit)? = null

    private var receiverRegistered = false
    private var timeoutJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** Whether Senda wants Tor (Tor mode chosen). Switching mode leaves Tor running, but it is not started again. */
    private var wanted = false
    private var appContext: Context? = null
    /** Android stopped a running Tor and its thread has not ended yet: a new Tor waits for it ([waitForOldTor]). */
    @Volatile private var oldTorClosing = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(TorService.EXTRA_STATUS) ?: return
            Log.i(TAG, "Tor broadcast recibido: status=$status (estado=$state)")
            val result = TorStatusRules.onStatus(state, status, wanted, oldTorClosing) ?: return
            if (result.oldTorStillClosing) oldTorClosing = true
            if (result.oldTorFinished) {
                oldTorClosing = false
                return
            }
            changeState(result.state)
            if (result.state == TorState.CONNECTED) {
                // tor-android 0.4.9 no longer exposes the port as a static field: it is the one set in ensureTorrc()
                socksPort = TOR_SOCKS_PORT
                Log.i(TAG, "Tor conectado exitosamente en puerto $socksPort")
            }
            // Android stops Tor when Senda stays in the background: before, the OFF often arrived just after Senda
            // came back to the front, nothing started Tor again and every page failed against the empty port
            if (result.restart) appContext?.let { start(it) }
        }
    }

    private fun changeState(newState: TorState) {
        state = newState
        statusMessage = when (newState) {
            TorState.STOPPED -> "Tor apagado"
            TorState.STARTING -> "Construyendo circuito Tor..."
            TorState.CONNECTED -> "Conectado a la Red Tor (127.0.0.1:$socksPort)"
            TorState.STOPPING -> "Deteniendo Tor..."
            TorState.ERROR -> "Tor no pudo conectarse"
        }
        // While Tor is not ready, pages wait for it (never a direct connection) or fail with ERROR
        onStateChanged?.invoke()
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        if (!receiverRegistered) {
            // Only the local broadcast: TorService sends every status both locally and globally, and listening to
            // both delivered each one twice
            LocalBroadcastManager.getInstance(context).registerReceiver(statusReceiver, IntentFilter(TorService.ACTION_STATUS))
            receiverRegistered = true
        }
    }

    /**
     * Applies the proxy mode saved in [prefs]. With Tor the proxy is applied at once, before Tor is ready: pages
     * wait for it instead of going out directly with the real IP while it connects.
     */
    fun applyMode(context: Context, prefs: PreferencesManager) {
        if (prefs.proxyMode == "TOR_ORBOT") {
            prefs.proxyHost = "127.0.0.1"
            prefs.proxyPort = 9050
            prefs.proxyDnsRemote = true
            start(context)
        } else {
            wanted = false
        }
        // Switching away from Tor leaves it running until Senda closes. Starting Tor again in the same process
        // often failed (tor-android frees its lock before the old Tor has closed): pages stayed loading or
        // showed an error. With Tor kept running, switching back is instant
        SendaGeckoEngine.applyProxy(prefs)
    }

    /**
     * Starts Tor again if it is not running when Senda comes to the front. When Android starts the process in the
     * background (after a reboot or an update, through the TV mode receiver) it does not allow starting Tor, and it
     * can stop Tor later: before this, browsing stayed without Tor until Senda was force-stopped.
     */
    fun resume(context: Context, prefs: PreferencesManager) {
        if (prefs.proxyMode == "TOR_ORBOT" && (state == TorState.STOPPED || state == TorState.ERROR)) start(context)
    }

    fun start(context: Context) {
        init(context)
        wanted = true
        if (state == TorState.CONNECTED || state == TorState.STARTING) return

        changeState(TorState.STARTING)

        val app = context.applicationContext
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            // A Tor that Android just stopped is still closing: tor-android frees its lock before that, so a new
            // Tor would run beside it in the same process and could not open the port
            waitForOldTor()
            withContext(Dispatchers.IO) { waitForFreePort() }
            if (launchService(app)) {
                // The receiver switches to CONNECTED with STATUS_ON. Without it in time, pages stop waiting and show
                // the connection error (never a direct connection); if Tor connects later, it is used from then on
                delay(60_000)
                if (state == TorState.STARTING) {
                    Log.w(TAG, "Tor no conectó en 60 s")
                    changeState(TorState.ERROR)
                }
            }
        }
    }

    private fun launchService(context: Context): Boolean {
        try {
            ensureTorrc(context)
            context.startService(Intent(context, TorService::class.java).apply { action = TorService.ACTION_START })
            return true
        } catch (e: IllegalStateException) {
            // Senda is in the background: Android does not allow starting the service. Pages wait; [resume] starts it
            Log.w(TAG, "Tor no se puede iniciar en segundo plano: ${e.message}")
            changeState(TorState.STOPPED)
        } catch (e: Exception) {
            Log.e(TAG, "Error al iniciar TorService: ${e.message}", e)
            changeState(TorState.ERROR)
        }
        return false
    }

    /** Waits (up to 10 s) for the STOPPING of a Tor that Android stopped while it was running. */
    private suspend fun waitForOldTor() {
        repeat(40) {
            if (!oldTorClosing) return
            delay(250)
        }
        Log.w(TAG, "El Tor anterior no terminó en 10 s")
        oldTorClosing = false
    }

    /** Waits (up to 10 s) until nothing answers on Tor's port. */
    private fun waitForFreePort() {
        repeat(40) {
            try {
                java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", TOR_SOCKS_PORT), 200) }
            } catch (e: java.io.IOException) {
                return
            }
            Thread.sleep(250)
        }
        Log.w(TAG, "El puerto $TOR_SOCKS_PORT sigue ocupado")
    }

    private fun ensureTorrc(context: Context) {
        try {
            val torDir = File(context.filesDir, "tor_data").apply { mkdirs() }
            val torrc = TorService.getTorrc(context)
            torrc.parentFile?.mkdirs()

            val content = """
                DataDirectory ${torDir.absolutePath}
                SocksPort 127.0.0.1:$TOR_SOCKS_PORT
                ControlPort auto
                ClientOnly 1
                SafeLogging 1
                DNSPort 0
            """.trimIndent()

            if (!torrc.exists() || torrc.length() == 0L) {
                torrc.writeText(content)
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo preparar torrc personalizado: ${e.message}")
        }
    }
}
