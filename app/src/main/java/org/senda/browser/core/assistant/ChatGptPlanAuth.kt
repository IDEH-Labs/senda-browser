package org.senda.browser.core.assistant

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.senda.browser.core.PreferencesManager
import org.senda.browser.core.SendaNet
import java.io.IOException
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.UUID

/**
 * «Sign in with ChatGPT» para usar el plan Plus/Pro del usuario (OpenAI, disponible para proyectos de código
 * abierto desde el 29-09-2026). Contrato oficial de developers.openai.com/siwc/token-sharing-open-source:
 * OAuth con PKCE S256, registro dinámico (client_id=dynamic_agent_client), vuelta a un puerto local
 * 127.0.0.1, validación del ID token contra el JWKS de auth.openai.com y tokens guardados cifrados.
 * Todo por SendaNet (el mismo Tor o proxy que la navegación).
 */
object ChatGptPlanAuth {

    private const val ISSUER = "https://auth.openai.com"
    private const val AUTHORIZE = "$ISSUER/api/accounts/authorize"
    private const val TOKEN = "$ISSUER/api/accounts/oauth/token"
    private const val REVOKE = "$ISSUER/api/accounts/oauth/revoke"
    private const val JWKS = "$ISSUER/.well-known/jwks.json"
    private const val RESOURCE = "https://api.openai.com/v1"
    private const val SCOPE = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
    private const val PORT = 1455
    private const val CALLBACK_PATH = "/auth/callback"
    private const val REDIRECT = "http://127.0.0.1:$PORT$CALLBACK_PATH"
    private const val APP_NAME = "Senda"
    /** Tiempo para iniciar sesión en el navegador antes de cancelar. */
    private const val LOGIN_TIMEOUT_MS = 5 * 60_000L

    /** Sesión guardada (sin el token en claro fuera de memoria). */
    data class Session(val clientId: String, val email: String?, val accessToken: String, val refreshToken: String?, val idToken: String, val expiresAt: Long)

    class AuthException(val code: String) : Exception(code)

    sealed interface SignInState {
        data object Idle : SignInState
        data object Waiting : SignInState
        data class Done(val email: String?) : SignInState
        data class Failed(val code: String) : SignInState
    }

    private val _signInState = kotlinx.coroutines.flow.MutableStateFlow<SignInState>(SignInState.Idle)

    /** Progreso del inicio de sesión, para la pantalla que esté abierta cuando vuelva el navegador. */
    val signInState: kotlinx.coroutines.flow.StateFlow<SignInState> = _signInState

    private val appScope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    private var signInJob: kotlinx.coroutines.Job? = null

