package org.senda.browser

import android.content.Intent
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
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import org.senda.browser.core.PreferencesManager
import org.senda.browser.ui.model.BrowserTab
import org.senda.browser.ui.screens.BrowserScreen
import org.senda.browser.ui.screens.SettingsScreen
import org.senda.browser.ui.theme.SendaTheme

class MainActivity : FragmentActivity() {

    private lateinit var prefs: PreferencesManager
    private var onNewIntentCallback: ((String) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferencesManager(this)

        // Protección contra espionaje en vista multitarea (FLAG_SECURE) si el usuario la activó
        updateAntiSnoopingFlag()

        // Verificar si se abrió mediante un enlace externo
        val initialUrl = intent?.dataString ?: "about:blank"

        setContent {
            var isUnlocked by remember { mutableStateOf(!prefs.requireBiometrics) }
            var currentScreen by remember { mutableStateOf("browser") }

            // Estado reactivo para refrescar temas al cambiar ajustes
            var themeRecomposeKey by remember { mutableIntStateOf(0) }

            val tabs = remember { mutableStateListOf<BrowserTab>() }
            var activeTabId by remember { mutableStateOf("") }

            // Inicializar con la primera pestaña
            LaunchedEffect(Unit) {
                if (tabs.isEmpty()) {
                    val firstTab = BrowserTab(initialUrl = initialUrl)
                    tabs.add(firstTab)
                    activeTabId = firstTab.id
                }

                if (prefs.requireBiometrics && !isUnlocked) {
                    showBiometricAuth { success ->
                        if (success) isUnlocked = true else finish()
                    }
                }
            }

            DisposableEffect(Unit) {
                onNewIntentCallback = { url ->
                    val newTab = BrowserTab(initialUrl = url)
                    tabs.add(newTab)
                    activeTabId = newTab.id
                    currentScreen = "browser"
                }
                onDispose {
                    onNewIntentCallback = null
                }
            }

            val activeTab = tabs.find { it.id == activeTabId } ?: tabs.firstOrNull()

            val shouldInterceptBack = currentScreen == "settings" || (activeTab?.canGoBack == true) || (tabs.size > 1)
            BackHandler(enabled = shouldInterceptBack) {
                if (currentScreen == "settings") {
                    currentScreen = "browser"
                } else if (activeTab?.canGoBack == true) {
                    activeTab.session.goBack()
                } else if (tabs.size > 1 && activeTab != null) {
                    val index = tabs.indexOf(activeTab)
                    activeTab.close()
                    tabs.remove(activeTab)
                    activeTabId = tabs.getOrNull((index - 1).coerceAtLeast(0))?.id ?: ""
                }
            }

            key(themeRecomposeKey) {
                SendaTheme(
                    themeMode = prefs.themeMode,
                    useSystemColor = prefs.useSystemColor,
                    accentHex = prefs.accentColorHex,
                    isTrueOled = prefs.isTrueOledBlack
                ) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        if (isUnlocked) {
                            if (currentScreen == "settings") {
                                SettingsScreen(
                                    prefs = prefs,
                                    onBack = { currentScreen = "browser" },
                                    onSettingsChanged = {
                                        themeRecomposeKey++
                                        updateAntiSnoopingFlag()
                                    }
                                )
                            } else {
                                BrowserScreen(
                                    prefs = prefs,
                                    tabs = tabs,
                                    activeTab = activeTab,
                                    onNewTab = { url ->
                                        val newTab = BrowserTab(initialUrl = url)
                                        tabs.add(newTab)
                                        activeTabId = newTab.id
                                    },
                                    onCloseTab = { tabToClose ->
                                        val index = tabs.indexOf(tabToClose)
                                        tabToClose.close()
                                        tabs.remove(tabToClose)
                                        if (tabs.isEmpty()) {
                                            val fresh = BrowserTab(initialUrl = "about:blank")
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
                                        val fresh = BrowserTab(initialUrl = "about:blank")
                                        tabs.add(fresh)
                                        activeTabId = fresh.id
                                    },
                                    onOpenSettings = { currentScreen = "settings" },
                                    onSettingsChanged = {
                                        themeRecomposeKey++
                                        updateAntiSnoopingFlag()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun updateAntiSnoopingFlag() {
        if (prefs.enableAntiSnooping) {
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
        intent.dataString?.let { url ->
            onNewIntentCallback?.invoke(url)
        }
    }

    private fun showBiometricAuth(onResult: (Boolean) -> Unit) {
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

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_prompt_title))
            .setSubtitle(getString(R.string.biometric_prompt_desc))
            .setNegativeButtonText(getString(R.string.biometric_cancel))
            .build()

        prompt.authenticate(promptInfo)
    }
}
