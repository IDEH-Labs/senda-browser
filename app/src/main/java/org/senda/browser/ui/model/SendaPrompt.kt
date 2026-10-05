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

    /** «Abrir enlaces en apps» en «Preguntar»: [appName] es null si Android mostraría su selector. */
    data class OpenInApp(
        val appName: String?,
        val onDecision: (Boolean) -> Unit
    ) : SendaPrompt

    /** Autenticación HTTP (Basic/Digest) o de proxy: sin esto la página quedaba en 401 sin poder entrar. */
    data class Auth(
        val prompt: GeckoSession.PromptDelegate.AuthPrompt,
        val result: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>
    ) : SendaPrompt

    /** Formulario de inicio de sesión con cuentas guardadas en la Bóveda (sin contraseña hasta identificarse). */
    data class LoginSelect(
        val request: GeckoSession.PromptDelegate.AutocompleteRequest<org.mozilla.geckoview.Autocomplete.LoginSelectOption>,
        val result: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>
    ) : SendaPrompt

    /** Se envió un inicio de sesión con una cuenta que no está en la Bóveda: ofrecer guardarla. */
    data class LoginSave(
        val request: GeckoSession.PromptDelegate.AutocompleteRequest<org.mozilla.geckoview.Autocomplete.LoginSaveOption>,
        val result: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>
    ) : SendaPrompt

    /** Un script tarda demasiado: el usuario decide si se detiene ([onDecision] true) o se espera. */
    data class SlowScript(
        val host: String,
        val onDecision: (stop: Boolean) -> Unit
    ) : SendaPrompt

    /** La página inició una descarga y «Preguntar dónde guardar» está apagado: confirmar antes de guardar. */
    data class Download(
        val fileName: String,
        val host: String,
        val sizeBytes: Long,
        val onDecision: (Boolean) -> Unit
    ) : SendaPrompt

    /** Pulsación larga sobre un enlace, una imagen o un video. */
    data class ContextMenu(
        val element: GeckoSession.ContentDelegate.ContextElement
    ) : SendaPrompt
}

enum class PermissionKind { LOCATION, CAMERA, MICROPHONE, NOTIFICATIONS }
