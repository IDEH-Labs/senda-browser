package org.senda.browser

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.senda.browser.core.PreferencesManager
import org.senda.browser.core.ai.ChatSender
import org.senda.browser.core.ai.SendaAiCalibrator
import org.senda.browser.core.ai.SendaAiModels
import org.senda.browser.core.ai.SendaChatMessage
import org.senda.browser.core.ai.SendaInferenceEngine
import org.senda.browser.core.ai.SendaModelManager
import org.senda.browser.core.ai.llama.SendaLlamaBridge

/**
 * La caché de prefijo debe sobrevivir al cambio de minuto (antes la hora iba en las instrucciones y cada
 * minuto invalidaba todo). Pregunta, espera 65 s, repregunta con historial; el resultado se lee en logcat:
 * «Conversation: N tokens, M reused from cache» (SendaLlamaJNI) y SendaCacheTest.
 */
@RunWith(AndroidJUnit4::class)
class SendaPrefixCacheTest {

    @Test
    fun cacheSurvivesMinuteChange() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = SendaAiModels.MODEL_GEMMA_4_E2B
        assumeTrue("Modelo no descargado", SendaModelManager.isModelReady(context, model))
        SendaLlamaBridge.initialize(context)
        var waits = 0
        while (!SendaLlamaBridge.isAvailable && waits++ < 50) delay(100)
        assumeTrue(SendaAiCalibrator.loadConfigured(context, PreferencesManager(context), model, SendaModelManager.getModelFile(context, model)).isSuccess)

        val q1 = "¿Quién escribió Cien años de soledad?"
        val a1 = SendaInferenceEngine.chat(userMessage = q1, backendMode = "CHIP").outputText
        Log.i("SendaCacheTest", "1: $a1")
        delay(65_000)
        val history = listOf(SendaChatMessage(sender = ChatSender.USER, text = q1), SendaChatMessage(sender = ChatSender.ASSISTANT, text = a1))
        val r2 = SendaInferenceEngine.chat(userMessage = "¿En qué año se publicó?", history = history, backendMode = "CHIP")
        Log.i("SendaCacheTest", "2 (${r2.latencyMs} ms): ${r2.outputText}")
        SendaLlamaBridge.unload()
    }
}
