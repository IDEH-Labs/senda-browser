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

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(TorService.EXTRA_STATUS) ?: return
            Log.i(TAG, "Tor broadcast recibido: status=$status")
            when (status) {
                TorService.STATUS_STARTING -> {
                    state = TorState.STARTING
                    statusMessage = "Construyendo circuito Tor..."
                }
                TorService.STATUS_ON -> {
                    if (state == TorState.CONNECTED) return
                    state = TorState.CONNECTED
                    // tor-android 0.4.9 no longer exposes the port as a static field: it is the one set in ensureTorrc()
                    socksPort = TOR_SOCKS_PORT
                    statusMessage = "Conectado a la Red Tor (127.0.0.1:$socksPort)"
                    Log.i(TAG, "Tor conectado exitosamente en puerto $socksPort")
                    onStateChanged?.invoke()
                }
                TorService.STATUS_STOPPING -> {
                    state = TorState.STOPPING
                    statusMessage = "Deteniendo Tor..."
                }
                TorService.STATUS_OFF -> {
                    state = TorState.STOPPED
                    statusMessage = "Tor apagado"
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    fun init(context: Context) {
        if (!receiverRegistered) {
            val filter = IntentFilter(TorService.ACTION_STATUS)
            try {
                LocalBroadcastManager.getInstance(context).registerReceiver(statusReceiver, filter)
            } catch (e: Exception) {
                Log.w(TAG, "LocalBroadcastManager error: ${e.message}")
            }
            try {
                androidx.core.content.ContextCompat.registerReceiver(
                    context,
                    statusReceiver,
                    filter,
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
                )
            } catch (e: Exception) {
                Log.w(TAG, "Receiver ya registrado o error leve: ${e.message}")
            }
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
            stop(context)
        }
        SendaGeckoEngine.applyProxy(prefs)
    }

    fun start(context: Context) {
        init(context)
        if (state == TorState.CONNECTED || state == TorState.STARTING) return

        state = TorState.STARTING
        statusMessage = "Iniciando demonio Tor embebido..."

        try {
            ensureTorrc(context)
            val intent = Intent(context, TorService::class.java).apply {
                action = TorService.ACTION_START
            }
            context.startService(intent)

            timeoutJob?.cancel()
            timeoutJob = scope.launch {
                // The receiver switches to CONNECTED with STATUS_ON. Without it in time, pages stop waiting and show
                // the connection error (never a direct connection); if Tor connects later, it is used from then on
                delay(60_000)
                if (state == TorState.STARTING) {
                    state = TorState.ERROR
                    statusMessage = "Tor no pudo conectarse"
                    Log.w(TAG, "Tor no conectó en 60 s")
                    onStateChanged?.invoke()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al iniciar TorService: ${e.message}", e)
            state = TorState.ERROR
            statusMessage = "Error al iniciar Tor: ${e.localizedMessage}"
            onStateChanged?.invoke()
        }
    }

    fun stop(context: Context) {
        timeoutJob?.cancel()
        state = TorState.STOPPING
        statusMessage = "Deteniendo Tor para conservar batería..."
        try {
            val intent = Intent(context, TorService::class.java)
            context.stopService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error al detener TorService: ${e.message}")
        } finally {
            state = TorState.STOPPED
            statusMessage = "Tor apagado"
        }
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
