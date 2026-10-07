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

/** What the chat needs from any connected AI: ChatGPT with the plan or an AI with an API key. */
interface AssistantBackend {
    /** Capabilities verified with a real account: only those are offered in the chat. */
    val canSearchWeb: Boolean
    val canAttach: Boolean
    val canThinkDeep: Boolean

    suspend fun listModels(): List<String>
    suspend fun chat(model: String, system: String, turns: List<ChatTurn>, deep: Boolean, onDelta: (String) -> Unit): ChatReply
}

/**
 * AIs connected with an API key (for advanced users; each company is paid separately). Researched on
 * 2026-10-06: Anthropic forbids using the Claude subscription in third-party apps (since 2026-02-19), Google
 * offers no permitted way for Gemini, xAI does not officially document subscription access for third-party
 * apps (gray area: not used) and Mistral's only works in Le Chat: for all of them, the official route is the key.
 */
enum class ApiProvider(
    val id: String,
    val displayName: String,
    /** Base of the official API (always https). */
    val baseUrl: String,
    /** Official page where the key is created. */
    val keysPage: String,
    /** Official page showing usage and limits (the one each company points to in its quota errors). */
    val usagePage: String
) {
    ANTHROPIC("anthropic", "Claude (Anthropic)", "https://api.anthropic.com/v1", "https://console.anthropic.com/settings/keys", "https://console.anthropic.com/settings/limits"),
    GEMINI("gemini", "Gemini (Google)", "https://generativelanguage.googleapis.com/v1beta/openai", "https://aistudio.google.com/apikey", "https://ai.dev/rate-limit"),
    XAI("xai", "Grok (xAI)", "https://api.x.ai/v1", "https://console.x.ai", "https://console.x.ai"),
    MISTRAL("mistral", "Mistral", "https://api.mistral.ai/v1", "https://console.mistral.ai/api-keys", "https://console.mistral.ai/usage");

    val host: String get() = java.net.URL(baseUrl).host

    fun client(apiKey: String): AssistantBackend = clientAt(baseUrl, apiKey)

    /** With a different base address: only for tests (simulated server on the phone). */
    internal fun clientAt(base: String, apiKey: String): AssistantBackend = when (this) {
        ANTHROPIC -> AnthropicClient(base, apiKey)
        // Measured with a real key on 2026-10-06 (SendaGeminiCapabilitiesTest): photos, PDF and reasoning work;
        // Google search could not be verified (the free tier answers "quota exceeded")
        GEMINI -> OpenAiCompatibleClient(base, apiKey, attachments = true, deep = true, modelFilter = ::isGeminiChatModel)
        else -> OpenAiCompatibleClient(base, apiKey)
    }

    companion object {
        fun byId(id: String?): ApiProvider? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Gemini also lists models that are not for chatting (voice, music, images, special "Interactions API" ones):
 * they are left out. First comes the newest "flash" (fast and with a free tier).
 */
private fun isGeminiChatModel(id: String): Boolean =
    id.startsWith("gemini-") && listOf("tts", "image", "transcribe", "embedding", "live", "audio", "customtools", "robotics", "computer-use", "banana", "omni")
        .none { id.contains(it) }

private fun geminiOrder(ids: List<String>): List<String> {
    val version = { id: String -> Regex("gemini-(\\d+)(?:\\.(\\d+))?").find(id)?.let { (it.groupValues[1].toInt() * 100) + (it.groupValues[2].toIntOrNull() ?: 0) } ?: 0 }
    return ids.sortedWith(compareByDescending<String> { Regex("^gemini-[\\d.]+-flash$").matches(it) }.thenByDescending(version).thenBy { it })
}

/**
 * By default only text chat: search, attachments and reasoning depend on each company and only the ones
 * verified with a real key are offered (nothing unverified is promised).
 */
private abstract class TextOnlyApiClient(protected val base: String, protected val apiKey: String) : AssistantBackend {
    override val canSearchWeb = false
    override val canAttach: Boolean = false
    override val canThinkDeep: Boolean = false

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
        // Only the code and a fragment of the error (it never contains the key that was sent)
        val err = try { conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(200) } catch (_: IOException) { null }.orEmpty()
        throw RemoteAiException(
            when {
                code == 401 || code == 403 -> RemoteAiException.Kind.AUTH
                // Plan quota exhausted (e.g. Gemini's free tier): waiting a moment does not fix it
                code == 429 && err.contains("quota", ignoreCase = true) -> RemoteAiException.Kind.USAGE_LIMIT
                // 429 for rate and 503 "too much demand": temporary
                code == 429 || code == 503 -> RemoteAiException.Kind.RATE_LIMIT
                else -> RemoteAiException.Kind.BAD_RESPONSE
            },
            "HTTP $code $err".trim()
        )
    }

    /**
     * Walks through an event stream (SSE) and delivers each "data:" line. With "too much demand" (503) before
     * the answer starts, it retries once after 2 s: it usually clears quickly.
     */
    protected suspend fun streamPost(url: String, payload: JSONObject, onData: (String) -> Boolean) {
        try {
            streamPostOnce(url, payload, onData)
        } catch (e: RemoteAiException) {
            if (e.kind != RemoteAiException.Kind.RATE_LIMIT || e.message?.startsWith("HTTP 503") != true) throw e
            kotlinx.coroutines.delay(2_000)
            streamPostOnce(url, payload, onData)
        }
    }

    private suspend fun streamPostOnce(url: String, payload: JSONObject, onData: (String) -> Boolean) {
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

/** Gemini, Grok and Mistral: /models and /chat/completions (OpenAI-compatible format, documented by each one). */
private class OpenAiCompatibleClient(
    base: String,
    apiKey: String,
    attachments: Boolean = false,
    deep: Boolean = false,
    private val modelFilter: ((String) -> Boolean)? = null
) : TextOnlyApiClient(base, apiKey) {
    override val canAttach = attachments
    override val canThinkDeep = deep

    override fun authorize(conn: HttpURLConnection) = conn.setRequestProperty("Authorization", "Bearer $apiKey")

    override suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val conn = open("$base/models").apply { requestMethod = "GET" }
        try {
            val data = JSONObject(readOrThrow(conn)).optJSONArray("data") ?: JSONArray()
            (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotBlank() } }
                // Gemini answers "models/gemini-…"; /chat/completions uses it without the prefix
                .map { it.removePrefix("models/") }.distinct().sorted()
                .let { ids -> modelFilter?.let { f -> geminiOrder(ids.filter(f)) } ?: ids }
        } finally {
            conn.disconnect()
        }
    }

    override suspend fun chat(model: String, system: String, turns: List<ChatTurn>, deep: Boolean, onDelta: (String) -> Unit): ChatReply =
        withContext(Dispatchers.IO) {
            val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
            turns.forEach { t ->
                val role = if (t.role == ChatTurn.Role.USER) "user" else "assistant"
                if (t.attachments.isEmpty() || !canAttach) {
                    messages.put(JSONObject().put("role", role).put("content", t.text))
                } else {
                    // Photos and PDFs as image_url with data (that is how Gemini accepts PDFs; it rejects the "file" type)
                    val parts = JSONArray().put(JSONObject().put("type", "text").put("text", t.text))
                    t.attachments.forEach { a ->
                        parts.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:${a.mime};base64,${a.base64}")))
                    }
                    messages.put(JSONObject().put("role", role).put("content", parts))
                }
            }
            val payload = JSONObject().put("model", model).put("messages", messages).put("stream", true)
            if (deep && canThinkDeep) payload.put("reasoning_effort", "high")
            val out = StringBuilder()
            var finished = false
            streamPost("$base/chat/completions", payload) { data ->
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

/** Claude: Anthropic's /v1/models and /v1/messages (with x-api-key and anthropic-version, per its documentation). */
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
