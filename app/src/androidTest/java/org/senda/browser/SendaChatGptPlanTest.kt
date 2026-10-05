package org.senda.browser

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.senda.browser.core.PreferencesManager
import org.senda.browser.core.assistant.ChatGptPlanAuth
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * «Sign in with ChatGPT» sin cuenta real: la URL que acepta OpenAI, la escucha local en 127.0.0.1:1455, la
 * comprobación de state y el canje en el endpoint de tokens (un código falso debe ser rechazado).
 * No guarda sesión: solo el identificador de instalación (ext_agent_host_id), que es necesario de todos modos.
 */
@RunWith(AndroidJUnit4::class)
class SendaChatGptPlanTest {

    private fun log(msg: String) = Log.i("SendaChatGptTest", msg)

    @Test
    fun loopbackFlowReachesTokenEndpoint() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = PreferencesManager(context)
        // Con una sesión real del usuario no se prueba: el inicio de sesión de prueba usaría su cliente y su id_token
        org.junit.Assume.assumeTrue("Hay una sesión real de ChatGPT en este teléfono", !ChatGptPlanAuth.isSignedIn(prefs) && prefs.assistantChatGptClientId.isBlank())
        var authorizeUrl = ""
        try {
            ChatGptPlanAuth.signIn(context, prefs) { url ->
                authorizeUrl = url
                // En vez de abrir el navegador, el «navegador» vuelve al instante con un código falso
                val q = url.substringAfter('?').split('&').associate {
                    it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
                }
                Thread {
                    Thread.sleep(500)
                    val cb = URL("http://127.0.0.1:1455/auth/callback?code=codigo-falso&state=${q["state"]}&client_id=dynamic_agent_client")
                    (cb.openConnection() as HttpURLConnection).apply { connectTimeout = 5000; readTimeout = 5000 }.run {
                        log("callback respondió $responseCode"); disconnect()
                    }
                }.start()
            }
            fail("Un código falso no puede dar una sesión")
        } catch (e: ChatGptPlanAuth.AuthException) {
            log("rechazado como se esperaba: ${e.code}")
            assertTrue("Debe llegar al endpoint de tokens y ser rechazado, no fallar antes: ${e.code}",
                e.code != "state_mismatch" && e.code != "port_in_use" && e.code != "no_code")
        }
        log("URL: ${authorizeUrl.take(140)}…")
        assertTrue(authorizeUrl.startsWith("https://auth.openai.com/api/accounts/authorize?"))
        assertTrue(prefs.assistantChatGptHostId.startsWith("urn:uuid:"))
        assertEquals(false, ChatGptPlanAuth.isSignedIn(prefs))

        // La URL generada en el teléfono la acepta OpenAI (302 a su inicio de sesión, no 400)
        val probe = (URL(authorizeUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false; connectTimeout = 15000; readTimeout = 15000
            setRequestProperty("User-Agent", org.senda.browser.core.SendaNet.USER_AGENT)
        }
        log("authorize → ${probe.responseCode} ${probe.getHeaderField("Location")}")
        assertTrue(probe.responseCode in 300..399)
        probe.disconnect()
    }
}
