package org.senda.browser.ui.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoSession
import org.senda.browser.core.SendaGeckoEngine
import java.util.UUID

class BrowserTab(
    val id: String = UUID.randomUUID().toString(),
    val isPrivate: Boolean = true,
    initialUrl: String = "about:blank"
) {
    val session: GeckoSession = SendaGeckoEngine.createSession(isPrivate)

    var url by mutableStateOf(initialUrl)
    var title by mutableStateOf("Nueva pestaña")
    var isLoading by mutableStateOf(false)
    var progress by mutableIntStateOf(0)
    var trackersBlocked by mutableIntStateOf(0)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)

    init {
        setupDelegates()
        if (initialUrl != "about:blank") {
            loadUri(initialUrl)
        }
    }

    private fun setupDelegates() {
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                newUrl: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                if (!newUrl.isNullOrBlank()) {
                    url = newUrl
                }
            }

            override fun onCanGoBack(session: GeckoSession, canGo: Boolean) {
                canGoBack = canGo
            }

            override fun onCanGoForward(session: GeckoSession, canGo: Boolean) {
                canGoForward = canGo
            }
        }

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, newUrl: String) {
                isLoading = true
                progress = 10
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                isLoading = false
                progress = 100
            }

            override fun onProgressChange(session: GeckoSession, newProgress: Int) {
                progress = newProgress
            }
        }

        session.contentBlockingDelegate = object : ContentBlocking.Delegate {
            override fun onContentBlocked(
                session: GeckoSession,
                event: ContentBlocking.BlockEvent
            ) {
                trackersBlocked++
            }
        }
    }

    fun loadUri(uri: String) {
        val target = if (uri.startsWith("http://") || uri.startsWith("https://") || uri.startsWith("about:")) {
            uri
        } else {
            "https://duckduckgo.com/?q=${UriEncoder.encode(uri)}"
        }
        url = target
        session.loadUri(target)
    }

    fun close() {
        session.close()
    }
}

object UriEncoder {
    fun encode(query: String): String {
        return java.net.URLEncoder.encode(query, "UTF-8")
    }
}
