package org.senda.browser.ui.components

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.senda.browser.core.SendaGeckoEngine

/**
 * Panel an extension opened from its button (GNU LibreJS): its own page, shown over the browser for the tab in
 * front. Closing it closes its Gecko session.
 */
@Composable
fun ExtensionPopupDialog(session: GeckoSession) {
    Dialog(
        onDismissRequest = { SendaGeckoEngine.closeExtensionPopup() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.8f)
        ) {
            AndroidView(
                factory = { context -> GeckoView(context).apply { setSession(session) } },
                onRelease = { view -> view.releaseSession() },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
