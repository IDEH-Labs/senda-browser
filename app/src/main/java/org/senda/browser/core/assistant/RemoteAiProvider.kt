package org.senda.browser.core.assistant

/**
 * Proveedores de IA remota. Solo empresas conocidas con su punto de acceso oficial por HTTPS (verificados el
 * 2026-10-05 en su documentación), más el servidor propio del usuario. Anthropic usa su SDK oficial; el
 * resto, su API compatible con OpenAI (/chat/completions y /models).
 */
enum class RemoteAiProvider(
    val id: String,
    val displayName: String,
    /** Base de la API; null en el servidor propio (la escribe el usuario). */
    val baseUrl: String?,
    val style: Style,
    /** Página oficial donde el usuario crea su clave. */
    val keysPage: String?,
    /** Modelo sugerido si el listado del proveedor falla; el usuario puede elegir otro de la lista. */
    val suggestedModel: String?
) {
    ANTHROPIC("anthropic", "Anthropic (Claude)", "https://api.anthropic.com", Style.ANTHROPIC,
        "https://console.anthropic.com/settings/keys", "claude-opus-5-5"),
    OPENAI("openai", "OpenAI", "https://api.openai.com/v1", Style.OPENAI_COMPATIBLE,
        "https://platform.openai.com/api-keys", null),
    GEMINI("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", Style.OPENAI_COMPATIBLE,
        "https://aistudio.google.com/apikey", null),
    XAI("xai", "xAI (Grok)", "https://api.x.ai/v1", Style.OPENAI_COMPATIBLE,
        "https://console.x.ai", null),
    MISTRAL("mistral", "Mistral AI", "https://api.mistral.ai/v1", Style.OPENAI_COMPATIBLE,
        "https://console.mistral.ai/api-keys", null),
    OWN_SERVER("own", "", null, Style.OPENAI_COMPATIBLE, null, null);

    enum class Style { ANTHROPIC, OPENAI_COMPATIBLE }

    val isOwnServer: Boolean get() = this == OWN_SERVER

    companion object {
        fun byId(id: String?): RemoteAiProvider? = entries.firstOrNull { it.id == id }
    }
}