    /**
     * Inicia sesión fuera de la pantalla: abrir la página de OpenAI cambia a una pestaña y puede cerrar Ajustes,
     * y con ello cancelaría la espera de la vuelta a 127.0.0.1.
     */
    fun startSignIn(context: Context, prefs: PreferencesManager, openUrl: (String) -> Unit) {
        if (_signInState.value == SignInState.Waiting) return
        injectedCallback = null
        _signInState.value = SignInState.Waiting
        signInJob = appScope.launch {
            _signInState.value = try {
                SignInState.Done(signIn(context.applicationContext, prefs, openUrl).email).also { done ->
                    // El usuario está en la pestaña de OpenAI, no en Ajustes: confirmarle que ya puede volver
                    val strings = org.senda.browser.core.SendaStrings.get(prefs.appLanguage, context)
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(context.applicationContext,
                            strings.as_chatgpt_signed_in.format(done.email ?: "ChatGPT"), android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: AuthException) {
                // Solo el código del error (nunca tokens ni el código de autorización)
                android.util.Log.w("SendaChatGpt", "Inicio de sesión fallido: ${e.code}")
                SignInState.Failed(e.code)
            } catch (e: kotlinx.coroutines.CancellationException) {
                SignInState.Idle
            } catch (e: RemoteAiException) {
                android.util.Log.w("SendaChatGpt", "Inicio de sesión fallido: ${e.kind} ${e.message}")
                SignInState.Failed(e.kind.name.lowercase())
            } catch (e: Exception) {
                SignInState.Failed(e.javaClass.simpleName)
            }
        }
    }

    /** Vuelve a Idle tras mostrar el resultado. */
    fun acknowledge() { if (_signInState.value != SignInState.Waiting) _signInState.value = SignInState.Idle }

    fun isSignedIn(prefs: PreferencesManager): Boolean = load(prefs) != null

    fun email(prefs: PreferencesManager): String? = load(prefs)?.email

    /** Identificador estable y distinto por instalación (ext_agent_host_id), guardado antes del primer inicio. */
    private fun hostId(prefs: PreferencesManager): String =
        // Formato «urn:uuid:…» de la guía oficial: un UUID suelto lo rechaza OpenAI (invalid_authorize_request)
        prefs.assistantChatGptHostId.ifBlank { "urn:uuid:${UUID.randomUUID()}".also { prefs.assistantChatGptHostId = it } }

    /**
     * Inicia sesión: abre el navegador del sistema (Senda u otro) en la página de OpenAI y espera la vuelta en
     * 127.0.0.1. [openUrl] abre la URL; la función vuelve cuando la sesión queda guardada.
     */
    suspend fun signIn(context: Context, prefs: PreferencesManager, openUrl: (String) -> Unit): Session {
        // 32 bytes en base64url (43 caracteres) para verificador, state y nonce: igual que el kit oficial de OpenAI
        val verifier = randomUrlSafe(32)
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = randomUrlSafe(32)
        val nonce = randomUrlSafe(32)
        val previous = load(prefs)
        val params = linkedMapOf(
            "client_id" to (previous?.clientId ?: prefs.assistantChatGptClientId.ifBlank { "dynamic_agent_client" }),
            "ext_agent_host_id" to hostId(prefs),
            "response_type" to "code",
            "redirect_uri" to REDIRECT,
            "scope" to SCOPE,
            "resource" to RESOURCE,
            "state" to state,
            "nonce" to nonce,
            "code_challenge_method" to "S256",
            "code_challenge" to challenge
        )
        if (params["client_id"] == "dynamic_agent_client") params["agent_name_hint"] = APP_NAME
        previous?.idToken?.let { params["id_token_hint"] = it }
        val url = AUTHORIZE + "?" + params.entries.joinToString("&") { "${it.key}=${enc(it.value)}" }

        return withContext(Dispatchers.IO) {
            val server = try {
                ServerSocket(PORT, 1, InetAddress.getByName("127.0.0.1"))
            } catch (e: IOException) {
                throw AuthException("port_in_use")
            }
            server.use { srv ->
                withContext(Dispatchers.Main) { openUrl(url) }
                // accept() bloquea el hilo: con soTimeout se revisa cada segundo si se canceló o venció el plazo
                srv.soTimeout = 1_000
                val callback = awaitCallback(srv, System.currentTimeMillis() + LOGIN_TIMEOUT_MS)
                if (callback["state"] != state) throw AuthException("state_mismatch")
                callback["error"]?.let { throw AuthException(it) }
                val code = callback["code"] ?: throw AuthException("no_code")
                val clientId = callback["client_id"] ?: params.getValue("client_id")
                if (clientId == "dynamic_agent_client" || !Regex("^[a-zA-Z0-9_-]{1,200}$").matches(clientId)) throw AuthException("registration_incomplete")
                // Como el kit oficial: guardar el cliente emitido antes del canje, para que un nuevo intento no
                // registre otra app si el código caduca o falla
                prefs.assistantChatGptClientId = clientId
                android.util.Log.i("SendaChatGpt", "Canje: cliente=${clientId.take(12)}… verificador=${verifier.length} car., " +
                    "reto coincide=${b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())) == challenge}, parámetros de vuelta=${callback.keys}")
                val tokens = postForm(TOKEN, mapOf(
                    "grant_type" to "authorization_code", "code" to code, "client_id" to clientId,
                    "code_verifier" to verifier, "redirect_uri" to REDIRECT, "resource" to RESOURCE
                ))
                val idToken = tokens.optString("id_token").ifBlank { throw AuthException("no_id_token") }
                val claims = verifyIdToken(idToken, clientId, nonce)
                val session = Session(
                    clientId = clientId,
                    email = claims.optString("email").ifBlank { null },
                    accessToken = tokens.getString("access_token"),
                    refreshToken = tokens.optString("refresh_token").ifBlank { null },
                    idToken = idToken,
                    expiresAt = System.currentTimeMillis() + tokens.optLong("expires_in", 3600) * 1000
                )
                prefs.assistantChatGptClientId = clientId
                save(prefs, session)
                session
            }
        }
    }

    /** Token de acceso vigente; renueva antes de que caduque. Null si no hay sesión o ya no sirve. */
    suspend fun accessToken(prefs: PreferencesManager): String = withContext(Dispatchers.IO) {
        val s = load(prefs) ?: throw RemoteAiException(RemoteAiException.Kind.NOT_CONFIGURED)
        if (s.expiresAt - 120_000 > System.currentTimeMillis()) return@withContext s.accessToken
        val refresh = s.refreshToken ?: run { clear(prefs); throw RemoteAiException(RemoteAiException.Kind.AUTH, "expired") }
        val json = try {
            postForm(TOKEN, mapOf("grant_type" to "refresh_token", "client_id" to s.clientId, "refresh_token" to refresh, "resource" to RESOURCE))
        } catch (e: AuthException) {
            // Token de renovación inválido o caducado: hay que volver a iniciar sesión (guía «Errors and recovery»)
            clear(prefs)
            throw RemoteAiException(RemoteAiException.Kind.AUTH, e.code)
        }
        val updated = s.copy(
            accessToken = json.getString("access_token"),
            refreshToken = json.optString("refresh_token").ifBlank { refresh },
            idToken = json.optString("id_token").ifBlank { s.idToken },
            expiresAt = System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000
        )
        save(prefs, updated)
        updated.accessToken
    }

    /** Cierra sesión: revoca el token de renovación en OpenAI y borra todo lo guardado. */
    fun signOut(prefs: PreferencesManager) {
        val s = load(prefs) ?: return
        // Primero se borra del teléfono (inmediato); la revocación en OpenAI sigue aunque se cierre la pantalla
        clear(prefs)
        val rt = s.refreshToken ?: return
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob()).launch {
            try { postForm(REVOKE, mapOf("token" to rt, "token_type_hint" to "refresh_token", "client_id" to s.clientId)) } catch (_: Exception) {}
        }
    }

    /** Vuelta entregada por la propia Senda (ver deliverCallback) mientras se espera. */
    @Volatile private var injectedCallback: Map<String, String>? = null

    /**
     * Si [url] es la vuelta del inicio de sesión en curso, la entrega y devuelve true (la pestaña no debe cargarla).
     * Cuando el inicio de sesión ocurre en la propia Senda, el modo solo HTTPS impide cargar http://127.0.0.1.
     */
    fun deliverCallback(url: String): Boolean {
        if (_signInState.value != SignInState.Waiting || !url.startsWith(REDIRECT)) return false
        injectedCallback = parseQuery(url.substringAfter('?', ""))
        android.util.Log.i("SendaChatGpt", "Vuelta de OpenAI recibida dentro de Senda")
        return true
    }

    private fun parseQuery(q: String): Map<String, String> = q.split('&').filter { it.contains('=') }.associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }

