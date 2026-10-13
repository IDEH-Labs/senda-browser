package org.senda.browser.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Enter on a physical keyboard: the action runs when the key is RELEASED and the field takes both events. When it ran
 * on press, the field went away, focus moved to the bar and releasing Enter triggered the Home button, which went back
 * to the blank page (measured: the home search failed 5 of 11 startups; the address bar, 1 of 5 on 2026-10-12). The
 * on-screen keyboard's action button does not send keys and still uses keyboardActions.
 */
fun Modifier.searchOnEnterRelease(onEnter: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    if (event.key == Key.Enter || event.key == Key.NumPadEnter) {
        if (event.type == KeyEventType.KeyUp) onEnter()
        true
    } else {
        false
    }
}
