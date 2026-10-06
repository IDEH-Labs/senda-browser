package org.senda.browser.core.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.senda.browser.core.SendaNet
import java.io.IOException
import java.net.HttpURLConnection

/** Lo que el chat necesita de cualquier IA conectada: ChatGPT con el plan o una IA con clave de API. */
interface AssistantBackend {
    /** Capacidades comprobadas con una cuenta real: solo esas se ofrecen en el chat. */
    val canSearchWeb: Boolean
    val canAttach: Boolean
    val canThinkDeep: Boolean

    suspend fun listModels(): List<String>
    suspend fun chat(model: String, system: String, turns: List<ChatTurn>, deep: Boolean, onDelta: (String) -> Unit): ChatReply
}

/**
 * IA conectadas con clave de API (para usuarios avanzados; se paga aparte a cada empresa). Investigado el
 * 2026-10-06: Anthropic prohíbe usar la suscripción de Claude en apps de terceros (desde el 19-02-2026), Google
 * no ofrece forma permitida para Gemini, xAI no documenta oficialmente su acceso por suscripción para apps de
 * terceros (zona gris: no se usa) y la de Mistral solo sirve en Le Chat: con todas ellas, la vía oficial es la clave.
 */
enum class ApiProvider(
    val id: String,
    val displayName: String,
    /** Base de la API oficial (siempre https). */
    val baseUrl: String,
    /** Página oficial donde se crea la clave. */
    val keysPage: String
) {
    ANTHROPIC("anthropic", "Claude (Anthropic)", "https://api.anthropic.com/v1", "https://console.anthropic.com/settings/keys"),
    GEMINI("gemini", "Gemini (Google)", "https://generativelanguage.googleapis.com/v1beta/openai", "https://aistudio.google.com/apikey"),
    XAI("xai", "Grok (xAI)", "https://api.x.ai/v1", "https://console.x.ai"),
    MISTRAL("mistral", "Mistral", "https://api.mistral.ai/v1", "https://console.mistral.ai/api-keys");

    val host: String get() = java.net.URL(baseUrl).host

    fun client(apiKey: String): AssistantBackend = clientAt(baseUrl, apiKey)

    /** Con otra dirección base: solo para las pruebas (servidor simulado en el teléfono). */
    internal fun clientAt(base: String, apiKey: String): AssistantBackend =
        if (this == ANTHROPIC) AnthropicClient(base, apiKey) else OpenAiCompatibleClient(base, apiKey)

    companion object {
        fun byId(id: String?): ApiProvider? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Solo conversación por texto: búsqueda, adjuntos y razonamiento dependen de cada empresa y no se han comprobado
 * con una clave real, así que no se ofrecen (no se promete lo que no está verificado).
 */
private abstract class TextOnlyApiClient(protected val base: String, protected val apiKey: String) : AssistantBackend {
    override val canSearchWeb = false
    override val canAttach = false
    override val canThinkDeep = false

    protected abstract fun authorize(conn: HttpURLConnection)

    protected fun open(url: String): HttpURLConnection = SendaNet.open(url).apply {
        connectTimeout = 20_000
        readTimeout = 180_000
        authorize(this)
    }

    protected fun readOrThrow(conn: HttpURLConnection): String {
        val code = try { conn.responseCode } catch (e: IOException) {
            throw RemoteAiException(RemoteAiException.Kind.NETWORK, e.javaClass.simpleName)
        }
        if (code in 200..299) return conn.inputStream.bufferedReader().use { it.readText() }
        // Solo el código y un fragmento del error (nunca contiene la clave enviada)
        val err = try { conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(200) } catch (_: IOException) { null }.orEmpty()
        throw RemoteAiException(
            when (code) {
                401, 403 -> RemoteAiException.Kind.AUTH
                429 -> RemoteAiException.Kind.RATE_LIMIT
                else -> RemoteAiException.Kind.BAD_RESPONSE
            },
            "HTTP $code $err".trim()
        )
    }

    /** Recorre un flujo de eventos (SSE) y entrega cada línea «data:». */
    protected suspend fun streamPost(url: String, payload: JSONObject, onData: (String) -> Boolean) {
        val conn = open(url).apply {
            requestMethod = "POST"; doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
        }
        try {
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            if (conn.responseCode !in 200..299) readOrThrow(conn)
            conn.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    currentCoroutineContext().ensureActive()
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty()) continue
                    if (!onData(data)) break
                }
            }
        } catch (e: IOException) {
            throw RemoteAiException(RemoteAiException.Kind.NETWORK, e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }
}

/** Gemini, Grok y Mistral: /models y /chat/completions (formato compatible con OpenAI, documentado por cada una). */
private class OpenAiCompatibleClient(base: String, apiKey: String) : TextOnlyApiClient(base, apiKey) {

    override fun authorize(conn: HttpURLConnection) = conn.setRequestProperty("Authorization", "Bearer $apiKey")

    override suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val conn = open("$base/models").apply { requestMethod = "GET" }
        try {
            val data = JSONObject(readOrThrow(conn)).optJSONArray("data") ?: JSONArray()
            (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotBlank() } }
                // Gemini responde «models/gemini-…»; en /chat/completions se usa sin el prefijo
                .map { it.removePrefix("models/") }.distinct().sorted()
        } finally {
            conn.disconnect()
        }
    }

    override suspend fun chat(model: String, system: String, turns: List<ChatTurn>, deep: Boolean, onDelta: (String) -> Unit): ChatReply =
        withContext(Dispatchers.IO) {
            val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
            turns.forEach { t -> messages.put(JSONObject().put("role", if (t.role == ChatTurn.Role.USER) "user" else "assistant").put("content", t.text)) }
            val out = StringBuilder()
            var finished = false
            streamPost("$base/chat/completions", JSONObject().put("model", model).put("messages", messages).put("stream", true)) { data ->
                if (data == "[DONE]") { finished = true; return@streamPost false }
                val choice = JSONObject(data).optJSONArray("choices")?.optJSONObject(0) ?: return@streamPost true
                val delta = choice.optJSONObject("delta")?.optString("content", "").orEmpty()
                if (delta.isNotEmpty() && delta != "null") { out.append(delta); onDelta(out.toString()) }
                if (choice.optString("finish_reason").let { it.isNotEmpty() && it != "null" }) finished = true
                true
            }
            if (!finished && out.isEmpty()) throw RemoteAiException(RemoteAiException.Kind.BAD_RESPONSE, "incomplete")
            ChatReply(out.toString(), emptyList())
        }
}