    /** Espera una sola petición GET /auth/callback?… y responde una página sencilla. */
    private suspend fun awaitCallback(server: ServerSocket, deadline: Long): Map<String, String> {
        while (true) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (System.currentTimeMillis() > deadline) throw AuthException("timeout")
            injectedCallback?.let { injectedCallback = null; return it }
            val accepted = try { server.accept() } catch (_: java.net.SocketTimeoutException) { continue }
            accepted.use { socket ->
                socket.soTimeout = 10_000
                val reader = socket.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: return@use
                val target = requestLine.split(" ").getOrNull(1) ?: return@use
                val ok = target.startsWith(CALLBACK_PATH)
                val body = if (ok)
                    "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>" +
                        "<body style='font-family:sans-serif;padding:2em'><h2>Senda</h2><p>✓</p></body>"
                else "Not found"
                val bytes = body.toByteArray()
                socket.getOutputStream().apply {
                    write(("HTTP/1.1 ${if (ok) "200 OK" else "404 Not Found"}\r\nContent-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                    write(bytes)
                    flush()
                }
                if (ok) return parseQuery(target.substringAfter('?', ""))
            }
        }
    }

    private fun postForm(url: String, fields: Map<String, String>): JSONObject {
        val conn = SendaNet.open(url).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 20_000; readTimeout = 30_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
        }
        try {
            conn.outputStream.use { it.write(fields.entries.joinToString("&") { "${it.key}=${enc(it.value)}" }.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val json = runCatching { JSONObject(text) }.getOrNull()
                val err = json?.optString("error").orEmpty()
                // La descripción de OpenAI explica el rechazo; no contiene el código ni los tokens
                android.util.Log.w("SendaChatGpt", "HTTP $code en ${url.substringAfterLast('/')}: $err — ${json?.optString("error_description")?.take(300)} (campos: ${fields.keys})")
                throw AuthException(err.ifBlank { "http_$code" })
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } catch (e: IOException) {
            throw RemoteAiException(RemoteAiException.Kind.NETWORK, e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    /** Firma RS256 contra el JWKS de OpenAI y comprobaciones de emisor, audiencia, caducidad y nonce. */
    private fun verifyIdToken(jwt: String, clientId: String, nonce: String): JSONObject {
        val parts = jwt.split('.')
        if (parts.size != 3) throw AuthException("bad_id_token")
        val header = JSONObject(String(b64urlDecode(parts[0])))
        val claims = JSONObject(String(b64urlDecode(parts[1])))
        if (header.optString("alg") != "RS256") throw AuthException("bad_alg")
        val kid = header.optString("kid")
        val conn = SendaNet.open(JWKS).apply { connectTimeout = 20_000; readTimeout = 30_000 }
        val keys = try { JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).getJSONArray("keys") } finally { conn.disconnect() }
        val jwk = (0 until keys.length()).map { keys.getJSONObject(it) }.firstOrNull { it.optString("kid") == kid }
            ?: throw AuthException("unknown_kid")
        val key = KeyFactory.getInstance("RSA").generatePublic(
            RSAPublicKeySpec(BigInteger(1, b64urlDecode(jwk.getString("n"))), BigInteger(1, b64urlDecode(jwk.getString("e"))))
        )
        val valid = Signature.getInstance("SHA256withRSA").run {
            initVerify(key); update("${parts[0]}.${parts[1]}".toByteArray()); verify(b64urlDecode(parts[2]))
        }
        if (!valid) throw AuthException("bad_signature")
        if (claims.optString("iss") != ISSUER) throw AuthException("bad_issuer")
        val aud = claims.opt("aud")
        val audOk = if (aud is org.json.JSONArray) (0 until aud.length()).any { aud.optString(it) == clientId } else aud == clientId
        if (!audOk) throw AuthException("bad_audience")
        if (claims.optLong("exp") * 1000 < System.currentTimeMillis() - 60_000) throw AuthException("expired_id_token")
        if (claims.optString("nonce") != nonce) throw AuthException("bad_nonce")
        return claims
    }

    // Tokens cifrados con el almacén de claves del teléfono (mismo mecanismo que las claves de API)
    private fun save(prefs: PreferencesManager, s: Session) {
        val json = JSONObject().put("clientId", s.clientId).put("email", s.email ?: "").put("access", s.accessToken)
            .put("refresh", s.refreshToken ?: "").put("id", s.idToken).put("exp", s.expiresAt)
        if (!prefs.setAssistantKey(TOKENS_KEY, json.toString())) throw AuthException("cannot_encrypt")
    }

    private fun load(prefs: PreferencesManager): Session? {
        val raw = prefs.getAssistantKey(TOKENS_KEY).ifBlank { return null }
        return try {
            val j = JSONObject(raw)
            Session(j.getString("clientId"), j.optString("email").ifBlank { null }, j.getString("access"),
                j.optString("refresh").ifBlank { null }, j.getString("id"), j.getLong("exp"))
        } catch (_: Exception) {
            null
        }
    }

    private fun clear(prefs: PreferencesManager) = prefs.setAssistantKey(TOKENS_KEY, "")

    private const val TOKENS_KEY = "chatgpt_plan_tokens"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun randomUrlSafe(bytes: Int) = b64url(ByteArray(bytes).also { SecureRandom().nextBytes(it) })
    private fun b64url(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    private fun b64urlDecode(s: String) = Base64.decode(s, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
