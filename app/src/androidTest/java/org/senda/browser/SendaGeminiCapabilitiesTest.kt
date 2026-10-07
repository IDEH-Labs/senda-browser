package org.senda.browser

import android.util.Base64
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.senda.browser.core.PreferencesManager
import org.senda.browser.core.SendaNet

/**
 * What Gemini accepts with an API key, using the real key the user saved in Senda (never logged). Through its
 * OpenAI-compatible layer and through its own API. Measurement: it does not fail; see logcat SendaGeminiCaps.
 */
@RunWith(AndroidJUnit4::class)
class SendaGeminiCapabilitiesTest {

    private fun log(msg: String) = Log.i("SendaGeminiCaps", msg)
    private val base = "https://generativelanguage.googleapis.com/v1beta"

    private val redPng: String by lazy {
        val bmp = android.graphics.Bitmap.createBitmap(400, 200, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.rgb(220, 0, 0))
        android.graphics.Canvas(bmp).drawText("MANGO", 60f, 130f, android.graphics.Paint().apply { textSize = 90f; color = android.graphics.Color.WHITE; isAntiAlias = true })
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private val tinyPdf: String by lazy {
        val doc = android.graphics.pdf.PdfDocument()
        val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(200, 100, 1).create())
        page.canvas.drawText("SENDA42", 20f, 50f, android.graphics.Paint().apply { textSize = 24f })
        doc.finishPage(page)
        val out = java.io.ByteArrayOutputStream()
        doc.writeTo(out); doc.close()
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun post(name: String, url: String, key: String, bearer: Boolean, body: JSONObject): JSONObject? {
        val conn = SendaNet.open(url).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 20_000; readTimeout = 180_000
            setRequestProperty("Content-Type", "application/json")
            if (bearer) setRequestProperty("Authorization", "Bearer $key") else setRequestProperty("x-goog-api-key", key)
        }
        return try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) { log("$name → HTTP $code ${text.take(350).replace('\n', ' ')}"); null } else JSONObject(text)
        } catch (e: Exception) {
            log("$name → excepción ${e.javaClass.simpleName}: ${e.message}"); null
        } finally { conn.disconnect() }
    }

    // --- OpenAI-compatible layer ---
    private fun compat(name: String, key: String, model: String, content: Any, extra: (JSONObject) -> Unit = {}) {
        val body = JSONObject().put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        extra(body)
        val r = post("compat $name", "$base/openai/chat/completions", key, true, body) ?: return
        val msg = r.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
        log("compat $name → ${msg?.optString("content").orEmpty().take(200).replace('\n', ' ')} | claves=${r.keys().asSequence().toList()}")
    }

    // --- Google's own API ---
    private fun native(name: String, key: String, model: String, parts: JSONArray, extra: (JSONObject) -> Unit = {}) {
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
        extra(body)
        val r = post("propia $name", "$base/models/$model:generateContent", key, false, body) ?: return
        val cand = r.optJSONArray("candidates")?.optJSONObject(0)
        val text = cand?.optJSONObject("content")?.optJSONArray("parts")?.let { p -> (0 until p.length()).joinToString("") { p.optJSONObject(it)?.optString("text").orEmpty() } }.orEmpty()
        val chunks = cand?.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")
        val sources = chunks?.let { c -> (0 until c.length()).mapNotNull { c.optJSONObject(it)?.optJSONObject("web")?.optString("title") } }.orEmpty()
        log("propia $name → ${text.take(200).replace('\n', ' ')} | fuentes=$sources | pensamiento=${r.optJSONObject("usageMetadata")?.optInt("thoughtsTokenCount")}")
    }

    @Test
    fun measure() {
        val prefs = PreferencesManager(InstrumentationRegistry.getInstrumentation().targetContext)
        val key = prefs.getAssistantKey("gemini")
        assumeTrue("Sin clave de Gemini", key.isNotBlank())
        // Models available with this key
        val conn = SendaNet.open("$base/openai/models").apply { setRequestProperty("Authorization", "Bearer $key") }
        val ids = try {
            val data = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optJSONArray("data") ?: JSONArray()
            (0 until data.length()).map { data.optJSONObject(it).optString("id").removePrefix("models/") }
        } finally { conn.disconnect() }
        log("modelos (${ids.size}): ${ids.joinToString(", ")}".take(3000))
        val model = InstrumentationRegistry.getArguments().getString("model")
            ?: prefs.assistantModelFor("gemini").takeIf { it.isNotBlank() } ?: "gemini-2.5-flash"
        log("modelo probado: $model")

        compat("texto", key, model, "Responde solo: OK")
        compat("foto", key, model, JSONArray()
            .put(JSONObject().put("type", "text").put("text", "¿Qué palabra está escrita y de qué color es el fondo? Responde: color, palabra."))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/png;base64,$redPng"))))
        compat("pdf (file)", key, model, JSONArray()
            .put(JSONObject().put("type", "text").put("text", "¿Qué palabra dice el PDF? Solo la palabra."))
            .put(JSONObject().put("type", "file").put("file", JSONObject().put("filename", "prueba.pdf").put("file_data", "data:application/pdf;base64,$tinyPdf"))))
        compat("pdf (image_url)", key, model, JSONArray()
            .put(JSONObject().put("type", "text").put("text", "¿Qué palabra dice el PDF? Solo la palabra."))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:application/pdf;base64,$tinyPdf"))))
        compat("razonamiento", key, model, "Si 3 máquinas hacen 3 piezas en 3 minutos, ¿cuántos minutos tardan 100 máquinas en hacer 100 piezas? Solo el número.") {
            it.put("reasoning_effort", "high")
        }
        compat("búsqueda", key, model, "Busca en internet: ¿cuál fue el último resultado de la selección Colombia de fútbol? Una frase.") {
            it.put("tools", JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "googleSearch"))))
        }

        native("texto", key, model, JSONArray().put(JSONObject().put("text", "Responde solo: OK")))
        native("búsqueda", key, model, JSONArray().put(JSONObject().put("text", "¿Cuál fue el último resultado de la selección Colombia de fútbol? Una frase."))) {
            it.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        }
        native("foto", key, model, JSONArray()
            .put(JSONObject().put("text", "¿Qué palabra está escrita y de qué color es el fondo?"))
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/png").put("data", redPng))))
        native("pdf", key, model, JSONArray()
            .put(JSONObject().put("text", "¿Qué palabra dice el PDF? Solo la palabra."))
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "application/pdf").put("data", tinyPdf))))
        native("razonamiento", key, model, JSONArray().put(JSONObject().put("text", "Si 3 máquinas hacen 3 piezas en 3 minutos, ¿cuántos minutos tardan 100 máquinas en hacer 100 piezas? Solo el número."))) {
            it.put("generationConfig", JSONObject().put("thinkingConfig", JSONObject().put("thinkingBudget", -1)))
        }
    }

    /** Senda's client with Gemini: filtered list (newest flash first), photo, PDF and reasoning. */
    @Test
    fun sendaGeminiClient() = kotlinx.coroutines.runBlocking {
        val prefs = PreferencesManager(InstrumentationRegistry.getInstrumentation().targetContext)
        val key = prefs.getAssistantKey("gemini")
        assumeTrue("Sin clave de Gemini", key.isNotBlank())
        val client = org.senda.browser.core.assistant.ApiProvider.GEMINI.client(key)
        val models = client.listModels()
        log("cliente modelos (${models.size}): ${models.take(12)}")
        val model = models.first()
        val system = org.senda.browser.core.assistant.SendaAssistant.systemPrompt("es")
        val u = org.senda.browser.core.assistant.ChatTurn.Role.USER
        suspend fun ask(name: String, turn: org.senda.browser.core.assistant.ChatTurn, deep: Boolean = false) = try {
            val r = client.chat(model, system, listOf(turn), deep) {}
            log("cliente $name → ${r.text.take(120).replace('\n', ' ')}")
        } catch (e: Exception) { log("cliente $name → ERROR ${e.message}") }
        ask("texto", org.senda.browser.core.assistant.ChatTurn(u, "Responde solo: OK"))
        ask("foto", org.senda.browser.core.assistant.ChatTurn(u, "¿Qué palabra está escrita y de qué color es el fondo?",
            listOf(org.senda.browser.core.assistant.Attachment("p.png", "image/png", redPng))))
        ask("pdf", org.senda.browser.core.assistant.ChatTurn(u, "¿Qué palabra dice el PDF? Solo la palabra.",
            listOf(org.senda.browser.core.assistant.Attachment("p.pdf", "application/pdf", tinyPdf))))
        ask("pensar a fondo", org.senda.browser.core.assistant.ChatTurn(u,
            "Si 3 máquinas hacen 3 piezas en 3 minutos, ¿cuántos minutos tardan 100 máquinas en hacer 100 piezas? Solo el número."), deep = true)
        Unit
    }
}
