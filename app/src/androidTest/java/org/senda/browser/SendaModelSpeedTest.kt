package org.senda.browser

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.senda.browser.core.ai.ChatSender
import org.senda.browser.core.ai.SendaChatMessage
import org.senda.browser.core.ai.SendaInferenceEngine
import org.senda.browser.core.ai.llama.SendaLlamaBridge
import java.io.File

/**
 * Mide en el teléfono la velocidad y la memoria de un modelo GGUF por el mismo camino que usa la app
 * (SendaInferenceEngine.chat → plantilla del modelo → historial). Solo corre si se le pasa el archivo:
 * adb shell am instrument -w -e model gemma-4-e2b-it-q4_k_m.gguf -e class org.senda.browser.SendaModelSpeedTest …
 * Resultados en logcat con la etiqueta SendaSpeed.
 */
@RunWith(AndroidJUnit4::class)
class SendaModelSpeedTest {

    private fun log(msg: String) = Log.i("SendaSpeed", msg)

    private fun rssMb(): Long = File("/proc/self/status").readLines()
        .firstOrNull { it.startsWith("VmRSS:") }?.split(Regex("\\s+"))?.get(1)?.toLong()?.div(1024) ?: -1

    @Test
    fun measureModelOnDevice() = runBlocking {
        val fileName = InstrumentationRegistry.getArguments().getString("model")
        assumeTrue("Sin argumento «model»", fileName != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelFile = File(context.filesDir, "models_ai/$fileName")
        assertTrue("No existe ${modelFile.absolutePath}", modelFile.exists())

        SendaLlamaBridge.initialize(context)
        var waits = 0
        while (!SendaLlamaBridge.isAvailable && waits++ < 50) delay(100)

        InstrumentationRegistry.getArguments().getString("load")?.split("/")?.let {
            SendaLlamaBridge.loadMode = it[0].toInt(); SendaLlamaBridge.lazyMode = it[1].toInt()
        }
        val rssBefore = rssMb()
        val t0 = System.currentTimeMillis()
        val load = SendaLlamaBridge.loadModel(modelFile)
        assertTrue("Carga fallida: ${load.exceptionOrNull()?.message}", load.isSuccess)
        log("modelo=$fileName load=${SendaLlamaBridge.loadMode}/${SendaLlamaBridge.lazyMode} carga=${System.currentTimeMillis() - t0} ms rss=${rssBefore}→${rssMb()} MB")

        val page = "La Biblioteca Municipal de Villanueva abrió en 1987. Cuenta con 42.000 volúmenes y una sala " +
            "infantil. Abre de lunes a viernes de 9:00 a 20:00 y los sábados de 10:00 a 14:00. El carné es " +
            "gratuito para residentes empadronados; los no residentes pagan 15 euros al año."
        val history = listOf(
            SendaChatMessage(sender = ChatSender.USER, text = "¿Quién escribió Cien años de soledad?"),
            SendaChatMessage(sender = ChatSender.ASSISTANT, text = "La escribió Gabriel García Márquez y se publicó en 1967.")
        )
        data class Case(val id: String, val q: String, val hist: List<SendaChatMessage> = emptyList(), val page: String? = null)
        val cases = listOf(
            Case("corta", "¿Es legal presentar un derecho de petición en Colombia?"),
            Case("historial", "¿Y ganó el Nobel? ¿En qué año?", history),
            Case("pagina", "¿Abren los sábados y cuánto paga alguien que no vive allí?", page = page),
            Case("larga", "Redáctame una carta breve y formal a la administración de mi edificio pidiendo que reparen una gotera en mi baño.")
        )
        // Lectura del prompt (hasta el primer token) frente a escritura (tokens por segundo después), con los
        // hilos pedidos en -e threads "gen/lectura,gen/lectura" (por defecto los de la app)
        val threadSets = InstrumentationRegistry.getArguments().getString("threads")?.split(",")
            ?.map { it.split("/").let { p -> p[0].toInt() to p[1].toInt() } } ?: listOf(null)
        for (ts in threadSets) {
        if (ts != null) SendaLlamaBridge.setThreads(ts.first, ts.second)
        repeat(2) { round ->
            val tStart = System.currentTimeMillis()
            var tFirst = 0L
            var n = 0
            SendaLlamaBridge.inferStream(
                userPrompt = "Contexto de la página web actual (Biblioteca):\n$page\n\n¿Abren los sábados?",
                systemPrompt = "Eres Senda AI. Responde en español, breve y preciso.",
                maxTokens = 64
            ).collect { if (n++ == 0) tFirst = System.currentTimeMillis() }
            val tEnd = System.currentTimeMillis()
            log("[directo$round hilos=${ts ?: "app"}] primer token ${tFirst - tStart} ms; $n tokens; escritura " +
                "%.2f tok/s".format((n - 1) * 1000.0 / (tEnd - tFirst).coerceAtLeast(1)))
        }
        }
        if (InstrumentationRegistry.getArguments().getString("onlyDirect") == "1") { SendaLlamaBridge.unload(); return@runBlocking }
        for (c in cases) {
            val res = SendaInferenceEngine.chat(
                userMessage = c.q, history = c.hist, pageTitle = "Biblioteca", pageContent = c.page,
                includePageContext = c.page != null, backendMode = "CHIP"
            )
            log("[${c.id}] ${res.latencyMs} ms, ~${res.tokensGenerated} tokens, rss=${rssMb()} MB, backend=${res.executionBackend}")
            log("[${c.id}] respuesta: ${res.outputText.replace('\n', ' ')}")
        }
        SendaLlamaBridge.unload()
    }
}
