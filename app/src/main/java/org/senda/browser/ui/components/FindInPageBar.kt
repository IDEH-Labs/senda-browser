package org.senda.browser.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.mozilla.geckoview.GeckoSession
import org.senda.browser.core.LocalSendaStrings
import org.senda.browser.ui.model.BrowserTab

/** Barra de «Buscar en la página»: resalta todas las coincidencias y salta entre ellas. */
@Composable
fun FindInPageBar(tab: BrowserTab, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val strings = LocalSendaStrings.current
    val finder = tab.session.finder
    var query by remember { mutableStateOf("") }
    var current by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    val focus = remember { FocusRequester() }

    fun search(backwards: Boolean = false) {
        if (query.isBlank()) {
            finder.clear()
            current = 0
            total = 0
            return
        }
        val flags = if (backwards) GeckoSession.FINDER_FIND_BACKWARDS else GeckoSession.FINDER_FIND_FORWARD
        finder.find(query, flags).accept { result ->
            current = result?.current ?: 0
            total = result?.total ?: 0
        }
    }

    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) {
        finder.displayFlags = GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL or GeckoSession.FINDER_DISPLAY_DRAW_LINK_OUTLINE
        focus.requestFocus()
    }
    DisposableEffect(finder) { onDispose { finder.clear() } }

    Surface(
        modifier = modifier.fillMaxWidth().padding(8.dp),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 6.dp,
        shadowElevation = 6.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 4.dp)) {
            TextField(
                value = query,
                onValueChange = {
                    query = it
                    search()
                },
                placeholder = { Text(strings.find_placeholder) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                modifier = Modifier.weight(1f).focusRequester(focus)
            )
            if (query.isNotBlank()) {
                Text(
                    text = if (total > 0) strings.find_count.format(current, total) else strings.find_none,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (total > 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                )
            }
            IconButton(onClick = { search(backwards = true) }, enabled = total > 0) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = strings.find_prev)
            }
            IconButton(onClick = { search() }, enabled = total > 0) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = strings.find_next)
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = strings.general_close)
            }
        }
    }
}
