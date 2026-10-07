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

/** Photo or PDF attached to a message, in base64 (sent inside the request; nothing is uploaded separately). */
data class Attachment(val name: String, val mime: String, val base64: String) {
    val isImage: Boolean get() = mime.startsWith("image/")
}

/** One conversation turn as it is sent. */
data class ChatTurn(val role: Role, val text: String, val attachments: List<Attachment> = emptyList()) {
    enum class Role { USER, ASSISTANT }
}

/**
 * Source the AI consulted: a web page (with [url], opened in a tab) or an OpenAI data service
 * without a page (e.g. "oai-weather" for the weather: empty [url]).
 */
data class Source(val url: String, val title: String)

data class ChatReply(val text: String, val sources: List<Source>)

/** Failure that can be explained to the user. */
class RemoteAiException(val kind: Kind, detail: String = "") : Exception(detail) {
    enum class Kind { NOT_CONFIGURED, AUTH, RATE_LIMIT, NETWORK, BAD_RESPONSE, USAGE_LIMIT, NOT_ELIGIBLE }
}

/**
 * The user's ChatGPT plan ("Sign in with ChatGPT"): /v1/models and /v1/responses with store=false and stream=true,
 * as the official guide requires (developers.openai.com/siwc/token-sharing-open-source/models-and-inference).
 *
 * Capabilities measured with the real account on 2026-10-06 (SendaChatGptCapabilitiesTest): internet search
 * (web_search tool, with sources), photos (input_image), PDF (input_file) and high reasoning work; creating
 * images does not: OpenAI answers "subscription_sharing_unsupported_capability" ('image_generation' is not supported).
 */
class ChatGptPlanClient(private val prefs: org.senda.browser.core.PreferencesManager) : AssistantBackend {

    override val canSearchWeb = true
    override val canAttach = true
    override val canThinkDeep = true

