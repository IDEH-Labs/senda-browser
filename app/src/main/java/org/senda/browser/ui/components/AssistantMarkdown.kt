package org.senda.browser.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp

/**
 * El Markdown básico con que responde ChatGPT (títulos, listas, **negrita**, *cursiva*, `código` y [enlaces](https://…))
 * como texto con formato, en vez de mostrar los símbolos. Los enlaces solo si son https y se abren en una pestaña
 * de Senda ([onLink]). No interpreta HTML ni nada que pueda ejecutar código.
 */
fun assistantMarkdown(text: String, linkColor: Color, onLink: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    val lines = text.replace("\r\n", "\n").split('\n')
    lines.forEachIndexed { index, raw ->
        var line = raw
        val heading = Regex("^#{1,6}\\s+").find(line)
        val bullet = Regex("^\\s*[-*•]\\s+").find(line)
        when {
            heading != null -> {
                line = line.substring(heading.range.last + 1)
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp)) { inline(line, linkColor, onLink) }
            }
            bullet != null -> {
                append("• ")
                inline(line.substring(bullet.range.last + 1), linkColor, onLink)
            }
            else -> inline(line, linkColor, onLink)
        }
        if (index < lines.lastIndex) append('\n')
    }
}

/** Texto sin los símbolos de formato (para copiar). */
fun assistantPlainText(text: String): String = assistantMarkdown(text, Color.Unspecified) {}.text

private val INLINE = Regex(
    "\\*\\*(.+?)\\*\\*" +                                  // 1: negrita
        "|`([^`\\n]+)`" +                                  // 2: código
        "|\\[([^\\]\\n]+)]\\((https://[^)\\s]+)\\)" +      // 3, 4: enlace
        "|(?<![*\\w])\\*([^*\\n]+)\\*(?![*\\w])"           // 5: cursiva
)

private fun AnnotatedString.Builder.inline(text: String, linkColor: Color, onLink: (String) -> Unit) {
    var pos = 0
    for (m in INLINE.findAll(text)) {
        append(text.substring(pos, m.range.first))
        val g = m.groupValues
        when {
            g[1].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { inline(g[1], linkColor, onLink) }
            g[2].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(g[2]) }
            g[3].isNotEmpty() -> withLink(
                LinkAnnotation.Clickable(
                    tag = g[4],
                    styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                    linkInteractionListener = { onLink(g[4]) }
                )
            ) { append(g[3]) }
            g[5].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[5]) }
        }
        pos = m.range.last + 1
    }
    append(text.substring(pos))
}
