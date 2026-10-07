package org.senda.browser.ui.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.MediaSession
import org.mozilla.geckoview.PageExtractionController
import org.mozilla.geckoview.SlowScriptResponse
import org.mozilla.geckoview.WebRequestError as WebErr
import android.content.Intent
import org.senda.browser.core.PreferencesManager
import org.senda.browser.core.SendaGeckoEngine
import java.net.URI
import java.net.URLEncoder
import java.util.UUID

class BrowserTab(
    val id: String = UUID.randomUUID().toString(),
    val isPrivate: Boolean = false,
    var searchBaseUrl: String = "https://duckduckgo.com/?q=",
    val prefs: PreferencesManager? = null,
    initialUrl: String = "about:blank",
    // Sesión que Gecko creó para un enlace target=_blank o window.open: Gecko la abre y carga la página
    existingSession: GeckoSession? = null,
    // Pestaña restaurada de la sesión anterior: no carga hasta que el usuario la abre
    lazyLoad: Boolean = false
) {
    private var pendingLoad: String? = if (lazyLoad && initialUrl != "about:blank") initialUrl else null
    var lastUsed: Long = System.currentTimeMillis()

    val session: GeckoSession = existingSession ?: SendaGeckoEngine.createSession(isPrivate)

    var url by mutableStateOf(initialUrl)
    // Lo informa Gecko tras validar el certificado; no se deduce del texto de la URL
    var isSecure by mutableStateOf(false)
        private set
    // Seguridad de la página original mientras se ve en modo lectura (el lector es un data: local)
    var readerSourceSecure by mutableStateOf(false)
        private set

    /** URL que se muestra en la barra: en modo lectura, la del artículo original, no el data: del lector. */
    val displayUrl: String
        get() = if (isReaderMode) originalArticleUrl ?: url else url

    /** Estado del candado: en modo lectura, el de la página original. */
    val displaySecure: Boolean
        get() = if (isReaderMode) readerSourceSecure else isSecure
    // Pestaña desde la que se abrió (enlace target=_blank, window.open o menú): Atrás vuelve a ella
    var parentTabId: String? = null
    var title by mutableStateOf("Nueva pestaña")
    var isLoading by mutableStateOf(false)
    /** Captura reducida de la página para la vista de pestañas. Solo en memoria, también en privado. */
    var thumbnail by mutableStateOf<android.graphics.Bitmap?>(null)
    var progress by mutableIntStateOf(0)
    var trackersBlocked by mutableIntStateOf(0)
    var canGoBack by mutableStateOf(false)
    /** Posición en el historial de la pestaña (-1 si Gecko aún no la informó); sirve para detectar trampas de Atrás. */
    var historyIndex = -1
        private set
    var canGoForward by mutableStateOf(false)
    var isFullScreen by mutableStateOf(false)
    // El video a pantalla completa es más alto que ancho (Shorts, grabaciones de móvil): no girar a horizontal
    var isFullScreenVideoPortrait by mutableStateOf(false)
        private set
    var isReaderMode by mutableStateOf(false)
    var originalArticleUrl by mutableStateOf<String?>(null)
    private var shownPrompt by mutableStateOf<SendaPrompt?>(null)
    private val queuedPrompts = ArrayDeque<SendaPrompt>()

    /**
     * Diálogo de la página a la vista. Si llega otro mientras hay uno abierto, espera su turno: antes lo
     * reemplazaba sin resolverlo (una segunda descarga perdía la primera, un aviso de script lento dejaba la
     * página esperando para siempre). Al ponerlo a null se muestra el siguiente. El menú de pulsación larga
     * no se encola: si hay otro diálogo se ignora, para que no aparezca después fuera de contexto.
     * Siempre en el hilo principal (los delegados de Gecko y los post al Looper principal lo son).
     */
    var activePrompt: SendaPrompt?
        get() = shownPrompt
        set(value) {
            when {
                value == null -> shownPrompt = queuedPrompts.removeFirstOrNull()
                shownPrompt == null -> shownPrompt = value
                value is SendaPrompt.ContextMenu -> if (shownPrompt is SendaPrompt.ContextMenu) shownPrompt = value
                else -> queuedPrompts.addLast(value)
            }
        }
    var isCrashed by mutableStateOf(false)

    // Reproducción multimedia informada por la API nativa de GeckoView (sin scripts en la página):
    // permite enviar el video a la TV desde el mismo minuto y pausarlo en el teléfono
    private var mediaSession: MediaSession? = null
    private var mediaPosition = 0.0
    private var mediaPositionAt = 0L
    private var mediaPlaying by mutableStateOf(false)

    /** La página tiene un video o audio activo (aunque esté en pausa). */
    var hasMedia by mutableStateOf(false)
        private set
    private var mediaRate = 1.0
    private var mediaDuration = 0.0
    // Video de YouTube al que corresponde la posición: al pasar a otro video la posición anterior no vale
    private var mediaVideoId: String? = null

    val currentMediaSeconds: Int
        get() {
            if (mediaSession == null || mediaPositionAt == 0L) return 0
            val elapsed = if (mediaPlaying) (android.os.SystemClock.elapsedRealtime() - mediaPositionAt) / 1000.0 * mediaRate else 0.0
            val position = mediaPosition + elapsed
            return (if (mediaDuration > 0.0) position.coerceAtMost(mediaDuration) else position).toInt()
        }

    val isMediaPlaying: Boolean
        get() = mediaSession != null && mediaPlaying

    fun pauseMedia() {
        try {
            mediaSession?.pause()
        } catch (_: Exception) {}
    }

    /** Duración del video activo en segundos (0 si no se conoce). */
    val mediaDurationSeconds: Double
        get() = mediaDuration

    fun seekMedia(seconds: Double) {
        try {
            mediaSession?.seekTo(seconds, false)
        } catch (_: Exception) {}
    }

    private var pausedPositionCallback: ((Int) -> Unit)? = null

    /**
     * Pausa y entrega la posición exacta en la que quedó el video, la que GeckoView informa tras la pausa.
     * Calcularla con el reloj se adelanta si el video se atascó cargando; si Gecko no responde a tiempo,
     * se usa ese cálculo.
     */
    fun pauseMediaAndGetPosition(onPosition: (Int) -> Unit) {
        if (mediaSession == null || !mediaPlaying) {
            onPosition(currentMediaSeconds)
            return
        }
        var delivered = false
        val deliver: (Int) -> Unit = { seconds ->
            if (!delivered) {
                delivered = true
                pausedPositionCallback = null
                onPosition(seconds)
            }
        }
        pausedPositionCallback = deliver
        pauseMedia()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ deliver(currentMediaSeconds) }, 600)
    }

    fun resumeMedia() {
        try {
            mediaSession?.play()
        } catch (_: Exception) {}
    }


    fun dismissActivePrompt() {
        val p = activePrompt ?: return
        try {
            when (p) {
                is SendaPrompt.Choice -> if (!p.prompt.isComplete) p.result.complete(p.prompt.dismiss())
                is SendaPrompt.Alert -> if (!p.prompt.isComplete) p.result.complete(p.prompt.dismiss())
                is SendaPrompt.Confirm -> if (!p.prompt.isComplete) p.result.complete(p.prompt.dismiss())
                is SendaPrompt.Text -> if (!p.prompt.isComplete) p.result.complete(p.prompt.dismiss())
                is SendaPrompt.BeforeUnload -> if (!p.prompt.isComplete) p.result.complete(p.prompt.dismiss())
                is SendaPrompt.RepostConfirm -> if (!p.prompt.isComplete) p.result.complete(p.prompt.dismiss())
                is SendaPrompt.File -> if (!p.prompt.isComplete) p.result.complete(p.prompt.dismiss())
                // Cerrar el aviso sin elegir equivale a no conceder nada
                is SendaPrompt.DateTime -> if (!p.prompt.isComplete) p.result.complete(p.prompt.dismiss())
                is SendaPrompt.Permission -> p.onDecision(false)
                is SendaPrompt.OpenInApp -> p.onDecision(false)
                is SendaPrompt.Auth -> if (!p.prompt.isComplete) p.result.complete(p.prompt.dismiss())
                is SendaPrompt.LoginSelect -> if (!p.request.isComplete) p.result.complete(p.request.dismiss())
                is SendaPrompt.LoginSave -> if (!p.request.isComplete) p.result.complete(p.request.dismiss())
                is SendaPrompt.SlowScript -> p.onDecision(true)
                is SendaPrompt.Download -> p.onDecision(false)
                is SendaPrompt.ContextMenu -> {}
            }
        } catch (_: Exception) {}
        activePrompt = null
    }

    init {
        setupDelegates()
        if (initialUrl.startsWith("moz-extension://")) {
            title = "uBlock Origin"
        }
        if (initialUrl != "about:blank" && existingSession == null && !lazyLoad) {
            loadUri(initialUrl)
        }
    }

    /**
     * Abre un enlace que no es web en la app que lo maneje. Con intent: se descartan componente y selector
     * (una web no puede elegir qué pantalla interna de otra app abrir) y, si no hay app, se usa la página
     * alternativa que la web indicó (browser_fallback_url).
     */
    private fun openExternal(uri: String) {
        val context = SendaGeckoEngine.appContext ?: return
        val isIntentUri = uri.startsWith("intent:", ignoreCase = true)
        val standard = uri.substringBefore(':').lowercase() in setOf("tel", "mailto", "sms", "smsto", "geo", "market")
        val intent = try {
            if (isIntentUri) {
                Intent.parseUri(uri, Intent.URI_INTENT_SCHEME).apply {
                    component = null
                    selector = null
                }
            } else {
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri))
            }
        } catch (_: Exception) {
            return
        }
        val fallback = intent.getStringExtra("browser_fallback_url")
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val mode = prefs?.openLinksInApps ?: "ASK"
        fun launch(): Boolean = try {
            context.startActivity(intent)
            true
        } catch (_: android.content.ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
        // «Nunca» en «Abrir enlaces en apps»: solo se respetan los esquemas estándar (llamar, correo, mapa)
        if (!standard && mode == "NEVER") {
            if (fallback != null) loadUri(fallback)
            return
        }
        if (!standard && mode == "ASK") {
            val target = try {
                context.packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            } catch (_: Exception) { null }
            // Sin app que lo abra no hay nada que preguntar
            if (target == null) {
                if (fallback != null) loadUri(fallback)
                return
            }
            // Si resuelve al selector del sistema, no hay una app concreta que nombrar
            val label = target.activityInfo?.packageName
                ?.takeIf { it != "android" }
                ?.let { target.loadLabel(context.packageManager)?.toString() }
            // Una sola decisión: el diálogo decide y luego se descarta (que vuelve a avisar con «no»)
            val decided = java.util.concurrent.atomic.AtomicBoolean(false)
            activePrompt = SendaPrompt.OpenInApp(label) { open ->
                if (decided.compareAndSet(false, true) && !(open && launch()) && fallback != null) loadUri(fallback)
            }
            return
        }
        if (!launch() && fallback != null) loadUri(fallback)
    }

    /**
     * Texto legible de la página (el mismo extractor que el modo lectura), para que la IA pueda leerla.
     * Si Gecko no responde en 4 s o la página no tiene texto, devuelve null.
     */
    @OptIn(org.mozilla.geckoview.ExperimentalGeckoViewApi::class)
    suspend fun extractPageText(): String? {
        if (url.isBlank() || url == "about:blank") return null
        val html = kotlinx.coroutines.withTimeoutOrNull(4_000L) {
            kotlinx.coroutines.suspendCancellableCoroutine<String?> { cont ->
                try {
                    session.sessionPageExtractor
                        .getPageContent(PageExtractionController.ContentParams(true, false))
                        .accept({ cont.resume(it) {} }, { cont.resume(null) {} })
                } catch (_: Exception) {
                    cont.resume(null) {}
                }
            }
        } ?: return null
        val text = android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_COMPACT).toString()
            .replace(Regex("[\\uFFFC\\s]+"), " ")
            .trim()
        return text.takeIf { it.length > 20 }
    }

    /** Abre [uri] en otra pestaña (menú de pulsación larga). */
    fun openInNewTab(uri: String, private: Boolean = isPrivate) {
        val tab = BrowserTab(
            isPrivate = private,
            searchBaseUrl = searchBaseUrl,
            prefs = prefs,
            initialUrl = uri
        )
        tabOpener?.invoke(tab, prefs?.openLinksInBackground == true)
    }

    // Momento en que el usuario aceptó cámara/micrófono en el aviso previo al permiso de Android
    private var mediaApprovedAt = 0L

    /** Muestra el aviso de permiso; [onDecision] se llama una sola vez (también si se cierra sin elegir). */
    private fun askUser(uri: String, kinds: List<PermissionKind>, onDecision: (Boolean) -> Unit) {
        val host = try { URI(uri).host?.removePrefix("www.") } catch (_: Exception) { null } ?: uri
        val decided = java.util.concurrent.atomic.AtomicBoolean(false)
        val once: (Boolean) -> Unit = { granted -> if (decided.compareAndSet(false, true)) onDecision(granted) }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            activePrompt = SendaPrompt.Permission(host, kinds, once)
        }
    }

    companion object {
        /** Tiempo para que la página aplique el desplegado antes de extraer el texto. */
        private const val UNFOLD_DELAY_MS = 350L

        /** Quita el plegado de secciones (atributo hidden, <details> cerrados, bloques plegables de Wikipedia). */
        private const val UNFOLD_SECTIONS_JS = "(function(){try{" +
            "document.querySelectorAll('[hidden=\"until-found\"]').forEach(function(e){e.removeAttribute('hidden');});" +
            "document.querySelectorAll('details:not([open])').forEach(function(d){d.open=true;});" +
            "document.querySelectorAll('.collapsible-block').forEach(function(e){e.classList.add('open-block');});" +
            "}catch(e){}})();"

        // Esquemas que Gecko carga por sí mismo; el resto se entrega a otras apps
        private val GECKO_SCHEMES = setOf(
            "http", "https", "about", "data", "blob", "file", "content", "javascript",
            "view-source", "moz-extension", "resource", "chrome", "jar", "ws", "wss"
        )

        /**
         * Lo asigna MainActivity: pide a Android los permisos indicados y responde si se concedieron todos.
         */
        var androidPermissionRequester: ((List<String>, (Boolean) -> Unit) -> Unit)? = null

        /**
         * Lo asigna MainActivity: añade a la lista una pestaña abierta desde la web o desde el menú
         * de pulsación larga. El segundo parámetro indica si debe quedar en segundo plano.
         */
        var tabOpener: ((BrowserTab, Boolean) -> Unit)? = null

        /** Lo asigna MainActivity: abre el diálogo de impresión de Android con el PDF de la página. */
        var printer: ((java.io.InputStream, String) -> Unit)? = null
    }

    // --- Traducción en el dispositivo (motor de Firefox: la página no sale del teléfono) ---
    /** Idioma detectado de la página (BCP 47) y el del usuario según Gecko; null mientras no se sepa. */
    var pageLanguage by mutableStateOf<String?>(null)
    var userLanguage by mutableStateOf<String?>(null)
    /** Idioma al que está traducida la página; null si se ve el original. */
    var translatedTo by mutableStateOf<String?>(null)
    var isTranslating by mutableStateOf(false)
    var translationError by mutableStateOf<String?>(null)

    fun translatePage(from: String, to: String) {
        val translation = session.sessionTranslation ?: return
        isTranslating = true
        translationError = null
        val options = org.mozilla.geckoview.TranslationsController.SessionTranslation.TranslationOptions.Builder()
            .downloadModel(true)
            .build()
        translation.translate(from, to, options).accept({ }, { e ->
            isTranslating = false
            translationError = e?.message ?: "error"
        })
    }

    fun showOriginalPage() {
        session.sessionTranslation?.restoreOriginalPage()
        translatedTo = null
        isTranslating = false
    }

    /** Genera el PDF de la página con Gecko y lo pasa al diálogo de impresión («Guardar como PDF» incluido). */
    fun printPage() {
        val context = org.senda.browser.SendaApplication.instance
        session.saveAsPdf().accept({ stream ->
            val print = printer
            if (stream != null && print != null) print(stream, title)
        }, {
            android.widget.Toast.makeText(context, org.senda.browser.core.SendaStrings.forApp(context).page_print_failed, android.widget.Toast.LENGTH_SHORT).show()
        })
    }

    private fun setupDelegates() {
        // window.print() de la página: mismo flujo que «Imprimir» del menú
        session.printDelegate = object : GeckoSession.PrintDelegate {
            override fun onPrint(session: GeckoSession) {
                printPage()
            }

            override fun onPrint(pdf: java.io.InputStream) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { printer?.invoke(pdf, title) }
            }
        }

        session.translationsSessionDelegate = object : org.mozilla.geckoview.TranslationsController.SessionTranslation.Delegate {
            override fun onTranslationStateChange(
                session: GeckoSession,
                state: org.mozilla.geckoview.TranslationsController.SessionTranslation.TranslationState?
            ) {
                if (state == null) return
                state.detectedLanguages?.let { detected ->
                    pageLanguage = detected.docLangTag
                    userLanguage = detected.userLangTag
                }
                translatedTo = state.requestedTranslationPair?.toLanguage
                if (state.error != null) {
                    translationError = state.error
                    isTranslating = false
                } else if (state.hasVisibleChange == true || state.requestedTranslationPair == null) {
                    isTranslating = false
                }
            }
        }

        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                newUrl: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                if (!newUrl.isNullOrBlank()) {
                    if (newUrl == "about:blank" && url.startsWith("moz-extension://")) {
                        return
                    }
                    // Página de error de Senda: la barra conserva la dirección que falló
                    val failed = pendingErrorUrl
                    if (failed != null && newUrl.startsWith("data:text/html")) {
                        pendingErrorUrl = null
                        url = failed
                        isReaderMode = false
                        showingError = true
                        return
                    }
                    showingError = false
                    url = newUrl
                    val videoId = org.senda.browser.core.cast.SendaYouTube.youTubeVideoId(newUrl)
                    if (videoId != mediaVideoId) {
                        mediaVideoId = videoId
                        resetMediaPosition()
                    }
                    if (newUrl.startsWith("data:text/html") && originalArticleUrl != null) {
                        isReaderMode = true
                    } else {
                        originalArticleUrl = newUrl
                        isReaderMode = false
                    }
                }
            }

            override fun onCanGoBack(session: GeckoSession, canGo: Boolean) {
                canGoBack = canGo
            }

            override fun onCanGoForward(session: GeckoSession, canGo: Boolean) {
                canGoForward = canGo
            }

            // Sin esto, un sitio inexistente, sin conexión o con certificado inválido dejaba ver la página de
            // inicio sin explicar nada
            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: org.mozilla.geckoview.WebRequestError
            ): GeckoResult<String>? {
                val failed = uri ?: return null
                if (error.code == org.mozilla.geckoview.WebRequestError.ERROR_CONTENT_CRASHED) return null
                pendingErrorUrl = failed
                isLoading = false
                return GeckoResult.fromValue(buildErrorPage(failed, error))
            }

            // tel:, mailto:, geo:, intent:, whatsapp:… no son páginas: se entregan a la app que corresponda
            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<org.mozilla.geckoview.AllowOrDeny>? {
                // Vuelta del inicio de sesión con ChatGPT: se entrega dentro de Senda sin cargarla. Por la red, el
                // modo solo HTTPS bloquea http://127.0.0.1 y la página mostraba «conexión no segura»
                if (org.senda.browser.core.assistant.ChatGptPlanAuth.deliverCallback(request.uri)) {
                    // Página en blanco: volver atrás recargaba la página de OpenAI de la misma autorización justo
                    // antes del canje y el código quedaba invalidado (invalid_grant, 2026-10-05)
                    android.os.Handler(android.os.Looper.getMainLooper()).post { session.loadUri("about:blank") }
                    return GeckoResult.fromValue(org.mozilla.geckoview.AllowOrDeny.DENY)
                }
                val scheme = request.uri.substringBefore(':', "").lowercase()
                if (scheme in GECKO_SCHEMES) return null
                // Solo tras un toque del usuario: una web no puede lanzar apps por su cuenta
                if (request.hasUserGesture) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post { openExternal(request.uri) }
                }
                return GeckoResult.fromValue(org.mozilla.geckoview.AllowOrDeny.DENY)
            }

            // Enlaces target=_blank y window.open: sin esto Gecko no abre nada y window.open devuelve null
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                val opener = tabOpener ?: return null
                val newSession = SendaGeckoEngine.createSession(isPrivate, open = false)
                val tab = BrowserTab(
                    isPrivate = isPrivate,
                    searchBaseUrl = searchBaseUrl,
                    prefs = prefs,
                    initialUrl = uri,
                    existingSession = newSession
                )
                opener(tab, false)
                return GeckoResult.fromValue(newSession)
            }
        }

        session.historyDelegate = object : GeckoSession.HistoryDelegate {
            override fun onHistoryStateChange(session: GeckoSession, historyList: GeckoSession.HistoryDelegate.HistoryList) {
                historyIndex = historyList.currentIndex
            }
        }

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, newUrl: String) {
                isLoading = true
                pageLanguage = null
                translatedTo = null
                translationError = null
                isTranslating = false
                progress = 10
                isSecure = false
            }

            override fun onSecurityChange(session: GeckoSession, securityInfo: GeckoSession.ProgressDelegate.SecurityInformation) {
                // Seguro solo con certificado válido, sin excepción manual y sin contenido mixto activo cargado
                isSecure = securityInfo.isSecure && !securityInfo.isException &&
                    securityInfo.mixedModeActive != GeckoSession.ProgressDelegate.SecurityInformation.CONTENT_LOADED
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                isLoading = false
                progress = 100

                if (success && !isPrivate && prefs != null && url != "about:blank" && !url.startsWith("data:") && !url.startsWith("about:")) {
                    prefs.addHistoryItem(title.ifBlank { url }, url)
                }

                if (success && prefs != null && url != "about:blank" && !isYouTubeUrl(url)) {
                    // Senda Labs: Inyección de CSS personalizado en caliente (nunca en YouTube, que se deja tal cual)
                    if (prefs.userCustomCss.isNotBlank()) {
                        val escapedCss = prefs.userCustomCss
                            .replace("\\", "\\\\")
                            .replace("\"", "\\\"")
                            .replace("\n", " ")
                            .replace("\r", "")
                        val cssJs = "javascript:(function(){try{var s=document.getElementById('senda-custom-css')||document.createElement('style');s.id='senda-custom-css';s.textContent=\"$escapedCss\";if(!s.parentNode){document.head.appendChild(s);}}catch(e){}})()"
                        session.loadUri(cssJs)
                    }

                    // Senda Labs: Inyección de UserScript personalizado
                    if (prefs.userCustomScript.isNotBlank()) {
                        val jsCode = prefs.userCustomScript
                            .replace("\\", "\\\\")
                            .replace("\"", "\\\"")
                            .replace("\n", " ")
                            .replace("\r", "")
                        val scriptJs = "javascript:(function(){try{eval(\"$jsCode\");}catch(e){console.error('Senda Script Error:', e);}})()"
                        session.loadUri(scriptJs)
                    }
                }
            }

            override fun onProgressChange(session: GeckoSession, newProgress: Int) {
                progress = newProgress
            }
        }

        session.mediaSessionDelegate = object : MediaSession.Delegate {
            override fun onActivated(session: GeckoSession, mediaSession: MediaSession) {
                this@BrowserTab.mediaSession = mediaSession
                hasMedia = true
            }

            override fun onDeactivated(session: GeckoSession, mediaSession: MediaSession) {
                if (this@BrowserTab.mediaSession == mediaSession) {
                    this@BrowserTab.mediaSession = null
                    hasMedia = false
                    resetMediaPosition()
                }
            }

            override fun onPlay(session: GeckoSession, mediaSession: MediaSession) {
                markMediaPosition(playing = true)
            }

            override fun onPause(session: GeckoSession, mediaSession: MediaSession) {
                markMediaPosition(playing = false)
            }

            override fun onPositionState(session: GeckoSession, mediaSession: MediaSession, state: MediaSession.PositionState) {
                mediaPosition = state.position
                mediaRate = if (state.playbackRate > 0.0) state.playbackRate else 1.0
                mediaDuration = state.duration
                mediaPositionAt = android.os.SystemClock.elapsedRealtime()
                if (state.playbackRate == 0.0) pausedPositionCallback?.invoke(kotlin.math.round(state.position).toInt())
            }

            override fun onFullscreen(
                session: GeckoSession,
                mediaSession: MediaSession,
                enabled: Boolean,
                meta: MediaSession.ElementMetadata?
            ) {
                isFullScreenVideoPortrait = enabled && meta != null && meta.width > 0 && meta.height > meta.width
            }
        }

        // App instalada como depurable (compilación de desarrollo): sin BuildConfig en este módulo
        val applicationDebuggable = (org.senda.browser.SendaApplication.instance.applicationInfo.flags and
            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        session.contentBlockingDelegate = object : ContentBlocking.Delegate {
            override fun onContentBlocked(
                session: GeckoSession,
                event: ContentBlocking.BlockEvent
            ) {
                trackersBlocked++
                // Solo en compilaciones de desarrollo: la versión de usuarios no registra las direcciones visitadas
                if (applicationDebuggable) {
                    android.util.Log.d("SendaBlocked", "${event.uri} categorías=${event.antiTrackingCategory} cookies=${event.cookieBehaviorCategory} safe=${event.safeBrowsingCategory}")
                }
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, newTitle: String?) {
                if (!newTitle.isNullOrBlank()) {
                    title = newTitle
                    if (!isPrivate && prefs != null && url != "about:blank" && !url.startsWith("data:") && !url.startsWith("about:")) {
                        prefs.addHistoryItem(newTitle, url)
                    }
                }
            }

            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                // Gecko ya ajusta el video a la pantalla con proporción correcta (object-fit: contain en su hoja
                // de estilos de pantalla completa); un estilo añadido en vw/vh choca con el tamaño que calcula la web
                isFullScreen = fullScreen
                if (!fullScreen) isFullScreenVideoPortrait = false
            }

            override fun onContextMenu(
                session: GeckoSession,
                screenX: Int,
                screenY: Int,
                element: GeckoSession.ContentDelegate.ContextElement
            ) {
                // Solo enlaces y multimedia: sobre texto Gecko ya muestra su barra de selección
                val hasTarget = !element.linkUri.isNullOrBlank() ||
                    (element.type != GeckoSession.ContentDelegate.ContextElement.TYPE_NONE && !element.srcUri.isNullOrBlank())
                if (hasTarget) activePrompt = SendaPrompt.ContextMenu(element)
            }

            override fun onExternalResponse(session: GeckoSession, response: org.mozilla.geckoview.WebResponse) {
                val p = prefs ?: return
                // Se guarda el flujo que ya trajo Gecko: misma conexión (Tor/proxy), cookies y modo privado
                val save = {
                    org.senda.browser.core.SendaDownloadManager.saveResponse(
                        context = org.senda.browser.SendaApplication.instance,
                        prefs = p,
                        response = response,
                        isPrivate = isPrivate
                    )
                }
                // Con «Preguntar dónde guardar» el selector ya pide permiso; sin él, la página no puede
                // dejar un archivo en Descargas sin que el usuario lo acepte
                if (p.askDownloadLocation) { save(); return }
                val decided = java.util.concurrent.atomic.AtomicBoolean(false)
                val once: (Boolean) -> Unit = { accept ->
                    if (decided.compareAndSet(false, true)) {
                        if (accept) save() else runCatching { response.body?.close() }
                    }
                }
                val name = org.senda.browser.core.SendaDownloadManager.suggestedFileName(response)
                val host = try { URI(response.uri).host?.removePrefix("www.") } catch (_: Exception) { null } ?: response.uri
                val size = response.headers["Content-Length"]?.trim()?.toLongOrNull() ?: -1L
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    activePrompt = SendaPrompt.Download(name, host, size, once)
                }
            }

            override fun onCrash(session: GeckoSession) {
                android.util.Log.e("Senda", "GeckoSession onCrash detectado en pestaña: $id (url: $url)")
                handleTabCrashed()
            }

            override fun onKill(session: GeckoSession) {
                android.util.Log.w("Senda", "GeckoSession onKill (LMK memoria) detectado en pestaña: $id (url: $url)")
                handleTabCrashed()
            }

            override fun onSlowScript(session: GeckoSession, scriptFileName: String): GeckoResult<SlowScriptResponse> {
                // Detenerlo sin preguntar rompía páginas que solo estaban ocupadas (editores, mapas, juegos)
                val result = GeckoResult<SlowScriptResponse>()
                val decided = java.util.concurrent.atomic.AtomicBoolean(false)
                val once: (Boolean) -> Unit = { stop ->
                    if (decided.compareAndSet(false, true)) result.complete(if (stop) SlowScriptResponse.STOP else SlowScriptResponse.CONTINUE)
                }
                val host = try { URI(url).host?.removePrefix("www.") } catch (_: Exception) { null } ?: url
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    activePrompt = SendaPrompt.SlowScript(host, once)
                }
                return result
            }
        }

        session.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onContentPermissionRequest(
                session: GeckoSession,
                perm: GeckoSession.PermissionDelegate.ContentPermission
            ): GeckoResult<Int> {
                // En navegación privada: Denegación automática de GPS/Geolocalización y Notificaciones para evitar rastreo físico o en segundo plano
                if (isPrivate) {
                    when (perm.permission) {
                        GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION,
                        GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> {
                            return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
                        }
                    }
                }
                val (kind, policy) = when (perm.permission) {
                    GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION ->
                        PermissionKind.LOCATION to (prefs?.sitePermissionLocation ?: "ASK")
                    GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION ->
                        PermissionKind.NOTIFICATIONS to (prefs?.sitePermissionNotifications ?: "BLOCK")
                    // Resto (almacenamiento persistente, DRM, autoplay…): decide Gecko con sus valores por defecto
                    else -> return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT)
                }
                val allow = GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                val deny = GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                return when (policy) {
                    "BLOCK" -> GeckoResult.fromValue(deny)
                    "ALLOW" -> GeckoResult.fromValue(allow)
                    else -> {
                        val result = GeckoResult<Int>()
                        askUser(perm.uri, listOf(kind)) { granted -> result.complete(if (granted) allow else deny) }
                        result
                    }
                }
            }

            override fun onMediaPermissionRequest(
                session: GeckoSession,
                uri: String,
                video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                callback: GeckoSession.PermissionDelegate.MediaCallback
            ) {
                val camPolicy = prefs?.sitePermissionCamera ?: "ASK"
                val micPolicy = prefs?.sitePermissionMic ?: "ASK"
                // Nunca se concede en silencio: «Preguntar» muestra el aviso y solo «Permitir» concede sin preguntar
                val wantedVideo = video?.firstOrNull()?.takeIf { camPolicy != "BLOCK" }
                val wantedAudio = audio?.firstOrNull()?.takeIf { micPolicy != "BLOCK" }
                if (wantedVideo == null && wantedAudio == null) {
                    callback.reject()
                    return
                }
                val toAsk = buildList {
                    if (wantedVideo != null && camPolicy != "ALLOW") add(PermissionKind.CAMERA)
                    if (wantedAudio != null && micPolicy != "ALLOW") add(PermissionKind.MICROPHONE)
                }
                // Ya aceptado hace un momento, antes de pedir el permiso de Android: no preguntar dos veces
                val justApproved = android.os.SystemClock.elapsedRealtime() - mediaApprovedAt < 30_000L
                mediaApprovedAt = 0L
                if (toAsk.isEmpty() || justApproved) {
                    callback.grant(wantedVideo, wantedAudio)
                } else {
                    askUser(uri, toAsk) { granted ->
                        if (granted) callback.grant(wantedVideo, wantedAudio) else callback.reject()
                    }
                }
            }

            // Gecko necesita además el permiso de Android (cámara, micrófono, ubicación). Solo llega aquí
            // después de que el usuario aceptó en el aviso de Senda o eligió «Permitir» en ajustes
            override fun onAndroidPermissionsRequest(
                session: GeckoSession,
                permissions: Array<out String>?,
                callback: GeckoSession.PermissionDelegate.Callback
            ) {
                val requester = androidPermissionRequester
                if (permissions.isNullOrEmpty() || requester == null) {
                    callback.reject()
                    return
                }
                val askAndroid = {
                    requester(permissions.toList()) { granted -> if (granted) callback.grant() else callback.reject() }
                }
                // Con cámara y micrófono Gecko pide el permiso de Android antes que el del sitio: Senda pregunta
                // primero por el sitio (o aplica el ajuste) y no vuelve a preguntar en onMediaPermissionRequest
                val kinds = buildList {
                    if (android.Manifest.permission.CAMERA in permissions) add(PermissionKind.CAMERA)
                    if (android.Manifest.permission.RECORD_AUDIO in permissions) add(PermissionKind.MICROPHONE)
                }
                if (kinds.isEmpty()) {
                    askAndroid()
                    return
                }
                val policies = kinds.map {
                    if (it == PermissionKind.CAMERA) prefs?.sitePermissionCamera ?: "ASK" else prefs?.sitePermissionMic ?: "ASK"
                }
                when {
                    "BLOCK" in policies -> callback.reject()
                    policies.all { it == "ALLOW" } -> askAndroid()
                    else -> askUser(url, kinds) { allowed ->
                        if (allowed) {
                            mediaApprovedAt = android.os.SystemClock.elapsedRealtime()
                            askAndroid()
                        } else {
                            callback.reject()
                        }
                    }
                }
            }
        }

        session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onChoicePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.ChoicePrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    activePrompt = SendaPrompt.Choice(prompt, result)
                }
                return result
            }

            override fun onAlertPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.AlertPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    activePrompt = SendaPrompt.Alert(prompt, result)
                }
                return result
            }

            override fun onButtonPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.ButtonPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    activePrompt = SendaPrompt.Confirm(prompt, result)
                }
                return result
            }

            override fun onTextPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.TextPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    activePrompt = SendaPrompt.Text(prompt, result)
                }
                return result
            }

            override fun onBeforeUnloadPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.BeforeUnloadPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    activePrompt = SendaPrompt.BeforeUnload(prompt, result)
                }
                return result
            }

            override fun onRepostConfirmPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.RepostConfirmPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    activePrompt = SendaPrompt.RepostConfirm(prompt, result)
                }
                return result
            }

            override fun onFilePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.FilePrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    activePrompt = SendaPrompt.File(prompt, result)
                }
                return result
            }

            override fun onAuthPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.AuthPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    activePrompt = SendaPrompt.Auth(prompt, result)
                }
                return result
            }

            override fun onLoginSelect(
                session: GeckoSession,
                request: GeckoSession.PromptDelegate.AutocompleteRequest<org.mozilla.geckoview.Autocomplete.LoginSelectOption>
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val tag = org.senda.browser.core.security.SendaVaultLoginStorage.TAG
                android.util.Log.i(tag, "onLoginSelect: ${request.options.size} opción(es)")
                // Tras identificarse con huella el selector anterior suele haberse cancelado: si el usuario ya
                // eligió la cuenta, se rellena directamente sin volver a preguntar
                val ctx = SendaGeckoEngine.appContext
                val pending = org.senda.browser.core.security.SendaVaultLoginStorage.takePending(request.options.map { it.value.guid })
                if (ctx != null && pending != null) {
                    val option = request.options.first { it.value.guid == pending }
                    try {
                        val filled = org.senda.browser.core.security.SendaVaultLoginStorage.filledOption(ctx, option)
                        if (filled != null) {
                            android.util.Log.i(tag, "onLoginSelect: cuenta pendiente rellenada")
                            return GeckoResult.fromValue(request.confirm(filled))
                        }
                    } catch (e: Exception) {
                        android.util.Log.w(tag, "onLoginSelect: no se pudo rellenar la cuenta pendiente: ${e.javaClass.simpleName}")
                    }
                }
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                request.setDelegate(object : GeckoSession.PromptDelegate.PromptInstanceDelegate {
                    override fun onPromptDismiss(prompt: GeckoSession.PromptDelegate.BasePrompt) {
                        android.util.Log.i(tag, "onLoginSelect: GeckoView canceló el selector")
                        // Cambió de campo o de página: la barra no debe quedarse a la vista. Si se estaba
                        // pidiendo la huella, ese flujo sigue y deja el relleno pendiente
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            if ((activePrompt as? SendaPrompt.LoginSelect)?.request === request) activePrompt = null
                            else queuedPrompts.removeAll { it is SendaPrompt.LoginSelect && it.request === request }
                        }
                    }
                })
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    activePrompt = SendaPrompt.LoginSelect(request, result)
                }
                return result
            }

            override fun onLoginSave(
                session: GeckoSession,
                request: GeckoSession.PromptDelegate.AutocompleteRequest<org.mozilla.geckoview.Autocomplete.LoginSaveOption>
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val tag = org.senda.browser.core.security.SendaVaultLoginStorage.TAG
                val entry = request.options.firstOrNull()?.value
                val ctx = SendaGeckoEngine.appContext
                if (entry == null || ctx == null || entry.password.isEmpty() || isPrivate) {
                    return GeckoResult.fromValue(request.dismiss())
                }
                // Si la cuenta ya está en la Bóveda no se pregunta: GeckoView solo conoce las cuentas sin contraseña
                // (no se descifran al cargar la página) y pediría «actualizar» en cada inicio de sesión
                val domain = org.senda.browser.core.security.SendaVaultManager.extractCanonicalDomain(entry.origin)
                val known = org.senda.browser.core.security.SendaVaultManager.getCredentials(ctx)
                    .any { it.domain.equals(domain, ignoreCase = true) && it.username == entry.username }
                android.util.Log.i(tag, "onLoginSave: ${if (known) "cuenta ya guardada, no se pregunta" else "se ofrece guardar"}")
                if (known) return GeckoResult.fromValue(request.dismiss())
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    activePrompt = SendaPrompt.LoginSave(request, result)
                }
                return result
            }

            override fun onDateTimePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.DateTimePrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    activePrompt = SendaPrompt.DateTime(prompt, result)
                }
                return result
            }

            // Ventanas emergentes que la página abre sin que el usuario pulse nada
            override fun onPopupPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.PopupPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val allow = prefs?.blockWebPopups == false
                return GeckoResult.fromValue(
                    prompt.confirm(if (allow) org.mozilla.geckoview.AllowOrDeny.ALLOW else org.mozilla.geckoview.AllowOrDeny.DENY)
                )
            }

            override fun onSharePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.SharePrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    val ctx = SendaGeckoEngine.appContext
                    if (ctx != null) {
                        try {
                            val shareUri = prompt.uri?.trim() ?: ""
                            val shareTitle = prompt.title?.trim() ?: ""
                            val shareText = prompt.text?.trim() ?: ""

                            val combinedText = buildString {
                                if (shareText.isNotBlank()) {
                                    append(shareText)
                                }
                                if (shareUri.isNotBlank()) {
                                    if (isNotEmpty() && !endsWith(shareUri)) {
                                        append("\n")
                                    }
                                    if (!contains(shareUri)) {
                                        append(shareUri)
                                    }
                                }
                            }.ifBlank { shareUri.ifBlank { shareTitle } }

                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                if (shareTitle.isNotBlank()) {
                                    putExtra(Intent.EXTRA_SUBJECT, shareTitle)
                                }
                                putExtra(Intent.EXTRA_TEXT, combinedText)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }

                            val chooser = Intent.createChooser(intent, shareTitle.ifBlank { "Compartir" }).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            ctx.startActivity(chooser)
                            result.complete(prompt.confirm(GeckoSession.PromptDelegate.SharePrompt.Result.SUCCESS))
                        } catch (e: Exception) {
                            android.util.Log.e("Senda", "Error al procesar onSharePrompt", e)
                            result.complete(prompt.confirm(GeckoSession.PromptDelegate.SharePrompt.Result.ABORT))
                        }
                    } else {
                        result.complete(prompt.confirm(GeckoSession.PromptDelegate.SharePrompt.Result.ABORT))
                    }
                }
                return result
            }
        }
    }

    private fun resetMediaPosition() {
        mediaPosition = 0.0
        mediaPositionAt = 0L
        mediaPlaying = false
        mediaRate = 1.0
        mediaDuration = 0.0
    }

    private fun markMediaPosition(playing: Boolean) {
        if (mediaPositionAt != 0L && mediaPlaying) {
            mediaPosition += (android.os.SystemClock.elapsedRealtime() - mediaPositionAt) / 1000.0 * mediaRate
        }
        mediaPositionAt = android.os.SystemClock.elapsedRealtime()
        mediaPlaying = playing
    }

    private fun isYouTubeUrl(pageUrl: String): Boolean {
        val host = try { URI(pageUrl).host ?: "" } catch (_: Exception) { "" }
        return host == "youtu.be" || host.endsWith("youtube.com") || host.endsWith("youtube-nocookie.com")
    }

    fun loadUri(uri: String, overrideSearchEngine: String? = null) {
        val searchEngine = overrideSearchEngine ?: searchBaseUrl
        val target = SendaUrlResolver.resolve(uri, searchEngine)
        url = target
        if (target.startsWith("moz-extension://")) {
            title = "uBlock Origin"
        }
        session.loadUri(target)
    }

    var isDesktopMode by mutableStateOf(false)

    fun goBack() {
        if (canGoBack) {
            session.goBack()
        } else if (url != "about:blank" && url.isNotBlank()) {
            loadUri("about:blank")
        }
    }

    fun goForward() {
        if (canGoForward) {
            session.goForward()
        }
    }

    fun reload() {
        // En la página de error, «recargar» es volver a intentar la dirección que falló
        if (showingError) loadUri(url) else session.reload()
    }

    // Dirección que falló, mientras Gecko carga la página de error que la sustituye
    private var pendingErrorUrl: String? = null
    var showingError by mutableStateOf(false)
        private set

    private fun buildErrorPage(failedUrl: String, error: org.mozilla.geckoview.WebRequestError): String {
        val context = SendaGeckoEngine.appContext
        val strings = org.senda.browser.core.SendaStrings.get(prefs?.appLanguage ?: "SYSTEM", context)
        val message = when {
            error.code == WebErr.ERROR_UNKNOWN_HOST -> strings.err_host
            error.category == WebErr.ERROR_CATEGORY_PROXY -> strings.err_proxy
            error.code == WebErr.ERROR_HTTPS_ONLY -> strings.err_https_only
            error.category == WebErr.ERROR_CATEGORY_SAFEBROWSING -> strings.err_unsafe
            error.category == WebErr.ERROR_CATEGORY_SECURITY || error.code == WebErr.ERROR_BAD_HSTS_CERT -> strings.err_cert
            error.category == WebErr.ERROR_CATEGORY_NETWORK -> strings.err_connection
            else -> strings.err_generic
        }
        val host = try { URI(failedUrl).host ?: failedUrl } catch (_: Exception) { failedUrl }
        val href = escapeHtml(failedUrl)
        // Solo se ofrece reintentar en direcciones web; nunca un botón para saltarse un certificado inválido
        val retry = if (failedUrl.startsWith("http://") || failedUrl.startsWith("https://"))
            "<a class=\"btn\" href=\"$href\">${escapeHtml(strings.err_retry)}</a>" else ""
        val html = """
            <!DOCTYPE html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>${escapeHtml(strings.err_title)}</title>
            <style>
              :root { color-scheme: light dark; }
              body { font-family: sans-serif; margin: 0; padding: 48px 28px; line-height: 1.55;
                     background: Canvas; color: CanvasText; }
              .icon { font-size: 44px; margin-bottom: 8px; }
              h1 { font-size: 22px; margin: 0 0 6px; }
              .host { opacity: .65; font-size: 14px; margin-bottom: 18px; overflow-wrap: anywhere; }
              p { font-size: 16px; }
              .btn { display: inline-block; margin-top: 18px; padding: 12px 22px; border-radius: 24px;
                     background: #006874; color: #fff; text-decoration: none; font-weight: 600; }
              .code { margin-top: 28px; font-size: 12px; opacity: .45; }
            </style></head><body>
            <div class="icon">⚠️</div>
            <h1>${escapeHtml(strings.err_title)}</h1>
            <div class="host">${escapeHtml(host)}</div>
            <p>${escapeHtml(message)}</p>
            $retry
            <div class="code">${error.category}/${error.code}</div>
            </body></html>
        """.trimIndent()
        return "data:text/html;charset=utf-8," + URLEncoder.encode(html, "UTF-8").replace("+", "%20")
    }

    fun stop() {
        session.stop()
    }

    fun toggleDesktopMode() {
        isDesktopMode = !isDesktopMode
        session.settings.userAgentMode = if (isDesktopMode) {
            org.mozilla.geckoview.GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
        } else {
            org.mozilla.geckoview.GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        }
        // Sin la ventana de escritorio, muchas webs siguen maquetando para móvil aunque cambie el agente
        session.settings.viewportMode = if (isDesktopMode) {
            org.mozilla.geckoview.GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
        } else {
            org.mozilla.geckoview.GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        }
        session.reload()
    }

    fun exitFullScreen() {
        if (isFullScreen) {
            session.exitFullScreen()
            isFullScreen = false
            isFullScreenVideoPortrait = false
        }
    }

    @org.mozilla.geckoview.ExperimentalGeckoViewApi
    fun toggleReaderMode() {
        if (isReaderMode) {
            isReaderMode = false
            val target = originalArticleUrl ?: url
            if (target.isNotBlank() && !target.startsWith("data:text/html")) {
                session.loadUri(target)
            } else {
                session.reload()
            }
            return
        }

        val currentUrl = url
        originalArticleUrl = currentUrl
        readerSourceSecure = isSecure
        val context = SendaGeckoEngine.appContext
        val rs = org.senda.browser.core.SendaStrings.get(prefs?.appLanguage ?: "SYSTEM", context)
        val readerLang = org.senda.browser.core.SendaLocaleManager.getEffectiveLanguage(prefs?.appLanguage ?: "SYSTEM", context).lowercase()
        val readerTexts = readerTextsJson(rs, readerLang)
        val articleTitle = title.takeIf { it.isNotBlank() && it != "about:blank" } ?: rs.rd_article

        val themeBg = when (prefs?.readerTheme) {
            "OLED_BLACK" -> "#000000"
            "LIGHT" -> "#FFFFFF"
            else -> "#FBF0D9" // SEPIA
        }
        val themeFg = when (prefs?.readerTheme) {
            "OLED_BLACK" -> "#E0E0E0"
            "LIGHT" -> "#1A1A1A"
            else -> "#433422" // SEPIA
        }
        val fontFamily = when (prefs?.readerFontFamily) {
            "SANS_SERIF", "SANS" -> "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif"
            "MONOSPACE", "MONO" -> "ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace"
            else -> "Georgia, Cambria, 'Times New Roman', Times, serif"
        }
        val fontScale = (prefs?.readerFontSizePercent ?: 100) / 100.0

        // Wikipedia móvil y otras webs pliegan secciones al cargar. El extractor de GeckoView descarta lo que no se
        // ve, y el lector perdía hasta el 95 % del artículo: se despliegan antes de extraer
        session.loadUri("javascript:" + URLEncoder.encode(UNFOLD_SECTIONS_JS, "UTF-8").replace("+", "%20"))
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
        val extractor = session.sessionPageExtractor
        extractor.getPageContent(PageExtractionController.ContentParams(true, false)).accept(
            { extractedHtml ->
                if (!extractedHtml.isNullOrBlank() && extractedHtml.length > 50) {
                    val wordCount = extractedHtml.split(Regex("\\s+")).size
                    // El extractor entrega texto, no HTML: se escapa y cada línea pasa a ser un párrafo. Antes se
                    // insertaba tal cual y el título de la página podía ejecutar código dentro del lector
                    val bodyHtml = markdownToSafeHtml(extractedHtml)
                    val safeTitle = escapeHtml(articleTitle)
                    val readTime = Math.max(1, Math.round(wordCount / 200.0))

                    val domain = escapeHtml(try {
                        URI(currentUrl).host?.removePrefix("www.") ?: ""
                    } catch (_: Exception) { "" })

                    val readerHtml = """
                    <!DOCTYPE html>
                    <html lang="$readerLang">
                    <head>
                        <meta charset="utf-8">
                        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=2.0">
                        <title>$safeTitle</title>
                        <style>
                            * { box-sizing: border-box; }
                            html, body {
                                background-color: $themeBg !important;
                                color: $themeFg !important;
                                font-family: $fontFamily !important;
                                font-size: ${19 * fontScale}px !important;
                                line-height: 1.8 !important;
                                letter-spacing: 0.2px;
                                margin: 0;
                                padding: 24px 20px 80px 20px;
                                -webkit-font-smoothing: antialiased;
                            }
                            .reader-container {
                                max-width: 680px;
                                margin: 0 auto;
                                overflow-wrap: anywhere;
                            }
                            .reader-header {
                                border-bottom: 1px solid rgba(128,128,128,0.25);
                                padding-bottom: 14px;
                                margin-bottom: 24px;
                                font-size: 13px;
                                opacity: 0.85;
                                display: flex;
                                justify-content: space-between;
                                align-items: center;
                            }
                            .reader-domain {
                                font-weight: bold;
                                text-transform: uppercase;
                                letter-spacing: 0.8px;
                                font-size: 11px;
                            }
                            h1 {
                                font-size: ${28 * fontScale}px !important;
                                line-height: 1.25 !important;
                                margin-top: 0;
                                margin-bottom: 24px;
                                font-weight: bold !important;
                                letter-spacing: -0.5px;
                                color: $themeFg !important;
                            }
                            p {
                                margin-bottom: 1.5em !important;
                                color: $themeFg !important;
                            }
                            img {
                                max-width: 100% !important;
                                height: auto !important;
                                border-radius: 12px;
                                margin: 24px auto;
                                display: block;
                            }
                            a {
                                color: inherit !important;
                                text-decoration: underline;
                                text-decoration-color: rgba(128,128,128,0.5);
                            }
                            blockquote {
                                border-left: 3px solid rgba(128,128,128,0.4);
                                margin: 20px 0;
                                padding-left: 16px;
                                font-style: italic;
                                opacity: 0.9;
                            }
                            pre, code {
                                background: rgba(128,128,128,0.15);
                                border-radius: 6px;
                                font-family: monospace;
                                padding: 2px 6px;
                                font-size: 0.9em;
                            }
                            pre {
                                padding: 14px;
                                overflow-x: auto;
                            }
                        </style>
                        <script>
                            var SR = $readerTexts;

                            function toggleSendaSummary() {
                                var box = document.getElementById('senda-summary-box');
                                if (!box) return;
                                if (box.style.display === 'none' || box.style.display === '') {
                                    box.style.display = 'block';
                                    var article = document.querySelector('article.reader-body');
                                    var content = document.getElementById('senda-summary-content');
                                    if (!article || !content) return;

                                    var rawText = (article.innerText || article.textContent || '').trim();
                                    if (rawText.length < 40) {
                                        content.innerHTML = '<p style=\"margin:0;opacity:0.8;\">' + SR.tooShort + '</p>';
                                        return;
                                    }

                                    // Frases de texto corrido: fuera referencias, enlaces, ISBN, fechas de consulta y listas
                                    // de datos. Antes se tomaban la primera, la del medio y la última de todo el texto, y
                                    // en Wikipedia dos de tres salían de la bibliografía («Consultado el 19 de mayo…»)
                                    function isProse(s) {
                                        if (s.length < 60 || s.length > 400) return false;
                                        if (/https?:|www\.|isbn|doi:|^[↑^\[]/i.test(s)) return false;
                                        var digits = (s.match(/\d/g) || []).length;
                                        return digits / s.length < 0.12;
                                    }
                                    var cleanText = rawText.replace(/\s+/g, ' ');
                                    var allSentences = cleanText.split(/(?<=[.!?。！？])\s*/)
                                        .map(function(s){ return s.trim(); })
                                        .filter(function(s){ return s.length > 30 && !s.includes('©'); });
                                    var sentences = allSentences.filter(isProse);
                                    if (sentences.length === 0) sentences = allSentences;

                                    // La primera y otras dos repartidas por el 70 % inicial, donde está el cuerpo del texto
                                    var selectedPoints = [];
                                    if (sentences.length <= 3) {
                                        selectedPoints = sentences;
                                    } else {
                                        var span = Math.max(3, Math.floor(sentences.length * 0.7));
                                        selectedPoints.push(sentences[0]);
                                        selectedPoints.push(sentences[Math.floor(span / 3)]);
                                        selectedPoints.push(sentences[Math.floor(span * 2 / 3)]);
                                    }

                                    var wordCount = rawText.split(/\s+/).length;
                                    var bullets = selectedPoints.map(function(p){ 
                                        var clean = p.replace(/^[-•*]\s*/, '');
                                        if (clean.length > 220) clean = clean.substring(0, 220) + '...';
                                        return '<li style=\"margin-bottom:8px;line-height:1.55;\">' + escapeHtml(clean) + '</li>'; 
                                    }).join('');

                                    content.innerHTML = '<ul style=\"margin:0;padding-left:18px;\">' + bullets + '</ul>' +
                                        '<div style=\"margin-top:10px;display:flex;justify-content:space-between;align-items:center;font-size:11px;opacity:0.8;border-top:1px solid rgba(128,128,128,0.2);padding-top:6px;\">' +
                                        '<span>📊 ' + SR.points.replace('{n}', selectedPoints.length).replace('{w}', wordCount) + '</span>' +
                                        '<button onclick=\"navigator.clipboard.writeText(Array.from(document.querySelectorAll(\'#senda-summary-content li\')).map(function(l){return \'• \' + l.innerText;}).join(\'\\n\')); this.innerText=\'✓ \' + SR.copied;\" style=\"background:rgba(128,128,128,0.25);border:none;color:inherit;padding:3px 8px;border-radius:4px;font-size:10px;cursor:pointer;font-weight:600;\">📋 ' + SR.copy + '</button>' +
                                        '</div>';
                                } else {
                                    box.style.display = 'none';
                                }
                            }

                            function toggleSendaAsk() {
                                var box = document.getElementById('senda-ask-box');
                                if (!box) return;
                                if (box.style.display === 'none' || box.style.display === '') {
                                    box.style.display = 'block';
                                    var inp = document.getElementById('senda-ask-input');
                                    if (inp) { inp.focus(); }
                                } else {
                                    box.style.display = 'none';
                                }
                            }

                            function stripAccents(str) {
                                return (str || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '');
                            }

                            // El texto del artículo es de la página: escaparlo siempre antes de usar innerHTML
                            function escapeHtml(s) {
                                return String(s).replace(/[&<>"']/g, function(c) {
                                    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
                                });
                            }

                            function highlightMatches(text, terms) {
                                if (!text) return '';
                                if (!terms || terms.length === 0) return escapeHtml(text);
                                var normTerms = terms.map(function(t){ return stripAccents(t.toLowerCase()); });
                                // split con grupo: los índices impares son palabras, los pares lo que hay entre ellas
                                return text.split(/([\p{L}\p{N}_]+)/u).map(function(part, idx) {
                                    if (idx % 2 === 1) {
                                        var normToken = stripAccents(part.toLowerCase());
                                        for (var i = 0; i < normTerms.length; i++) {
                                            var t = normTerms[i];
                                            if (normToken === t || (t.length >= 5 && normToken.indexOf(t.substring(0, t.length - 1)) === 0)) {
                                                return '<mark style="background:rgba(0,180,255,0.28);color:inherit;padding:1px 3px;border-radius:3px;font-weight:600;">' + escapeHtml(part) + '</mark>';
                                            }
                                        }
                                    }
                                    return escapeHtml(part);
                                }).join('');
                            }

                            function executeSendaAsk() {
                                var inp = document.getElementById('senda-ask-input');
                                var res = document.getElementById('senda-ask-result');
                                if (!inp || !res) return;
                                var query = inp.value.trim();
                                if (!query) return;

                                res.style.display = 'block';
                                res.innerHTML = '<span style=\"opacity:0.7;\">🔍 ' + SR.searching + '</span>';

                                setTimeout(function() {
                                    var article = document.querySelector('article.reader-body, .reader-body');
                                    if (!article) {
                                        res.innerHTML = '<em>' + SR.noText + '</em>';
                                        return;
                                    }

                                    var rawText = (article.innerText || article.textContent || '').trim();
                                    var rawParagraphs = rawText.split(/\n+/).map(function(p){ return p.trim(); }).filter(function(p){ return p.length > 25; });

                                    var sentences = [];
                                    rawParagraphs.forEach(function(p, pIdx) {
                                        var pSentences = p.replace(/\s+/g, ' ').split(/(?<=[.!?])\s+/).map(function(s){ return s.trim(); }).filter(function(s){ return s.length > 20; });
                                        pSentences.forEach(function(s) {
                                            sentences.push({ text: s, paragraphNum: pIdx + 1, fullParagraph: p });
                                        });
                                    });

                                    if (sentences.length === 0) {
                                        res.innerHTML = '<em>' + SR.notEnough + '</em>';
                                        return;
                                    }

                                    // Palabras vacías del idioma de Senda (antes solo español)
                                    var stopWords = new Set(SR.stop);

                                    var normQuery = stripAccents(query.toLowerCase());
                                    var queryTerms = normQuery
                                        .replace(/[^\p{L}\p{N}\s]/gu, ' ')
                                        .split(/\s+/)
                                        .filter(function(w){ return w.length > 2 && !stopWords.has(w); });

                                    if (queryTerms.length === 0) {
                                        queryTerms = normQuery.replace(/[^\p{L}\p{N}\s]/gu, ' ').split(/\s+/).filter(function(w){ return w.length > 1; });
                                    }

                                    var matches = [];
                                    sentences.forEach(function(item) {
                                        var normSentence = stripAccents(item.text.toLowerCase());
                                        var score = 0;
                                        var matchedWords = [];

                                        queryTerms.forEach(function(term) {
                                            if (normSentence.indexOf(term) !== -1) {
                                                score += 10;
                                                matchedWords.push(term);
                                            } else if (term.length >= 5 && normSentence.indexOf(term.substring(0, term.length - 1)) !== -1) {
                                                score += 5;
                                                matchedWords.push(term);
                                            }
                                        });

                                        if (matchedWords.length > 1) {
                                            score += matchedWords.length * 6;
                                        }

                                        if (score > 0) {
                                            matches.push({ item: item, score: score, matchedWords: matchedWords });
                                        }
                                    });

                                    matches.sort(function(a, b){ return b.score - a.score; });

                                    if (matches.length === 0) {
                                        res.innerHTML = '<div style=\"color:inherit;\">' +
                                            '<strong>ℹ️</strong> ' + SR.notFound + '<br>' +
                                            '<span style=\"font-size:11px;opacity:0.7;\">' + SR.notFoundNote + '</span>' +
                                            '</div>';
                                        return;
                                    }

                                    var best = matches[0];
                                    var highlighted = highlightMatches(best.item.text, best.matchedWords);

                                    var contextHtml = '';
                                    if (best.item.fullParagraph && best.item.fullParagraph.length > best.item.text.length + 15) {
                                        var highlightedContext = highlightMatches(best.item.fullParagraph, best.matchedWords);
                                        contextHtml = '<details style=\"margin-top:8px;font-size:0.9em;opacity:0.9;cursor:pointer;\">' +
                                            '<summary style=\"font-size:11px;font-weight:600;color:#00B0FF;user-select:none;outline:none;\">🔎 ' + SR.context.replace('{n}', best.item.paragraphNum) + '</summary>' +
                                            '<div style=\"margin-top:6px;padding:8px 10px;background:rgba(128,128,128,0.1);border-left:2px solid #00B0FF;border-radius:4px;line-height:1.55;\">' + highlightedContext + '</div>' +
                                            '</details>';
                                    }

                                    var secondaryHtml = '';
                                    if (matches.length > 1 && matches[1].score >= matches[0].score * 0.6 && matches[1].item.text !== best.item.text) {
                                        var second = matches[1];
                                        var highlightedSecond = highlightMatches(second.item.text, second.matchedWords);
                                        secondaryHtml = '<div style=\"margin-top:10px;padding-top:8px;border-top:1px dashed rgba(128,128,128,0.25);font-size:0.92em;\">' +
                                            '<strong style=\"font-size:11px;opacity:0.75;\">📌 ' + SR.otherMatch.replace('{n}', second.item.paragraphNum) + '</strong>' +
                                            '<div style=\"margin-top:4px;\">' + highlightedSecond + '</div>' +
                                            '</div>';
                                    }

                                    var citation = '📌 <em>' + SR.foundIn.replace('{n}', best.item.paragraphNum) + '</em>';

                                    res.innerHTML = '<div style=\"margin-bottom:6px;line-height:1.6;\">' + highlighted + '</div>' +
                                        contextHtml +
                                        secondaryHtml +
                                        '<div style=\"display:flex;justify-content:space-between;align-items:center;font-size:11px;opacity:0.8;border-top:1px solid rgba(128,128,128,0.2);padding-top:6px;margin-top:10px;\">' +
                                        '<span>' + citation + '</span>' +
                                        '<button onclick=\"navigator.clipboard.writeText(document.getElementById(\'senda-ask-result\').innerText); this.innerText=\'✓ \' + SR.copied;\" style=\"background:rgba(128,128,128,0.25);border:none;color:inherit;padding:3px 8px;border-radius:4px;font-size:10px;cursor:pointer;font-weight:600;\">📋 ' + SR.copyResult + '</button>' +
                                        '</div>';
                                }, 120);
                            }
                        </script>
                    </head>
                    <body>
                        <div class="reader-container">
                            <div class="reader-header">
                                <div style="display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:8px;">
                                    <div>
                                        <span class="reader-domain">📖 $domain</span> · <span>${escapeHtml(rs.rd_reading_time.replace("{n}", readTime.toString()))}</span>
                                    </div>
                                    <div style="display:flex;align-items:center;gap:6px;">
                                        <button onclick="toggleSendaSummary()" style="background:rgba(0,210,160,0.15);border:1px solid #00D2A0;color:inherit;padding:4px 10px;border-radius:6px;font-size:11px;font-weight:600;cursor:pointer;">⚡ ${escapeHtml(rs.rd_key_sentences)}</button>
                                        <button onclick="toggleSendaAsk()" style="background:rgba(0,180,255,0.15);border:1px solid #00B0FF;color:inherit;padding:4px 10px;border-radius:6px;font-size:11px;font-weight:600;cursor:pointer;">🔎 ${escapeHtml(rs.rd_ask)}</button>
                                    </div>
                                </div>
                            </div>
                            <div id="senda-summary-box" style="display:none;background:rgba(128,128,128,0.12);border-left:3px solid #00D2A0;border-radius:8px;padding:12px 14px;margin-bottom:14px;">
                                <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:6px;">
                                    <strong style="font-size:12px;color:#00D2A0;">⚡ ${escapeHtml(rs.rd_key_sentences_header.uppercase())}</strong>
                                    <button onclick="document.getElementById('senda-summary-box').style.display='none'" style="background:none;border:none;color:inherit;font-size:14px;cursor:pointer;opacity:0.6;">✕</button>
                                </div>
                                <div id="senda-summary-content" style="font-size:0.92em;line-height:1.55;"></div>
                                <div style="margin-top:8px;font-size:10px;opacity:0.6;border-top:1px solid rgba(128,128,128,0.2);padding-top:4px;">
                                    🔒 ${escapeHtml(rs.rd_local_note)}
                                </div>
                            </div>
                            <div id="senda-ask-box" style="display:none;background:rgba(128,128,128,0.12);border-left:3px solid #00B0FF;border-radius:8px;padding:12px 14px;margin-bottom:14px;">
                                <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;">
                                    <strong style="font-size:12px;color:#00B0FF;">🔎 ${escapeHtml(rs.rd_ask_header.uppercase())}</strong>
                                    <button onclick="document.getElementById('senda-ask-box').style.display='none'" style="background:none;border:none;color:inherit;font-size:14px;cursor:pointer;opacity:0.6;">✕</button>
                                </div>
                                <div style="display:flex;gap:6px;margin-bottom:8px;">
                                    <input type="text" id="senda-ask-input" placeholder="${escapeHtml(rs.rd_ask_placeholder)}" style="flex:1;background:rgba(128,128,128,0.15);border:1px solid rgba(128,128,128,0.3);border-radius:6px;padding:8px 10px;color:inherit;font-size:13px;outline:none;" onkeydown="if(event.key==='Enter') executeSendaAsk();" />
                                    <button onclick="executeSendaAsk()" style="background:#00B0FF;color:#FFFFFF;border:none;border-radius:6px;padding:8px 12px;font-weight:600;font-size:12px;cursor:pointer;">${escapeHtml(rs.general_search)}</button>
                                </div>
                                <div id="senda-ask-result" style="display:none;font-size:0.92em;line-height:1.55;background:rgba(128,128,128,0.08);border-radius:6px;padding:10px;margin-top:6px;"></div>
                                <div style="margin-top:8px;font-size:10px;opacity:0.6;border-top:1px solid rgba(128,128,128,0.2);padding-top:4px;">
                                    🔒 ${escapeHtml(rs.rd_ask_note)}
                                </div>
                            </div>
                            <h1>$safeTitle</h1>
                            <article class="reader-body">
                                $bodyHtml
                            </article>
                        </div>
                    </body>
                    </html>
                    """.trimIndent()

                    isReaderMode = true
                    val encoded = URLEncoder.encode(readerHtml, "UTF-8").replace("+", "%20")
                    session.loadUri("data:text/html;charset=utf-8,$encoded")
                } else {
                    fallbackInPageReader(articleTitle, themeBg, themeFg, fontFamily, fontScale, readerTexts)
                }
            },
            { err ->
                android.util.Log.w("Senda", "SessionPageExtractor no disponible, usando fallback: ${err?.message}")
                fallbackInPageReader(articleTitle, themeBg, themeFg, fontFamily, fontScale, readerTexts)
            }
        )
        }, UNFOLD_DELAY_MS)
    }

    /**
     * Convierte el Markdown del extractor en HTML del lector. Todo se escapa primero; luego solo se
     * reconstruyen encabezados, listas, citas, negritas, cursivas, enlaces e imágenes con http(s).
     */
    private fun markdownToSafeHtml(markdown: String): String {
        // Texto del enlace con corchetes escapados (\[1\]) incluidos; destino cualquiera
        val link = Regex("""(!?)\[((?:\\.|[^\]\\])*)]\(([^)\s]*)\)""")
        fun unescapeMd(t: String) = t.replace(Regex("""\\([\\\[\]()*_#`>~-])"""), "$1")
        fun inline(raw: String): String {
            var t = escapeHtml(raw)
            t = link.replace(t) { m ->
                val (bang, rawText, href) = m.destructured
                val text = unescapeMd(rawText)
                val web = href.startsWith("http://") || href.startsWith("https://")
                when {
                    // Destinos internos (anclas de citas, moz-nullprincipal): solo el texto
                    !web -> if (bang == "!") "" else text
                    bang == "!" -> "<img src=\"$href\" alt=\"$text\" loading=\"lazy\">"
                    else -> "<a href=\"$href\">$text</a>"
                }
            }
            t = unescapeMd(t)
            t = t.replace(Regex("""\*\*(.+?)\*\*"""), "<strong>$1</strong>")
            t = t.replace(Regex("""(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])"""), "<em>$1</em>")
            return t
        }
        val out = StringBuilder()
        var inList = false
        for (line in markdown.lines().map { it.trim() }) {
            val bullet = Regex("""^([-*+]|\d+[.)])\s+(.*)""").find(line)
            if (bullet == null && inList) {
                out.append("</ul>\n"); inList = false
            }
            when {
                line.isEmpty() -> {}
                bullet != null -> {
                    if (!inList) { out.append("<ul>\n"); inList = true }
                    out.append("<li>").append(inline(bullet.groupValues[2])).append("</li>\n")
                }
                line.startsWith("#") -> {
                    val level = line.takeWhile { it == '#' }.length.coerceIn(1, 4) + 1
                    out.append("<h$level>").append(inline(line.trimStart('#').trim())).append("</h$level>\n")
                }
                line.startsWith(">") -> out.append("<blockquote>").append(inline(line.trimStart('>').trim())).append("</blockquote>\n")
                else -> out.append("<p>").append(inline(line)).append("</p>\n")
            }
        }
        if (inList) out.append("</ul>\n")
        return out.toString()
    }

    /**
     * Textos del lector en el idioma de Senda, como objeto JSON para su JavaScript. Ya van escapados para HTML
     * porque el lector los inserta con innerHTML; JSONObject escapa también «</» para no cerrar el <script>.
     */
    private fun readerTextsJson(rs: org.senda.browser.core.SendaStringPack, lang: String): String {
        fun plain(t: String) = java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        val stop = when (lang) {
            "en" -> "the a an of to in on for with and or but is are was were be been it its that this what who which how when where why does did do has have had from by at as about"
            "de" -> "der die das den dem des ein eine einen einem einer und oder aber ist sind war waren wer was wie wann wo warum mit von zu im in auf für nicht es sie er"
            "fr" -> "le la les un une des du de et ou mais est sont était qui que quoi comment quand où pourquoi avec pour par dans sur ce cette il elle"
            "pt" -> "o a os as um uma de do da dos das em no na com por para que quem qual como quando onde porque e ou mas é são foi era se"
            "it" -> "il lo la i gli le un una di del della da in con per su che chi quale come quando dove perché e o ma è sono era fu si"
            "es" -> "el la los las un una unos unas de del a al en con por para hacia desde sin sobre entre tras hasta durante mediante que quien quienes cual cuales como cuando donde porque y e ni o u pero sino si no es son era eran fue fueron ser sido siendo ha han habia hay hubo tener tiene tienen tuvo se su sus lo le les me nos te"
            else -> "" // japonés y chino no separan palabras con espacios
        }
        val texts = mapOf(
            "reader" to rs.tb_reader_mode,
            "readingTime" to rs.rd_reading_time,
            "keySentences" to rs.rd_key_sentences,
            "firstSentencesUp" to rs.rd_first_sentences.uppercase(),
            "ask" to rs.rd_ask,
            "askHeaderUp" to rs.rd_ask_header.uppercase(),
            "askPlaceholder" to rs.rd_ask_placeholder,
            "search" to rs.general_search,
            "tooShort" to rs.rd_too_short,
            "points" to rs.rd_points_summary,
            "copy" to rs.rd_copy,
            "copied" to rs.rd_copied,
            "copyResult" to rs.rd_copy_result,
            "searching" to rs.rd_searching,
            "noText" to rs.rd_no_text,
            "notEnough" to rs.rd_not_enough,
            "notFound" to rs.rd_not_found,
            "notFoundNote" to rs.rd_not_found_note,
            "context" to rs.rd_context,
            "otherMatch" to rs.rd_other_match,
            "foundIn" to rs.rd_found_in,
            "paragraph" to rs.rd_paragraph
        )
        val json = org.json.JSONObject()
        texts.forEach { (k, v) -> json.put(k, escapeHtml(v)) }
        json.put("stop", org.json.JSONArray(plain(stop).split(' ').filter { it.isNotBlank() }))
        return json.toString()
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun fallbackInPageReader(
        articleTitle: String,
        themeBg: String,
        themeFg: String,
        fontFamily: String,
        fontScale: Double,
        readerTexts: String
    ) {
        // Barra invertida primero: si no, un título con «\\» deshacía el escape de las comillas
        val safeTitle = articleTitle.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"")
            .replace("\n", " ").replace("\r", " ").replace("<", "\\u003c")
        val js = "(function(){" +
            "try{" +
            "var SR=$readerTexts;" +
            "var ex=document.getElementById('senda-reader-container');if(ex){ex.remove();document.body.style.overflow='';return;}" +
            "var candidates=document.querySelectorAll('article,[role=\"main\"],main,.article-content,.post-content,.entry-content');" +
            "var m=candidates[0];" +
            "if(!m){var divs=Array.from(document.querySelectorAll('div,section'));divs.sort(function(a,b){return (b.innerText?b.innerText.length:0)-(a.innerText?a.innerText.length:0);});m=divs[0]||document.body;}" +
            "var clone=m.cloneNode(true);" +
            "clone.className=(clone.className||'')+' reader-body';" +
            "clone.querySelectorAll('script,style,iframe,nav,header,footer,aside,.ad,.advertisement,[class*=\"social\"],form,button').forEach(function(el){el.remove();});" +
            "clone.querySelectorAll('*').forEach(function(el){if(el.tagName!=='IMG'){el.removeAttribute('style');el.removeAttribute('class');el.removeAttribute('id');}else{el.style.maxWidth='100%';el.style.height='auto';el.style.borderRadius='12px';el.style.margin='20px auto';el.style.display='block';}});" +
            "var words=(clone.innerText||'').trim().split(/\\s+/).length;var time=Math.max(1,Math.round(words/200));" +
            "var c=document.createElement('div');c.id='senda-reader-container';" +
            "c.style.cssText='position:fixed;top:0;left:0;width:100vw;height:100vh;overflow-y:auto;background:$themeBg;color:$themeFg;z-index:2147483647;padding:24px 20px 80px 20px;box-sizing:border-box;font-family:$fontFamily;line-height:1.75;font-size:${19 * fontScale}px;';" +
            "var b=document.createElement('div');b.style.cssText='max-width:680px;margin:0 auto 24px auto;display:flex;justify-content:space-between;align-items:center;border-bottom:1px solid rgba(128,128,128,0.25);padding-bottom:12px;font-size:13px;opacity:0.85;flex-wrap:wrap;gap:8px;';" +
            "b.innerHTML='<div><span>📖 '+SR.reader+' · '+SR.readingTime.replace('{n}',time)+'</span></div><div style=\"display:flex;gap:6px;align-items:center;\"><button id=\"senda-fb-sum\" style=\"background:rgba(0,210,160,0.15);border:1px solid #00D2A0;color:inherit;padding:3px 8px;border-radius:6px;font-size:11px;font-weight:600;cursor:pointer;\">⚡ '+SR.keySentences+'</button><button id=\"senda-fb-ask\" style=\"background:rgba(0,180,255,0.15);border:1px solid #00B0FF;color:inherit;padding:3px 8px;border-radius:6px;font-size:11px;font-weight:600;cursor:pointer;\">🔎 '+SR.ask+'</button><button id=\"senda-reader-close\" style=\"background:rgba(128,128,128,0.2);border:none;color:inherit;padding:4px 10px;border-radius:14px;font-weight:bold;cursor:pointer;font-size:12px;\">✕</button></div>';" +
            "var w=document.createElement('div');w.style.cssText='max-width:680px;margin:0 auto;word-break:break-word;';" +
            "var sumBox=document.createElement('div');sumBox.id='senda-summary-box';sumBox.style.cssText='display:none;background:rgba(128,128,128,0.12);border-left:3px solid #00D2A0;border-radius:8px;padding:12px 14px;margin-bottom:14px;font-size:0.9em;';" +
            "sumBox.innerHTML='<div style=\"display:flex;justify-content:space-between;align-items:center;margin-bottom:6px;\"><strong style=\"font-size:12px;color:#00D2A0;\">⚡ '+SR.firstSentencesUp+'</strong><button onclick=\"document.getElementById(\\'senda-summary-box\\').style.display=\\'none\\'\" style=\"background:none;border:none;color:inherit;font-size:14px;cursor:pointer;\">✕</button></div><div id=\"senda-summary-content\"></div>';" +
            "var askBox=document.createElement('div');askBox.id='senda-ask-box';askBox.style.cssText='display:none;background:rgba(128,128,128,0.12);border-left:3px solid #00B0FF;border-radius:8px;padding:12px 14px;margin-bottom:14px;font-size:0.9em;';" +
            "askBox.innerHTML='<div style=\"display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;\"><strong style=\"font-size:12px;color:#00B0FF;\">🔎 '+SR.askHeaderUp+'</strong><button onclick=\"document.getElementById(\\'senda-ask-box\\').style.display=\\'none\\'\" style=\"background:none;border:none;color:inherit;font-size:14px;cursor:pointer;\">✕</button></div><div style=\"display:flex;gap:6px;margin-bottom:8px;\"><input type=\"text\" id=\"senda-ask-input\" placeholder=\"'+SR.askPlaceholder+'\" style=\"flex:1;background:rgba(128,128,128,0.15);border:1px solid rgba(128,128,128,0.3);border-radius:6px;padding:8px;color:inherit;font-size:13px;outline:none;\"/><button id=\"senda-fb-ask-exec\" style=\"background:#00B0FF;color:#FFF;border:none;border-radius:6px;padding:8px 12px;font-weight:600;font-size:12px;cursor:pointer;\">'+SR.search+'</button></div><div id=\"senda-ask-result\" style=\"display:none;background:rgba(128,128,128,0.08);border-radius:6px;padding:10px;margin-top:6px;\"></div>';" +
            "var h=document.createElement('h1');h.innerText='$safeTitle';h.style.cssText='font-size:${28 * fontScale}px;line-height:1.3;margin-bottom:20px;font-weight:bold;';" +
            "w.appendChild(h);w.appendChild(sumBox);w.appendChild(askBox);w.appendChild(clone);c.appendChild(b);c.appendChild(w);document.body.appendChild(c);document.body.style.overflow='hidden';" +
            "document.getElementById('senda-reader-close').onclick=function(){c.remove();document.body.style.overflow='';};" +
            "function stripAcc(s){return (s||'').normalize('NFD').replace(/[\\u0300-\\u036f]/g,'');}" +
            "function escH(x){return String(x).replace(/[&<>\"']/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',\"'\":'&#39;'}[c];});}" +
            "function hlM(txt,tms){if(!tms||tms.length===0)return escH(txt);var nt=tms.map(function(t){return stripAcc(t.toLowerCase());});return txt.split(/([\\p{L}\\p{N}_]+)/u).map(function(tok,ix){if(ix%2===1){var ntok=stripAcc(tok.toLowerCase());for(var i=0;i<nt.length;i++){if(ntok===nt[i]||(nt[i].length>=5&&ntok.indexOf(nt[i].substring(0,nt[i].length-1))===0))return '<mark style=\"background:rgba(0,180,255,0.28);color:inherit;padding:1px 3px;border-radius:3px;font-weight:600;\">'+escH(tok)+'</mark>';}}return escH(tok);}).join('');}" +
            "document.getElementById('senda-fb-sum').onclick=function(){if(sumBox.style.display==='none'||sumBox.style.display===''){sumBox.style.display='block';var raw=(clone.innerText||'').trim();var sens=raw.replace(/\\s+/g,' ').split(/(?<=[.!?])\\s+/).map(function(s){return s.trim();}).filter(function(s){return s.length>30;});var pts=sens.slice(0,3);var ul=pts.map(function(p){return '<li>'+escH(p)+'</li>';}).join('');document.getElementById('senda-summary-content').innerHTML='<ul>'+ul+'</ul>';}else{sumBox.style.display='none';}};" +
            "document.getElementById('senda-fb-ask').onclick=function(){askBox.style.display=(askBox.style.display==='none'||askBox.style.display==='')?'block':'none';if(askBox.style.display==='block'){document.getElementById('senda-ask-input').focus();}};" +
            "function doAsk(){var q=document.getElementById('senda-ask-input').value.trim();if(!q)return;var res=document.getElementById('senda-ask-result');res.style.display='block';res.innerHTML='🔍 '+SR.searching;var stp=new Set(SR.stop);var terms=stripAcc(q.toLowerCase()).replace(/[^\\p{L}\\p{N}\\s]/gu,' ').split(/\\s+/).filter(function(w){return w.length>2&&!stp.has(w);});if(terms.length===0)terms=stripAcc(q.toLowerCase()).replace(/[^\\p{L}\\p{N}\\s]/gu,' ').split(/\\s+/).filter(function(w){return w.length>1;});var pars=(clone.innerText||'').split(/\\n+/).filter(function(p){return p.length>20;});var sens=[];pars.forEach(function(p,pIdx){p.replace(/\\s+/g,' ').split(/(?<=[.!?])\\s+/).forEach(function(s){if(s.length>20)sens.push({text:s,pNum:pIdx+1,pText:p});});});var m=[];sens.forEach(function(item){var ns=stripAcc(item.text.toLowerCase());var sc=0;var mw=[];terms.forEach(function(t){if(ns.indexOf(t)!==-1){sc+=10;mw.push(t);}});if(sc>0)m.push({item:item,score:sc,mw:mw});});m.sort(function(a,b){return b.score-a.score;});if(m.length===0){res.innerHTML='<strong>ℹ️ '+SR.notFound+'</strong><br><span style=\"font-size:11px;opacity:0.7;\">'+SR.notFoundNote+'</span>';return;}var best=m[0];res.innerHTML='<div>'+hlM(best.item.text,best.mw)+'</div><div style=\"font-size:11px;opacity:0.8;margin-top:6px;border-top:1px solid rgba(128,128,128,0.2);padding-top:4px;\">📌 '+SR.paragraph.replace('{n}',best.item.pNum)+'</div>';}" +
            "document.getElementById('senda-fb-ask-exec').onclick=doAsk;" +
            "document.getElementById('senda-ask-input').onkeydown=function(e){if(e.key==='Enter')doAsk();};" +
            "}catch(e){console.error(e);}" +
            "})();"

        isReaderMode = true
        val encoded = URLEncoder.encode(js, "UTF-8").replace("+", "%20")
        session.loadUri("javascript:$encoded")
    }

    private fun handleTabCrashed() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            isLoading = false
            isCrashed = true
        }
    }

    fun restoreSession() {
        try {
            isCrashed = false
            isLoading = true
            if (!session.isOpen) {
                session.open(SendaGeckoEngine.getRuntime())
            }
            if (url.isNotBlank() && url != "about:blank") {
                session.loadUri(url)
            } else {
                session.reload()
            }
        } catch (e: Exception) {
            android.util.Log.e("Senda", "Error al restaurar sesión tras caída: ${e.message}")
        }
    }

    fun setActiveState(isActive: Boolean) {
        if (isActive) {
            lastUsed = System.currentTimeMillis()
            pendingLoad?.let { target ->
                pendingLoad = null
                session.loadUri(target)
            }
        }
        try {
            session.setActive(isActive)
            session.setPriorityHint(if (isActive) GeckoSession.PRIORITY_HIGH else GeckoSession.PRIORITY_DEFAULT)
        } catch (_: Exception) {}
    }

    fun close() {
        // Resolver también los que esperaban turno: un GeckoResult sin completar deja a Gecko esperando
        while (activePrompt != null) dismissActivePrompt()
        thumbnail = null
        try {
            session.setActive(false)
            session.close()
        } catch (_: Exception) {}
    }
}

