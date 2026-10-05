package org.senda.browser.core.ai

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.senda.browser.core.PreferencesManager
import org.senda.browser.core.ai.llama.SendaLlamaBridge
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Decide, midiendo en cada teléfono, si el modelo local corre en la CPU o en una GPU.
 *
 * Tener GPU no basta: en un moto g34 (Adreno 619, OpenCL) la GPU lee el prompt un 56 % más rápido pero escribe
 * 2,3 veces más lento y ocupa más RAM, así que para conversar gana la CPU. Y hay drivers que devuelven texto
 * basura sin dar error. Por eso cada GPU que ggml haya podido cargar (OpenCL en Adreno, Vulkan en Mali, Xclipse,
 * PowerVR…) se mide contra la CPU con las mismas preguntas en modo determinista, y solo se elige si responde
 * bien y es claramente más rápida. Si algo falla, se queda la CPU: la IA nunca deja de funcionar por esto.
 * Todo ocurre en el teléfono; no se envía nada.
 */
object SendaAiCalibrator {

    private const val TAG = "SendaCalibration"

    // Una sola carga/calibración a la vez (el chat y el diálogo de modelos pueden pedirla juntos)
    private val mutex = Mutex()

    private val _progress = MutableStateFlow<String?>(null)

    /** Paso de la calibración en curso («1/2: CPU»…) o null si no se está midiendo. */
    val progress: StateFlow<String?> = _progress

    /** La GPU debe ahorrar al menos un 15 % del tiempo de una respuesta típica; si no, la CPU es más estable. */
    private const val GPU_MIN_GAIN = 0.85

    /** Respuesta típica en una conversación con caché de prefijo: ~60 tokens nuevos que leer y ~60 que escribir. */
    private const val TYPICAL_PROMPT_TOKENS = 60.0
    private const val TYPICAL_ANSWER_TOKENS = 60.0

    data class Measure(
        val device: String,          // "CPU" o nombre ggml de la GPU
        val description: String,
        val promptTps: Double,
        val genTps: Double,
        val correct: Boolean,
        val error: String? = null
    ) {
        /** Segundos estimados para una respuesta típica (infinito si falló). */
        val typicalSeconds: Double
            get() = if (error != null || promptTps <= 0 || genTps <= 0) Double.POSITIVE_INFINITY
            else TYPICAL_PROMPT_TOKENS / promptTps + TYPICAL_ANSWER_TOKENS / genTps
    }

