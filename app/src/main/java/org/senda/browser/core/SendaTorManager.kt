@file:Suppress("DEPRECATION")
package org.senda.browser.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.*
import org.torproject.jni.TorService
import java.io.File
import `IPtProxy`.Controller
import `IPtProxy`.`IPtProxy`

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

    /**
     * Where Tor and the proxy point while Tor is not connected: below 1024, where no app can listen on Android, so a
     * request fails on the phone. With a fixed 9050 any app that took the port first received Senda's requests
     * (measured on 2026-10-13: a fake listener on 9050 got Senda's SOCKS greetings, and Tor could not start).
     */
    const val CLOSED_PORT = 1

    /**
     * The TCP SOCKS port of Senda's own Tor, chosen by Tor on each start (SocksPort auto); [CLOSED_PORT] until then.
     * Only for Senda's own Java connections (news, the TV relay): Java cannot speak SOCKS over a Unix socket. Pages
     * go through [socksSocket].
     */
    var socksPort by mutableIntStateOf(CLOSED_PORT)
        private set

    /**
     * Tor's SOCKS socket for pages, a file in a folder only Senda can open, as in Tor Browser for Android
     * (TorAndroidIntegration.java: SocksPort unix:…/tor-private/socks-ipc). Its path is known before Tor starts, so
     * Gecko points to it from the first request, and no other app can take its place or connect to it. Kept in
     * files/, not in the cache: Android may delete cache files while Tor is running.
     */
    fun socksSocket(context: Context) = File(File(context.applicationContext.filesDir, "tor-ipc"), "socks")

    /** [socksSocket] as Gecko takes it: a file: URL as the SOCKS host (nsSOCKSIOLayer.cpp, IsHostLocalTarget). */
    fun socksSocketUrl(context: Context) = "file://" + socksSocket(context).absolutePath

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
            // Pages use the socket; the TCP port for Senda's own connections is asked of Tor once it is ready
            if (result.state == TorState.CONNECTED) appContext?.let { askSocksPort(it) }
            // Android stops Tor when Senda stays in the background: before, the OFF often arrived just after Senda
            // came back to the front, nothing started Tor again and every page failed against the empty port
            if (result.restart) appContext?.let { start(it) }
        }
    }

    private fun changeState(newState: TorState) {
        state = newState
        if (newState != TorState.CONNECTED) socksPort = CLOSED_PORT
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
            if (launchService(app)) {
                // The receiver switches to CONNECTED with STATUS_ON. Tor's first start downloads the network's
                // directory, measured from 22 to more than 90 s over mobile data on a moto g34 (2026-10-14), and it
                // once stayed at 51 % for over a minute before going on: a time limit failed pages while Tor was still
                // working. As in Tor Browser, there is no time limit: pages show the connection error (never a direct
                // connection) only when Tor itself reports that it cannot connect. If it connects later, it is used from then on
                watchBootstrap(app)
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

    /**
     * While Tor starts, reads its bootstrap status (GETINFO status/bootstrap-phase, control-spec) and marks Tor failed
     * when Tor reports that it cannot connect: the same rule as Tor Browser (TorProvider.sys.mjs, a WARN bootstrap
     * event whose RECOMMENDATION is not "ignore").
     */
    private suspend fun watchBootstrap(app: Context) {
        var service: TorService? = null
        val connection = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName?, binder: android.os.IBinder?) {
                service = (binder as? TorService.LocalBinder)?.service
            }

            override fun onServiceDisconnected(name: android.content.ComponentName?) {
                service = null
            }
        }
        // Flags 0: only bind to the TorService already running, never start a new one here
        val bound = try { app.bindService(Intent(app, TorService::class.java), connection, 0) } catch (e: Exception) { false }
        try {
            while (state == TorState.STARTING) {
                delay(2_000)
                val status = withContext(Dispatchers.IO) { service?.getInfo("status/bootstrap-phase") }
                if (state == TorState.STARTING && TorStatusRules.bootstrapFailed(status)) {
                    Log.w(TAG, "Tor no puede conectar (progreso ${TorStatusRules.bootstrapProgress(status)} %)")
                    changeState(TorState.ERROR)
                }
            }
        } finally {
            if (bound) try { app.unbindService(connection) } catch (_: Exception) {}
        }
    }

    /**
     * Asks the running TorService (Senda's own, in this process) which TCP SOCKS port its Tor opened. Asked of Tor itself through tor-android's private ControlSocket (GETINFO net/listeners/socks) once Tor
     * says it is ready: TorService.getSocksPort() is read as soon as the control connection opens, possibly before Tor
     * opened its SOCKS port, and then it is 0 (seen on 2026-10-13: Tor listening, Senda given no port).
     */
    private fun askSocksPort(context: Context) {
        val app = context.applicationContext
        val connection = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName?, binder: android.os.IBinder?) {
                val service = (binder as? TorService.LocalBinder)?.service
                val self = this
                scope.launch {
                    val port = withContext(Dispatchers.IO) {
                        TorStatusRules.socksPortFrom(service?.getInfo("net/listeners/socks"))
                    }
                    try { app.unbindService(self) } catch (_: Exception) {}
                    if (state != TorState.CONNECTED) return@launch
                    if (port != null) socksPort = port else Log.e(TAG, "Tor no dio su puerto SOCKS")
                }
            }

            override fun onServiceDisconnected(name: android.content.ComponentName?) {}
        }
        // Flags 0: only bind to the TorService already running, never start a new one here
        if (!app.bindService(Intent(app, TorService::class.java), connection, 0)) Log.e(TAG, "No se pudo consultar el TorService")
    }

    private var ptController: Controller? = null

    @Synchronized
    private fun getPtController(context: Context): Controller {
        ptController?.let { return it }
        val stateDir = File(context.applicationContext.filesDir, "pt_state").apply { mkdirs() }.absolutePath
        val c = Controller(stateDir, false, false, null, null)
        ptController = c
        return c
    }

    fun stopPluggableTransports() {
        try {
            ptController?.stop(IPtProxy.Snowflake)
        } catch (e: Exception) {
            Log.w(TAG, "Error deteniendo Snowflake: ${e.message}")
        }
        try {
            ptController?.stop(IPtProxy.Obfs4)
        } catch (e: Exception) {
            Log.w(TAG, "Error deteniendo Obfs4: ${e.message}")
        }
    }

    fun restart(context: Context) {
        init(context)
        wanted = true
        val app = context.applicationContext
        if (state == TorState.CONNECTED || state == TorState.STARTING) {
            changeState(TorState.STOPPING)
            try {
                app.startService(Intent(app, TorService::class.java).apply { action = TorService.ACTION_STOP })
            } catch (e: Exception) {
                Log.w(TAG, "Error deteniendo TorService para reinicio: ${e.message}")
                start(context)
            }
        } else {
            start(context)
        }
    }

    private fun ensureTorrc(context: Context) {
        try {
            val torrc = TorService.getTorrc(context)
            torrc.parentFile?.mkdirs()
            // Left empty by versions up to 0.2.0-beta.13: tor-android sets its own DataDirectory on the command line
            File(context.filesDir, "tor_data").delete()

            // Tor checks that the socket's folder is private before it creates the socket (check_location_for_unix_socket
            // in connection.c): readable only by Senda, as Tor Browser does with its tor-private folder. Tor itself
            // removes a socket left by an earlier Tor before it listens (connection_listener_new), so Senda never does:
            // that could pull the socket from under a Tor still running
            val socket = socksSocket(context)
            val folder = socket.parentFile!!
            folder.mkdirs()
            android.system.Os.chmod(folder.path, "700".toInt(8))

            val flags = "IPv6Traffic PreferIPv6 KeepAliveIsolateSOCKSAuth"
            val baseConfig = """
                SocksPort unix:"${socket.absolutePath}" $flags
                SocksPort auto $flags
                HTTPTunnelPort 0
                ClientOnly 1
                SafeLogging 1
                DNSPort 0
            """.trimIndent()

            val prefs = PreferencesManager(context)
            val bridgeMode = prefs.torBridgeMode
            val customBridges = prefs.torCustomBridges

            var snowflakePort = 0L
            var obfs4Port = 0L

            if (bridgeMode == "SNOWFLAKE" || (bridgeMode == "CUSTOM" && TorBridgeRules.needsSnowflake(customBridges))) {
                val controller = getPtController(context)
                snowflakePort = controller.port(IPtProxy.Snowflake)
                if (snowflakePort <= 0L) {
                    try {
                        controller.start(IPtProxy.Snowflake, null)
                        snowflakePort = controller.port(IPtProxy.Snowflake)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error al iniciar Snowflake: ${e.message}", e)
                    }
                }
            } else {
                try { ptController?.stop(IPtProxy.Snowflake) } catch (_: Exception) {}
            }

            if (bridgeMode == "CUSTOM" && TorBridgeRules.needsObfs4(customBridges)) {
                val controller = getPtController(context)
                obfs4Port = controller.port(IPtProxy.Obfs4)
                if (obfs4Port <= 0L) {
                    try {
                        controller.start(IPtProxy.Obfs4, null)
                        obfs4Port = controller.port(IPtProxy.Obfs4)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error al iniciar Obfs4: ${e.message}", e)
                    }
                }
            } else {
                try { ptController?.stop(IPtProxy.Obfs4) } catch (_: Exception) {}
            }

            if (bridgeMode == "NONE") {
                stopPluggableTransports()
            }

            val bridgeConfig = TorBridgeRules.buildTorrcBridgeConfig(
                bridgeMode = bridgeMode,
                customBridges = customBridges,
                snowflakePort = snowflakePort,
                obfs4Port = obfs4Port
            )

            val content = if (bridgeConfig.isNotBlank()) "$baseConfig\n$bridgeConfig" else baseConfig

            // torrc is Senda's own (there is no way to edit it): rewritten whenever Senda's configuration changes,
            // or installations from an older version would keep the old one forever
            if (!torrc.exists() || torrc.readText() != content) {
                val partial = File(torrc.path + ".new")
                partial.writeText(content)
                if (!partial.renameTo(torrc)) {
                    partial.delete()
                    throw java.io.IOException("Could not replace ${torrc.path}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo preparar torrc personalizado: ${e.message}")
        }
    }
}
