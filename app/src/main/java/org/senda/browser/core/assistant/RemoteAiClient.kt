package org.senda.browser.core.assistant

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.senda.browser.core.SendaNet
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration

/** Un turno de la conversación tal como se envía al proveedor. */
data class ChatTurn(val role: Role, val text: String) {
    enum class Role { USER, ASSISTANT }
}

/** Fallo explicable al usuario. El mensaje nunca contiene la clave. */
class RemoteAiException(val kind: Kind, detail: String = "") : Exception(detail) {
    enum class Kind { NOT_CONFIGURED, INSECURE_URL, AUTH, RATE_LIMIT, NETWORK, REFUSED, BAD_RESPONSE }
}

interface RemoteAiClient {
    /** Modelos que ofrece el proveedor con esta clave (para elegir, no se inventan nombres). */
    suspend fun listModels(): List<String>

    /** Respuesta completa; [onDelta] recibe el texto acumulado mientras llega. */
    suspend fun chat(model: String, system: String, turns: List<ChatTurn>, onDelta: (String) -> Unit): String
}

object RemoteAiClients {

    /** Cliente para el proveedor configurado, o error explicable si falta algo. */
    fun create(provider: RemoteAiProvider, apiKey: String, serverUrl: String): RemoteAiClient {
        return when (provider.style) {
            RemoteAiProvider.Style.ANTHROPIC -> {
                if (apiKey.isBlank()) throw RemoteAiException(RemoteAiException.Kind.NOT_CONFIGURED)
                ClaudeClient(apiKey)
            }
            RemoteAiProvider.Style.OPENAI_COMPATIBLE -> {
                val base = (provider.baseUrl ?: serverUrl).trim().trimEnd('/')
                if (base.isBlank()) throw RemoteAiException(RemoteAiException.Kind.NOT_CONFIGURED)
                checkTransport(base)
                if (!provider.isOwnServer && apiKey.isBlank()) throw RemoteAiException(RemoteAiException.Kind.NOT_CONFIGURED)
                OpenAiCompatibleClient(base, apiKey)
            }
        }
    }

    /**
     * Por http:// sin cifrar solo a la red local (el servidor de casa). Hacia Internet, la clave, lo que el
     * usuario escribe y las páginas viajarían legibles por cualquiera en el camino.
     */
    fun checkTransport(base: String) {
        val url = try { URL(base) } catch (e: Exception) { throw RemoteAiException(RemoteAiException.Kind.INSECURE_URL, "URL") }
        if (url.protocol == "https") return
        if (url.protocol == "http" && SendaNet.isLocal(url.host)) return
        throw RemoteAiException(RemoteAiException.Kind.INSECURE_URL, url.host)
    }
}

/** OpenAI, Gemini, xAI, Mistral y servidores propios (Ollama, llama.cpp, LM Studio…): /models y /chat/completions. */
private class OpenAiCompatibleClient(private val base: String, private val apiKey: String) : RemoteAiClient {

    override suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val conn = open("$base/models").apply { requestMethod = "GET" }
        try {
            val body = readOrThrow(conn)
            val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
            (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotBlank() } }
                // Gemini responde «models/gemini-…»; en /chat/completions se usa sin el prefijo
                .map { it.removePrefix("models/") }.distinct().sorted()
        } finally {
            conn.disconnect()
        }
    }

    override suspend fun chat(model: String, system: String, turns: List<ChatTurn>, onDelta: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
            turns.forEach { t ->
                messages.put(JSONObject().put("role", if (t.role == ChatTurn.Role.USER) "user" else "assistant").put("content", t.text))
            }
            val payload = JSONObject().put("model", model).put("messages", messages).put("stream", true)
            val conn = open("$base/chat/completions").apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "text/event-stream")
            }
            try {
                conn.outputStream.use { it.write(payload.toString().toByteArray()) }
                if (conn.responseCode !in 200..299) readOrThrow(conn)
                val out = StringBuilder()
                var filtered = false
                conn.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        currentCoroutineContext().ensureActive()
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        val choice = JSONObject(data).optJSONArray("choices")?.optJSONObject(0) ?: continue
                        if (choice.optString("finish_reason") == "content_filter") filtered = true
                        val delta = choice.optJSONObject("delta")?.optString("content", "") ?: ""
                        if (delta.isNotEmpty() && delta != "null") {
                            out.append(delta)
                            onDelta(out.toString())
                        }
                    }
                }
                if (filtered && out.isBlank()) throw RemoteAiException(RemoteAiException.Kind.REFUSED)
                out.toString()
            } catch (e: IOException) {
                throw RemoteAiException(RemoteAiException.Kind.NETWORK, e.javaClass.simpleName)
            } finally {
                conn.disconnect()
            }
        }

    private fun open(url: String): HttpURLConnection = SendaNet.open(url).apply {
        connectTimeout = 20_000
        readTimeout = 120_000
        if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
    }

    private fun readOrThrow(conn: HttpURLConnection): String {
        val code = try { conn.responseCode } catch (e: IOException) {
            throw RemoteAiException(RemoteAiException.Kind.NETWORK, e.javaClass.simpleName)
        }
        if (code in 200..299) return conn.inputStream.bufferedReader().use { it.readText() }
        // Solo el código y un fragmento del error del proveedor (nunca contiene la clave enviada)
        val err = try { conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(300) } catch (_: IOException) { null } ?: ""
        throw RemoteAiException(
            when (code) {
                401, 403 -> RemoteAiException.Kind.AUTH
                429 -> RemoteAiException.Kind.RATE_LIMIT
                else -> RemoteAiException.Kind.BAD_RESPONSE
            },
            "HTTP $code $err"
        )
    }
}

