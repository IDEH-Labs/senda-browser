package org.senda.browser

import android.util.Base64
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.senda.browser.core.PreferencesManager
import org.senda.browser.core.SendaNet
import org.senda.browser.core.assistant.ChatGptPlanAuth

/**
 * What OpenAI accepts with "Sign in with ChatGPT" besides text (its guide does not document it). With the user's
 * real session: one short request per capability, logging what it answers. Usage: am instrument -e class ... ; see
 * logcat SendaGptCaps. It does not fail: it is a measurement.
 */
@RunWith(AndroidJUnit4::class)
class SendaChatGptCapabilitiesTest {

    private fun log(msg: String) = Log.i("SendaGptCaps", msg)

    // Red PNG with the word MANGO in white: it should say "red, MANGO"
    private val redPng: String by lazy {
        val bmp = android.graphics.Bitmap.createBitmap(400, 200, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.rgb(220, 0, 0))
        android.graphics.Canvas(bmp).drawText("MANGO", 60f, 130f, android.graphics.Paint().apply { textSize = 90f; color = android.graphics.Color.WHITE; isAntiAlias = true })
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    // Minimal PDF with the text "SENDA42"
    private val tinyPdf: String by lazy {
        val doc = android.graphics.pdf.PdfDocument()
        val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(200, 100, 1).create())
        page.canvas.drawText("SENDA42", 20f, 50f, android.graphics.Paint().apply { textSize = 24f })
        doc.finishPage(page)
        val out = java.io.ByteArrayOutputStream()
        doc.writeTo(out); doc.close()
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun probe(name: String, token: String, body: JSONObject) {
        body.put("store", false).put("stream", true)
        val conn = SendaNet.open("https://api.openai.com/v1/responses").apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 20_000; readTimeout = 180_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                log("$name → HTTP $code ${err.take(400)}")
                return
            }
            val text = StringBuilder(); val types = linkedSetOf<String>(); var images = 0; var final = ""
            conn.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty() || data == "[DONE]") continue
                    val ev = JSONObject(data)
                    val t = ev.optString("type"); types.add(t)
                    if (t == "response.output_text.delta") text.append(ev.optString("delta"))
                    if (t.contains("annotation") || (t == "response.output_item.done" && ev.optJSONObject("item")?.optString("type") == "web_search_call")) log("$name RAW $t: ${data.take(700)}")
                    if (t == "response.output_item.done" && ev.optJSONObject("item")?.optString("type") == "image_generation_call") {
                        if (ev.optJSONObject("item")?.optString("result").orEmpty().length > 100) images++
                    }
                    if (t == "response.failed" || t == "error") final = data.take(400)
                    if (t == "response.completed") { final = "completed"; break }
                }
            }
            log("$name → $final | imágenes=$images | texto=${text.toString().take(160).replace('\n', ' ')} | eventos=${types.filter { !it.endsWith(".delta") }.joinToString(",").take(300)}")
        } catch (e: Exception) {
            log("$name → excepción ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    private fun user(content: Any) = JSONArray().put(JSONObject().put("role", "user").put("content", content))

    @Test
    fun measureCapabilities() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = PreferencesManager(context)
        assumeTrue("Sin sesión de ChatGPT", ChatGptPlanAuth.isSignedIn(prefs))
        val token = ChatGptPlanAuth.accessToken(prefs)
        val model = prefs.assistantModelFor("chatgpt_plan").ifBlank { "gpt-5.6-terra" }
        log("modelo configurado: $model")

        val only = InstrumentationRegistry.getArguments().getString("only")
        if (only == "fuentes") {
            probe("fuentes", token, JSONObject().put("model", model)
                .put("tools", JSONArray().put(JSONObject().put("type", "web_search")))
                .put("include", JSONArray().put("web_search_call.action.sources"))
                .put("input", user("¿Qué tiempo hace hoy en Barranquilla? Una frase con la fuente.")))
            return@runBlocking
        }
        if (only == "imagen") {
            probe("imagen de entrada", token, JSONObject().put("model", model).put("input", user(JSONArray()
                .put(JSONObject().put("type", "input_text").put("text", "¿De qué color es el fondo y qué palabra está escrita? Responde: color, palabra."))
                .put(JSONObject().put("type", "input_image").put("image_url", "data:image/png;base64,$redPng")))))
            return@runBlocking
        }
        probe("texto", token, JSONObject().put("model", model).put("input", user("Responde solo: OK")))
        probe("razonamiento alto", token, JSONObject().put("model", model)
            .put("reasoning", JSONObject().put("effort", "high")).put("input", user("¿Cuánto es 17*23? Solo el número")))
        probe("búsqueda web", token, JSONObject().put("model", model)
            .put("tools", JSONArray().put(JSONObject().put("type", "web_search")))
            .put("input", user("Busca en internet: ¿quién ganó el último partido de la selección Colombia de fútbol? Responde en una frase con la fuente.")))
        probe("imagen de entrada", token, JSONObject().put("model", model).put("input", user(JSONArray()
            .put(JSONObject().put("type", "input_text").put("text", "¿De qué color es el fondo y qué palabra está escrita? Responde: color, palabra."))
            .put(JSONObject().put("type", "input_image").put("image_url", "data:image/png;base64,$redPng")))))
        probe("archivo PDF", token, JSONObject().put("model", model).put("input", user(JSONArray()
            .put(JSONObject().put("type", "input_text").put("text", "¿Qué palabra dice el PDF? Solo la palabra."))
            .put(JSONObject().put("type", "input_file").put("filename", "prueba.pdf").put("file_data", "data:application/pdf;base64,$tinyPdf")))))
        probe("crear imagen", token, JSONObject().put("model", model)
            .put("tools", JSONArray().put(JSONObject().put("type", "image_generation").put("size", "1024x1024").put("quality", "low")))
            .put("input", user("Genera una imagen simple: un círculo azul sobre fondo blanco.")))
    }

    /** Senda's client as the chat uses it: search with sources, attached photo and high reasoning. */
    @Test
    fun sendaClientEndToEnd() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = PreferencesManager(context)
        assumeTrue("Sin sesión de ChatGPT", ChatGptPlanAuth.isSignedIn(prefs))
        val client = org.senda.browser.core.assistant.SendaAssistant.backendFor("chatgpt_plan", prefs)
        val model = prefs.assistantModelFor("chatgpt_plan").ifBlank { "gpt-5.6-terra" }
        val system = org.senda.browser.core.assistant.SendaAssistant.systemPrompt("es")
        val t = org.senda.browser.core.assistant.ChatTurn.Role.USER

        val web = client.chat(model, system, listOf(org.senda.browser.core.assistant.ChatTurn(t,
            "¿Qué tiempo hace hoy en Barranquilla? Una frase.")), deep = false) {}
        log("cliente búsqueda → ${web.text.take(160).replace('\n', ' ')} | fuentes=${web.sources.map { it.url.ifBlank { "api:" + it.title }.take(60) }}")
        val news = client.chat(model, system, listOf(org.senda.browser.core.assistant.ChatTurn(t,
            "¿Cuál fue el último resultado de la selección Colombia de fútbol? Una frase.")), deep = false) {}
        log("cliente noticias → ${news.text.take(160).replace('\n', ' ')} | fuentes=${news.sources.map { it.url.ifBlank { "api:" + it.title }.take(60) }}")

        val photo = client.chat(model, system, listOf(org.senda.browser.core.assistant.ChatTurn(t,
            "¿Qué palabra está escrita y de qué color es el fondo?",
            listOf(org.senda.browser.core.assistant.Attachment("prueba.png", "image/png", redPng)))), deep = false) {}
        log("cliente foto → ${photo.text.take(120).replace('\n', ' ')}")

        val start = System.currentTimeMillis()
        val deepReply = client.chat(model, system, listOf(org.senda.browser.core.assistant.ChatTurn(t,
            "Si 3 máquinas hacen 3 piezas en 3 minutos, ¿cuántos minutos tardan 100 máquinas en hacer 100 piezas? Solo el número.")), deep = true) {}
        log("cliente pensar a fondo → ${deepReply.text.take(80)} en ${(System.currentTimeMillis() - start) / 1000} s")
        Unit
    }

    @Test
    fun markdownPlainText() {
        val plain = org.senda.browser.ui.components.assistantPlainText(
            "## Hoy\nHace **29 °C** y *sol* ([fcf.com.co](https://www.fcf.com.co/x))\n- uno\n* dos\nusa `npm`")
        log("markdown → ${plain.replace('\n', '|')}")
        org.junit.Assert.assertEquals("Hoy\nHace 29 °C y sol (fcf.com.co)\n• uno\n• dos\nusa npm", plain)
    }
}
