package org.senda.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.senda.browser.core.assistant.ApiProvider
import org.senda.browser.core.assistant.ChatTurn
import org.senda.browser.core.assistant.RemoteAiException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Clientes por clave de API sin claves reales: un servidor en 127.0.0.1 responde con los formatos documentados
 * (OpenAI-compatible: /models y /chat/completions en SSE; Anthropic: /v1/models y /v1/messages en SSE). Comprueba
 * que Senda lista modelos, junta el texto que llega por partes, envía la clave en la cabecera correcta y traduce
 * los errores. No comprueba el comportamiento real de cada empresa.
 */
@RunWith(AndroidJUnit4::class)
class SendaApiClientsTest {

    private lateinit var server: ServerSocket
    private val requests = mutableListOf<String>()
    @Volatile private var nextStatus = 200

    @Before
    fun start() {
        server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: Exception) { break }
                s.use { sock ->
                    val input = sock.getInputStream().bufferedReader()
                    val head = StringBuilder(); var len = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        head.append(line).append('\n')
                        if (line.startsWith("Content-Length:", true)) len = line.substringAfter(':').trim().toInt()
                    }
                    val body = CharArray(len).also { if (len > 0) input.read(it, 0, len) }.concatToString()
                    synchronized(requests) { requests += head.toString() + body }
                    val path = head.lineSequence().first().split(' ')[1]
                    val retired = body.contains("\"model\":\"gemini-9-flash\"")
                    val (type, text) = when {
                        nextStatus != 200 -> "application/json" to """{"error":{"message":"bad key"}}"""
                        retired -> "application/json" to """{"error":{"code":404,"message":"This model models/gemini-9-flash is no longer available to new users."}}"""
                        path.contains("/models") && path.contains("auto") -> "application/json" to """{"data":[{"id":"models/gemini-9-flash"},{"id":"models/gemini-8-flash"}]}"""
                        path.contains("/models") && path.contains("anthropic") ->
                            "application/json" to """{"data":[{"id":"claude-opus-5-5","type":"model"},{"id":"claude-sonnet-5-5","type":"model"}]}"""
                        path.contains("/models") -> "application/json" to """{"data":[{"id":"models/gemini-3-pro"},{"id":"grok-5"}]}"""
                        path.endsWith("/messages") -> "text/event-stream" to listOf(
                            """event: message_start""" + "\n" + """data: {"type":"message_start","message":{"id":"m"}}""",
                            """event: content_block_delta""" + "\n" + """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hola "}}""",
                            """event: content_block_delta""" + "\n" + """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"mundo"}}""",
                            """event: message_stop""" + "\n" + """data: {"type":"message_stop"}"""
                        ).joinToString("\n\n", postfix = "\n\n")
                        else -> "text/event-stream" to listOf(
                            """data: {"choices":[{"index":0,"delta":{"role":"assistant","content":""}}]}""",
                            """data: {"choices":[{"index":0,"delta":{"content":"Hola "}}]}""",
                            """data: {"choices":[{"index":0,"delta":{"content":"mundo"},"finish_reason":"stop"}]}""",
                            "data: [DONE]"
                        ).joinToString("\n\n", postfix = "\n\n")
                    }
                    val bytes = text.toByteArray()
                    val status = if (retired) "404 Not Found" else if (nextStatus == 200) "200 OK" else "$nextStatus Error"
                    sock.getOutputStream().write("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray() + bytes)
                }
            }
        }
    }

    @After
    fun stop() = server.close()

    private val base get() = "http://127.0.0.1:${server.localPort}"

    @Test
    fun openAiCompatibleListsAndStreams() = runBlocking {
        // Grok y Mistral muestran todos los modelos; Gemini solo los de conversación de Gemini
        assertEquals(listOf("gemini-3-pro", "grok-5"), ApiProvider.XAI.clientAt("$base/openai", "clave-de-prueba").listModels())
        val client = ApiProvider.GEMINI.clientAt("$base/openai", "clave-de-prueba")
        assertEquals(listOf("gemini-3-pro"), client.listModels())
        val parts = mutableListOf<String>()
        val reply = client.chat("gemini-3-pro", "sistema", listOf(ChatTurn(ChatTurn.Role.USER, "hola")), deep = false) { parts += it }
        assertEquals("Hola mundo", reply.text)
        assertTrue("Debe llegar por partes", parts.size >= 2)
        val sent = synchronized(requests) { requests.last() }
        assertTrue(sent.contains("Authorization: Bearer clave-de-prueba"))
        assertTrue(sent.contains("\"stream\":true") && sent.contains("\"role\":\"system\""))
        Unit
    }

    @Test
    fun anthropicListsAndStreams() = runBlocking {
        val client = ApiProvider.ANTHROPIC.clientAt("$base/anthropic/v1", "clave-de-prueba")
        assertEquals(listOf("claude-opus-5-5", "claude-sonnet-5-5"), client.listModels())
        val reply = client.chat("claude-opus-5-5", "sistema", listOf(ChatTurn(ChatTurn.Role.USER, "hola")), deep = false) {}
        assertEquals("Hola mundo", reply.text)
        val sent = synchronized(requests) { requests.last() }
        assertTrue(sent.contains("x-api-key: clave-de-prueba") && sent.contains("anthropic-version: 2023-06-01"))
        assertTrue(sent.contains("\"system\":\"sistema\"") && sent.contains("\"max_tokens\""))
        Unit
    }

    @Test
    fun badKeyIsReportedAsAuth() = runBlocking {
        nextStatus = 401
        try {
            ApiProvider.XAI.clientAt(base, "mala").listModels()
            fail("Debía fallar")
        } catch (e: RemoteAiException) {
            assertEquals(RemoteAiException.Kind.AUTH, e.kind)
        }
        Unit
    }

    /** Modelo automático: se salta el retirado (404) y se queda con el primero que responde. */
    @Test
    fun autoModelSkipsRetired() = runBlocking {
        val client = ApiProvider.GEMINI.clientAt("$base/auto", "clave-de-prueba")
        // Primero el flash de versión más alta (gemini-9-flash, retirado en el simulador)
        assertEquals(listOf("gemini-9-flash", "gemini-8-flash"), client.listModels())
        assertEquals("gemini-8-flash", org.senda.browser.core.assistant.SendaAssistant.autoSelectModel(client))
    }
}
