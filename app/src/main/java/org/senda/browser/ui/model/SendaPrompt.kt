package org.senda.browser.ui.model

import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession

/**
 * The different kinds of requests and interactive dialogs a web page
 * or the Gecko engine can trigger (<select> dropdowns, alerts, confirmations,
 * file uploads, etc.).
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

    /** <input type="date|time|datetime-local|month">: without this the field opens no picker. */
    data class DateTime(
        val prompt: GeckoSession.PromptDelegate.DateTimePrompt,
        val result: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>
    ) : SendaPrompt

    /** A site asks for location, camera, microphone or notifications and the setting is "Ask". */
    data class Permission(
        val host: String,
        val kinds: List<PermissionKind>,
        val onDecision: (Boolean) -> Unit
    ) : SendaPrompt

    /** "Open links in apps" set to "Ask": [appName] is null if Android would show its chooser. */
    data class OpenInApp(
        val appName: String?,
        val onDecision: (Boolean) -> Unit
    ) : SendaPrompt

    /** HTTP (Basic/Digest) or proxy authentication: without this the page stayed at 401 with no way in. */
    data class Auth(
        val prompt: GeckoSession.PromptDelegate.AuthPrompt,
        val result: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>
    ) : SendaPrompt

    /** Sign-in form with accounts saved in the vault (no password until authenticating). */
    data class LoginSelect(
        val request: GeckoSession.PromptDelegate.AutocompleteRequest<org.mozilla.geckoview.Autocomplete.LoginSelectOption>,
        val result: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>
    ) : SendaPrompt

    /** A sign-in was submitted with an account that is not in the vault: offer to save it. */
    data class LoginSave(
        val request: GeckoSession.PromptDelegate.AutocompleteRequest<org.mozilla.geckoview.Autocomplete.LoginSaveOption>,
        val result: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>
    ) : SendaPrompt

    /** A script is taking too long: the user decides whether to stop it ([onDecision] true) or wait. */
    data class SlowScript(
        val host: String,
        val onDecision: (stop: Boolean) -> Unit
    ) : SendaPrompt

    /** The page started a download and "Ask where to save" is off: confirm before saving. */
    data class Download(
        val fileName: String,
        val host: String,
        val sizeBytes: Long,
        val onDecision: (Boolean) -> Unit
    ) : SendaPrompt

    /** Long press on a link, an image or a video. */
    data class ContextMenu(
        val element: GeckoSession.ContentDelegate.ContextElement
    ) : SendaPrompt
}

enum class PermissionKind { LOCATION, CAMERA, MICROPHONE, NOTIFICATIONS }
