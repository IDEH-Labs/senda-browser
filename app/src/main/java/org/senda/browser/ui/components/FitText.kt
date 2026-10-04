package org.senda.browser.ui.components

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Texto de una línea que reduce su tamaño hasta [minSize] si no cabe, en vez de partir la palabra
 * («Compact / o») o cortarla con «…». Para botones y chips de ancho fijo.
 */
@Composable
fun FitText(
    text: String,
    modifier: Modifier = Modifier,
    maxSize: TextUnit = 12.sp,
    minSize: TextUnit = 9.sp,
    fontFamily: FontFamily? = null,
    fontWeight: FontWeight? = null,
    color: Color = Color.Unspecified,
    textAlign: TextAlign = TextAlign.Center,
    style: TextStyle = LocalTextStyle.current,
    // Varios FitText de una misma fila comparten este estado: todos quedan del tamaño del que menos cabe
    sharedSize: androidx.compose.runtime.MutableState<TextUnit>? = null
) {
    val ownSize = remember(text, maxSize) { mutableStateOf(maxSize) }
    val sizeState = sharedSize ?: ownSize
    var size by sizeState
    var ready by remember(text, maxSize) { mutableStateOf(false) }
    Text(
        text = text,
        modifier = modifier.drawWithContent { if (ready) drawContent() },
        fontSize = size,
        fontFamily = fontFamily,
        fontWeight = fontWeight,
        color = color,
        textAlign = textAlign,
        maxLines = 1,
        softWrap = false,
        style = style,
        onTextLayout = { result ->
            if (result.didOverflowWidth && size.value > minSize.value) {
                size = (size.value - 0.5f).sp
            } else {
                ready = true
            }
        }
    )
}

/** Tamaño compartido para [FitText] de una misma fila. */
@Composable
fun rememberFitGroup(maxSize: TextUnit = 12.sp) = remember { mutableStateOf(maxSize) }
