package org.senda.browser

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.senda.browser.core.PreferencesManager
import org.senda.browser.core.ai.SendaAiCalibrator
import org.senda.browser.core.ai.SendaAiModels
import org.senda.browser.core.ai.SendaInferenceEngine
import org.senda.browser.core.ai.SendaModelManager
import org.senda.browser.core.ai.llama.SendaLlamaBridge

/**
 * Calibración CPU/GPU en el teléfono real: detección, medición, decisión guardada y respuesta posterior.
 * Necesita el modelo descargado; si no está, se omite. Resultados en logcat (SendaCalibration y SendaCalibTest).
 */
@RunWith(AndroidJUnit4::class)
class SendaCalibrationTest {

    private fun log(msg: String) = Log.i("SendaCalibTest", msg)

    @Test
    fun calibratesAndAnswers() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = PreferencesManager(context)
        val model = SendaAiModels.MODEL_GEMMA_4_E2B
        val file = SendaModelManager.getModelFile(context, model)
        assumeTrue("Modelo no descargado", SendaModelManager.isModelReady(context, model))

        SendaLlamaBridge.initialize(context)
        var waits = 0
        while (!SendaLlamaBridge.isAvailable && waits++ < 50) delay(100)

        val devices = SendaLlamaBridge.listDevices()
        devices.forEach { log("dispositivo: $it") }
        assertTrue("Debe haber al menos la CPU", devices.any { it.type == "CPU" })

        val t0 = System.currentTimeMillis()
        val load = SendaAiCalibrator.recalibrate(context, prefs, model, file)
        log("calibración + carga: ${System.currentTimeMillis() - t0} ms, ok=${load.isSuccess} ${load.exceptionOrNull()?.message ?: ""}")
        assertTrue(load.isSuccess)

        val gpus = devices.filter { it.isGpu }
        val result = SendaAiCalibrator.storedResult(context, prefs, model)
        if (gpus.isEmpty()) {
            log("sin GPU: no se mide, CPU directa")
        } else {
            assertNotNull("Con GPU la calibración debe quedar guardada", result)
            result!!.measures.forEach { log("medida: $it  típica=${"%.1f".format(it.typicalSeconds)} s") }
            log("elegido: ${result.chosenDevice}")
            val cpu = result.measures.first { it.device == "CPU" }
            assertTrue("La CPU debe responder bien a las preguntas de control", cpu.correct)
            assertEquals("Una medida por candidato (CPU + GPUs)", gpus.size + 1, result.measures.size)
            assertEquals(if (result.usesGpu) 999 else 0, SendaLlamaBridge.gpuLayers)
        }

        // Segunda carga: debe reutilizar la medición guardada, sin volver a medir
        val t1 = System.currentTimeMillis()
        SendaAiCalibrator.loadConfigured(context, prefs, model, file)
        log("segunda carga (sin medir): ${System.currentTimeMillis() - t1} ms")

        val res = SendaInferenceEngine.chat(userMessage = "¿Cuál es la capital de Australia?", backendMode = "CHIP")
        log("respuesta (${res.latencyMs} ms): ${res.outputText}")
        assertTrue(res.outputText.contains("Canberra", ignoreCase = true))
        SendaLlamaBridge.unload()
    }
}
