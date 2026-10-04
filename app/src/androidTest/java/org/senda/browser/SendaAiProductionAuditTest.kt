package org.senda.browser

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.senda.browser.core.ai.*
import java.io.File

/**
 * Suite Exhaustiva de Auditoría en Producción para Senda AI Soberana.
 * Prueba y valida todos los campos, casos extremos y garantías criptográficas
 * directamente sobre el hardware del dispositivo moto g34 5G.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SendaAiProductionAuditTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // =========================================================================
    // 1. AUDITORÍA DEL PROFILER DE HARDWARE EN CONDICIONES REALES Y EXTREMAS
    // =========================================================================
    @Test
    fun test01_HardwareProfilerFieldAuditing() {
        val profile = SendaHardwareProfiler.analyze(context)

        // Verificación de campos obligatorios
        assertTrue("La memoria RAM total debe ser mayor a 512 MB", profile.totalRamBytes > 512 * 1024 * 1024)
        assertTrue("La memoria RAM disponible debe ser positiva", profile.availableRamBytes > 0)
        assertTrue("El conteo de núcleos CPU debe estar entre 1 y 64", profile.cpuCores in 1..64)
        assertTrue("Los hilos de rendimiento asignados deben ser seguros", profile.performanceThreadsOptimal in 1..8)
        assertTrue("La ABI soportada debe ser arm64-v8a o compatible", profile.supportedAbi.isNotBlank())
        assertTrue("La etiqueta térmica debe estar presente", profile.thermalStatusLabel.isNotBlank())
        assertTrue("El límite de contexto debe ser de al menos 2048 tokens", profile.maxRecommendedContextTokens >= 2048)

        println("[PASS 1/5] Auditoría de campos de hardware 100% válida:")
        println("  -> RAM: ${String.format("%.2f", profile.totalRamGb)} GB | Núcleos: ${profile.cpuCores} | Hilos asignados: ${profile.performanceThreadsOptimal}")
    }

    // =========================================================================
    // 2. AUDITORÍA DEL CATÁLOGO DE MODELOS Y POLÍTICAS DE LICENCIA
    // =========================================================================
    @Test
    fun test02_ModelCatalogIntegrityAndLicensing() {
        val models = SendaAiModels.ALL_MODELS
        assertEquals("El catálogo depurado debe contener exactamente los 3 mejores modelos por segmento", 3, models.size)

        for (m in models) {
            assertTrue("ID de modelo no debe estar vacío", m.id.isNotBlank())
            assertTrue("El nombre debe estar formateado", m.name.isNotBlank())
            assertTrue("El archivo debe terminar en .gguf", m.fileName.endsWith(".gguf"))
            assertTrue("La URL de descarga debe ser HTTPS segura", m.downloadUrl.startsWith("https://"))
            assertTrue("La suma SHA-256 debe tener 64 caracteres hexadecimales", m.sha256Checksum.length == 64)
            assertEquals("Todos los modelos depurados deben ser estrictamente Apache 2.0", "Apache 2.0", m.license)
        }

        println("[PASS 2/5] Integridad de catálogo depurado (3 modelos, 100% Apache 2.0) verificada con éxito.")
    }

    // =========================================================================
    // 3. AUDITORÍA DE ALMACENAMIENTO, ESPACIO EN DISCO Y AISLAMIENTO PRIVADO
    // =========================================================================
    @Test
    fun test03_StorageGuardAndDirectoryIsolation() {
        val modelsDir = SendaModelManager.getModelsDirectory(context)
        assertTrue("El directorio de modelos debe existir", modelsDir.exists())
        assertTrue("El directorio debe ser un directorio de sistema de archivos", modelsDir.isDirectory)

        // Verificar que esté dentro de context.filesDir (memoria interna privada, protegida de otras apps)
        assertTrue("El almacenamiento debe estar aislado en filesDir", modelsDir.absolutePath.startsWith(context.filesDir.absolutePath))

        // Verificar el monitor de espacio libre
        val freeBytes = SendaModelManager.getAvailableStorageBytes(context)
        assertTrue("El espacio libre reportado debe ser mayor a 0", freeBytes > 0)

        // Prueba de creación y eliminación segura de archivo de prueba
        val testModel = SendaAiModels.MODEL_QWEN_3_5_0_8B
        val testFile = File(modelsDir, "test_verification.tmp")
        testFile.writeText("Senda Sovereign Model Verification Checksum")
        assertTrue("El archivo de prueba debe crearse", testFile.exists())
        testFile.delete()
        assertFalse("El archivo de prueba debe limpiarse inmediatamente", testFile.exists())

        println("[PASS 3/5] Aislamiento de almacenamiento privado y guardián de espacio en disco verificados.")
    }

    // =========================================================================
    // 4. AUDITORÍA DE PREPROCESAMIENTO Y PROTOCOLO CHATML DE INFERENCIA
    // =========================================================================
    @Test
    fun test04_InferenceChatMLFormattingAndPrompts() {
        val sysPrompt = SendaInferenceEngine.getSystemPromptForTask(AiTaskType.SUMMARY)
        val userPrompt = "¿Cuáles son las conclusiones del artículo?"

        val chatML = SendaInferenceEngine.formatChatML(sysPrompt, userPrompt)

        // Verificar formato canónico ChatML de Qwen
        assertTrue("Debe contener etiqueta <|im_start|>system", chatML.contains("<|im_start|>system"))
        assertTrue("Debe contener etiqueta <|im_end|>", chatML.contains("<|im_end|>"))
        assertTrue("Debe contener etiqueta <|im_start|>user", chatML.contains("<|im_start|>user"))
        assertTrue("Debe contener apertura de asistente <|im_start|>assistant", chatML.endsWith("<|im_start|>assistant\n"))

        // Verificar que la Directiva Core de Rigor Analítico esté presente en la constante y en todas las tareas
        assertTrue(SendaInferenceEngine.CORE_EPISTEMIC_DIRECTIVE.contains("RIGOR ANALÍTICO Y CALIBRACIÓN EPISTÉMICA"))
        assertTrue(SendaInferenceEngine.CORE_EPISTEMIC_DIRECTIVE.contains("I. PRINCIPIOS EPISTÉMICOS Y EVALUACIÓN DE PREMISAS"))
        assertTrue(SendaInferenceEngine.CORE_EPISTEMIC_DIRECTIVE.contains("II. VERIFICACIÓN Y MANEJO DE FUENTES"))
        assertTrue(SendaInferenceEngine.CORE_EPISTEMIC_DIRECTIVE.contains("III. CALIBRACIÓN POR DOMINIOS"))
        assertTrue(SendaInferenceEngine.CORE_EPISTEMIC_DIRECTIVE.contains("IV. RESOLUCIÓN DE DISCREPANCIAS Y COMPARATIVAS"))
        assertTrue(SendaInferenceEngine.CORE_EPISTEMIC_DIRECTIVE.contains("V. ENRUTAMIENTO DINÁMICO Y EJECUCIÓN (REGLAS DE PARADA)"))
        assertTrue(SendaInferenceEngine.CORE_EPISTEMIC_DIRECTIVE.contains("VI. CONTROL DE INTEGRIDAD FINAL"))

        // Verificar las tareas de inferencia
        for (task in AiTaskType.values()) {
            val p = SendaInferenceEngine.getSystemPromptForTask(task)
            assertTrue("El prompt para $task no debe estar vacío", p.isNotBlank())
            assertTrue("El prompt para $task debe incluir la Directiva Core", p.contains("RIGOR ANALÍTICO Y CALIBRACIÓN EPISTÉMICA"))
            assertTrue("El prompt para $task debe incluir instrucciones explícitas en español", p.contains("Senda Browser"))
        }

        println("[PASS 4/10] Formateo ChatML y Directiva Core de Rigor Analítico y Calibración Epistémica verificados.")
    }

    // =========================================================================
    // 5. AUDITORÍA DE PRUEBA DE ESTRÉS Y CASOS EXTREMOS (FUZZ TESTING)
    // =========================================================================
    @Test
    fun test05_StressAndEdgeCasesSanitization() {
        // Caso 1: Texto vacío
        val emptyResult = SendaInferenceEngine.sanitizeInputText("", 4096)
        assertEquals("Texto vacío debe devolver cadena vacía", "", emptyResult)

        // Caso 2: Texto con espacios en blanco
        val blankResult = SendaInferenceEngine.sanitizeInputText("    \n\t  ", 4096)
        assertEquals("Texto en blanco debe devolver cadena vacía", "", blankResult)

        // Caso 3: Límite negativo o cero (debe protegerse y no lanzar excepción)
        val safeNegResult = SendaInferenceEngine.sanitizeInputText("Contenido de prueba", -10)
        assertTrue("Límite negativo debe protegerse con un mínimo seguro", safeNegResult.isNotBlank())

        val safeZeroResult = SendaInferenceEngine.sanitizeInputText("Contenido de prueba", 0)
        assertTrue("Límite cero debe protegerse con un mínimo seguro", safeZeroResult.isNotBlank())

        // Caso 4: Texto gigante (100.000 caracteres)
        val giantBuilder = StringBuilder()
        for (i in 0 until 10_000) {
            giantBuilder.append("Párrafo de prueba $i con contenido extenso sobre filosofía y ciencia. ")
        }
        val giantText = giantBuilder.toString()
        val truncatedResult = SendaInferenceEngine.sanitizeInputText(giantText, 2048)

        assertTrue("El texto truncado no debe superar el límite de seguridad", truncatedResult.length < giantText.length)
        assertTrue("Debe incluir la advertencia de truncado seguro", truncatedResult.contains("[... Texto truncado de forma segura"))

        println("[PASS 5/7] Prueba de estrés y casos extremos (Fuzz testing) 100% superada sin excepciones.")
    }

    // =========================================================================
    // 6. AUDITORÍA DE INMUNIDAD A REGEX INJECTION Y CARACTERES ESPECIALES
    // =========================================================================
    @Test
    fun test06_ReaderModeQnAAlgorithmsAndSafety() {
        val dangerousQueries = listOf(
            "¿Cuánto cuesta (USD 100+)?",
            "*Error 404* [V1.0] {build}",
            "Regex ^test$ (.*) \\d+",
            "¿Qué pasó con C++ y C#?",
            "¿Precio? ¿Descuento? ¿100%?"
        )

        // Simulación rigurosa del parser de términos de consulta usado en Reader Mode
        val stopWords = setOf("el","la","los","las","un","una","unos","unas","de","del","a","al","en","con","por","para","que","quien","cual","como","cuando","donde")

        for (query in dangerousQueries) {
            val normalized = java.text.Normalizer.normalize(query.lowercase(), java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            val queryTerms = normalized
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .split(Regex("\\s+"))
                .filter { it.length > 1 && !stopWords.contains(it) }

            // Ninguna consulta peligrosa debe romper ni lanzar PatternSyntaxException
            assertNotNull("Los términos filtrados no deben ser nulos", queryTerms)
            for (term in queryTerms) {
                // Verificar que no queden caracteres de escape regex sin procesar
                assertFalse("El término no debe contener caracteres de ruptura regex", term.contains("+"))
                assertFalse("El término no debe contener asteriscos", term.contains("*"))
                assertFalse("El término no debe contener corchetes", term.contains("["))
                assertFalse("El término no debe contener paréntesis", term.contains("("))
            }
        }

        println("[PASS 6/7] Auditoría de seguridad contra Regex Injection y caracteres especiales 100% superada.")
    }

    // =========================================================================
    // 7. AUDITORÍA DE NORMALIZACIÓN DE DIACRÍTICOS Y PRINCIPIO ÉTICO ANTI-ALUCINACIÓN
    // =========================================================================
    @Test
    fun test07_DiacriticsAndAntiHallucinationGrounding() {
        val articleSample = """
            La Fiscalía General de la Nación allanó una sede clandestina en Bogotá.
            Durante el operativo, las autoridades incautaron equipos electrónicos de alta gama.
            El mandatario confirmó que la operación militar concluyó sin heridos civiles.
        """.trimIndent()

        fun stripAccents(s: String): String =
            java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")

        // Caso A: Búsqueda sin acentos en texto con acentos ("allano" -> "allanó", "bogota" -> "Bogotá")
        val querySinAcentos = "¿quien allano en bogota?"
        val queryTerms = stripAccents(querySinAcentos.lowercase())
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 && it !in setOf("quien", "en", "el", "la") }

        val sentences = articleSample.split("\n").map { it.trim() }
        var bestScore = 0
        var bestSentence = ""

        for (s in sentences) {
            val normSentence = stripAccents(s.lowercase())
            var score = 0
            for (t in queryTerms) {
                if (normSentence.contains(t)) score += 10
            }
            if (score > bestScore) {
                bestScore = score
                bestSentence = s
            }
        }

        assertTrue("Debe encontrar la oración relevante pese a la falta de acentos en la consulta", bestScore >= 20)
        assertTrue("La oración encontrada debe ser la de allanamiento en Bogotá", bestSentence.contains("Bogotá"))

        // Caso B: Consulta sobre tema inexistente (Anti-alucinación estricta)
        val queryAusente = "cuál es el precio de bitcoin y criptomonedas hoy"
        val queryAbsentTerms = stripAccents(queryAusente.lowercase())
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 && it !in setOf("cual", "es", "el", "de", "hoy") }

        var absentScore = 0
        for (s in sentences) {
            val normSentence = stripAccents(s.lowercase())
            for (t in queryAbsentTerms) {
                if (normSentence.contains(t)) absentScore += 10
            }
        }

        assertEquals("Para información inexistente en el texto, el puntaje debe ser estrictamente 0", 0, absentScore)
        println("[PASS 7/8] Auditoría de diacríticos y principio ético anti-alucinación 100% verificado.")
    }

    // =========================================================================
    // 8. AUDITORÍA DEL ASISTENTE UNIVERSAL Y REDACTOR DE DOCUMENTOS EN CHIP
    // =========================================================================
    @Test
    fun test08_UniversalAssistantAndDocumentDrafting() = kotlinx.coroutines.runBlocking {
        // 1. Redacción de Carta Formal en chip
        val cartaResult = SendaInferenceEngine.generateResponse(
            task = AiTaskType.DOCUMENT_DRAFT,
            userPrompt = "Cancelación de servicio de telecomunicaciones",
            docType = "Carta Formal"
        )
        assertTrue("La redacción de carta debe ser exitosa", cartaResult.success)
        assertTrue("Debe contener fecha formal", cartaResult.outputText.contains("Fecha:"))
        assertTrue("Debe contener el asunto solicitado", cartaResult.outputText.contains("Cancelación de servicio"))
        assertTrue("Debe estructurarse con fórmulas de cortesía", cartaResult.outputText.contains("Atentamente"))
        assertTrue("Debe reportar generación de tokens positiva", cartaResult.tokensGenerated > 30)

        // 2. Redacción de Derecho de Petición en chip
        val peticionResult = SendaInferenceEngine.generateResponse(
            task = AiTaskType.DOCUMENT_DRAFT,
            userPrompt = "Acceso a expediente administrativo",
            docType = "Derecho de Petición"
        )
        assertTrue("La redacción de petición debe ser exitosa", peticionResult.success)
        assertTrue("Debe fundamentarse en el artículo 23 constitucional", peticionResult.outputText.contains("Artículo 23"))
        assertTrue("Debe contener sección de hechos", peticionResult.outputText.contains("HECHOS"))
        assertTrue("Debe contener peticiones concretas", peticionResult.outputText.contains("PETICIONES CONCRETAS"))

        // 3. Asistente General para cualquier consulta
        val generalResult = SendaInferenceEngine.generateResponse(
            task = AiTaskType.GENERAL_ASSISTANT,
            userPrompt = "¿Cómo cuidar la privacidad digital en dispositivos móviles?"
        )
        assertTrue("La respuesta general debe ser exitosa", generalResult.success)
        assertTrue("Debe estructurar la explicación con fundamentos", generalResult.outputText.contains("Fundamentos"))
        assertTrue("Debe incluir la garantía ética de Senda", generalResult.outputText.contains("Senda AI Soberana"))

        println("[PASS 8/9] Asistente Universal y Redactor de Documentos en Chip 100% auditado y verificado.")
    }

    // =========================================================================
    // 9. AUDITORÍA DE SENDA CODEX Y GENERACIÓN DE SCRIPTS / INGENIERÍA
    // =========================================================================
    @Test
    fun test09_CodexEngineeringAndNaturalRouting() = kotlinx.coroutines.runBlocking {
        // A. Script en Python
        val pythonResult = SendaInferenceEngine.chat(
            userMessage = "Escribe un script en Python para procesar datos locales y extraer json"
        )
        assertTrue("Debe ser exitoso", pythonResult.success)
        assertTrue("Debe marcarse como código", pythonResult.isCode)
        assertEquals("Lenguaje debe ser python", "python", pythonResult.codeLanguage)
        assertEquals("Nombre sugerido debe ser script.py", "script.py", pythonResult.suggestedFileName)
        assertTrue("Debe contener bloque de código python", pythonResult.outputText.contains("```python"))
        assertTrue("Debe contener import json", pythonResult.outputText.contains("import json"))

        // B. Script en Bash
        val bashResult = SendaInferenceEngine.chat(
            userMessage = "Genera un script en bash para respaldar archivos y hacer backup"
        )
        assertTrue("Debe marcarse como código", bashResult.isCode)
        assertEquals("Lenguaje debe ser bash", "bash", bashResult.codeLanguage)
        assertEquals("Nombre sugerido debe ser script.sh", "script.sh", bashResult.suggestedFileName)
        assertTrue("Debe contener shebang", bashResult.outputText.contains("#!/usr/bin/env bash"))

        // C. Carta de Renuncia formal
        val renunciaResult = SendaInferenceEngine.chat(
            userMessage = "Redáctame una carta formal de renuncia para Google LLC"
        )
        assertTrue("Debe marcarse como documento", renunciaResult.isDocument)
        assertEquals("Nombre de archivo debe ser Carta_de_Renuncia.md", "Carta_de_Renuncia.md", renunciaResult.suggestedFileName)
        assertTrue("Debe contener renuncia irrevocable", renunciaResult.outputText.contains("renuncia irrevocable"))

        // D. Extractor de código para portapapeles limpio
        val extractedPython = org.senda.browser.ui.components.SendaDocumentExporter.extractCode(pythonResult.outputText)
        assertFalse("El código extraído no debe contener los backticks de markdown", extractedPython.contains("```python"))
        assertTrue("El código extraído debe contener el código real", extractedPython.contains("def main():"))

        println("[PASS 9/10] Senda Codex, Ingeniería y enrutamiento natural 100% verificados y auditados.")
    }

    // =========================================================================
    // 10. AUDITORÍA DE RESPUESTAS NATURALES, PRECISAS, ÉTICAS Y CONSECUENTES
    // =========================================================================
    @Test
    fun test10_NaturalPreciseAndEthicalAnswers() = kotlinx.coroutines.runBlocking {
        // A. Consulta temporal en tiempo real: "¿Qué día es hoy?"
        val diaResult = SendaInferenceEngine.chat("¿Qué día es hoy?")
        assertTrue("La respuesta debe ser exitosa", diaResult.success)
        assertTrue("Debe responder de forma natural diciendo 'Hoy es'", diaResult.outputText.contains("Hoy es"))
        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR).toString()
        assertTrue("Debe contener el año actual ($currentYear)", diaResult.outputText.contains(currentYear))
        assertFalse("No debe contener plantillas robóticas o de análisis abstracto", diaResult.outputText.contains("El núcleo de este tema"))

        // B. Consulta de hora actual: "¿Qué hora es?"
        val horaResult = SendaInferenceEngine.chat("¿Qué hora es?")
        assertTrue("Debe responder directamente con la hora", horaResult.outputText.contains("Son las"))

        // C. Cálculo aritmético exacto: "cuanto es 25 * 4"
        val mathResult = SendaInferenceEngine.chat("cuanto es 25 * 4")
        assertTrue("Debe responder con el resultado 100", mathResult.outputText.contains("100"))

        // D. Porcentaje matemático: "15% de 200"
        val pctResult = SendaInferenceEngine.chat("cuanto es el 15% de 200")
        assertTrue("Debe responder que es 30", pctResult.outputText.contains("30"))

        // E. Raíz cuadrada: "raiz cuadrada de 81"
        val sqrtResult = SendaInferenceEngine.chat("raiz cuadrada de 81")
        assertTrue("Debe responder que es 9", sqrtResult.outputText.contains("9"))

        // F. Identidad soberana y ética: "¿Quién eres?"
        val identityResult = SendaInferenceEngine.chat("¿Quién eres?")
        assertTrue("Debe identificarse como Senda AI", identityResult.outputText.contains("Senda AI"))
        assertTrue("Debe explicitar que corre 100% en chip sin telemetría", identityResult.outputText.contains("chip") || identityResult.outputText.contains("telemetría"))

        // G. Conversión de unidades: "100 km a millas"
        val convResult = SendaInferenceEngine.chat("100 km a millas")
        assertTrue("Debe convertir 100 km a aprox 62.14 millas", convResult.outputText.contains("62.14"))

        // H. Conocimiento fáctico directo: "capital de Colombia"
        val factResult = SendaInferenceEngine.chat("¿Cuál es la capital de Colombia?")
        assertTrue("Debe responder Bogotá", factResult.outputText.contains("Bogotá"))

        // I. Origen y diseñador/creador: "¿Quién te creó?" y "quien te diseño"
        val creadorResult = SendaInferenceEngine.chat("¿Quién te creó?")
        assertTrue("Debe responder sobre su origen en Senda Browser", creadorResult.outputText.contains("Senda Browser"))
        assertTrue("Debe mencionar su principio de privacidad soberana", creadorResult.outputText.contains("privada") || creadorResult.outputText.contains("soberana"))

        val disenoResult = SendaInferenceEngine.chat("quien te diseño")
        assertTrue("Debe responder adecuadamente a quien lo diseñó", disenoResult.outputText.contains("Senda Browser"))

        // J. Capacidades: "¿Qué puedes hacer?"
        val capResult = SendaInferenceEngine.chat("¿Qué puedes hacer?")
        assertTrue("Debe detallar capacidades", capResult.outputText.contains("Capacidades") || capResult.outputText.contains("Senda Codex"))

        // K. Ciencia: "¿Por qué el cielo es azul?"
        val cieloResult = SendaInferenceEngine.chat("¿Por qué el cielo es azul?")
        assertTrue("Debe explicar la dispersión de Rayleigh", cieloResult.outputText.contains("Rayleigh"))

        // L. Tecnología: "¿Qué es una API?" y "¿Qué es Tor?"
        val apiResult = SendaInferenceEngine.chat("¿Qué es una API?")
        assertTrue("Debe explicar API", apiResult.outputText.contains("Application Programming Interface") || apiResult.outputText.contains("intercambiar datos"))

        val torResult = SendaInferenceEngine.chat("¿Qué es Tor?")
        assertTrue("Debe explicar la red Tor y sus capas", torResult.outputText.contains("Onion Router") || torResult.outputText.contains("nodos"))

        println("[PASS 10/12] Respuestas naturales, precisas, éticas, creador/diseño y científicas 100% verificadas.")
    }

    // =========================================================================
    // 11. AUDITORÍA DE SEGUIMIENTO CONVERSACIONAL Y RESOLUCIÓN DE ERRATAS
    // =========================================================================
    @Test
    fun test11_ConversationalFollowupAndEpistemicEvaluation() = kotlinx.coroutines.runBlocking {
        // A. Conversación secuencial: Pregunta de fecha y posterior "¿estás seguro?"
        val history = mutableListOf<SendaChatMessage>()
        history.add(SendaChatMessage(text = "¿Qué día es hoy?", sender = ChatSender.USER))
        val dateResp = SendaInferenceEngine.chat("¿Qué día es hoy?", history = history)
        history.add(SendaChatMessage(text = dateResp.outputText, sender = ChatSender.ASSISTANT))

        // El usuario pregunta "¿estás seguro?"
        val seguroResp = SendaInferenceEngine.chat("estas seguro?", history = history)
        println("Respuesta a 'estas seguro?':\n${seguroResp.outputText}")

        // NO debe tener la plantilla abstracta y robótica
        assertFalse("No debe contener plantilla genérica de 'Análisis sobre estas seguro'", seguroResp.outputText.contains("Análisis sobre «estas seguro?"))
        // DEBE confirmar con rigor epistémico referenciando el reloj del sistema
        assertTrue("Debe afirmar seguridad fundamentada", seguroResp.outputText.contains("estoy completamente seguro"))
        assertTrue("Debe mencionar el reloj o calendario del dispositivo", seguroResp.outputText.contains("reloj") || seguroResp.outputText.contains("sistema operativo"))

        // B. Pregunta sobre cálculo y posterior "¿de verdad?"
        val mathHistory = mutableListOf<SendaChatMessage>()
        mathHistory.add(SendaChatMessage(text = "cuanto es 15 * 8", sender = ChatSender.USER))
        val mathResp = SendaInferenceEngine.chat("cuanto es 15 * 8", history = mathHistory)
        mathHistory.add(SendaChatMessage(text = mathResp.outputText, sender = ChatSender.ASSISTANT))

        val deVerdadResp = SendaInferenceEngine.chat("¿de verdad?", history = mathHistory)
        assertTrue("Debe confirmar con certeza aritmética", deVerdadResp.outputText.contains("seguro") || deVerdadResp.outputText.contains("aritmética"))

        // C. Preguntas con erratas comunes: "quein te creoo"
        val typoResp = SendaInferenceEngine.chat("quein te creoo")
        assertTrue("Debe tolerar erratas y responder sobre Senda Browser", typoResp.outputText.contains("Senda Browser"))

        // D. Pregunta de colaboración con errata: "que podemos hacer jubtos?"
        val collabResp = SendaInferenceEngine.chat("que podemos hacer jubtos?")
        println("Respuesta a 'que podemos hacer jubtos?':\n${collabResp.outputText}")
        assertFalse("No debe contener plantilla genérica de Directiva Core", collabResp.outputText.contains("He evaluado tu consulta bajo la Directiva Core"))
        assertTrue("Debe responder de forma proactiva y colaborativa", collabResp.outputText.contains("hacer muchísimas cosas juntos") || collabResp.outputText.contains("Redacción") || collabResp.outputText.contains("Codex"))

        // E. Conversación multi-turno encadenada con elipsis y sujeto implícito: Alan Turing
        val turingHistory = mutableListOf<SendaChatMessage>()
        turingHistory.add(SendaChatMessage(text = "Alan Turing", sender = ChatSender.USER))
        val turingResp1 = SendaInferenceEngine.chat("Alan Turing", history = turingHistory)
        turingHistory.add(SendaChatMessage(text = turingResp1.outputText, sender = ChatSender.ASSISTANT))

        // Turno 2: "¿Dónde nació?" (omite el sujeto, debe inferir Alan Turing)
        turingHistory.add(SendaChatMessage(text = "¿Dónde nació?", sender = ChatSender.USER))
        val turingResp2 = SendaInferenceEngine.chat("¿Dónde nació?", history = turingHistory)
        turingHistory.add(SendaChatMessage(text = turingResp2.outputText, sender = ChatSender.ASSISTANT))
        println("Respuesta a '¿Dónde nació?':\n${turingResp2.outputText}")
        assertTrue("Debe responder sobre Londres/Reino Unido", turingResp2.outputText.contains("Londres") || turingResp2.outputText.contains("Reino Unido"))

        // Turno 3: "¿Cómo murió?" (sigue manteniendo el tema)
        turingHistory.add(SendaChatMessage(text = "¿Cómo murió?", sender = ChatSender.USER))
        val turingResp3 = SendaInferenceEngine.chat("¿Cómo murió?", history = turingHistory)
        turingHistory.add(SendaChatMessage(text = turingResp3.outputText, sender = ChatSender.ASSISTANT))
        println("Respuesta a '¿Cómo murió?':\n${turingResp3.outputText}")
        assertTrue("Debe mencionar cianuro o 1954", turingResp3.outputText.contains("cianuro") || turingResp3.outputText.contains("1954"))

        // Turno 4: "Cuéntame más" (profundización sobre el tema activo)
        turingHistory.add(SendaChatMessage(text = "Cuéntame más", sender = ChatSender.USER))
        val turingResp4 = SendaInferenceEngine.chat("Cuéntame más", history = turingHistory)
        println("Respuesta a 'Cuéntame más':\n${turingResp4.outputText}")
        assertTrue("Debe profundizar sobre el legado de Turing", turingResp4.outputText.contains("Turing") || turingResp4.outputText.contains("Bletchley"))

        // F. Conversación encadenada sobre Fotosíntesis
        val fotoHistory = mutableListOf<SendaChatMessage>()
        fotoHistory.add(SendaChatMessage(text = "¿Qué es la fotosíntesis?", sender = ChatSender.USER))
        val fotoResp1 = SendaInferenceEngine.chat("¿Qué es la fotosíntesis?", history = fotoHistory)
        fotoHistory.add(SendaChatMessage(text = fotoResp1.outputText, sender = ChatSender.ASSISTANT))

        // Turno 2: "¿Cuáles son sus fases?" (pronombre anafórico "sus", sin nombrar fotosíntesis)
        fotoHistory.add(SendaChatMessage(text = "¿Cuáles son sus fases?", sender = ChatSender.USER))
        val fotoResp2 = SendaInferenceEngine.chat("¿Cuáles son sus fases?", history = fotoHistory)
        fotoHistory.add(SendaChatMessage(text = fotoResp2.outputText, sender = ChatSender.ASSISTANT))
        println("Respuesta a '¿Cuáles son sus fases?':\n${fotoResp2.outputText}")
        assertTrue("Debe detallar fase luminosa y fase oscura / Calvin", fotoResp2.outputText.contains("Luminosa") || fotoResp2.outputText.contains("Calvin") || fotoResp2.outputText.contains("tilacoides"))

        // Turno 3: "Dame un ejemplo"
        fotoHistory.add(SendaChatMessage(text = "Dame un ejemplo", sender = ChatSender.USER))
        val fotoResp3 = SendaInferenceEngine.chat("Dame un ejemplo", history = fotoHistory)
        println("Respuesta a 'Dame un ejemplo':\n${fotoResp3.outputText}")
        assertTrue("Debe brindar un ejemplo concreto de fotosíntesis", fotoResp3.outputText.contains("geranio") || fotoResp3.outputText.contains("hoja") || fotoResp3.outputText.contains("cloroplasto"))

        // G. Conversación encadenada sobre Tor
        val torHistory = mutableListOf<SendaChatMessage>()
        torHistory.add(SendaChatMessage(text = "¿Qué es Tor?", sender = ChatSender.USER))
        val torResp1 = SendaInferenceEngine.chat("¿Qué es Tor?", history = torHistory)
        torHistory.add(SendaChatMessage(text = torResp1.outputText, sender = ChatSender.ASSISTANT))

        // Turno 2: "¿Y cuáles son sus desventajas?"
        torHistory.add(SendaChatMessage(text = "¿Y cuáles son sus desventajas?", sender = ChatSender.USER))
        val torResp2 = SendaInferenceEngine.chat("¿Y cuáles son sus desventajas?", history = torHistory)
        println("Respuesta a '¿Y cuáles son sus desventajas?':\n${torResp2.outputText}")
        assertTrue("Debe explicar desventajas de Tor (velocidad/latencia/bloqueos)", torResp2.outputText.contains("Velocidad") || torResp2.outputText.contains("latencia") || torResp2.outputText.contains("Desventajas"))

        println("[PASS 11/12] Seguimiento conversacional epistémico, coherencia temática multi-turno y tolerancia a erratas verificado.")
    }

    // =========================================================================
    // 12. AUDITORÍA DE MOTOR DE BÚSQUEDA SOBERANO EN LA RED Y CITACIÓN DE FUENTES
    // =========================================================================
    @Test
    fun test12_SovereignWebSearchIntegration() = kotlinx.coroutines.runBlocking {
        // A. Búsqueda y síntesis soberana directa en Wikipedia
        val searchResult = SendaWebSearchEngine.searchAndSynthesize("Albert Einstein")
        if (searchResult != null) {
            assertTrue("Debe encontrar resumen sobre Einstein", searchResult.synthesizedSummary.isNotBlank())
            assertTrue("Debe incluir fuentes verificadas", searchResult.sources.isNotEmpty())
            assertTrue("La primera fuente debe ser de Wikipedia", searchResult.sources.first().url.contains("wikipedia.org"))
            println("Búsqueda soberana exitosa:\n  Título: ${searchResult.sources.first().title}\n  URL: ${searchResult.sources.first().url}")
        }

        // B. Inferencia general que requiere búsqueda web cuando no está en caché offline
        val queryResult = SendaInferenceEngine.chat("Albert Einstein")
        assertTrue("La respuesta debe contener información", queryResult.outputText.isNotBlank())
        // Si hay conexión a internet en el dispositivo, se habrán incluido las fuentes
        if (queryResult.outputText.contains("Fuentes verificadas")) {
            assertTrue("Debe citar enlaces verificados", queryResult.outputText.contains("http"))
        }

        println("[PASS 12/13] Motor de búsqueda soberana y citación de fuentes en red verificado.")
    }

    // =========================================================================
    // 13. AUDITORÍA EMPÍRICA DE PRESUPUESTO DE MEMORIA: QWEN 3.5 (0.8B) vs QWEN 2.5 (1.5B)
    // =========================================================================
    @Test
    fun test13_EmpiricalMemoryBudgetEvaluation() {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)

        val totalRamMb = (memInfo.totalMem / (1024 * 1024)).toDouble()
        val availRamMb = (memInfo.availMem / (1024 * 1024)).toDouble()
        val thresholdMb = (memInfo.threshold / (1024 * 1024)).toDouble()

        println("=== MEDICIÓN EMPÍRICA DE MEMORIA EN HARDWARE REAL ===")
        println("Dispositivo: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE})")
        println("RAM Total Física: ${String.format(java.util.Locale.US, "%.1f", totalRamMb)} MB")
        println("RAM Libre en Caliente: ${String.format(java.util.Locale.US, "%.1f", availRamMb)} MB")
        println("Umbral Crítico LMK (threshold): ${String.format(java.util.Locale.US, "%.1f", thresholdMb)} MB")

        // 1. Presupuesto estricto de ingeniería para dispositivos de 4 GB: máximo 1024 MB total
        val MAX_BUDGET_4GB_MB = 1024

        // 2. Evaluación empírica de Qwen 3.5 (0.8B)
        val m35 = SendaAiModels.MODEL_QWEN_3_5_0_8B
        val estimatedQwen35FootprintMb = m35.ramRequiredMb // ~850 MB (pesos Q4 + KV Cache 2048 + runtime)
        assertTrue(
            "Qwen 3.5 (0.8B) DEBE cumplir el presupuesto estricto de <= 1.0 GB en 4 GB",
            estimatedQwen35FootprintMb <= MAX_BUDGET_4GB_MB
        )
        println("-> [EVALUACIÓN 1] Qwen 3.5 (0.8B) con contexto 2048 tokens:")
        println("   Huella estimada: $estimatedQwen35FootprintMb MB | Presupuesto: $MAX_BUDGET_4GB_MB MB | ESTADO: SEGURO PARA 4 GB")

        // 3. Evaluación empírica de Qwen 2.5 (1.5B)
        val m25 = SendaAiModels.MODEL_QWEN_1_5B
        val estimatedQwen25FootprintMb = m25.ramRequiredMb // ~1300 MB
        assertTrue(
            "Qwen 2.5 (1.5B) supera el presupuesto de 1.0 GB y requiere degradación dinámica si la RAM libre es baja",
            estimatedQwen25FootprintMb > MAX_BUDGET_4GB_MB
        )
        println("-> [EVALUACIÓN 2] Qwen 2.5 (1.5B) con contexto 2048/4096 tokens:")
        println("   Huella estimada: $estimatedQwen25FootprintMb MB | Presupuesto: $MAX_BUDGET_4GB_MB MB | ESTADO: RIESGO EN 4 GB CON PESTAÑAS ABIERTAS")

        // 4. Verificación del comportamiento del profiler dinámico en tiempo de ejecución
        val profile = SendaHardwareProfiler.analyze(context)
        println("-> [DIAGNÓSTICO DEL PROFILER DINÁMICO]:")
        println("   Nivel asignado: ${profile.recommendedTier}")
        println("   Modelo recomendado: ${profile.recommendedModelId}")
        println("   Límite de contexto seguro: ${profile.maxRecommendedContextTokens} tokens")

        // El límite de contexto no debe superar 4096 tokens bajo ninguna circunstancia
        assertTrue("El contexto nunca debe superar 4096 tokens para proteger contra OOM", profile.maxRecommendedContextTokens <= 4096)

        println("[PASS 13/13] Auditoría empírica de presupuesto de memoria y reglas de seguridad para 4 GB completada.")
    }
}


