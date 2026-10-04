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

    var socksPort by mutableIntStateOf(9050)
        private set

    private var receiverRegistered = false
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
                    state = TorState.CONNECTED
                    socksPort = if (TorService.socksPort > 0) TorService.socksPort else 9050
                    statusMessage = "Conectado a la Red Tor (127.0.0.1:$socksPort)"
                    Log.i(TAG, "Tor conectado exitosamente en puerto $socksPort")
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

    fun start(context: Context, onConnected: (() -> Unit)? = null) {
        init(context)
        if (state == TorState.CONNECTED) {
            onConnected?.invoke()
            return
        }

        state = TorState.STARTING
        statusMessage = "Iniciando demonio Tor embebido..."

        try {
            ensureTorrc(context)
            val intent = Intent(context, TorService::class.java).apply {
                action = TorService.ACTION_START
            }
            context.startService(intent)

            scope.launch {
                var waitMs = 0
                while (state != TorState.CONNECTED && waitMs < 35000) {
                    delay(500)
                    waitMs += 500
                    if (TorService.socksPort > 0 && state == TorState.STARTING) {
                        state = TorState.CONNECTED
                        socksPort = TorService.socksPort
                        statusMessage = "Conectado a la Red Tor (127.0.0.1:$socksPort)"
                        break
                    }
                }
                if (state == TorState.CONNECTED) {
                    onConnected?.invoke()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al iniciar TorService: ${e.message}", e)
            state = TorState.ERROR
            statusMessage = "Error al iniciar Tor: ${e.localizedMessage}"
        }
    }

    fun stop(context: Context) {
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
                SocksPort 127.0.0.1:9050
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
