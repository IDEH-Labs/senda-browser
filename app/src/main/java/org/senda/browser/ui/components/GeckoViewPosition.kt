package org.senda.browser.ui.components

import android.graphics.Region
import org.mozilla.geckoview.GeckoView

/**
 * GeckoView learns where it is on screen only when Android gathers transparent regions (GeckoView.java,
 * gatherTransparentRegion), and that does not reach a GeckoView inside Compose: it stays at (0, 0), so accessibility
 * services get every page element as far too high as the view is from the top of the screen (e2e test H3b).
 * Gathering into a scratch region after each move gives it its real position.
 */
fun GeckoView.updateScreenPosition() {
    gatherTransparentRegion(Region())
}
