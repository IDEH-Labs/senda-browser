package org.senda.browser.core.ai.llama

import android.content.Context
import android.util.Log
import dalvik.annotation.optimization.FastNative
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
    @FastNative
    private external fun nativeInit(nativeLibDir: String)

    @FastNative
    private external fun nativeLoadModel(modelPath: String): Int

    @FastNative
    private external fun nativePrepare(): Int

    @FastNative
    private external fun nativeSystemInfo(): String

    @FastNative
    private external fun nativeBenchModel(pp: Int, tg: Int, pl: Int, nr: Int): String

    @FastNative
    private external fun nativeProcessSystemPrompt(systemPrompt: String): Int

    @FastNative
    private external fun nativeProcessUserPrompt(userPrompt: String, predictLength: Int): Int

    @FastNative
    private external fun nativeGenerateNextToken(): String?

    @FastNative
    private external fun nativeUnload()

    @FastNative
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
            }

            Log.i(TAG, "Loading GGUF model: ${modelFile.absolutePath} (${modelFile.length() / 1024 / 1024} MB)")
            val loadRes = nativeLoadModel(modelFile.absolutePath)
            if (loadRes != 0) {
                return@withContext Result.failure(RuntimeException("llama_model_load_from_file returned error code $loadRes"))
            }

            val prepRes = nativePrepare()
            if (prepRes != 0) {
                return@withContext Result.failure(RuntimeException("nativePrepare returned error code $prepRes"))
            }

            isModelLoaded = true
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

    fun inferStream(
        userPrompt: String,
        systemPrompt: String = "You are Senda AI, a private, accurate, and ethical assistant running directly on-device.",
        maxTokens: Int = 512
    ): Flow<String> = flow {
        if (!isModelLoaded) {
            emit("Error: No GGUF neural model loaded in native memory.")
            return@flow
        }

        _status.value = EngineStatus.Inferring
        try {
            val sysRes = nativeProcessSystemPrompt(systemPrompt)
            if (sysRes != 0) {
                emit("Error: Failed to process system prompt (code $sysRes)")
                return@flow
            }

            val userRes = nativeProcessUserPrompt(userPrompt, maxTokens)
            if (userRes != 0) {
                emit("Error: Failed to process user prompt (code $userRes)")
                return@flow
            }

            var tokenCount = 0
            while (tokenCount < maxTokens) {
                val token = nativeGenerateNextToken() ?: break
                if (token.isNotEmpty()) {
                    emit(token)
                }
                tokenCount++
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Exception during native inference", t)
            emit("\n[Native Inference Error: ${t.message}]")
        } finally {
            _status.value = EngineStatus.Ready
        }
    }.flowOn(llamaDispatcher)

    fun unload() {
        llamaScope.launch {
            if (isModelLoaded) {
                nativeUnload()
                isModelLoaded = false
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