/** Claude: /v1/models y /v1/messages de Anthropic (con x-api-key y anthropic-version, según su documentación). */
private class AnthropicClient(base: String, apiKey: String) : TextOnlyApiClient(base, apiKey) {

    override fun authorize(conn: HttpURLConnection) {
        conn.setRequestProperty("x-api-key", apiKey)
        conn.setRequestProperty("anthropic-version", "2023-06-01")
    }

    override suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val conn = open("$base/models?limit=100").apply { requestMethod = "GET" }
        try {
            val data = JSONObject(readOrThrow(conn)).optJSONArray("data") ?: JSONArray()
            (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotBlank() } }
        } finally {
            conn.disconnect()
        }
    }

    override suspend fun chat(model: String, system: String, turns: List<ChatTurn>, deep: Boolean, onDelta: (String) -> Unit): ChatReply =
        withContext(Dispatchers.IO) {
            val messages = JSONArray()
            turns.forEach { t -> messages.put(JSONObject().put("role", if (t.role == ChatTurn.Role.USER) "user" else "assistant").put("content", t.text)) }
            val payload = JSONObject().put("model", model).put("max_tokens", 8192).put("system", system)
                .put("messages", messages).put("stream", true)
            val out = StringBuilder()
            var finished = false
            streamPost("$base/messages", payload) { data ->
                val ev = JSONObject(data)
                when (ev.optString("type")) {
                    "content_block_delta" -> ev.optJSONObject("delta")?.takeIf { it.optString("type") == "text_delta" }?.let {
                        out.append(it.optString("text")); onDelta(out.toString())
                    }
                    "message_stop" -> { finished = true; return@streamPost false }
                    "error" -> throw RemoteAiException(
                        if (ev.optJSONObject("error")?.optString("type") == "overloaded_error") RemoteAiException.Kind.RATE_LIMIT
                        else RemoteAiException.Kind.BAD_RESPONSE,
                        ev.optJSONObject("error")?.optString("type").orEmpty()
                    )
                }
                true
            }
            if (!finished) throw RemoteAiException(RemoteAiException.Kind.BAD_RESPONSE, "incomplete")
            ChatReply(out.toString(), emptyList())
        }
}