    override suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val conn = open("$BASE/models", ChatGptPlanAuth.accessToken(prefs)).apply { requestMethod = "GET" }
        try {
            val body = readOrThrow(conn)
            val models = JSONObject(body).optJSONArray("models") ?: JSONArray()
            (0 until models.length()).mapNotNull { models.optJSONObject(it) }
                .filter { it.optString("visibility") == "list" }
                .mapNotNull { it.optString("slug").ifBlank { null } }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Full answer; [onDelta] receives the accumulated text as it arrives. [deep]: high reasoning (slower and
     * more careful). Internet search is always available: the model decides when to use it.
     */
    override suspend fun chat(
        model: String,
        system: String,
        turns: List<ChatTurn>,
        deep: Boolean,
        onDelta: (String) -> Unit
    ): ChatReply = withContext(Dispatchers.IO) {
        val input = JSONArray().put(JSONObject().put("role", "developer").put("content", system + "\n\n" + CAPABILITIES))
        turns.forEach { t -> input.put(turnJson(t)) }
        val payload = JSONObject()
            .put("model", model)
            .put("input", input)
            .put("tools", JSONArray().put(JSONObject().put("type", "web_search")))
            // Pages consulted even if the answer does not cite them in the text (e.g. the weather)
            .put("include", JSONArray().put("web_search_call.action.sources"))
            .put("store", false)
            .put("stream", true)
        if (deep) payload.put("reasoning", JSONObject().put("effort", "high"))
        // "usage_unavailable" (503) is temporary: bounded retry before starting to show text
        var attempt = 0
        while (true) {
            try {
                return@withContext stream(payload.toString(), onDelta)
            } catch (e: RemoteAiException) {
                if (e.message == "subscription_sharing_usage_unavailable" && attempt < 2) {
                    attempt++
                    kotlinx.coroutines.delay(2_000L * attempt)
                    continue
                }
                throw e
            }
        }
        @Suppress("UNREACHABLE_CODE") ChatReply("", emptyList())
    }

    private fun turnJson(t: ChatTurn): JSONObject {
        if (t.role == ChatTurn.Role.ASSISTANT) return JSONObject().put("role", "assistant").put("content", t.text)
        if (t.attachments.isEmpty()) return JSONObject().put("role", "user").put("content", t.text)
        val parts = JSONArray().put(JSONObject().put("type", "input_text").put("text", t.text))
        t.attachments.forEach { a ->
            val dataUrl = "data:${a.mime};base64,${a.base64}"
            parts.put(
                if (a.isImage) JSONObject().put("type", "input_image").put("image_url", dataUrl)
                else JSONObject().put("type", "input_file").put("filename", a.name).put("file_data", dataUrl)
            )
        }
        return JSONObject().put("role", "user").put("content", parts)
    }

    private suspend fun stream(payload: String, onDelta: (String) -> Unit): ChatReply {
        val conn = open("$BASE/responses", ChatGptPlanAuth.accessToken(prefs)).apply {
            requestMethod = "POST"; doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
        }
        try {
            conn.outputStream.use { it.write(payload.toByteArray()) }
            if (conn.responseCode !in 200..299) readOrThrow(conn)
            val out = StringBuilder()
            val sources = LinkedHashMap<String, Source>()
            var completed = false
            conn.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    currentCoroutineContext().ensureActive()
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty() || data == "[DONE]") continue
                    val ev = JSONObject(data)
                    when (ev.optString("type")) {
                        "response.output_text.delta" -> { out.append(ev.optString("delta")); onDelta(out.toString()) }
                        "response.output_text.annotation.added" -> ev.optJSONObject("annotation")?.let { a ->
                            val url = a.optString("url")
                            if (a.optString("type") == "url_citation" && url.startsWith("https://") && url !in sources) {
                                sources[url] = Source(url, a.optString("title").ifBlank { url })
                            }
                        }
                        "response.output_item.done" -> ev.optJSONObject("item")
                            ?.takeIf { it.optString("type") == "web_search_call" }
                            ?.optJSONObject("action")?.optJSONArray("sources")?.let { list ->
                                for (i in 0 until list.length()) {
                                    val src = list.optJSONObject(i) ?: continue
                                    val url = src.optString("url")
                                    when {
                                        url.startsWith("https://") && url !in sources ->
                                            sources[url] = Source(url, src.optString("title").ifBlank { url })
                                        src.optString("type") == "api" && src.optString("name").isNotBlank() ->
                                            sources.putIfAbsent("api:" + src.optString("name"), Source("", src.optString("name")))
                                    }
                                }
                            }
                        "response.completed" -> { completed = true; break }
                        "response.failed" -> throw planError(ev.optJSONObject("response")?.optJSONObject("error")?.optString("code").orEmpty(), 0)
                        "response.incomplete" -> throw RemoteAiException(RemoteAiException.Kind.BAD_RESPONSE, "incomplete")
                    }
                }
            }
            // The guide requires response.completed to accept the answer as valid
            if (!completed) throw RemoteAiException(RemoteAiException.Kind.BAD_RESPONSE, "incomplete")
            return ChatReply(out.toString(), sources.values.toList())
        } catch (e: IOException) {
            throw RemoteAiException(RemoteAiException.Kind.NETWORK, e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, token: String): HttpURLConnection = SendaNet.open(url).apply {
        connectTimeout = 20_000
        // With high reasoning and searches the answer can take minutes
        readTimeout = 300_000
        setRequestProperty("Authorization", "Bearer $token")
    }

    private fun readOrThrow(conn: HttpURLConnection): String {
        val code = try { conn.responseCode } catch (e: IOException) {
            throw RemoteAiException(RemoteAiException.Kind.NETWORK, e.javaClass.simpleName)
        }
        if (code in 200..299) return conn.inputStream.bufferedReader().use { it.readText() }
        val err = try { conn.errorStream?.bufferedReader()?.use { it.readText() } } catch (_: IOException) { null }.orEmpty()
        val errCode = runCatching { JSONObject(err).optJSONObject("error")?.optString("code") }.getOrNull().orEmpty()
        throw planError(errCode, code)
    }

    /** Codes from the "Errors and recovery" guide and the action they call for. */
    private fun planError(code: String, http: Int): RemoteAiException {
        android.util.Log.w("SendaChatGpt", "Respuesta del plan: HTTP $http código=$code")
        return when (code) {
            "subscription_sharing_usage_limit_exceeded" -> RemoteAiException(RemoteAiException.Kind.USAGE_LIMIT, code)
            "subscription_sharing_user_not_eligible" -> RemoteAiException(RemoteAiException.Kind.NOT_ELIGIBLE, code)
            "subscription_sharing_invalid_user" -> RemoteAiException(RemoteAiException.Kind.AUTH, code)
            "subscription_sharing_usage_unavailable" -> RemoteAiException(RemoteAiException.Kind.RATE_LIMIT, code)
            else -> when (http) {
                401, 403 -> RemoteAiException(RemoteAiException.Kind.AUTH, "HTTP $http $code")
                429 -> RemoteAiException(RemoteAiException.Kind.RATE_LIMIT, "HTTP 429 $code")
                else -> RemoteAiException(RemoteAiException.Kind.BAD_RESPONSE, "HTTP $http $code".trim())
            }
        }
    }

    companion object {
        private const val BASE = "https://api.openai.com/v1"
        /** OpenAI page where the user sees and adjusts what this app can use from their plan. */
        const val MANAGE_USAGE_URL = "https://chatgpt.com/settings/usage"
        /** Where images can be created with the plan (OpenAI does not allow it here). */
        const val CHATGPT_WEB = "https://chatgpt.com"

        /** What it can do here, for the model (not shown to the user). */
        private const val CAPABILITIES = "Capabilities in Senda: you have a web_search tool; use it for current events, " +
            "prices, schedules, people, places or anything you are not sure about, and cite the sources. You can read " +
            "the photos and PDF files the user attaches. You cannot create or edit images here: if asked, say that " +
            "image creation is available at chatgpt.com with the same ChatGPT plan."
    }
}
