package org.senda.browser.ui.components

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalContext
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.senda.browser.core.SendaGeckoEngine
import org.senda.browser.core.cast.SendaTvMode

/**
 * La página que se ve en el teléfono, en formato TV: a pantalla completa y en horizontal en la TV, mientras el
 * teléfono sigue igual. Es una segunda vista de la misma dirección (misma sesión de cookies que la pestaña) que
 * sigue al teléfono al navegar; solo muestra, no se puede tocar desde la TV.
 */
@Composable
fun TvPageHost(url: String?, isPrivate: Boolean) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val currentUrl by rememberUpdatedState(url)
    val holder = remember(isPrivate) { arrayOfNulls<TvPagePresentation>(1) }
    fun open() {
        if (holder[0] != null) return
        val display = SendaTvMode.tvDisplay() ?: return
        holder[0] = try {
            TvPagePresentation(context, display, isPrivate).also {
                it.show()
                it.showUrl(currentUrl)
            }
        } catch (e: Exception) {
            Log.w("SendaTvPage", "No se pudo mostrar en la TV: ${e.message}")
            null
        }
    }
    fun close() {
        holder[0]?.close()
        holder[0] = null
    }
    // Solo mientras Senda está delante: en otra app la TV vuelve a mostrar el teléfono. Se hace desde el ciclo de
    // vida y no desde la composición, que en segundo plano no se redibuja
    DisposableEffect(lifecycleOwner, isPrivate) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> open()
                Lifecycle.Event.ON_STOP -> close()
                else -> {}
            }
        }
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) open()
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            close()
        }
    }
    LaunchedEffect(url) {
        holder[0]?.showUrl(url)
    }
}

/**
 * La TV vuelve a pedir la dirección que muestra el teléfono. No se hace con las que pueden tener efecto al pedirse
 * dos veces o que no son de internet: enlaces de un solo uso (confirmar, restablecer, códigos de inicio de sesión),
 * y direcciones locales (127.0.0.1 —el inicio de sesión con ChatGPT de Senda usa una—, el router, aparatos de casa).
 * En esos casos la TV muestra solo «Senda».
 */
private fun isSafeToReload(url: String): Boolean {
    val uri = try { android.net.Uri.parse(url) } catch (_: Exception) { return false }
    if (uri.scheme != "https" && uri.scheme != "http") return false
    val host = uri.host?.lowercase()?.trim('[', ']') ?: return false
    if (host == "localhost" || host.endsWith(".local") || host.endsWith(".localhost") || host.endsWith(".lan")) return false
    if (Regex("^[0-9.]+$").matches(host) || host.contains(':')) {
        val address = try { java.net.InetAddress.getByName(host) } catch (_: Exception) { return false }
        if (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress || address.isAnyLocalAddress) return false
    }
    val oneTime = Regex("(^|[_-])(token|code|otp|key|secret|signature|sig|nonce|state|ticket|magic|reset|verify|verification|confirm|confirmation|auth|session|password|pwd)([_-]|$)")
    if (uri.queryParameterNames.any { oneTime.containsMatchIn(it.lowercase()) }) return false
    val path = uri.path?.lowercase() ?: ""
    if (Regex("/(reset|verify|confirm|activate|unsubscribe|callback|oauth|login/token|magic)").containsMatchIn(path)) return false
    return true
}

private class TvPagePresentation(
    context: Context,
    display: Display,
    private val isPrivate: Boolean
) : Presentation(context, display) {

    private var session: GeckoSession? = null
    private var geckoView: GeckoView? = null
    private var idleView: View? = null
    private var currentUrl: String? = null
    private var closed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)

        val s = SendaGeckoEngine.createSession(isPrivate).apply {
            // Solo muestra: nada de ventanas nuevas, apps externas, permisos ni sonido propio (el sonido es del
            // teléfono o del video de la TV)
            navigationDelegate = object : GeckoSession.NavigationDelegate {
                override fun onLoadRequest(
                    session: GeckoSession,
                    request: GeckoSession.NavigationDelegate.LoadRequest
                ): GeckoResult<AllowOrDeny>? {
                    val scheme = request.uri.substringBefore(':').lowercase()
                    return GeckoResult.fromValue(
                        if (scheme in setOf("http", "https", "about", "data", "moz-extension")) AllowOrDeny.ALLOW else AllowOrDeny.DENY
                    )
                }

                override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? = null
            }
            permissionDelegate = object : GeckoSession.PermissionDelegate {
                override fun onContentPermissionRequest(
                    session: GeckoSession,
                    perm: GeckoSession.PermissionDelegate.ContentPermission
                ): GeckoResult<Int> = GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
            }
        }
        session = s
        geckoView = GeckoView(context).apply {
            setSession(s)
            visibility = View.GONE
        }
        root.addView(geckoView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Pestaña nueva (sin página): el nombre de Senda en vez del teléfono en vertical
        idleView = TextView(context).apply {
            text = "Senda"
            setTextColor(Color.WHITE)
            alpha = 0.85f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
            gravity = Gravity.CENTER
        }
        root.addView(idleView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun showUrl(url: String?) {
        if (closed) return
        val page = url?.takeIf { isSafeToReload(it) }
        geckoView?.visibility = if (page != null) View.VISIBLE else View.GONE
        idleView?.visibility = if (page != null) View.GONE else View.VISIBLE
        if (page == null || page == currentUrl) return
        currentUrl = page
        session?.loadUri(page)
    }

    fun close() {
        if (closed) return
        closed = true
        session?.close()
        try { dismiss() } catch (_: Exception) {}
    }
}