/** Claude con el SDK oficial de Anthropic, por el mismo Tor o proxy que la navegación. */
private class ClaudeClient(apiKey: String) : RemoteAiClient {

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .proxy(SendaNet.proxyFor("api.anthropic.com"))
        .timeout(Duration.ofMinutes(10))
        .maxRetries(2)
        .build()

    override suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        guarded { client.models().list().autoPager().map { it.id() }.toList() }
    }

    override suspend fun chat(model: String, system: String, turns: List<ChatTurn>, onDelta: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            guarded {
                val builder = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(64_000L)
                    .system(system)
                    .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.MEDIUM).build())
                turns.forEach { t ->
                    if (t.role == ChatTurn.Role.USER) builder.addUserMessage(t.text) else builder.addAssistantMessage(t.text)
                }
                // Si el modelo declina, el servidor de Anthropic repite la petición con el modelo de respaldo que
                // recomienda según el motivo (fallbacks «default»). Solo en los modelos que lo admiten
                if (model in SERVER_FALLBACK_MODELS) {
                    builder.addBeta("server-side-fallback-2026-07-01")
                    builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                }
                val out = StringBuilder()
                var refused = false
                // Si el usuario cierra el chat, se corta el flujo: seguir leyendo gastaría tokens que paga él
                val job = currentCoroutineContext()[kotlinx.coroutines.Job]
                client.beta().messages().createStreaming(builder.build()).use { stream ->
                    stream.stream().forEach { event ->
                        if (job?.isActive == false) throw kotlinx.coroutines.CancellationException()
                        event.contentBlockDelta().flatMap { it.delta().text() }.ifPresent { d ->
                            out.append(d.text())
                            onDelta(out.toString())
                        }
                        event.messageDelta().flatMap { it.delta().stopReason() }.ifPresent { r ->
                            refused = r == BetaStopReason.REFUSAL
                        }
                    }
                }
                // Una negativa a mitad de respuesta deja texto parcial: no se presenta como respuesta completa
                if (refused) throw RemoteAiException(RemoteAiException.Kind.REFUSED)
                out.toString()
            }
        }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: RemoteAiException) {
        throw e
    } catch (e: UnauthorizedException) {
        throw RemoteAiException(RemoteAiException.Kind.AUTH, "HTTP 401")
    } catch (e: PermissionDeniedException) {
        throw RemoteAiException(RemoteAiException.Kind.AUTH, "HTTP 403")
    } catch (e: RateLimitException) {
        throw RemoteAiException(RemoteAiException.Kind.RATE_LIMIT, "HTTP 429")
    } catch (e: AnthropicIoException) {
        throw RemoteAiException(RemoteAiException.Kind.NETWORK, e.javaClass.simpleName)
    } catch (e: AnthropicServiceException) {
        throw RemoteAiException(RemoteAiException.Kind.BAD_RESPONSE, "HTTP ${e.statusCode()}")
    }

    companion object {
        /** Modelos que aceptan fallbacks «default» en la API de Anthropic (guía oficial, 2026-09). */
        private val SERVER_FALLBACK_MODELS = setOf("claude-opus-5-5", "claude-fable-5-1", "claude-opus-5", "claude-sonnet-5-5")
    }
}
