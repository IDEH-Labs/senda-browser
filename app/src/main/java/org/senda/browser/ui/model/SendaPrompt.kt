package org.senda.browser.ui.model

import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession

/**
 * Representa los diferentes tipos de solicitudes y diálogos interactivos que una página web
 * o el motor Gecko pueden disparar (menús desplegables <select>, alertas, confirmaciones,
 * subida de archivos, etc.).
 */
sealed interface SendaPrompt {
    data class Choice(
        val prompt: GeckoSession.PromptDelegate.ChoicePrompt,
        val result: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>
    ) : SendaPrompt

    data class Alert(
        val prompt: GeckoSession.PromptDelegate.AlertPrompt,
        val result: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>
    ) : SendaPrompt

    data class Confirm(
        val prompt: GeckoSession.PromptDelegate.ButtonPrompt,
        val result: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>
    ) : SendaPrompt

    data class Text(
        val prompt: GeckoSession.PromptDelegate.TextPrompt,
        val result: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>
    ) : SendaPrompt

    data class BeforeUnload(
        val prompt: GeckoSession.PromptDelegate.BeforeUnloadPrompt,
        val result: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>
    ) : SendaPrompt

    data class RepostConfirm(
        val prompt: GeckoSession.PromptDelegate.RepostConfirmPrompt,
        val result: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>
    ) : SendaPrompt

    data class File(
        val prompt: GeckoSession.PromptDelegate.FilePrompt,
        val result: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>
    ) : SendaPrompt

    /** <input type="date|time|datetime-local|month">: sin esto el campo no abre ningún selector. */
    data class DateTime(
        val prompt: GeckoSession.PromptDelegate.DateTimePrompt,
        val result: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>
    ) : SendaPrompt

    /** Un sitio pide ubicación, cámara, micrófono o notificaciones y el ajuste es «Preguntar». */
    data class Permission(
        val host: String,
        val kinds: List<PermissionKind>,
        val onDecision: (Boolean) -> Unit
    ) : SendaPrompt

    /** Pulsación larga sobre un enlace, una imagen o un video. */
    data class ContextMenu(
        val element: GeckoSession.ContentDelegate.ContextElement
    ) : SendaPrompt
}

enum class PermissionKind { LOCATION, CAMERA, MICROPHONE, NOTIFICATIONS }
