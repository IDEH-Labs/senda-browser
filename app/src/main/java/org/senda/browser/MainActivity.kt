package org.senda.browser

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.senda.browser.core.LocalSendaStrings
import org.senda.browser.core.PreferencesManager
import org.senda.browser.core.SendaGeckoEngine
import org.senda.browser.core.SendaLocaleManager
import org.senda.browser.core.SendaStringPack
import org.senda.browser.core.SendaStrings
import org.senda.browser.ui.model.BrowserTab
import org.senda.browser.ui.screens.BrowserScreen
import org.senda.browser.ui.screens.SettingsScreen
import org.senda.browser.ui.theme.SendaTheme

class MainActivity : FragmentActivity() {

    private lateinit var prefs: PreferencesManager
    private var onNewIntentCallback: ((String) -> Unit)? = null

    // Permisos de Android que pide Gecko (cámara, micrófono, ubicación) tras aceptar el aviso de un sitio
    private var pendingPermissionResult: ((Boolean) -> Unit)? = null
    private val androidPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val callback = pendingPermissionResult
        pendingPermissionResult = null
        callback?.invoke(results.isNotEmpty() && results.values.all { it })
    }

    private fun requestAndroidPermissions(permissions: List<String>, onResult: (Boolean) -> Unit) {
        val missing = permissions.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            onResult(true)
            return
        }
        // Una petición a la vez: si había otra pendiente se da por denegada
        pendingPermissionResult?.invoke(false)
        pendingPermissionResult = onResult
        androidPermissionLauncher.launch(missing.toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferencesManager(this)
        SendaLocaleManager.applyLocale(this, prefs.appLanguage)
        // Protección contra espionaje en vista multitarea (FLAG_SECURE) si el usuario la activó
        updateAntiSnoopingFlag()

        // Verificar si se abrió mediante un enlace externo o texto compartido
        val initialUrl = intent?.dataString
            ?: intent?.getStringExtra("url")
            ?: intent?.getStringExtra(Intent.EXTRA_TEXT)
            ?: "about:blank"

        setContent {
            var isUnlocked by remember { mutableStateOf(!prefs.requireBiometrics) }
            var currentScreen by remember { mutableStateOf("browser") }

            // Estado reactivo para refrescar temas e idioma al cambiar ajustes
            var themeRecomposeKey by remember { mutableIntStateOf(0) }
            var appLanguage by remember { mutableStateOf(prefs.appLanguage) }
            val currentStrings: SendaStringPack = remember(appLanguage, themeRecomposeKey) {
                SendaStrings.get(appLanguage, this@MainActivity)
            }

            val tabs = remember { mutableStateListOf<BrowserTab>() }
            var activeTabId by remember { mutableStateOf("") }

            // Inicializar con la primera pestaña
            LaunchedEffect(Unit) {
                if (tabs.isEmpty()) {
                    // Reabrir las pestañas de la sesión anterior (las privadas nunca se guardan)
                    if (!prefs.alwaysPrivateMode) {
                        val (saved, activeIndex) = prefs.loadOpenTabs()
                        saved.forEach { savedTab ->
                            tabs.add(
                                BrowserTab(
                                    initialUrl = savedTab.url,
                                    searchBaseUrl = prefs.customSearchEngineUrl,
                                    prefs = prefs,
                                    lazyLoad = true
                                ).apply {
                                    if (savedTab.title.isNotBlank()) title = savedTab.title
                                    lastUsed = savedTab.lastUsed
                                }
                            )
                        }
                        tabs.getOrNull(activeIndex)?.let { activeTabId = it.id }
                    }
                    // Un enlace abierto desde otra app va en una pestaña nueva y al frente
                    if (tabs.isEmpty() || initialUrl != "about:blank") {
                        val firstTab = BrowserTab(
                            initialUrl = initialUrl,
                            isPrivate = prefs.alwaysPrivateMode,
                            searchBaseUrl = prefs.customSearchEngineUrl,
                            prefs = prefs
                        )
                        tabs.add(firstTab)
                        activeTabId = firstTab.id
                    }
                }

                if (prefs.requireBiometrics && !isUnlocked) {
                    // Nunca se cierra la app si falla: queda la pantalla de bloqueo para reintentar
                    showBiometricAuth { success -> if (success) isUnlocked = true }
                }
            }

            DisposableEffect(Unit) {
                onNewIntentCallback = { url ->
                    val newTab = BrowserTab(
                        initialUrl = url,
                        isPrivate = prefs.alwaysPrivateMode,
                        searchBaseUrl = prefs.customSearchEngineUrl,
                        prefs = prefs
                    )
                    tabs.add(newTab)
                    activeTabId = newTab.id
                    currentScreen = "browser"
                }
                // Pestañas abiertas por la web (target=_blank, window.open) o desde el menú de pulsación larga
                BrowserTab.tabOpener = { newTab, background ->
                    if (!background) newTab.parentTabId = activeTabId
                    val parentIndex = tabs.indexOfFirst { it.id == activeTabId }
                    tabs.add(if (parentIndex >= 0) parentIndex + 1 else tabs.size, newTab)
                    if (!background) activeTabId = newTab.id
                    currentScreen = "browser"
                }
                BrowserTab.androidPermissionRequester = { permissions, onResult ->
                    requestAndroidPermissions(permissions, onResult)
                }
                onDispose {
                    BrowserTab.tabOpener = null
                    BrowserTab.androidPermissionRequester = null
                    onNewIntentCallback = null
                }
            }

            val activeTab = tabs.find { it.id == activeTabId } ?: tabs.firstOrNull()
            val isBrowsingExternalSite = activeTab != null && activeTab.url != "about:blank" && activeTab.url.isNotBlank()
            // Pantalla completa inmersiva solo cuando el usuario lo solicita explícitamente en la pestaña
            // El reproductor de TV también ocupa toda la pantalla, igual que un video a pantalla completa
            val tvPlayerActive = org.senda.browser.core.cast.SendaTvPlayer.playback != null &&
                prefs.tvModeEnabled && prefs.tvModeLandscape
            val isFullScreen = activeTab?.isFullScreen == true || tvPlayerActive
            val keepPortrait = !tvPlayerActive && activeTab?.isFullScreenVideoPortrait == true

            // Modo inmersivo total para reproducción de video en pantalla completa solicitada por el usuario
            LaunchedEffect(isFullScreen, keepPortrait) {
                val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
                // En pantalla completa la ventana se dibuja bajo las barras y la cámara: si no, Android
                // sigue reservando su espacio aunque estén ocultas y el video queda con márgenes
                WindowCompat.setDecorFitsSystemWindows(window, !isFullScreen)
                if (isFullScreen) {
                    windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        // SHORT_EDGES: en horizontal la cámara queda en un lado corto; con DEFAULT Android reserva
                        // esa franja y el video se centra en un área más estrecha, descentrado y más pequeño
                        // (en la TV se ve con bordes desiguales)
                        val lp = window.attributes
                        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                        window.attributes = lp
                    }
                    requestedOrientation = if (keepPortrait) {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }
                } else {
                    windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val lp = window.attributes
                        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                        window.attributes = lp
                    }
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }

            // Al salir de la app (Inicio, Recientes, otra app) abandonar la pantalla completa: si no, Senda queda
            // con barras ocultas y orientación bloqueada, y al volver al sistema la ventana inmersiva sigue reclamando la pantalla
            // Guardar las pestañas abiertas al salir de Senda, para reabrirlas la próxima vez
            DisposableEffect(Unit) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_STOP) {
                        val keep = tabs.filter { tab ->
                            !tab.isPrivate && (tab.url.startsWith("http://") || tab.url.startsWith("https://"))
                        }
                        val active = keep.indexOfFirst { it.id == activeTabId }.coerceAtLeast(0)
                        prefs.saveOpenTabs(
                            keep.map { PreferencesManager.SavedTab(it.url, it.title, it.lastUsed) },
                            active
                        )
                    }
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }

            DisposableEffect(activeTab) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_STOP) {
                        activeTab?.exitFullScreen()
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }

            // Asignar prioridades de memoria: sólo la pestaña activa consume buffers en primer plano
            LaunchedEffect(activeTab?.id) {
                tabs.forEach { tab ->
                    tab.setActiveState(tab.id == activeTab?.id)
                }
            }

            // Blindaje de privacidad a nivel de hardware/SO: Activar FLAG_SECURE automáticamente si la pestaña es privada
            LaunchedEffect(activeTab?.isPrivate, prefs.enableAntiSnooping, org.senda.browser.core.cast.SendaTvMode.tvConnected) {
                privateTabActive = activeTab?.isPrivate == true
                updateAntiSnoopingFlag()
            }

            // Solo interceptar Atrás en MainActivity si estamos dentro de la pantalla de ajustes
            BackHandler(enabled = currentScreen == "settings") {
                currentScreen = "browser"
            }

            // Referenciar themeRecomposeKey para provocar recomposición de SendaTheme sin destruir el árbol UI ni cerrar diálogos
            @Suppress("UNUSED_VARIABLE")
            val themeRecomposeTrigger = themeRecomposeKey

            CompositionLocalProvider(LocalSendaStrings provides currentStrings) {
                SendaTheme(
                    themeMode = prefs.themeMode,
                    useSystemColor = prefs.useSystemColor,
                    accentHex = prefs.accentColorHex,
                    isTrueOled = prefs.isTrueOledBlack,
                    fontFamilyKey = prefs.uiFontFamily,
                    fontScalePercent = prefs.uiFontScalePercent,
                    hinting = prefs.fontHinting,
                    recomposeKey = themeRecomposeKey
                ) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        SendaGeckoEngine.pendingExtensionInstall?.let { request ->
                            org.senda.browser.ui.components.ExtensionInstallDialog(request)
                        }
                        if (!isUnlocked) {
                            SendaLockScreen(onUnlock = {
                                showBiometricAuth { success -> if (success) isUnlocked = true }
                            })
                        }
                        // Volver a bloquear si Senda pasó más de 30 s en segundo plano
                        DisposableEffect(Unit) {
                            var hiddenAt = 0L
                            val observer = LifecycleEventObserver { _, event ->
                                when (event) {
                                    Lifecycle.Event.ON_STOP -> hiddenAt = android.os.SystemClock.elapsedRealtime()
                                    Lifecycle.Event.ON_START -> if (prefs.requireBiometrics && isUnlocked && hiddenAt != 0L &&
                                        android.os.SystemClock.elapsedRealtime() - hiddenAt > 30_000L
                                    ) {
                                        isUnlocked = false
                                        showBiometricAuth { success -> if (success) isUnlocked = true }
                                    }
                                    else -> {}
                                }
                            }
                            lifecycle.addObserver(observer)
                            onDispose { lifecycle.removeObserver(observer) }
                        }
                        if (isUnlocked) {
                            if (currentScreen == "settings") {
                                SettingsScreen(
                                    prefs = prefs,
                                    onBack = { currentScreen = "browser" },
                                    onOpenUrl = { url ->
                                        val newTab = BrowserTab(
                                            initialUrl = url,
                                            isPrivate = prefs.alwaysPrivateMode,
                                            searchBaseUrl = prefs.customSearchEngineUrl,
                                            prefs = prefs
                                        )
                                        tabs.add(newTab)
                                        activeTabId = newTab.id
                                        currentScreen = "browser"
                                    },
                                    onSettingsChanged = {
                                        themeRecomposeKey++
                                        if (appLanguage != prefs.appLanguage) {
                                            appLanguage = prefs.appLanguage
                                            SendaLocaleManager.applyLocale(this@MainActivity, prefs.appLanguage)
                                        }
                                        updateAntiSnoopingFlag()
                                        SendaGeckoEngine.applyPreferences(prefs)
                                        tabs.forEach { it.searchBaseUrl = prefs.customSearchEngineUrl }
                                    }
                                )
                            } else {
                                BrowserScreen(
                                    prefs = prefs,
                                    tabs = tabs,
                                    activeTab = activeTab,
                                    onNewTab = { url ->
                                        val newTab = BrowserTab(
                                            initialUrl = url,
                                            isPrivate = prefs.alwaysPrivateMode,
                                            searchBaseUrl = prefs.customSearchEngineUrl,
                                            prefs = prefs
                                        )
                                        tabs.add(newTab)
                                        activeTabId = newTab.id
                                    },
                                    onNewPrivateTab = { url ->
                                        val newTab = BrowserTab(
                                            initialUrl = url,
                                            isPrivate = true,
                                            searchBaseUrl = prefs.customSearchEngineUrl,
                                            prefs = prefs
                                        )
                                        tabs.add(newTab)
                                        activeTabId = newTab.id
                                    },
                                    onCloseTab = { tabToClose ->
                                        val index = tabs.indexOf(tabToClose)
                                        tabToClose.close()
                                        tabs.remove(tabToClose)
                                        if (tabs.isEmpty()) {
                                            val fresh = BrowserTab(
                                                initialUrl = "about:blank",
                                                isPrivate = prefs.alwaysPrivateMode,
                                                searchBaseUrl = prefs.customSearchEngineUrl,
                                                prefs = prefs
                                            )
                                            tabs.add(fresh)
                                            activeTabId = fresh.id
                                        } else if (tabToClose.id == activeTabId) {
                                            activeTabId = tabs.getOrNull((index - 1).coerceAtLeast(0))?.id ?: tabs.first().id
                                        }
                                    },
                                    onSelectTab = { tab ->
                                        activeTabId = tab.id
                                    },
                                    onCloseAllTabs = {
                                        tabs.forEach { it.close() }
                                        tabs.clear()
                                        val fresh = BrowserTab(
                                            initialUrl = "about:blank",
                                            isPrivate = prefs.alwaysPrivateMode,
                                            searchBaseUrl = prefs.customSearchEngineUrl,
                                            prefs = prefs
                                        )
                                        tabs.add(fresh)
                                        activeTabId = fresh.id
                                    },
                                    onOpenSettings = { currentScreen = "settings" },
                                    onSettingsChanged = {
                                        themeRecomposeKey++
                                        updateAntiSnoopingFlag()
                                        SendaGeckoEngine.applyPreferences(prefs)
                                        tabs.forEach { it.searchBaseUrl = prefs.customSearchEngineUrl }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // La actividad gestiona ella misma los giros (configChanges), así que Gecko no se entera solo:
        // sin esto la web conserva el tamaño de pantalla anterior y el video queda en negro al girar
        try {
            val runtime = SendaGeckoEngine.getRuntime()
            runtime.configurationChanged(newConfig)
            runtime.orientationChanged()
        } catch (_: Exception) {}
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            System.gc()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        System.gc()
    }

    // Mientras se duplica la pantalla a la TV, pedir 60 Hz en vez de 120 Hz: la TV no muestra más de 60
    // y componer/codificar el doble de fotogramas es lo que retrasa la imagen en la TV
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = updateRefreshRateForMirroring()
        override fun onDisplayRemoved(displayId: Int) = updateRefreshRateForMirroring()
        override fun onDisplayChanged(displayId: Int) {}
    }

    override fun onStart() {
        super.onStart()
        getSystemService(DisplayManager::class.java)?.registerDisplayListener(displayListener, null)
        updateRefreshRateForMirroring()
    }

    override fun onStop() {
        getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
        super.onStop()
    }

    private fun updateRefreshRateForMirroring() {
        val displayManager = getSystemService(DisplayManager::class.java) ?: return
        val mirroring = displayManager.displays.any { it.displayId != android.view.Display.DEFAULT_DISPLAY }
        val display = window.decorView.display ?: displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: return
        val modeId = if (mirroring) {
            val current = display.mode
            display.supportedModes
                .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight && it.refreshRate in 59f..61f }
                .minByOrNull { it.refreshRate }?.modeId ?: 0
        } else {
            0
        }
        val lp = window.attributes
        val targetRefresh = if (mirroring) 60f else 0f
        if (lp.preferredDisplayModeId != modeId || lp.preferredRefreshRate != targetRefresh) {
            lp.preferredDisplayModeId = modeId
            lp.preferredRefreshRate = targetRefresh
            window.attributes = lp
        }
    }

    override fun onResume() {
        super.onResume()
        updateAntiSnoopingFlag()
    }

    // Pestaña activa privada: se recuerda aquí para que onResume no retire la protección de una pestaña privada
    private var privateTabActive = false

    private fun updateAntiSnoopingFlag() {
        // La protección anti-espionaje hace que Senda se vea en negro en la TV: mientras se duplica la pantalla
        // se levanta para las pestañas normales. Las privadas siguen protegidas siempre
        val tvConnected = org.senda.browser.core.cast.SendaTvMode.tvConnected
        if ((prefs.enableAntiSnooping && !tvConnected) || privateTabActive) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val incoming = intent.dataString
            ?: intent.getStringExtra("url")
            ?: intent.getStringExtra(Intent.EXTRA_TEXT)
        incoming?.let { url ->
            onNewIntentCallback?.invoke(url)
        }
    }

    private fun showBiometricAuth(onResult: (Boolean) -> Unit) {
        // Huella o, si no hay o falla, el PIN/patrón del teléfono. Sin esto, un móvil sin huella registrada
        // (o con el sensor roto) dejaba al usuario fuera de Senda para siempre
        val authenticators = androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or
            androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val canAuth = androidx.biometric.BiometricManager.from(this).canAuthenticate(authenticators)
        if (canAuth != androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS) {
            // El teléfono no tiene ningún bloqueo configurado: no hay con qué verificar, se avisa y se entra
            android.widget.Toast.makeText(this, getString(R.string.biometric_no_device_lock), android.widget.Toast.LENGTH_LONG).show()
            onResult(true)
            return
        }
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onResult(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    onResult(false)
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_prompt_title))
            .setSubtitle(getString(R.string.biometric_prompt_desc))
            .setAllowedAuthenticators(authenticators)
            .build()

        prompt.authenticate(promptInfo)
    }
}

/** Pantalla mientras Senda está bloqueada: nada del contenido queda a la vista. */
@androidx.compose.runtime.Composable
private fun SendaLockScreen(onUnlock: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)
        ) {
            androidx.compose.material3.Icon(
                imageVector = androidx.compose.material.icons.Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = androidx.compose.material3.MaterialTheme.colorScheme.primary
            )
            androidx.compose.material3.Text(
                text = androidx.compose.ui.res.stringResource(R.string.biometric_prompt_title),
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium
            )
            androidx.compose.material3.Button(onClick = onUnlock) {
                androidx.compose.material3.Icon(
                    imageVector = androidx.compose.material.icons.Icons.Default.Fingerprint,
                    contentDescription = null
                )
                androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.biometric_unlock_button))
            }
        }
    }
}
