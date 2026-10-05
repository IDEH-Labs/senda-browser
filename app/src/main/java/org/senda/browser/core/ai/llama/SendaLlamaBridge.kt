package org.senda.browser.core.ai.llama

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * High-performance, native C++ JNI bridge to llama.cpp for on-device GGUF neural inference.
 * Runs completely locally on ARM64-v8a NEON without external dependencies or cloud telemetry.
 */
object SendaLlamaBridge {
    private const val TAG = "SendaLlamaBridge"

    sealed class EngineStatus {
        object Uninitialized : EngineStatus()
        object Initializing : EngineStatus()
        object Ready : EngineStatus()
        data class ModelLoaded(val path: String, val systemInfo: String) : EngineStatus()
        object Inferring : EngineStatus()
        data class Error(val message: String) : EngineStatus()
    }

    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.Uninitialized)
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private val llamaDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val llamaScope = CoroutineScope(llamaDispatcher + SupervisorJob())

    private var isNativeLibLoaded = false
    @Volatile
    private var isModelLoaded = false

    // JNI Native methods implemented in senda_llama_jni.cpp
    // Sin @FastNative: cargar el modelo o generar tokens tarda segundos, y en ese modo el GC queda bloqueado
    // hasta que vuelve la llamada (medido: de 25 ms a 1,7 s)
    private external fun nativeInit(nativeLibDir: String)

    private external fun nativeLoadModel(modelPath: String, loadMode: Int, lazyMode: Int, gpuLayers: Int, gpuDevice: String?): Int

    private external fun nativeListDevices(): String

    private external fun nativeSetTemperature(temp: Float)

    private external fun nativeLastDecodedTokens(): Int

    /**
     * Modo de carga (llama_load_mode / llama_lazy_mode). Medido con Gemma 4 E2B QAT en un moto g34 (8 GB):
     * mmap + embeddings por capa bajo demanda = 2,8-3,1 GB y 5,5-7 tok/s; mmap solo = 4,3 GB; sin mmap +
     * bajo demanda = 2,2 GB pero 2,4 tok/s; sin mmap = 3,9 GB. Variables para poder medir otros en el dispositivo.
     */
    @Volatile var loadMode = 1 // LLAMA_LOAD_MODE_MMAP
    @Volatile var lazyMode = 2 // LLAMA_LAZY_MODE_ON

    /** Archivo y configuración CPU/GPU del modelo cargado ahora (null si no hay ninguno). */
    @Volatile var loadedConfig: String? = null
        private set

    /** Capas en la GPU: 0 salvo que la calibración de este teléfono haya medido que la GPU es mejor. */
    @Volatile var gpuLayers = 0

    /** Nombre ggml de la GPU a usar cuando gpuLayers > 0 (p. ej. «GPUOpenCL»). */
    @Volatile var gpuDevice: String? = null

    private external fun nativePrepare(): Int

    private external fun nativeSystemInfo(): String

    private external fun nativeBenchModel(pp: Int, tg: Int, pl: Int, nr: Int): String

    private external fun nativeProcessConversation(roles: Array<String>, contents: Array<String>, predictLength: Int, thinking: Boolean): Int

    private external fun nativeGenerateNextToken(): String?

    private external fun nativeSetThreads(nGen: Int, nBatch: Int)

    private external fun nativeUnload()

    private external fun nativeShutdown()

    fun initialize(context: Context) {
        if (isNativeLibLoaded) return
        llamaScope.launch {
            try {
                _status.value = EngineStatus.Initializing
                val nativeLibDir = context.applicationInfo.nativeLibraryDir
                Log.i(TAG, "Loading native library senda-llama from $nativeLibDir...")
                System.loadLibrary("senda-llama")
                nativeInit(nativeLibDir)
                // Los módulos de ggml ya están cargados: la lista no cambia hasta reiniciar la app
                cachedDevices = parseDevices(nativeListDevices())
                isNativeLibLoaded = true
                _status.value = EngineStatus.Ready
                Log.i(TAG, "senda-llama loaded successfully! System info: ${nativeSystemInfo()}")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load senda-llama native library", t)
                _status.value = EngineStatus.Error("Failed to initialize llama.cpp: ${t.message}")
            }
        }
    }

    suspend fun loadModel(modelFile: File): Result<String> = withContext(llamaDispatcher) {
        if (!isNativeLibLoaded) {
            return@withContext Result.failure(IllegalStateException("Native engine not initialized"))
        }
        if (!modelFile.exists() || modelFile.length() == 0L) {
            return@withContext Result.failure(IllegalArgumentException("GGUF model file does not exist: ${modelFile.absolutePath}"))
        }

        try {
            if (isModelLoaded) {
                nativeUnload()
                isModelLoaded = false
                loadedConfig = null
            }

            Log.i(TAG, "Loading GGUF model: ${modelFile.absolutePath} (${modelFile.length() / 1024 / 1024} MB)")
            val loadRes = nativeLoadModel(modelFile.absolutePath, loadMode, lazyMode, gpuLayers, gpuDevice)
            if (loadRes != 0) {
                return@withContext Result.failure(RuntimeException("llama_model_load_from_file returned error code $loadRes"))
            }

            val prepRes = nativePrepare()
            if (prepRes != 0) {
                return@withContext Result.failure(RuntimeException("nativePrepare returned error code $prepRes"))
            }

            isModelLoaded = true
            loadedConfig = "${modelFile.absolutePath}|$gpuLayers|$gpuDevice"
            val sysInfo = nativeSystemInfo()
            _status.value = EngineStatus.ModelLoaded(modelFile.name, sysInfo)
            Log.i(TAG, "Model loaded and prepared successfully!")
            Result.success(sysInfo)
        } catch (t: Throwable) {
            Log.e(TAG, "Error loading GGUF model", t)
            _status.value = EngineStatus.Error("Model load error: ${t.message}")
            Result.failure(t)
        }
    }

    suspend fun benchmark(pp: Int = 128, tg: Int = 64): String = withContext(llamaDispatcher) {
        if (!isModelLoaded) return@withContext "No model loaded for benchmark"
        nativeBenchModel(pp, tg, 1, 1)
    }

    /** Turno de una conversación: rol ("user" / "assistant") y texto. */
    data class ChatTurn(val role: String, val content: String)

    /** La conversación no cabe en el contexto del modelo (hay que recortar el historial). */
    class ContextOverflowException : RuntimeException("Conversation does not fit in the model context")

    /**
     * Genera la respuesta a [userPrompt] viendo el [history] previo. Los fallos se lanzan como excepción:
     * antes se emitían como texto («Error: …») y la app los mostraba como si fueran la respuesta del modelo.
     */
    fun inferStream(
        userPrompt: String,
        systemPrompt: String = "You are Senda AI, a private, accurate, and ethical assistant running directly on-device.",
        maxTokens: Int = 512,
        history: List<ChatTurn> = emptyList(),
        thinking: Boolean = false
    ): Flow<String> = flow {
        check(isModelLoaded) { "No GGUF model loaded" }

        _status.value = EngineStatus.Inferring
        try {
            val turns = listOf(ChatTurn("system", systemPrompt)) + history + ChatTurn("user", userPrompt)
            val res = nativeProcessConversation(
                turns.map { it.role }.toTypedArray(), turns.map { it.content }.toTypedArray(), maxTokens, thinking
            )
            if (res == 1) throw ContextOverflowException()
            check(res == 0) { "Native conversation processing failed (code $res)" }

            var tokenCount = 0
            while (tokenCount < maxTokens) {
                val token = nativeGenerateNextToken() ?: break
                if (token.isNotEmpty()) {
                    emit(token)
                }
                tokenCount++
            }
        } finally {
            _status.value = EngineStatus.Ready
        }
    }.flowOn(llamaDispatcher)

    /** Un dispositivo de cálculo que ggml cargó en este teléfono. */
    data class ComputeDevice(val type: String, val name: String, val description: String, val freeMb: Long, val totalMb: Long) {
        val isGpu: Boolean get() = type == "GPU" || type == "IGPU"
    }

    /** Temperatura del muestreo (0 = determinista). La app usa 0,3. */
    suspend fun setTemperature(temp: Float) = withContext(llamaDispatcher) {
        if (isModelLoaded) nativeSetTemperature(temp)
    }

    /** Tokens del prompt leídos en la última pregunta (sin los reutilizados de la anterior). */
    fun lastDecodedTokens(): Int = if (isNativeLibLoaded) nativeLastDecodedTokens() else 0

    @Volatile private var cachedDevices: List<ComputeDevice> = emptyList()

    /**
     * Dispositivos disponibles (vacío si la biblioteca nativa no cargó). Leídos una vez al iniciar: llamar a la
     * función nativa aquí esperaba el mismo cerrojo que la carga del modelo (5-20 s) y congelaba la pantalla
     */
    fun listDevices(): List<ComputeDevice> = cachedDevices

    private fun parseDevices(raw: String): List<ComputeDevice> =
        raw.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val p = line.split('|')
            if (p.size < 5) null else ComputeDevice(p[0], p[1], p[2], p[3].toLongOrNull() ?: 0, p[4].toLongOrNull() ?: 0)
        }

    /** Hilos de escritura y de lectura del prompt (para medir en el dispositivo; la app usa los de nativePrepare). */
    suspend fun setThreads(nGen: Int, nBatch: Int) = withContext(llamaDispatcher) {
        if (isModelLoaded) nativeSetThreads(nGen, nBatch)
    }

    fun unload() {
        llamaScope.launch {
            if (isModelLoaded) {
                nativeUnload()
                isModelLoaded = false
                loadedConfig = null
                _status.value = EngineStatus.Ready
            }
        }
    }

    fun shutdown() {
        llamaScope.launch {
            if (isModelLoaded) {
                nativeUnload()
                isModelLoaded = false
            }
            if (isNativeLibLoaded) {
                nativeShutdown()
                isNativeLibLoaded = false
                _status.value = EngineStatus.Uninitialized
            }
        }
    }

    val isAvailable: Boolean
        get() = isNativeLibLoaded

    val isReadyForInference: Boolean
        get() = isNativeLibLoaded && isModelLoaded
}