    data class Result(
        val signature: String,
        val chosenDevice: String,    // "CPU" o nombre ggml de la GPU
        val measures: List<Measure>,
        val measuredAt: Long
    ) {
        val usesGpu: Boolean get() = chosenDevice != "CPU"

        fun toJson(): String = JSONObject().apply {
            put("signature", signature)
            put("chosen", chosenDevice)
            put("measuredAt", measuredAt)
            put("measures", JSONArray().apply {
                measures.forEach { m ->
                    put(JSONObject().apply {
                        put("device", m.device); put("description", m.description)
                        put("promptTps", m.promptTps); put("genTps", m.genTps)
                        put("correct", m.correct); m.error?.let { put("error", it) }
                    })
                }
            })
        }.toString()

        companion object {
            fun fromJson(json: String): Result? = try {
                val o = JSONObject(json)
                val arr = o.getJSONArray("measures")
                Result(
                    signature = o.getString("signature"),
                    chosenDevice = o.getString("chosen"),
                    measuredAt = o.getLong("measuredAt"),
                    measures = (0 until arr.length()).map { i ->
                        val m = arr.getJSONObject(i)
                        Measure(
                            m.getString("device"), m.getString("description"), m.getDouble("promptTps"),
                            m.getDouble("genTps"), m.getBoolean("correct"), m.optString("error").ifBlank { null }
                        )
                    }
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Firma de lo que hace válida una medición: modelo, dispositivos que ggml cargó y fecha de instalación o
     * actualización de la app (una versión nueva de llama.cpp o del driver puede cambiar el resultado).
     */
    fun signature(context: Context, model: AiModelDefinition, devices: List<SendaLlamaBridge.ComputeDevice>): String {
        val updated = try {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        } catch (_: Exception) {
            0L
        }
        return "${model.id}|$updated|" + devices.joinToString(";") { "${it.type}:${it.name}:${it.description}" }
    }

    /** Última calibración guardada, si sigue siendo válida para este modelo, app y dispositivos. */
    fun storedResult(context: Context, prefs: PreferencesManager, model: AiModelDefinition): Result? {
        val stored = prefs.aiCalibration?.let { Result.fromJson(it) } ?: return null
        return stored.takeIf { it.signature == signature(context, model, SendaLlamaBridge.listDevices()) }
    }

    /**
     * Carga el modelo con la configuración calibrada. Si hay alguna GPU y no hay medición válida, mide primero
     * ([onProgress] recibe un texto corto para la interfaz). Sin GPU no mide nada: CPU directamente.
     */
    suspend fun loadConfigured(
        context: Context,
        prefs: PreferencesManager,
        model: AiModelDefinition,
        modelFile: File,
        onProgress: (String) -> Unit = {}
    ): kotlin.Result<String> = mutex.withLock {
        val devices = SendaLlamaBridge.listDevices()
        val gpus = devices.filter { it.isGpu }
        val result = when {
            gpus.isEmpty() -> null
            else -> storedResult(context, prefs, model) ?: try {
                calibrate(context, prefs, model, modelFile, gpus) { step -> _progress.value = step; onProgress(step) }
            } catch (c: kotlinx.coroutines.CancellationException) {
                // Medición a medias: el modelo puede haber quedado cargado en la GPU sin calibrar; se descarga
                // para que la próxima pregunta vuelva a pasar por aquí
                SendaLlamaBridge.unload()
                throw c
            } finally {
                _progress.value = null
            }
        }
        apply(result)
        // Ya cargado con esta misma configuración (otra pantalla lo cargó mientras se esperaba el turno)
        if (SendaLlamaBridge.isReadyForInference &&
            SendaLlamaBridge.loadedConfig == "${modelFile.absolutePath}|${SendaLlamaBridge.gpuLayers}|${SendaLlamaBridge.gpuDevice}"
        ) {
            return@withLock kotlin.Result.success("already loaded")
        }
        SendaLlamaBridge.loadModel(modelFile)
    }

    private fun apply(result: Result?) {
        if (result != null && result.usesGpu) {
            SendaLlamaBridge.gpuLayers = 999
            SendaLlamaBridge.gpuDevice = result.chosenDevice
        } else {
            SendaLlamaBridge.gpuLayers = 0
            SendaLlamaBridge.gpuDevice = null
        }
    }

    /** Olvida la medición guardada, vuelve a medir (si hay GPU) y deja el modelo cargado con lo elegido. */
    suspend fun recalibrate(context: Context, prefs: PreferencesManager, model: AiModelDefinition, modelFile: File): kotlin.Result<String> {
        prefs.aiCalibration = null
        return loadConfigured(context, prefs, model, modelFile)
    }

    /** Mide la CPU y cada GPU, elige y guarda. Siempre deja gpuLayers/gpuDevice en un estado válido. */
    private suspend fun calibrate(
        context: Context,
        prefs: PreferencesManager,
        model: AiModelDefinition,
        modelFile: File,
        gpus: List<SendaLlamaBridge.ComputeDevice> = SendaLlamaBridge.listDevices().filter { it.isGpu },
        onProgress: (String) -> Unit = {}
    ): Result {
        val measures = mutableListOf<Measure>()
        val candidates = listOf<SendaLlamaBridge.ComputeDevice?>(null) + gpus
        for ((i, gpu) in candidates.withIndex()) {
            val label = gpu?.description ?: "CPU"
            onProgress("${i + 1}/${candidates.size}: $label")
            measures += measureOne(modelFile, gpu)
            Log.i(TAG, "medido ${measures.last()}")
        }
        val cpu = measures.first()
        val bestGpu = measures.drop(1).filter { it.correct && it.error == null }.minByOrNull { it.typicalSeconds }
        val chosen = if (bestGpu != null && bestGpu.typicalSeconds < cpu.typicalSeconds * GPU_MIN_GAIN) bestGpu.device else "CPU"
        val result = Result(signature(context, model, SendaLlamaBridge.listDevices()), chosen, measures, System.currentTimeMillis())
        prefs.aiCalibration = result.toJson()
        Log.i(TAG, "elegido: $chosen")
        return result
    }

    private suspend fun measureOne(modelFile: File, gpu: SendaLlamaBridge.ComputeDevice?): Measure {
        val name = gpu?.name ?: "CPU"
        val description = gpu?.description ?: "CPU"
        return try {
            SendaLlamaBridge.gpuLayers = if (gpu == null) 0 else 999
            SendaLlamaBridge.gpuDevice = gpu?.name
            val load = SendaLlamaBridge.loadModel(modelFile)
            if (load.isFailure) return Measure(name, description, 0.0, 0.0, false, "carga: ${load.exceptionOrNull()?.message}")
            SendaLlamaBridge.setTemperature(0f)

            // Calentamiento: la primera pasada incluye leer pesos del almacenamiento y compilar kernels
            run(SYSTEM, "Hola.", 4)

            // Lectura: un texto de ~100 tokens, sin reutilizar nada (sistema distinto en cada pasada)
            val read = run("$SYSTEM ${System.nanoTime()}", READING_TEXT, 1)
            val promptTokens = SendaLlamaBridge.lastDecodedTokens()
            val promptTps = if (read.firstTokenMs > 0) promptTokens * 1000.0 / read.firstTokenMs else 0.0

            // Escritura: tokens por segundo tras el primero
            val gen = run(SYSTEM, "Escribe los números del uno al treinta en palabras, separados por comas.", 32)
            val genTps = if (gen.tokens > 1 && gen.afterFirstMs > 0) (gen.tokens - 1) * 1000.0 / gen.afterFirstMs else 0.0

            // Corrección: respuestas cortas y comprobables; un driver que devuelve basura no las acierta
            val capital = run(SYSTEM, "¿Cuál es la capital de Francia? Responde solo con el nombre de la ciudad.", 12).text
            val sum = run(SYSTEM, "¿Cuánto es 7 más 5? Responde solo con el número.", 8).text
            val correct = (capital.contains("París", true) || capital.contains("Paris", true)) && Regex("\\b12\\b").containsMatchIn(sum)
            Log.i(TAG, "$name: capital=«$capital» suma=«$sum»")
            Measure(name, description, promptTps, genTps, correct)
        } catch (c: kotlinx.coroutines.CancellationException) {
            // Cerrar la pantalla durante la medición la cancela: no es un fallo de la GPU y no se guarda nada
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "fallo midiendo $name", t)
            Measure(name, description, 0.0, 0.0, false, t.message ?: t.javaClass.simpleName)
        } finally {
            withContext(NonCancellable) { try { SendaLlamaBridge.setTemperature(0.3f) } catch (_: Exception) {} }
        }
    }

    private class Run(val text: String, val tokens: Int, val firstTokenMs: Long, val afterFirstMs: Long)

    private suspend fun run(system: String, user: String, maxTokens: Int): Run {
        val start = System.nanoTime()
        var first = 0L
        var n = 0
        val sb = StringBuilder()
        SendaLlamaBridge.inferStream(userPrompt = user, systemPrompt = system, maxTokens = maxTokens).collect { t ->
            if (n++ == 0) first = System.nanoTime()
            sb.append(t)
        }
        val end = System.nanoTime()
        return Run(
            SendaInferenceEngine.stripReasoning(sb.toString()), n,
            if (first > 0) (first - start) / 1_000_000 else 0, if (first > 0) (end - first) / 1_000_000 else 0
        )
    }

    private const val SYSTEM = "Eres un asistente. Responde en español, breve y exacto."

    private const val READING_TEXT = "Lee este texto y responde solo «leído». La Biblioteca Municipal de Villanueva abrió " +
        "en 1987 y cuenta con 42.000 volúmenes, una sala infantil, una hemeroteca con prensa local desde 1950 y un " +
        "archivo de fotografías antiguas del pueblo. Abre de lunes a viernes de 9:00 a 20:00 y los sábados de 10:00 " +
        "a 14:00. El carné es gratuito para los residentes empadronados y cuesta 15 euros al año para los demás. " +
        "Organiza clubes de lectura los martes y talleres de escritura los jueves por la tarde."
}
