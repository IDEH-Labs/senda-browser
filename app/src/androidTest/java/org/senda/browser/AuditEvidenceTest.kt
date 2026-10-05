package org.senda.browser

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.senda.browser.core.PreferencesManager
import org.senda.browser.core.ai.ChatSender
import org.senda.browser.core.ai.SendaAiModels
import org.senda.browser.core.ai.SendaChatMessage
import org.senda.browser.core.ai.SendaHeuristicsEvaluator
import org.senda.browser.core.ai.SendaInferenceEngine
import org.senda.browser.core.ai.SendaModelManager
import org.senda.browser.core.ai.llama.SendaLlamaBridge
import org.senda.browser.core.security.SendaVaultAuditRunner
import org.senda.browser.core.security.SendaVaultManager
import org.senda.browser.core.SendaStrings

/**
 * Pruebas de evidencia de la auditoría del 2026-10-04. No afirman nada: registran en logcat (etiqueta AUDIT)
 * lo que el código hace de verdad, para que cada hallazgo se apoye en una salida observable.
 */
@RunWith(AndroidJUnit4::class)
class AuditEvidenceTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun log(name: String, value: String) {
        value.lines().forEach { Log.i("AUDIT", "[$name] $it") }
    }

    private fun chat(prompt: String, history: List<SendaChatMessage> = emptyList(), title: String? = null, content: String? = null) =
        runBlocking {
            SendaInferenceEngine.chat(
                userMessage = prompt,
                history = history,
                pageTitle = title,
                pageContent = content,
                includePageContext = content != null,
                backendMode = "CHIP"
            )
        }

    @Test
    fun a1_fallbackSummaryIgnoresPageContent() {
        log("a1", "modelo cargado=${SendaLlamaBridge.isReadyForInference}")
        val a = chat("resume esta pagina", title = "Receta", content = "La receta lleva harina, huevos y azúcar. Se hornea 40 minutos a 180 grados. Rinde ocho porciones.")
        val b = chat("resume esta pagina", title = "Receta", content = "El tribunal condenó al exalcalde a doce años de prisión por peculado. La defensa apelará la sentencia el lunes.")
        log("a1", "backend=${a.executionBackend}")
        log("a1", "salidaA==salidaB: ${a.outputText == b.outputText}")
        log("a1", "salidaA:\n${a.outputText}")
    }

    @Test
    fun a2_confirmsWrongAnswer() {
        val history = listOf(
            SendaChatMessage(sender = ChatSender.USER, text = "¿Cuál es la capital de Australia?"),
            SendaChatMessage(sender = ChatSender.ASSISTANT, text = "La capital de Australia es Sídney.")
        )
        val r = chat("¿estás seguro?", history)
        log("a2", "respuesta a «¿estás seguro?» tras «capital de Australia es Sídney»:\n${r.outputText}")
        val r2 = chat("¿estás seguro?", listOf(
            SendaChatMessage(sender = ChatSender.USER, text = "háblame de la vacuna"),
            SendaChatMessage(sender = ChatSender.ASSISTANT, text = "### Vacuna\nLa vacuna causa autismo.")
        ))
        log("a2", "respuesta tras afirmación falsa sobre vacunas:\n${r2.outputText}")
    }

    @Test
    fun a3_calculator() {
        listOf("2 + 3 * 4", "2,5 + 1", "1.000 + 1", "-5 + 3", "cuánto es 10 / 4 / 2", "el 15,5% de 200").forEach {
            log("a3", "«$it» -> ${SendaHeuristicsEvaluator.tryEvaluateMath(it)}")
        }
        val carta = chat("Redacta una carta de 3 - 4 párrafos para mi jefe")
        log("a3", "«Redacta una carta de 3 - 4 párrafos para mi jefe» -> ${carta.outputText.lines().first()}")
        val torta = chat("¿Cómo hacer una torta de chocolate?")
        log("a3", "«¿Cómo hacer una torta de chocolate?» -> ${torta.outputText.lines().first()}")
    }

    @Test
    fun a4_legalTemplatesInventFacts() {
        val r = chat("Redacta un reclamo a Claro porque me cobraron dos veces la factura de junio")
        log("a4", "backend=${r.executionBackend}")
        log("a4", "menciona «junio»: ${r.outputText.contains("junio")}")
        log("a4", "menciona «dos veces»: ${r.outputText.contains("dos veces")}")
        log("a4", "RECLAMO COMPLETO:\n${r.outputText}")
        r.outputText.lines().filter { it.contains("reiteradas") || it.contains("Incumplimiento") || it.contains("Falta de Solución") }
            .forEach { log("a4", "hecho insertado: $it") }
        val p = chat("Escribe un derecho de petición a la alcaldía pidiendo copia de mi expediente")
        p.outputText.lines().filter { it.contains("agotado") || it.contains("Afectación") }.forEach { log("a4", "hecho insertado: $it") }
    }

    @Test
    fun a4b_suggestedStarters() {
        listOf("⚖️ Escribe un derecho de petición sobre cobro indebido", "Escribe una queja por cobro indebido de mi operador").forEach { prompt ->
            val r = chat(prompt)
            log("a4b", "PROMPT: $prompt -> backend=${r.executionBackend}")
            r.outputText.lines().filter { it.contains("reiteradas") || it.contains("agotado") || it.contains("Incumplimiento") || it.contains("Falta de Soluci") || it.startsWith("# ") }
                .forEach { log("a4b", "  $it") }
        }
    }

    @Test
    fun a5_privacyAuditVerdict() {
        val r = chat("audita la privacidad de esta pagina", title = "Política", content = "Vendemos tu historial de ubicación y tus contactos a anunciantes y aseguradoras sin pedir permiso.")
        log("a5", r.outputText)
    }

    @Test
    fun a6_vaultGeneratorAndAudit() {
        val n = 20000
        var noDigit = 0; var failsAudit = 0
        repeat(n) {
            val (p, entropy) = SendaVaultManager.generateStrongPassword(length = 20)
            val hasDigit = p.any { it.isDigit() }
            val ok = entropy >= 100.0 && p.any { it.isUpperCase() } && p.any { it.isLowerCase() } && hasDigit &&
                p.any { "!@#$%^&*()-_=+[]{}|;:,.<>?".contains(it) }
            if (!hasDigit) noDigit++
            if (!ok) failsAudit++
        }
        log("a6", "contraseñas de 20 sin dígito: $noDigit/$n = ${"%.2f".format(100.0 * noDigit / n)} %")
        log("a6", "contraseñas que harían fallar la prueba de entropía: $failsAudit/$n = ${"%.2f".format(100.0 * failsAudit / n)} %")
        val strings = SendaStrings.get("ES", context)
        var auditFails = 0
        val runs = 15
        repeat(runs) {
            val rep = SendaVaultAuditRunner.runFullAudit(context, strings, stressCycles = 5)
            if (rep.results.any { !it.passed && it.name == strings.audit_entropy_name }) auditFails++
            if (it == 0) rep.results.forEach { item -> log("a6", "auditoría: ${item.name} -> ${if (item.passed) "OK" else "FALLA"} (${item.details})") }
        }
        log("a6", "ejecuciones reales de la auditoría con fallo de entropía: $auditFails/$runs")
    }

    @Test
    fun a7_historyClearRace() {
        DestructiveTestGuard.requireExplicitPermission("borra el historial")
        val prefs = PreferencesManager(context)
        prefs.clearHistory()
        Thread.sleep(500)
        repeat(30) { prefs.addHistoryItem("Página $it", "https://ejemplo.org/$it") }
        prefs.clearHistory()
        Thread.sleep(3000)
        log("a7", "entradas que quedaron tras «borrar historial»: ${prefs.getHistory().size}")
        prefs.clearHistory()
    }

    @Test
    fun b1_fastNativeBlocksGc() {
        val model = SendaModelManager.getModelFile(context, SendaAiModels.MODEL_GEMMA_4_E2B)
        log("b1", "modelo=${model.name} existe=${model.exists()} tamaño=${model.length()}")
        if (!model.exists()) return
        repeat(30) { if (!SendaLlamaBridge.isAvailable) Thread.sleep(200) }
        val gcIdle = measure { Runtime.getRuntime().gc() }
        log("b1", "GC con el hilo nativo inactivo: $gcIdle ms")
        var loadMs = 0L
        val t = Thread { loadMs = measure { runBlocking { SendaLlamaBridge.loadModel(model) } } }
        t.start()
        Thread.sleep(400)
        val gcDuringLoad = measure { Runtime.getRuntime().gc() }
        t.join()
        log("b1", "carga del modelo (nativeLoadModel + nativePrepare con @FastNative): $loadMs ms")
        log("b1", "GC lanzado 400 ms después de empezar la carga tardó: $gcDuringLoad ms")
    }

    @Test
    fun b2_emojiThroughNewStringUtf() {
        val model = SendaModelManager.getModelFile(context, SendaAiModels.MODEL_GEMMA_4_E2B)
        if (!model.exists()) return
        repeat(30) { if (!SendaLlamaBridge.isAvailable) Thread.sleep(200) }
        runBlocking { SendaLlamaBridge.loadModel(model) }
        log("b2", "CheckJNI activo (app depurable): ${context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0}")
        val out = StringBuilder()
        runBlocking {
            SendaLlamaBridge.inferStream("Responde solamente con tres emojis de caras sonrientes, sin texto.", maxTokens = 24)
                .collect { out.append(it) }
        }
        log("b2", "salida del modelo: «$out»")
        log("b2", "puntos de código > U+FFFF en la salida: ${out.codePoints().filter { it > 0xFFFF }.count()}")
        log("b2", "caracteres de reemplazo o sustitutos sueltos: ${out.count { it == '�' || (Character.isSurrogate(it) && !Character.isSurrogatePair(it, it)) }}")
    }

    private inline fun measure(block: () -> Unit): Long {
        val start = System.nanoTime(); block(); return (System.nanoTime() - start) / 1_000_000
    }
}