object SendaUrlResolver {
    private val IP_PATTERN = Regex("""^((25[0-5]|(2[0-4]|1\d|[1-9]|)\d)\.){3}(25[0-5]|(2[0-4]|1\d|[1-9]|)\d)(:\d+)?(/.*)?$""")
    private val DOMAIN_PATTERN = Regex("""^([a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,}(:\d+)?(/.*)?$""")

    fun resolve(input: String, searchBaseUrl: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return "about:blank"

        val lower = trimmed.lowercase()

        // 1. Esquemas directos
        if (lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.startsWith("about:") ||
            lower.startsWith("file://") ||
            lower.startsWith("view-source:") ||
            lower.startsWith("content://") ||
            lower.startsWith("data:") ||
            lower.startsWith("blob:") ||
            lower.startsWith("moz-extension://") ||
            lower.startsWith("javascript:")
        ) {
            return trimmed
        }

        // 2. Si no tiene espacios, evaluar si es un dominio, IP o localhost
        if (!trimmed.any { it.isWhitespace() }) {
            // Localhost
            if (lower == "localhost" || lower.startsWith("localhost:") || lower.startsWith("localhost/")) {
                return "http://$trimmed"
            }

            // Direcciones IP
            if (IP_PATTERN.matches(trimmed)) {
                return "http://$trimmed"
            }

            // Nombres de dominio con TLD válido
            if (DOMAIN_PATTERN.matches(trimmed) || android.util.Patterns.WEB_URL.matcher(trimmed).matches()) {
                return "https://$trimmed"
            }
        }

        // 3. De lo contrario, derivar al motor de búsqueda ético
        return if (searchBaseUrl.contains("%s")) {
            searchBaseUrl.replace("%s", UriEncoder.encode(trimmed))
        } else {
            "$searchBaseUrl${UriEncoder.encode(trimmed)}"
        }
    }
}

object UriEncoder {
    fun encode(query: String): String {
        return java.net.URLEncoder.encode(query, "UTF-8")
    }
}
