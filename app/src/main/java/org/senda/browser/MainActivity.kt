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
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
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

    // Android permissions Gecko asks for (camera, microphone, location) after accepting a site's notice
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
        // One request at a time: if another was pending it is treated as denied
        pendingPermissionResult?.invoke(false)
        pendingPermissionResult = onResult
        androidPermissionLauncher.launch(missing.toTypedArray())
    }

    // "Ask where to save": one file picker request at a time; the others wait their turn
    private class SaveRequest(val fileName: String, val mime: String, val onResult: (android.net.Uri?) -> Unit)
    private val pendingSaves = ArrayDeque<SaveRequest>()
    private val saveLocationLauncher: androidx.activity.result.ActivityResultLauncher<SaveRequest> = registerForActivityResult(
        object : androidx.activity.result.contract.ActivityResultContract<SaveRequest, android.net.Uri?>() {
            override fun createIntent(context: android.content.Context, input: SaveRequest) =
                Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = input.mime.takeIf { '*' !in it } ?: "application/octet-stream"
                    putExtra(Intent.EXTRA_TITLE, input.fileName)
                }
            override fun parseResult(resultCode: Int, intent: Intent?) =
                intent?.data.takeIf { resultCode == RESULT_OK }
        }
    ) { uri ->
        pendingSaves.removeFirstOrNull()?.onResult?.invoke(uri)
        pendingSaves.firstOrNull()?.let { saveLocationLauncher.launch(it) }
    }

    private fun askSaveLocation(fileName: String, mime: String, onResult: (android.net.Uri?) -> Unit) {
        pendingSaves.addLast(SaveRequest(fileName, mime, onResult))
        if (pendingSaves.size == 1) saveLocationLauncher.launch(pendingSaves.first())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Since Android 15 (targetSdk 35+) the window always also covers the system bars area.
        // It is enabled the same way on all versions and the UI reserves that space with WindowInsets.safeDrawing
        enableEdgeToEdge()
        prefs = PreferencesManager(this)
        SendaLocaleManager.applyLocale(this, prefs.appLanguage)
        // Protection against snooping in the recents view (FLAG_SECURE) if the user turned it on
        updateAntiSnoopingFlag()

        // Recreated by Android (it should not be: configChanges covers TV mode screen changes): the tabs are reopened
        // as they were, without repeating the link that opened the app or putting a home tab in front
        val recreated = savedInstanceState != null
        // Check whether it was opened through an external link or shared text
        val initialUrl = (if (recreated) null else externalUrlFrom(intent)) ?: "about:blank"

        setContent {
            var isUnlocked by remember { mutableStateOf(!prefs.requireBiometrics) }
            var currentScreen by remember { mutableStateOf("browser") }

            // Reactive state to refresh themes and language when settings change
            var themeRecomposeKey by remember { mutableIntStateOf(0) }
            var appLanguage by remember { mutableStateOf(prefs.appLanguage) }
            val currentStrings: SendaStringPack = remember(appLanguage, themeRecomposeKey) {
                SendaStrings.get(appLanguage, this@MainActivity)
            }

            val tabs = remember { mutableStateListOf<BrowserTab>() }
            var activeTabId by remember { mutableStateOf("") }

            // Initialize with the first tab
            LaunchedEffect(Unit) {
                if (tabs.isEmpty()) {
                    val startupMode = if (recreated) "RESUME" else prefs.startupMode
                    // Reopen the tabs of the previous session (private ones are never saved)
                    if (!prefs.alwaysPrivateMode && startupMode != "CLEAN") {
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
                    // "Clean home page": start on an empty tab; if there already was one (the last
                    // used or any other), reuse it so empty tabs don't pile up at every startup
                    val reusableHome = if (startupMode == "HOME" && initialUrl == "about:blank") {
                        tabs.firstOrNull { it.id == activeTabId && it.url == "about:blank" }
                            ?: tabs.firstOrNull { it.url == "about:blank" }
                    } else null
                    if (reusableHome != null) activeTabId = reusableHome.id
                    // A link opened from another app goes into a new tab, in front
                    if (tabs.isEmpty() || initialUrl != "about:blank" || (startupMode == "HOME" && reusableHome == null)) {
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
                    // The app never closes on failure: the lock screen stays so the user can try again
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
                // Tabs opened by the website (target=_blank, window.open) or from the long-press menu
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
                org.senda.browser.core.SendaDownloadManager.locationPicker = ::askSaveLocation
                BrowserTab.printer = { pdf, title -> org.senda.browser.core.SendaPageActions.print(this@MainActivity, pdf, title) }
                onDispose {
                    BrowserTab.tabOpener = null
                    BrowserTab.androidPermissionRequester = null
                    org.senda.browser.core.SendaDownloadManager.locationPicker = null
                    BrowserTab.printer = null
                    onNewIntentCallback = null
                }
            }

            val activeTab = tabs.find { it.id == activeTabId } ?: tabs.firstOrNull()
            // Immersive full screen only when the user explicitly asks for it in the tab
            val isFullScreen = activeTab?.isFullScreen == true
            val keepPortrait = activeTab?.isFullScreenVideoPortrait == true

            // Fully immersive mode for full-screen video playback requested by the user
            LaunchedEffect(isFullScreen, keepPortrait) {
                val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
                // The UI reserves the bars' space (safeDrawing), except in full screen
                if (isFullScreen) {
                    windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        // SHORT_EDGES: in landscape the camera sits on a short edge; with DEFAULT Android reserves
                        // that strip and the video is centered in a narrower area, off-center and smaller
                        // (on the TV it shows with uneven borders)
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

            // When leaving the app (Home, Recents, another app) exit full screen: otherwise Senda is left
            // with hidden bars and locked orientation, and back in the system the immersive window keeps claiming the screen
            // Save the open tabs when leaving Senda, to reopen them next time
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

            // Assign memory priorities: only the active tab uses foreground buffers
            LaunchedEffect(activeTab?.id) {
                tabs.forEach { tab ->
                    tab.setActiveState(tab.id == activeTab?.id)
                }
            }

            // OS-level privacy shield: turn on FLAG_SECURE automatically if the tab is private
            LaunchedEffect(activeTab?.isPrivate, prefs.enableAntiSnooping, org.senda.browser.core.cast.SendaTvMode.tvConnected) {
                privateTabActive = activeTab?.isPrivate == true
                updateAntiSnoopingFlag()
            }

            // Only intercept Back in MainActivity when we are inside the settings screen
            BackHandler(enabled = currentScreen == "settings") {
                currentScreen = "browser"
            }

            // Reference themeRecomposeKey to recompose SendaTheme without destroying the UI tree or closing dialogs
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
                    // Outside full screen, content does not go under the bars, the camera or the keyboard;
                    // the Surface background does extend behind the bars
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (isFullScreen) Modifier
                                else Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
                            )
                    ) {
                        SendaGeckoEngine.pendingExtensionInstall?.let { request ->
                            org.senda.browser.ui.components.ExtensionInstallDialog(request)
                        }
                        if (!isUnlocked) {
                            SendaLockScreen(onUnlock = {
                                showBiometricAuth { success -> if (success) isUnlocked = true }
                            })
                        }
                        // Lock again if Senda spent more than 30 s in the background
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
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The activity handles rotations itself (configChanges), so Gecko is not told on its own:
        // without this the page keeps the previous screen size and the video goes black on rotation
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

    // While mirroring to the TV, ask for 60 Hz instead of 120 Hz: the TV shows no more than 60
    // and composing/encoding twice as many frames is what delays the picture on the TV
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

    // Active private tab: remembered here so onResume does not remove a private tab's protection
    private var privateTabActive = false

    private fun updateAntiSnoopingFlag() {
        // Anti-snooping protection makes Senda show black on the TV: while mirroring it is
        // lifted for normal tabs. Private tabs always stay protected
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
        externalUrlFrom(intent)?.let { url ->
            onNewIntentCallback?.invoke(url)
        }
    }

    /**
     * What arrives from other apps is not trusted: javascript:, data:, about:, resource: or a file:// pointing to
     * Senda's private folder used to run or be listed as if the user had typed them.
     */
    private fun externalUrlFrom(intent: Intent?): String? {
        val raw = (intent?.dataString ?: intent?.getStringExtra("url") ?: intent?.getStringExtra(Intent.EXTRA_TEXT))
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val scheme = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):").find(raw)?.groupValues?.get(1)?.lowercase()
            ?: return raw // Shared text without a scheme: the address bar treats it as an address or a search
        return when (scheme) {
            "http", "https", "content" -> raw
            "file" -> raw.takeIf { isPublicFile(it) }
            else -> {
                android.util.Log.w("Senda", "Enlace externo rechazado (esquema $scheme)")
                null
            }
        }
    }

    private fun isPublicFile(fileUrl: String): Boolean {
        val path = android.net.Uri.parse(fileUrl).path ?: return false
        val canonical = try { java.io.File(path).canonicalPath } catch (_: java.io.IOException) { return false }
        val privateRoots = listOfNotNull(applicationInfo.dataDir, filesDir.parent, "/data/data", "/data/user", "/data/user_de")
            .map { java.io.File(it).canonicalPath }
        return privateRoots.none { canonical == it || canonical.startsWith("$it/") }
    }

    private fun showBiometricAuth(onResult: (Boolean) -> Unit) {
        // Fingerprint or, if there is none or it fails, the phone's PIN/pattern. Without this, a phone with no enrolled
        // fingerprint (or a broken sensor) locked the user out of Senda forever
        val authenticators = androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or
            androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val canAuth = androidx.biometric.BiometricManager.from(this).canAuthenticate(authenticators)
        if (canAuth != androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS) {
            // The phone has no lock set up: there is nothing to verify with, so the user is warned and let in
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

/** Screen shown while Senda is locked: none of the content is visible. */
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
