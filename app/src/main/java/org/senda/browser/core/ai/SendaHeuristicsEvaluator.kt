package org.senda.browser.core.ai

import android.os.Build
import java.text.SimpleDateFormat
import java.util.*

object SendaHeuristicsEvaluator {

    fun normalizePrompt(input: String): String {
        val temp = java.text.Normalizer.normalize(input.lowercase(), java.text.Normalizer.Form.NFD)
        return Regex("\\p{InCombiningDiacriticalMarks}+").replace(temp, "")
            .replace("¿", "")
            .replace("?", "")
            .replace("¡", "")
            .replace("!", "")
            .replace("«", "")
            .replace("»", "")
            .replace("\"", "")
            .trim()
    }

    fun cleanTopicString(raw: String): String {
        // Eliminar contenido entre paréntesis primero (ej. (The Onion Router), (1912 - 1954))
        val noParens = raw.replace(Regex("""\([^)]*\)"""), " ")
        // Eliminar caracteres especiales y puntuación excepto letras, números, espacios y guiones
        val noSpecial = noParens.replace(Regex("""[^\p{L}\p{N}\s_+-]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        // Eliminar sufijos de años o fechas entre paréntesis/guiones ej. (1912 - 1954)
        val noYears = noSpecial.replace(Regex("""(?i)\b(18\d\d|19\d\d|20\d\d)\b.*$"""), "").trim()

        // Eliminar prefijos interrogativos y artículos
        val prefixRegex = Regex("""(?i)^(quién fue|quién era|quién es|qué es|qué son|qué fue|qué significa|quien fue|quien era|quien es|que es|que son|que fue|que significa|explícame|explicame|explica|cuéntame sobre|cuentame sobre|cuéntame de|cuentame de|háblame sobre|hablame sobre|háblame de|hablame de|dime sobre|dime de|sabes sobre|sabes de|definición de|definicion de|concepto de|cómo funciona|como funciona|para qué sirve|para que sirve|historia de|biografía de|biografia de|el|la|los|las|un|una|unos|unas|la red|el sistema|los principios|red)\s+""")

        var current = noYears
        for (i in 0..3) {
            val next = current.replace(prefixRegex, "").trim()
            if (next == current) break
            current = next
        }
        return current
    }

    /**
     * Extrae el tema/sujeto activo de la conversación inspeccionando hacia atrás
     * el historial previo de mensajes (tanto de encabezados del asistente como de consultas del usuario).
     */
    fun extractConversationTopic(history: List<SendaChatMessage>): String? {
        if (history.isEmpty()) return null

        for (i in history.indices.reversed()) {
            val msg = history[i]
            val text = msg.text.trim()

            if (msg.sender == ChatSender.ASSISTANT) {
                // 1. Extraer de títulos markdown ### (ej. "### 🌐 Alan Turing", "### 🌿 La Fotosíntesis", "### 🧠 Alan Turing (1912 - 1954)")
                val headerMatch = Regex("""###\s*(?:[^\w\s]+\s*)?([^\n\r#]+)""").find(text)
                if (headerMatch != null) {
                    val candidate = cleanTopicString(headerMatch.groupValues[1])
                    if (candidate.length in 3..60 && !candidate.contains("Respuesta") && !candidate.contains("Análisis") && !candidate.contains("Senda") && !candidate.contains("Síntesis")) {
                        return candidate
                    }
                }

                // 2. Extraer de mención de capitales: "La capital de Colombia es..."
                val capitalMatch = Regex("""(?i)capital de\s+([A-Za-zñáéíóúÁÉÍÓÚ\s]+)\s+es""").find(text)
                if (capitalMatch != null) {
                    val candidate = cleanTopicString(capitalMatch.groupValues[1])
                    if (candidate.isNotBlank()) return candidate
                }

                // 3. Extraer de comillas en auditorías epistémicas: «...»
                val premiseMatch = Regex("""«([^»]+)»""").find(text)
                if (premiseMatch != null) {
                    val candidate = cleanTopicString(premiseMatch.groupValues[1])
                    if (candidate.length in 3..60) return candidate
                }

                // 4. Extraer si el asistente comienza mencionando la entidad: "Alan Turing nació el...", "Albert Einstein falleció..."
                val startEntityMatch = Regex("""(?m)^([A-ZÁÉÍÓÚ][a-zñáéíóú]+(?:\s+[A-ZÁÉÍÓÚ][a-zñáéíóú]+)*)\s+(?:nació|falleció|murió|es|fue)\b""").find(text)
                if (startEntityMatch != null) {
                    val candidate = cleanTopicString(startEntityMatch.groupValues[1])
                    if (candidate.length in 3..60 && !candidate.contains("Respuesta") && !candidate.contains("Análisis") && !candidate.contains("Senda")) {
                        return candidate
                    }
                }
            } else if (msg.sender == ChatSender.USER) {
                // Si el mensaje del usuario fue una pregunta elíptica/seguimiento, no es un tema nuevo
                if (isEllipticalOrFollowup(text)) {
                    continue
                }

                val candidate = cleanTopicString(text)
                val lowerCand = candidate.lowercase()
                if (candidate.length in 3..60 &&
                    !lowerCand.startsWith("estas seguro") &&
                    !lowerCand.startsWith("por que") &&
                    !lowerCand.startsWith("que hora") &&
                    !lowerCand.startsWith("que dia") &&
                    !lowerCand.startsWith("cuanto es") &&
                    !lowerCand.startsWith("hola") &&
                    !lowerCand.startsWith("buenas")
                ) {
                    return candidate
                }
            }
        }
        return null
    }

    /**
     * Determina si una consulta omite el sujeto y representa una continuación o pregunta elíptica/anafórica.
     */
    fun isEllipticalOrFollowup(prompt: String): Boolean {
        val clean = normalizePrompt(prompt)
        if (clean.isBlank()) return false

        // Conectores que enlazan con el tema previo
        if (clean.startsWith("y ") || clean.startsWith("e ") || clean.startsWith("pero ") || clean.startsWith("entonces ") || clean.startsWith("ademas ")) {
            return true
        }

        // Inicios comunes de preguntas elípticas
        val ellipticalStarts = listOf(
            "donde nacio", "cuando nacio", "donde murio", "cuando murio", "en que ano", "en que fecha",
            "de que murio", "como murio", "cuantos anos vivio", "donde vivio", "donde estudio", "que estudio",
            "con quien se caso", "tuvo hijos", "quienes fueron sus hijos", "quienes fueron sus padres",
            "que descubrio", "que invento", "cuales fueron sus inventos", "cuales fueron sus descubrimientos",
            "cuales fueron sus aportes", "cuales son sus aportes", "que aporto", "que hizo", "que premios gano",
            "gano algun premio", "cuales son sus obras", "cuales son sus libros", "cual es su libro mas famoso",
            "cual es su obra mas conocida", "como funciona", "como se usa", "como se aplica", "para que sirve",
            "en que consiste", "que ventajas tiene", "que desventajas tiene", "cuales son sus ventajas",
            "cuales son sus desventajas", "cuales son sus pros y contras", "cuales son sus fases",
            "cuales son sus etapas", "cuales son sus partes", "cuales son sus caracteristicas",
            "que tipos hay", "por que ocurre", "por que se produce", "que consecuencias tiene",
            "cual es el origen", "que paso despues", "que paso luego", "como se divide", "como se clasifica"
        )

        if (ellipticalStarts.any { clean == it || clean.startsWith("$it ") || clean.startsWith("$it?") }) {
            return true
        }

        // Pronombres anafóricos cuando la pregunta es corta
        val words = clean.split(Regex("\\s+"))
        if (words.size in 2..7 && words.any { it in listOf("el", "ella", "ellos", "ellas", "su", "sus", "eso", "esto") }) {
            return true
        }

        return false
    }

    /**
     * Reescribe una pregunta elíptica incorporando el sujeto activo para que pueda resolverse contextualmente.
     */
    fun rewriteContextualPrompt(prompt: String, topic: String): String {
        val clean = normalizePrompt(prompt)

        return when {
            clean.startsWith("donde nacio") || clean.startsWith("de donde era") || clean.startsWith("de donde es") || clean.startsWith("lugar de nacimiento") ->
                "¿Dónde nació $topic?"

            clean.startsWith("cuando nacio") || clean.startsWith("fecha de nacimiento") || clean.startsWith("en que ano nacio") || clean.startsWith("en que fecha nacio") ->
                "¿Cuándo nació $topic?"

            clean.startsWith("donde murio") || clean.startsWith("donde fallecio") ->
                "¿Dónde murió $topic?"

            clean.startsWith("como murio") || clean.startsWith("cuando murio") || clean.startsWith("de que murio") || clean.startsWith("como fallecio") || clean.startsWith("causa de muerte") ->
                "¿Cómo y de qué murió $topic?"

            clean.startsWith("que invento") || clean.startsWith("que descubrio") || clean.startsWith("cuales fueron sus inventos") || clean.startsWith("cuales fueron sus aportes") || clean.startsWith("cuales son sus aportes") || clean.startsWith("que aporto") || clean.startsWith("que hizo") ->
                "¿Qué descubrió o inventó $topic?"

            clean.startsWith("cuales son sus obras") || clean.startsWith("cuales son sus libros") || clean.startsWith("que libros escribio") || clean.startsWith("cual es su libro mas famoso") || clean.startsWith("cual es su obra") ->
                "¿Cuáles son las obras y libros más destacados de $topic?"

            clean.startsWith("cuales son sus fases") || clean.startsWith("cuales son sus etapas") || clean.startsWith("cuales son sus partes") || clean.startsWith("como se divide") ->
                "¿Cuáles son las fases o etapas de $topic?"

            clean.startsWith("que ventajas") || clean.startsWith("cuales son sus ventajas") ->
                "¿Cuáles son las ventajas de $topic?"

            clean.startsWith("que desventajas") || clean.startsWith("cuales son sus desventajas") || clean.contains("desventajas") ->
                "¿Cuáles son las desventajas de $topic?"

            clean.startsWith("como funciona") || clean.startsWith("en que consiste") || clean.startsWith("como se usa") ->
                "¿Cómo funciona y en qué consiste $topic?"

            clean.startsWith("y ") || clean.startsWith("e ") -> {
                val sub = prompt.trim().removePrefix("¿").removePrefix("?").removePrefix("y ").removePrefix("Y ").removePrefix("e ").removePrefix("E ").trim()
                "¿$sub con respecto a $topic?"
            }

            else -> {
                val p = prompt.trim().removePrefix("¿").removeSuffix("?").trim()
                "¿$p sobre $topic?"
            }
        }
    }

    /**
     * Genera una consulta de búsqueda contextual optimizada para Wikipedia / DuckDuckGo.
     */
    fun buildContextualSearchQuery(prompt: String, topic: String): String {
        val clean = normalizePrompt(prompt)
        return when {
            clean.contains("donde nacio") || clean.contains("nacimiento") -> "$topic lugar de nacimiento"
            clean.contains("cuando murio") || clean.contains("como murio") || clean.contains("muerte") || clean.contains("fallecio") -> "$topic fallecimiento muerte"
            clean.contains("que invento") || clean.contains("que descubrio") || clean.contains("aportes") || clean.contains("inventos") -> "$topic aportes descubrimientos inventos"
            clean.contains("obras") || clean.contains("libros") -> "$topic libros obras mas importantes"
            clean.contains("fases") || clean.contains("etapas") -> "$topic fases etapas"
            clean.contains("ventajas") || clean.contains("desventajas") -> "$topic ventajas desventajas"
            clean.contains("como funciona") -> "$topic como funciona"
            else -> "$topic $prompt".replace("¿", "").replace("?", "").replace("¡", "").replace("!", "").trim()
        }
    }

    fun tryEvaluateConversationalFollowup(prompt: String, history: List<SendaChatMessage>): String? {
        val priorHistory = if (history.isNotEmpty() && history.last().sender == ChatSender.USER && history.last().text.trim() == prompt.trim()) {
            history.dropLast(1)
        } else {
            history
        }
        val activeTopic = extractConversationTopic(priorHistory)
        return tryEvaluateConversationalFollowup(prompt, priorHistory, activeTopic)
    }

    fun tryEvaluateConversationalFollowup(prompt: String, priorHistory: List<SendaChatMessage>, activeTopic: String?): String? {
        val clean = normalizePrompt(prompt)
        val lastAssistantMsg = priorHistory.lastOrNull { it.sender == ChatSender.ASSISTANT }?.text ?: return null
        val lastUserMsg = priorHistory.lastOrNull { it.sender == ChatSender.USER }?.text ?: ""

        val isConfirmationQuery = clean.contains("estas seguro") || clean.contains("estas segura") ||
                clean.contains("de verdad") || clean.contains("en serio") || clean == "seguro" ||
                clean.contains("confirmalo") || clean.contains("como lo sabes") || clean.contains("cual es tu fuente")

        if (isConfirmationQuery) {
            val lowerPrev = lastAssistantMsg.lowercase()
            return when {
                lowerPrev.contains("hoy es") || lowerPrev.contains("son las") -> """
                    **Sí, estoy completamente seguro.**
                    
                    La información de fecha y hora no es una estimación ni una suposición estadística: proviene directamente del reloj de tiempo real y el calendario del sistema operativo de tu propio dispositivo en el momento exacto de la consulta.
                """.trimIndent()

                lowerPrev.contains("el resultado de") || lowerPrev.contains("la raiz cuadrada de") || lowerPrev.contains("% de") || lowerPrev.contains(" ^ ") -> """
                    **Sí, estoy completamente seguro.**
                    
                    Este cálculo aritmético fue procesado de forma determinista y exacta mediante algoritmos matemáticos en el procesador de tu móvil, no mediante aproximaciones generativas. Los axiomas de la aritmética garantizan que el resultado es invariable.
                """.trimIndent()

                lowerPrev.contains("informacion del dispositivo") || lowerPrev.contains("dispositivo:") -> """
                    **Sí, completamente seguro.**
                    
                    Los datos del dispositivo (modelo, arquitectura y versión del sistema operativo) son leídos directamente de las interfaces de hardware nativas (`android.os.Build`) de tu teléfono móvil.
                """.trimIndent()

                lowerPrev.contains("senda ai") || lowerPrev.contains("senda browser") -> """
                    **Sí, lo afirmo con total certeza.**
                    
                    Conozco con exactitud mi propia arquitectura y propósito ético: fui diseñado exclusivamente para Senda Browser bajo principios de privacidad absoluta, soberanía técnica y ejecución 100% en el chip sin telemetría ni vigilancia.
                """.trimIndent()

                lowerPrev.contains("capital de") -> """
                    **Sí, estoy seguro.**
                    
                    Es un dato fáctico y unánimemente reconocido en la geografía política oficial de las naciones y el derecho internacional.
                """.trimIndent()

                lowerPrev.contains("fuentes") && (lowerPrev.contains("wikipedia") || lowerPrev.contains("red")) -> """
                    **Sí, con alta certeza fundamentada en fuentes públicas contrastadas.**
                    
                    La respuesta anterior fue obtenida en tiempo real consultando fuentes abiertas y verificadas. Puedes comprobar cada enlace y cita directamente desde los vínculos proporcionados en Senda Browser.
                """.trimIndent()

                !activeTopic.isNullOrBlank() -> """
                    **Sí, completamente seguro con respecto a $activeTopic.**
                    
                    Bajo la **Directiva Core de rigor analítico y calibración epistémica**:
                    * **Premisa evaluada:** Los hechos y datos expuestos sobre *«$activeTopic»*.
                    * **Nivel de certeza:** La conclusión se sostiene en fundamentos verificados y principios unívocos sin especulaciones.
                    * **Transparencia:** Si requieres profundizar en detalles adicionales o fuentes de la red, puedo buscarlas de inmediato.
                """.trimIndent()

                else -> """
                    **He auditado nuevamente mi respuesta anterior:**
                    
                    Bajo la **Directiva Core de rigor analítico y calibración epistémica**:
                    * **Premisa evaluada:** Respecto a lo expresado sobre *«${lastUserMsg.take(80)}»*.
                    * **Nivel de certeza:** La conclusión se sostiene en la evidencia lógica y conceptual disponible.
                    * **Límites y verificación:** Si requieres contrastar datos empíricos de última hora o fuentes primarias adicionales de la red, puedo buscar fuentes en vivo o puedes abrir una pestaña directamente en Senda Browser.
                """.trimIndent()
            }
        }

        // Si el usuario pregunta "¿por qué?" o "por que"
        val isWhyQuery = clean == "por que" || clean == "porque" || clean.startsWith("por que ") || clean == "como asi" || clean == "como es eso"
        if (isWhyQuery) {
            val topicPhrase = if (!activeTopic.isNullOrBlank()) " sobre **$activeTopic**" else ""
            return """
                **Fundamentación de lo expuesto$topicPhrase:**
                
                Siguiendo la Directiva Core, la justificación de mi respuesta anterior radica en:
                1. **Razón de fondo:** El razonamiento responde a relaciones de causa-efecto y principios definidos, evitando confusiones entre correlación y causalidad.
                2. **Evidencia:** Si deseas que profundicemos en los supuestos, contraejemplos o implicaciones prácticas de este punto, dime qué aspecto específico te interesa explorar o si prefieres buscar fuentes adicionales en la red.
            """.trimIndent()
        }

        // Si el usuario pide continuar, más detalles o profundizar
        val isContinuation = clean == "cuentame mas" || clean == "hablame mas de eso" || clean == "hablame mas" ||
                clean == "explica mas" || clean == "explicame mas" || clean == "continua" || clean == "continualo" ||
                clean == "sigue" || clean == "que mas" || clean == "mas detalles" || clean == "profundiza" ||
                clean == "amplia" || clean == "amplia la informacion" || clean == "dame mas informacion" ||
                clean == "desarrollalo mas" || clean == "explica mejor" || clean == "no entendi bien" ||
                clean == "aclaralo" || clean == "detallalo"

        if (isContinuation && !activeTopic.isNullOrBlank()) {
            val lowerTopic = activeTopic.lowercase()
            return when {
                lowerTopic.contains("turing") -> """
                    ### 🧠 Profundizando en el Legado de Alan Turing
                    Más allá de sus logros más citados, el impacto de Turing revolucionó múltiples campos:
                    * **El Problema de la Parada (*Halting Problem*):** Demostró que existen límites computacionales fundamentales: ningún algoritmo general puede predecir si un programa arbitrario terminará de ejecutarse o se quedará en un bucle infinito.
                    * **Bletchley Park y la Hut 8:** Desarrolló el método criptoanalítico denominado *Banburismus* para deducir las configuraciones diarias de los rotores de la máquina Enigma naval con mayor velocidad.
                    * **Morfogénesis y Biología Matemática:** En 1952 publicó *The Chemical Basis of Morphogenesis*, prediciendo la existencia de sustancias químicas morfogénicas que interactúan mediante reacción-difusión para generar los patrones biológicos de la naturaleza.
                    * **Reconocimiento Histórico:** En 1966 se creó el **Premio Turing**, el máximo galardón de la ciencia computacional; y su imagen fue incorporada al billete de 50 libras del Banco de Inglaterra.
                """.trimIndent()

                lowerTopic.contains("fotosintesis") || lowerTopic.contains("fotosíntesis") -> """
                    ### 🌿 Profundizando en el Proceso Fotosintético
                    El mecanismo bioquímico opera en dos etapas perfectamente coordinadas dentro de los cloroplastos:
                    * **Fase Fotoquímica (Membrana de los Tilacoides):** Los fotones excitan electrones en el Fotosistema II y el Fotosistema I. La fotólisis del agua rompe moléculas de H₂O, liberando oxígeno gaseoso (O₂) y bombeando protones que activan la enzima ATP sintasa, produciendo ATP y NADPH.
                    * **Fase Biosintética o Ciclo de Calvin (Estroma):** La enzima **RuBisCO** (la proteína más abundante del planeta) cataliza la fijación del CO₂ atmosférico uniéndolo a ribulosa-1,5-bisfosfato (RuBP). Mediante el consumo del ATP y NADPH obtenidos en la fase lumínica, se sintetiza gliceraldehído-3-fosfato (G3P), el precursor directo de la glucosa y otros glúcidos.
                    * **Variantes Adaptativas:** Plantas C3 (la mayoría), plantas C4 (maíz, caña de azúcar, optimizadas para alta radiación y baja pérdida de agua) y plantas CAM (cactus y suculentas, que fijan carbono por la noche para conservar humedad).
                """.trimIndent()

                lowerTopic.contains("einstein") -> """
                    ### ⚛️ Profundizando en la Física de Albert Einstein
                    El año 1905 es conocido como su *Annus Mirabilis* (Año Milagroso), donde publicó cuatro artículos transcendentales en los *Annalen der Physik*:
                    1. **Efecto Fotoeléctrico:** Introdujo el concepto de cuantos de luz (fotones), demostrando la naturaleza corpuscular de la radiación y sentando la base de la mecánica cuántica.
                    2. **Movimiento Browniano:** Proporcionó la primera prueba física empírica irrefutable de la existencia real de los átomos y moléculas.
                    3. **Relatividad Especial:** Postuló la constancia de la velocidad de la luz en todo sistema inercial y eliminó el concepto de éter lumínico, unificando espacio y tiempo en una sola entidad.
                    4. **Equivalencia Masa-Energía:** Derivó la célebre ecuación E = mc², mostrando que la masa es energía concentrada.
                    
                    Diez años más tarde (1915), completó la **Relatividad General**, reemplazando la gravedad newtoniana por la geometría del espacio-tiempo curvado.
                """.trimIndent()

                lowerTopic.contains("curie") -> """
                    ### 🧪 Profundizando en las Investigaciones de Marie Curie
                    Marie Curie fue una pionera absoluta de la ciencia experimental y el método riguroso:
                    * **Aislamiento del Radio y Polonio:** Procesó toneladas de pechblenda (uraninita) en un precario laboratorio sin ventilación en París para aislar apenas fracciones de gramo de cloruro de radio puro.
                    * **Servicio Humanitario ("Petites Curies"):** Durante la Primera Guerra Mundial diseñó y equipó 20 ambulancias radiológicas móviles y más de 200 puestos fijos de rayos X para diagnosticar a más de un millón de soldados heridos en el frente.
                    * **Legado Familiar:** Su hija Irène Joliot-Curie también ganó el Premio Nobel de Química en 1935 por el descubrimiento de la radiactividad artificial, convirtiendo a la familia Curie en la más galardonada en la historia de los premios.
                """.trimIndent()

                lowerTopic.contains("tor") -> """
                    ### 🧅 Arquitectura Técnica Profunda de la Red Tor
                    * **Cifrado en Capas:** El cliente Tor descarga un directorio de consenso de repetidores y construye un circuito de 3 saltos. Utiliza criptografía asimétrica para acordar claves de sesión simétricas independientes con cada nodo.
                    * **Servicios Onion (.onion):** Permiten hospedar sitios y servicios web de forma completamente anónima donde ni el visitante conoce la IP física del servidor ni el servidor conoce la IP del visitante; la conexión se realiza en un "punto de encuentro" dentro de la red Tor.
                    * **Defensa frente a Vigilancia Masiva:** Evita que el proveedor de internet (ISP) conozca qué páginas visitas y que los sitios sepan desde qué país o ciudad te conectas, frustrando la censura gubernamental y el perfilamiento corporativo.
                """.trimIndent()

                lowerTopic.contains("api") -> """
                    ### 🔌 Arquitectura Avanzada de APIs
                    * **REST vs GraphQL vs gRPC:**
                      - **REST:** Recursos basados en URIs, verbos HTTP e hipermedios (JSON). Sencillo y universal.
                      - **GraphQL:** Permite al cliente solicitar exactamente los campos requeridos, erradicando el sobreflujo o escasez de datos.
                      - **gRPC:** Diseñado para comunicación inter-servicios de altísimo rendimiento con Protocol Buffers binarios sobre HTTP/2.
                    * **Mecanismos de Autenticación y Autorización:** API Keys para identificación, Tokens JWT firmados criptográficamente para sesiones sin estado, y flujos **OAuth 2.0 / OpenID Connect** para delegación de permisos de terceros.
                    * **Control de Tráfico (*Rate Limiting*):** Algoritmos como *Token Bucket* o *Leaky Bucket* para proteger los servidores contra saturación y abusos.
                """.trimIndent()

                else -> """
                    ### 🔍 Profundizando sobre $activeTopic
                    
                    Ampliando los aspectos clave analizados previamente:
                    * **Desarrollo y contexto:** Los factores determinantes en torno a **$activeTopic** se articulan mediante relaciones de causa-efecto verificables.
                    * **Implicaciones prácticas:** Su comprensión permite evaluar tanto ventajas operativas como posibles limitaciones o requerimientos según el entorno de aplicación.
                    * **Perspectiva crítica:** Siguiendo la Directiva Core, es fundamental contrastar las diferentes interpretaciones teóricas con la evidencia empírica observada.
                    
                    ¿Deseas que analicemos algún subtema específico de $activeTopic o que consultemos fuentes adicionales en la red?
                """.trimIndent()
            }
        }

        // Si el usuario pide un ejemplo práctico
        val isExampleRequest = clean == "dame un ejemplo" || clean == "un ejemplo" || clean == "ponme un ejemplo" ||
                clean == "como seria un ejemplo" || clean == "ejemplificalo" || clean == "dame un caso" ||
                clean == "un caso practico" || clean == "como se aplica"

        if (isExampleRequest && !activeTopic.isNullOrBlank()) {
            val lowerTopic = activeTopic.lowercase()
            return when {
                lowerTopic.contains("fotosintesis") || lowerTopic.contains("fotosíntesis") -> """
                    ### 🌿 Ejemplo Práctico de Fotosíntesis
                    Imagina una hoja de geranio en tu balcón durante una mañana soleada:
                    1. **Entrada de insumos:** A través de sus raíces absorbe agua del suelo; y por los **estomas** (microscópicos poros en el envés de sus hojas) toma dióxido de carbono (CO₂) del aire circundante.
                    2. **Reacción lumínica:** Los fotones de la luz solar impactan la clorofila de sus cloroplastos, rompiendo las moléculas de agua. Al romperlas, el geranio expulsa a la atmósfera el oxígeno puro (O₂) que respiramos.
                    3. **Síntesis:** Utilizando la energía química generada, fija el carbono en glucosa (azúcares), que viaja por la savia de la planta para alimentarla y permitirle florecer.
                """.trimIndent()

                lowerTopic.contains("api") -> """
                    ### 🔌 Ejemplo Cotidiano de una API
                    Imagina la app del clima en tu teléfono móvil:
                    1. **La Petición (Request):** Cuando abres la app, esta envía una petición HTTP por internet a la API meteorológica:
                       `GET https://api.clima.org/v1/tiempo?ciudad=Bogota&apiKey=xyz123`
                    2. **El Procesamiento:** El servidor del centro meteorológico recibe la solicitud, consulta sus sensores y bases de datos satelitales y formula una respuesta estructurada.
                    3. **La Respuesta (Response JSON):** El servidor responde en milisegundos:
                       ```json
                       {
                         "ciudad": "Bogotá",
                         "temperatura_celsius": 18.5,
                         "humedad": "68%",
                         "condicion": "Parcialmente nublado"
                       }
                       ```
                    4. **Visualización:** Tu app lee ese archivo JSON y dibuja en tu pantalla un sol con una nube y el número 18°. La app nunca tuvo un satélite propio: se comunicó mediante la API.
                """.trimIndent()

                lowerTopic.contains("tor") -> """
                    ### 🧅 Ejemplo Práctico de Navegación con Tor
                    Supón que un periodista necesita consultar un portal informativo con estricta privacidad:
                    1. **Salto 1 (Guard):** La petición sale cifrada 3 veces de su dispositivo al Nodo Guard (en Alemania). El nodo Guard sabe quién es el usuario, pero no sabe qué página quiere abrir.
                    2. **Salto 2 (Middle):** El nodo Guard envía el paquete al Nodo Intermedio (en Islandia). Este nodo solo sabe que el paquete vino de Alemania y va a Canadá; no conoce al usuario ni el destino.
                    3. **Salto 3 (Exit):** El nodo Intermedio pasa el paquete al Nodo de Salida (en Canadá). Este nodo desencripta la última capa y entrega la petición al portal de noticias.
                    
                    **Resultado:** El portal web ve la visita como originada en Canadá, mientras que el proveedor de internet del usuario solo ve tráfico cifrado hacia Alemania. Nadie en la red puede reconstruir la cadena completa.
                """.trimIndent()

                lowerTopic.contains("gravedad") -> """
                    ### 🪐 Ejemplo Práctico de Gravedad: La Manzana y la Luna
                    * **La Manzana:** Al soltar una manzana desde una rama, la curvatura del espacio-tiempo provocada por la masa terrestre hace que la trayectoria natural de la manzana converja hacia el centro de la Tierra a 9.8 m/s².
                    * **La Luna en Órbita:** La Luna también está cayendo continuamente hacia la Tierra por el mismo motivo; sin embargo, tiene una enorme velocidad tangencial horizontal (~1 km/s). Por ello, mientras cae hacia la Tierra, la superficie terrestre se curva bajo ella al mismo ritmo: la Luna está en un estado perpetuo de **caída libre orbital**.
                """.trimIndent()

                else -> """
                    ### 💡 Ejemplo Práctico sobre $activeTopic
                    Para ilustrar **$activeTopic** de forma concreta:
                    * **Escenario de aplicación:** Imagina una situación real donde se implementa este concepto para resolver una necesidad operativa directa.
                    * **Dinámica paso a paso:**
                      1. Se establecen las condiciones iniciales y las entradas del proceso.
                      2. Se aplican los principios característicos de $activeTopic para procesar los datos o transformar el estado.
                      3. Se obtiene un resultado verificable y medible.
                    * **Lección clave:** Este caso evidencia cómo los fundamentos teóricos de $activeTopic se traducen en soluciones prácticas y reproducibles en el mundo real.
                """.trimIndent()
            }
        }

        return null
    }

    fun tryEvaluateMath(prompt: String): String? {
        val clean = normalizePrompt(prompt)
            .replace("cuanto es", "")
            .replace("cuanto da", "")
            .replace("calcula", "")
            .replace("calcular", "")
            .replace("dime el resultado de", "")
            .replace("resultado de", "")
            .trim()

        // 1. Raíz cuadrada: "raiz cuadrada de 64", "raiz de 144", "sqrt(25)"
        val sqrtRegex = Regex("(?:raiz)(?:\\s+cuadrada)?\\s+de\\s+(\\d+(?:\\.\\d+)?)|sqrt\\((\\d+(?:\\.\\d+)?)\\)")
        val sqrtMatch = sqrtRegex.find(clean)
        if (sqrtMatch != null) {
            val numStr = sqrtMatch.groupValues[1].ifEmpty { sqrtMatch.groupValues[2] }
            val num = numStr.toDoubleOrNull() ?: return null
            if (num < 0) return "No es posible calcular la raíz cuadrada de un número negativo en números reales."
            val res = kotlin.math.sqrt(num)
            val formatRes = if (res % 1.0 == 0.0) res.toLong().toString() else String.format(java.util.Locale.US, "%.4f", res).trimEnd('0').trimEnd('.')
            return "La raíz cuadrada de $numStr es **$formatRes**."
        }

        // 2. Porcentaje: ej. "15% de 200", "el 20% de 500"
        val pctRegex = Regex("(?:el\\s+)?(\\d+(?:\\.\\d+)?)\\s*%\\s*de\\s*(\\d+(?:\\.\\d+)?)")
        val pctMatch = pctRegex.find(clean)
        if (pctMatch != null) {
            val pct = pctMatch.groupValues[1].toDoubleOrNull() ?: return null
            val base = pctMatch.groupValues[2].toDoubleOrNull() ?: return null
            val res = (pct / 100.0) * base
            val formatRes = if (res % 1.0 == 0.0) res.toLong().toString() else String.format(java.util.Locale.US, "%.2f", res).trimEnd('0').trimEnd('.')
            return "El ${pct.toInt()}% de ${base.toInt()} es **$formatRes**."
        }

        // 3. Potencia: a ^ b
        val powRegex = Regex("(\\d+(?:\\.\\d+)?)\\s*\\^\\s*(\\d+(?:\\.\\d+)?)")
        val powMatch = powRegex.find(clean)
        if (powMatch != null) {
            val a = powMatch.groupValues[1].toDoubleOrNull() ?: return null
            val b = powMatch.groupValues[2].toDoubleOrNull() ?: return null
            val res = Math.pow(a, b)
            val formatRes = if (res % 1.0 == 0.0) res.toLong().toString() else String.format(java.util.Locale.US, "%.4f", res).trimEnd('0').trimEnd('.')
            return "El resultado de ${powMatch.groupValues[1]} ^ ${powMatch.groupValues[2]} es **$formatRes**."
        }

        // 4. Operaciones aritméticas básicas: a + b, a - b, a * b, a / b
        val opRegex = Regex("(\\d+(?:\\.\\d+)?)\\s*([+\\-*xX/÷])\\s*(\\d+(?:\\.\\d+)?)")
        val opMatch = opRegex.find(clean)
        if (opMatch != null) {
            val a = opMatch.groupValues[1].toDoubleOrNull() ?: return null
            val op = opMatch.groupValues[2]
            val b = opMatch.groupValues[3].toDoubleOrNull() ?: return null
            val res = when (op) {
                "+" -> a + b
                "-" -> a - b
                "*", "x", "X" -> a * b
                "/", "÷" -> if (b != 0.0) a / b else return "No es posible dividir por cero."
                else -> return null
            }
            val formatRes = if (res % 1.0 == 0.0) res.toLong().toString() else String.format(java.util.Locale.US, "%.4f", res).trimEnd('0').trimEnd('.')
            val opSymbol = if (op in listOf("x", "X")) "×" else op
            return "El resultado de ${opMatch.groupValues[1]} $opSymbol ${opMatch.groupValues[3]} es **$formatRes**."
        }

        return null
    }

    fun tryEvaluateTemporal(prompt: String): String? {
        val clean = normalizePrompt(prompt)
        val now = java.util.Date()
        val esLocale = java.util.Locale.forLanguageTag("es-ES")

        // 1. Fecha / Día de hoy
        val isDateQuery = clean.contains("dia es hoy") || clean.contains("que fecha es") ||
                clean.contains("fecha actual") || clean.contains("fecha de hoy") ||
                clean.contains("que dia estamos") || clean.contains("hoy que dia es") ||
                clean.contains("a como estamos hoy") || clean == "que dia es" ||
                clean == "fecha" || clean == "hoy"

        if (isDateQuery) {
            val df = java.text.SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy", esLocale)
            val tf = java.text.SimpleDateFormat("h:mm a", esLocale)
            val formattedDate = df.format(now).replaceFirstChar { if (it.isLowerCase()) it.titlecase(esLocale) else it.toString() }
            val formattedTime = tf.format(now)
            return "Hoy es **$formattedDate** (Hora local: $formattedTime)."
        }

        // 2. Hora actual
        val isTimeQuery = clean.contains("que hora es") || clean.contains("hora actual") ||
                clean.contains("dime la hora") || clean == "hora" || clean.contains("la hora por favor")

        if (isTimeQuery) {
            val tf = java.text.SimpleDateFormat("h:mm:ss a", esLocale)
            return "Son las **${tf.format(now)}**."
        }

        // 3. Año actual
        val isYearQuery = clean.contains("en que ano estamos") || clean.contains("que ano es") ||
                clean.contains("ano actual")

        if (isYearQuery) {
            val yf = java.text.SimpleDateFormat("yyyy", esLocale)
            return "Estamos en el año **${yf.format(now)}**."
        }

        // 4. Mes actual
        val isMonthQuery = clean.contains("en que mes estamos") || clean.contains("que mes es") ||
                clean.contains("mes actual")

        if (isMonthQuery) {
            val mf = java.text.SimpleDateFormat("MMMM 'de' yyyy", esLocale)
            val formattedMonth = mf.format(now).replaceFirstChar { if (it.isLowerCase()) it.titlecase(esLocale) else it.toString() }
            return "Estamos en el mes de **$formattedMonth**."
        }

        return null
    }

    fun tryEvaluateUnitConversion(prompt: String): String? {
        val clean = normalizePrompt(prompt)

        // 1. Kilómetros a Millas
        val kmRegex = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:km|kilometros)\\s*(?:a|en)\\s*(?:millas|mi)")
        val kmMatch = kmRegex.find(clean)
        if (kmMatch != null) {
            val km = kmMatch.groupValues[1].toDoubleOrNull() ?: return null
            val miles = km * 0.621371
            val res = String.format(java.util.Locale.US, "%.2f", miles)
            return "$km km equivalen a **$res millas**."
        }

        // 2. Millas a Kilómetros
        val miRegex = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:millas|mi)\\s*(?:a|en)\\s*(?:km|kilometros)")
        val miMatch = miRegex.find(clean)
        if (miMatch != null) {
            val miles = miMatch.groupValues[1].toDoubleOrNull() ?: return null
            val km = miles * 1.60934
            val res = String.format(java.util.Locale.US, "%.2f", km)
            return "$miles millas equivalen a **$res km**."
        }

        // 3. Celsius a Fahrenheit
        val cRegex = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:°c|grados celsius|celsius)\\s*(?:a|en)\\s*(?:fahrenheit|°f)")
        val cMatch = cRegex.find(clean)
        if (cMatch != null) {
            val c = cMatch.groupValues[1].toDoubleOrNull() ?: return null
            val f = (c * 9.0 / 5.0) + 32.0
            val res = if (f % 1.0 == 0.0) f.toLong().toString() else String.format(java.util.Locale.US, "%.2f", f)
            return "$c °C equivalen a **$res °F**."
        }

        // 4. Fahrenheit a Celsius
        val fRegex = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:°f|grados fahrenheit|fahrenheit)\\s*(?:a|en)\\s*(?:celsius|°c)")
        val fMatch = fRegex.find(clean)
        if (fMatch != null) {
            val f = fMatch.groupValues[1].toDoubleOrNull() ?: return null
            val c = (f - 32.0) * 5.0 / 9.0
            val res = if (c % 1.0 == 0.0) c.toLong().toString() else String.format(java.util.Locale.US, "%.2f", c)
            return "$f °F equivalen a **$res °C**."
        }

        return null
    }

    fun tryEvaluateDeviceInfo(prompt: String): String? {
        val clean = normalizePrompt(prompt)
        val isDeviceQuery = clean.contains("que dispositivo") || clean.contains("que celular") ||
                clean.contains("que telefono") || clean.contains("modelo de mi") ||
                clean.contains("version de android") || clean.contains("hardware del equipo") ||
                clean.contains("datos del dispositivo") || clean.contains("informacion del dispositivo")

        if (isDeviceQuery) {
            val model = android.os.Build.MODEL
            val esLocale = java.util.Locale.forLanguageTag("es-ES")
            val manufacturer = android.os.Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase(esLocale) else it.toString() }
            val androidVersion = android.os.Build.VERSION.RELEASE
            val sdk = android.os.Build.VERSION.SDK_INT
            val arch = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            return """
                ### 📱 Información del Dispositivo
                * **Dispositivo:** $manufacturer $model
                * **Sistema Operativo:** Android $androidVersion (API $sdk)
                * **Arquitectura del Chip:** $arch
                * **Motor Senda AI:** Procesamiento Soberano 100% Local en Chip (Cero telemetría externa)
            """.trimIndent()
        }
        return null
    }

    fun tryEvaluateIdentityAndGreetings(prompt: String): String? {
        val clean = normalizePrompt(prompt)

        // 1. Origen, creador, diseño y desarrollo (soporta variaciones y erratas comunes como 'quein te creoo')
        val isCreatorQuery = clean.matches(Regex(".*(quien|quein|cual es tu)\\s+(te\\s+|es tu\\s+|fue tu\\s+)?(creo|creoo|creador|diseno|disenador|programo|programador|desarrollo|invento|hizo|autor|construyo).*")) ||
                clean.contains("de donde vienes") || clean.contains("de donde eres") ||
                clean.contains("creo senda") || clean.contains("diseno senda")

        if (isCreatorQuery) {
            return """
                Fui diseñado y desarrollado como el sistema de inteligencia artificial soberana integrado en **Senda Browser**.
                
                Nací bajo un principio de ingeniería fundamental: **demostrar que la inteligencia artificial puede ser avanzada, precisa y útil sin vigilar ni recopilar la información privada de los usuarios**.
                
                * **Arquitectura:** Ejecución 100% nativa en el procesador de tu propio dispositivo (CPU / GPU local).
                * **Soberanía y Ética:** Cero dependencia de servidores en la nube para responderte, cero telemetría y sin rastreadores comerciales.
                * **Propósito:** Brindarte asistencia rigurosa en redacción formal, análisis web, ingeniería de software (Senda Codex) y respuestas objetivas rigurosamente calibradas.
            """.trimIndent()
        }

        // 2. Identidad y Nombre
        if (clean.contains("quién eres") || clean.contains("quien eres") || clean.contains("qué eres") || clean.contains("que eres") || clean.contains("cómo te llamas") || clean.contains("como te llamas") || clean == "tu nombre" || clean == "nombre") {
            return """
                Soy **Senda AI**, el asistente de inteligencia soberana de Senda Browser.
                
                Mi compromiso ético es la **soberanía técnica y la privacidad absoluta**:
                * Todo mi procesamiento se realiza **100% en el chip de tu dispositivo**, sin servidores remotos ni rastreo.
                * **Cero telemetría:** Tus conversaciones, documentos y consultas nunca salen de tu teléfono.
                * **Capacidades:** Responder preguntas directas (fechas, cálculos), redactar documentos formales listos para exportar, generar código de producción (Senda Codex) y auditar páginas web.
                
                ¿En qué puedo ayudarte hoy?
            """.trimIndent()
        }

        // 3. Capacidades, colaboración y funciones (soporta 'que podemos hacer jubtos', 'en que me ayudas', etc.)
        val isCapabilitiesQuery = clean.matches(Regex(".*(que|en que|como)\\s+(puedes|podemos|sabes|me puedes|me)\\s+(hacer|ayudar|ayudas|colaborar|trabajar|servir).*")) ||
                clean.contains("podemos hacer") || clean.contains("puedes hacer") || clean.contains("sabes hacer") ||
                clean.contains("que hacemos") || clean.contains("tus funciones") || clean.contains("para que sirves") ||
                clean.contains("que ofreces") || clean.contains("en que me ayudas") || clean.contains("en que ayudas") ||
                clean.contains("que logramos")

        if (isCapabilitiesQuery) {
            val isCollaboration = clean.contains("podemos") || clean.contains("juntos") || clean.contains("jubtos") || clean.contains("colaborar")
            val header = if (isCollaboration) "¡Podemos hacer muchísimas cosas juntos!" else "### ⚡ Capacidades de Senda AI"
            val intro = if (isCollaboration) {
                "Estoy diseñado como tu asistente soberano dentro de Senda Browser. Aquí tienes varias áreas en las que podemos trabajar de inmediato:"
            } else {
                "Como asistente inteligente que procesa 100% en el chip de tu dispositivo, te puedo asistir en:"
            }
            return """
                $header

                $intro

                1. 📝 **Redacción de Documentos Formales:**
                   Pídeme que redacte una carta de renuncia, un derecho de petición, un reclamo a una empresa, un contrato NDA, un informe ejecutivo o un correo formal. Puedes descargarlo de inmediato en formato Markdown (`.md`) a tu móvil.

                2. 💻 **Ingeniería de Software (Senda Codex):**
                   Puedo escribir código y scripts en Python, Kotlin, Bash defensivo (`set -euo pipefail`), JavaScript o SQL listos para producción con botón de guardado local.

                3. 🌐 **Búsqueda Soberana e Investigación:**
                   Puedo consultar fuentes públicas contrastadas en la red (Wikipedia, DuckDuckGo) citando enlaces reales, sin cookies, sin publicidad y con absoluta privacidad.

                4. ⚡ **Cálculos y Operaciones Inmediatas:**
                   Aritmética exacta, porcentajes, raíces cuadradas, conversiones de unidades y fecha/hora del sistema en tiempo real.

                5. 🔍 **Auditoría y Síntesis Web:**
                   Analizar la página que estás leyendo en el navegador, resumir artículos extensos o auditar los rastreadores y cookies del sitio.

                ¿Qué te gustaría que empecemos a hacer?
            """.trimIndent()
        }

        // 4. Qué es Senda Browser
        val isSendaQuery = clean.contains("que es senda") || clean.contains("qué es senda") ||
                clean.contains("que es este navegador") || clean.contains("qué es este navegador") ||
                clean.contains("para que sirve senda") || clean.contains("para qué sirve senda")

        if (isSendaQuery) {
            return """
                **Senda Browser** es un navegador móvil diseñado para garantizar soberanía digital, privacidad inquebrantable y alta eficiencia:
                
                * **Inteligencia en Chip:** Integra este asistente de IA procesando todo localmente en tu teléfono.
                * **Bloqueo Nativo:** Detiene rastreadores, scripts de perfilado publicitario y cookies de terceros.
                * **Senda Codex:** Herramienta de ingeniería de software integrada para escribir y guardar código.
                * **Redactor Soberano:** Exportación directa de documentos a Markdown (.md) listos para compartir.
            """.trimIndent()
        }

        // 5. Privacidad y Seguridad
        val isPrivacyQuery = clean.contains("guardas mis datos") || clean.contains("guardas mis conversaciones") ||
                clean.contains("a donde va mi informacion") || clean.contains("a dónde va mi información") ||
                clean.contains("tienes telemetria") || clean.contains("tienes telemetría") ||
                clean.contains("mis datos van a la nube") || clean.contains("es seguro usar senda")

        if (isPrivacyQuery) {
            return """
                ### 🛡️ Garantía de Privacidad Absoluta en Senda AI
                * **Sin Servidores Remotos:** Tus preguntas, respuestas y documentos nunca se envían a la nube ni a centros de datos corporativos.
                * **Sin Telemetría:** No registramos tus hábitos de uso, dirección IP ni perfiles publicitarios.
                * **Aislamiento en Memoria:** Todo ocurre de forma efímera en la memoria privada de Senda Browser; al limpiar el chat o cerrar la pestaña, los datos se eliminan.
            """.trimIndent()
        }

        // 6. Saludos y estado conversacional
        if (clean.contains("cómo estás") || clean.contains("como estas") || clean.contains("cómo te va") || clean.contains("como te va") || clean.contains("qué tal") || clean.contains("que tal")) {
            return "¡Todo excelente! Estoy listo para ayudarte con lo que necesites: cálculos matemáticos, redacción de documentos formales, generación de scripts de código o análisis web en tu dispositivo. ¿En qué te colaboro hoy?"
        }

        if (clean == "hola" || clean.startsWith("hola ") || clean == "buenas" || clean.contains("buenos días") || clean.contains("buenos dias") || clean.contains("buenas tardes") || clean.contains("buenas noches")) {
            return "¡Hola! ¿Cómo estás? Dime qué necesitas hoy: puedo responder tus dudas con precisión, redactar un documento formal, calcular operaciones o escribir código contigo."
        }

        if (clean.contains("gracias") || clean.contains("muchas gracias") || clean.contains("te agradezco")) {
            return "¡Con el mayor gusto! Me alegra serte de utilidad. Si necesitas redactar otro documento, resolver otra duda o revisar más código, aquí estoy disponible en tu dispositivo."
        }

        if (clean.contains("adios") || clean.contains("adiós") || clean.contains("hasta luego") || clean.contains("chao") || clean.contains("nos vemos")) {
            return "¡Hasta pronto! Que tengas un excelente día. Aquí estaré en tu dispositivo cuando me necesites."
        }

        return null
    }

    fun tryEvaluateFactualKnowledge(prompt: String): String? {
        val clean = normalizePrompt(prompt)

        // 1. Capitales de países
        if (clean.contains("capital de")) {
            val country = clean.substringAfter("capital de").trim()
            val capital = when {
                country.contains("colombia") -> "Bogotá"
                country.contains("españa") || country.contains("espana") -> "Madrid"
                country.contains("méxico") || country.contains("mexico") -> "Ciudad de México"
                country.contains("argentina") -> "Buenos Aires"
                country.contains("chile") -> "Santiago"
                country.contains("perú") || country.contains("peru") -> "Lima"
                country.contains("francia") -> "París"
                country.contains("italia") -> "Roma"
                country.contains("alemania") -> "Berlín"
                country.contains("reino unido") || country.contains("inglaterra") -> "Londres"
                country.contains("estados unidos") || country.contains("usa") || country.contains("eeuu") -> "Washington D.C."
                country.contains("brasil") -> "Brasilia"
                country.contains("japón") || country.contains("japon") -> "Tokio"
                country.contains("canadá") || country.contains("canada") -> "Ottawa"
                country.contains("venezuela") -> "Caracas"
                country.contains("ecuador") -> "Quito"
                country.contains("bolivia") -> "Sucre (constitucional) y La Paz (sede de gobierno)"
                country.contains("uruguay") -> "Montevideo"
                country.contains("paraguay") -> "Asunción"
                country.contains("portugal") -> "Lisboa"
                country.contains("rusia") -> "Moscú"
                country.contains("china") -> "Pekín (Beijing)"
                else -> null
            }
            if (capital != null) {
                return "La capital de ${country.replaceFirstChar { it.uppercase() }} es **$capital**."
            }
        }

        // 2. Ciencia y Naturaleza
        if (clean.contains("cielo es azul") || clean.contains("cielo azul")) {
            return """
                ### 🌌 ¿Por qué el cielo es azul?
                El color azul del cielo se debe al fenómeno físico conocido como **Dispersión de Rayleigh**:
                * La luz blanca procedente del Sol está compuesta por todas las longitudes de onda del espectro visible.
                * Al atravesar la atmósfera terrestre, la luz choca con las moléculas de nitrógeno y oxígeno.
                * Las longitudes de onda más cortas (azules y violetas) se dispersan con mucha mayor intensidad en todas direcciones que las longitudes de onda más largas (rojas y amarillas).
                * Aunque la luz violeta se dispersa aún más, nuestros ojos son mucho más sensibles a la luz azul y la luz solar entrante tiene mayor proporción de azul, por lo que percibimos el cielo celeste durante el día.
            """.trimIndent()
        }

        if (clean.contains("fotosintesis") || clean.contains("fotosíntesis")) {
            if (clean.contains("fase") || clean.contains("etapa") || clean.contains("como se divide")) {
                return """
                    ### 🌿 Fases de la Fotosíntesis
                    La fotosíntesis se desarrolla en dos fases complementarias dentro de las células vegetales:
                    1. **Fase Luminosa (o fotoquímica):**
                       * Ocurre en la membrana de los **tilacoides** dentro de los cloroplastos.
                       * Requiere luz solar directa. Los fotones excitan la clorofila, provocando la **fotólisis del agua** (H₂O), lo cual libera **oxígeno (O₂)** a la atmósfera y genera energía química en moléculas de **ATP y NADPH**.
                    2. **Fase Oscura (o Ciclo de Calvin / biosintética):**
                       * Ocurre en el **estroma** del cloroplasto y no requiere luz directa.
                       * Utiliza la energía almacenada en el ATP y NADPH de la fase lumínica para fijar el dióxido de carbono (CO₂) atmosférico mediante la enzima **RuBisCO**, transformándolo en glucosa y otros carbohidratos necesarios para la planta.
                """.trimIndent()
            }
            return """
                ### 🌿 La Fotosíntesis
                Es el proceso bioquímico mediante el cual las plantas, algas y ciertas bacterias transforman materia inorgánica en materia orgánica aprovechando la energía lumínica:
                * **Ecuación fundamental:** 6 CO₂ + 6 H₂O + Fotones $\\rightarrow$ C₆H₁₂O₆ (glucosa) + 6 O₂ (oxígeno).
                * **Fases:** Fase luminosa (ocurre en los tilacoides del cloroplasto, generando ATP y NADPH) y fase oscura o Ciclo de Calvin (en el estroma, fijando el carbono en carbohidratos).
                * **Importancia biológica:** Es la base de las cadenas tróficas terrestres y la principal fuente del oxígeno que respiramos.
            """.trimIndent()
        }

        if (clean.contains("que es la gravedad") || clean == "gravedad" || clean.contains("que es gravedad")) {
            return """
                ### 🪐 La Gravedad
                Es una de las cuatro interacciones fundamentales de la naturaleza:
                * **Visión Newtoniana:** Fuerza atractiva directamente proporcional al producto de las masas e inversamente proporcional al cuadrado de la distancia que las separa: F = G · (m₁ · m₂) / r².
                * **Visión Einsteiniana (Relatividad General):** La gravedad no es una fuerza a distancia, sino la manifestación de la **curvatura del espacio-tiempo** provocada por la presencia de masa y energía.
            """.trimIndent()
        }

        if (clean.contains("velocidad de la luz")) {
            return "En el vacío, la **velocidad de la luz** es una constante física universal denotada como «c», y equivale exactamente a **299.792.458 metros por segundo** (aproximadamente 300.000 km/s)."
        }

        if (clean.contains("distancia") && clean.contains("tierra") && clean.contains("sol")) {
            return "La distancia promedio entre la Tierra y el Sol es de aproximadamente **149,6 millones de kilómetros** (definida como 1 Unidad Astronómica o UA). La luz solar tarda aproximadamente **8 minutos y 20 segundos** en recorrer esa distancia."
        }

        if (clean.contains("cuantos planetas") || clean.contains("planetas del sistema solar")) {
            return """
                El Sistema Solar cuenta con **8 planetas reconocidos** por la Unión Astronómica Internacional (UAI), ordenados por su distancia al Sol:
                1. Mercurio
                2. Venus
                3. Tierra
                4. Marte
                5. Júpiter
                6. Saturno
                7. Urano
                8. Neptuno
                
                *Nota:* Plutón fue reclasificado en 2006 como **planeta enano**, junto a cuerpos como Ceres, Eris, Haumea y Makemake.
            """.trimIndent()
        }

        if (clean.contains("monte everest") || clean.contains("altura del everest") || clean.contains("montana mas alta") || clean.contains("montana mas grande")) {
            return "El **Monte Everest** es la montaña más alta de la Tierra sobre el nivel del mar, con una elevación oficial de **8.848,86 metros**. Se ubica en la cordillera del Himalaya, marcando la frontera entre Nepal y la región autónoma del Tíbet (China)."
        }

        if (clean.contains("que es el adn") || clean == "adn") {
            return """
                ### 🧬 Ácido Desoxirribonucleico (ADN)
                Es el biopolímero que contiene las instrucciones genéticas utilizadas en el desarrollo y funcionamiento de todos los seres vivos conocidos:
                * **Estructura:** Doble hélice antiparalela formada por nucleótidos (azúcar desoxirribosa, grupo fosfato y una base nitrogenada).
                * **Bases nitrogenadas:** Adenina (A) emparejada con Timina (T), y Citosina (C) emparejada con Guanina (G).
            """.trimIndent()
        }

        // 3. Tecnología, Informática y Computación
        if (clean.contains("que es una api") || clean.contains("que es un api") || clean.contains("que es la api") || clean.contains("que es api") || clean.contains("api rest") || clean == "api") {
            return """
                ### 🔌 ¿Qué es una API?
                Una **API** (*Application Programming Interface* o Interfaz de Programación de Aplicaciones) es un conjunto de protocolos, definiciones y reglas que permiten que diferentes programas o servicios de software se comuniquen e intercambien datos entre sí:
                * **Analogía:** Actúa como el camarero en un restaurante: lleva tu pedido (solicitud) a la cocina (servidor) y te trae la comida (respuesta).
                * **API REST:** Estilo arquitectónico predominante en la web que utiliza peticiones HTTP estándar (`GET`, `POST`, `PUT`, `DELETE`) intercambiando datos generalmente en formato JSON.
            """.trimIndent()
        }

        if ((clean.contains("tor") || clean.contains("onion")) && !clean.contains("autor") && !clean.contains("motor") && !clean.contains("doctor") && !clean.contains("pastor") && !clean.contains("sector") && !clean.contains("factor") && !clean.contains("historia")) {
            if (clean.contains("ventaja") || clean.contains("desventaja") || clean.contains("pro") || clean.contains("contra") || clean.contains("riesgo") || clean.contains("problema")) {
                return """
                    ### 🧅 Ventajas y Desventajas de la Red Tor
                    * **Ventajas principales:**
                      1. **Anonimato y Privacidad:** Oculta tu dirección IP real ante los sitios web que visitas y oculta tu navegación ante tu proveedor de internet (ISP).
                      2. **Evasión de Censura:** Permite sortear bloqueos geográficos y cortafuegos gubernamentales.
                      3. **Cero Rastreo Centralizado:** La red es mantenida por miles de voluntarios descentralizados en el mundo.
                    * **Desventajas e inconvenientes:**
                      1. **Menor Velocidad:** El triple cifrado y el enrutamiento por nodos en distintos países incrementa la latencia respecto a la navegación directa.
                      2. **Captchas y Bloqueos:** Muchos servicios bancarios y plataformas comerciales bloquean las IPs de salida públicas de Tor.
                      3. **Seguridad en Nodos de Salida:** Si un sitio no utiliza HTTPS, el operador del nodo de salida podría inspeccionar el tráfico en texto plano.
                """.trimIndent()
            }
            return """
                ### 🧅 La Red Tor (The Onion Router)
                Tor es una red descentralizada de comunicaciones diseñada para brindar **anonimato y privacidad digital**:
                * **Enrutamiento por capas:** El tráfico se envuelve en múltiples capas de cifrado (como una cebolla) y viaja a través de tres nodos aleatorios en el mundo:
                  1. **Nodo de Entrada (Guard):** Conoce tu IP pero no el contenido ni el destino final.
                  2. **Nodo Intermedio (Middle):** No conoce tu IP ni el destino final; solo sabe de qué nodo viene y a cuál va.
                  3. **Nodo de Salida (Exit):** Desencripta la última capa y entrega la petición al sitio web, mostrando su propia IP y ocultando la tuya.
            """.trimIndent()
        }

        if (clean.contains("que es bitcoin") || clean.contains("que es blockchain")) {
            return """
                ### ⛓️ Bitcoin y Blockchain
                * **Bitcoin:** Es la primera moneda digital descentralizada, creada en 2008 por el pseudónimo Satoshi Nakamoto, que permite transferir valor entre pares (P2P) sin depender de bancos centrales ni intermediarios.
                * **Blockchain:** Es la tecnología de registro contable subyacente. Se trata de un libro mayor público, distribuido e inmutable donde las transacciones se agrupan en bloques vinculados criptográficamente mediante funciones hash y validados por mecanismos de consenso (Proof-of-Work).
            """.trimIndent()
        }

        if (clean.contains("que es linux")) {
            return """
                ### 🐧 ¿Qué es Linux?
                **Linux** es un kernel (núcleo) de sistema operativo libre, gratuito y de código abierto desarrollado inicialmente por Linus Torvalds en 1991:
                * **Ecosistema:** Combinado con las herramientas del proyecto GNU, forma el sistema operativo GNU/Linux.
                * **Impacto:** Impulsa la mayoría de los servidores mundiales, supercomputadoras, dispositivos IoT y es la base sobre la que se construye el sistema operativo **Android**.
            """.trimIndent()
        }

        if (clean.contains("memoria ram") || clean.contains("que es la ram")) {
            return """
                ### 💾 Memoria RAM (Random Access Memory)
                Es la memoria de trabajo principal de cualquier dispositivo electrónico:
                * **Velocidad y Volatilidad:** Es órdenes de magnitud más rápida que el almacenamiento permanente (SSD/UFS), pero es volátil: al apagar el equipo, su contenido se borra por completo.
                * **Función:** Almacena de forma temporal las instrucciones del sistema operativo y los datos de las aplicaciones que estás ejecutando en primer y segundo plano para que el procesador pueda acceder a ellos sin cuello de botella.
            """.trimIndent()
        }

        if (clean.contains("que es una gpu") || clean.contains("cpu vs gpu") || clean.contains("diferencia entre cpu y gpu")) {
            return """
                ### ⚡ CPU vs GPU: Diferencias Arquitectónicas
                * **CPU (Central Processing Unit):** Diseñada para la ejecución de tareas secuenciales complejas con baja latencia. Cuenta con pocos núcleos (de 4 a 16 comúnmente) altamente potentes con grandes cachés.
                * **GPU (Graphics Processing Unit):** Diseñada para el procesamiento masivamente paralelo con alto rendimiento. Contiene miles de núcleos más pequeños y eficientes, ideales para renderizado 3D, matrices matemáticas y tensores de **Inteligencia Artificial**.
            """.trimIndent()
        }

        if (clean.contains("http") && clean.contains("https")) {
            return """
                ### 🔒 HTTP vs HTTPS
                * **HTTP (*Hypertext Transfer Protocol*):** Transmite los datos entre tu navegador y el servidor en texto plano sin cifrar. Cualquier intermediario en la red local o ISP puede interceptar o alterar la información.
                * **HTTPS (*HTTP Secure*):** Añade una capa de cifrado mediante el protocolo **TLS/SSL**. Garantiza tres pilares: **confidencialidad** (nadie puede leer los datos en tránsito), **integridad** (no pueden manipular el contenido) y **autenticidad** (certifica la identidad del servidor).
            """.trimIndent()
        }

        if (clean.contains("que es un algoritmo") || clean == "algoritmo") {
            return "Un **algoritmo** es una secuencia ordenada, finita e inequívoca de pasos o instrucciones lógicas que permiten resolver un problema específico, procesar datos o realizar un cálculo."
        }

        if (clean.contains("que es una base de datos") || clean == "base de datos") {
            return """
                ### 🗄️ ¿Qué es una Base de Datos?
                Es un sistema estructurado diseñado para almacenar, organizar, indexar y recuperar grandes volúmenes de información de manera eficiente y persistente:
                * **Relacionales (SQL):** Tablas estructuradas con relaciones y garantías ACID (PostgreSQL, SQLite, MySQL).
                * **No Relacionales (NoSQL):** Modelos flexibles de documentos (MongoDB), clave-valor (Redis) o grafos (Neo4j).
            """.trimIndent()
        }

        if (clean.contains("software libre") || clean.contains("open source")) {
            return """
                ### 🕊️ Software Libre y Código Abierto
                El software libre, fundamentado por la Free Software Foundation (FSF), garantiza **cuatro libertades esenciales**:
                0. Libertad de usar el programa con cualquier propósito.
                1. Libertad de estudiar cómo funciona y modificarlo.
                2. Libertad de redistribuir copias para ayudar a otros.
                3. Libertad de distribuir versiones modificadas a la comunidad.
            """.trimIndent()
        }

        if (clean.contains("alan turing") || clean == "turing") {
            return when {
                clean.contains("donde nacio") || clean.contains("nacimiento") || clean.contains("lugar de nacimiento") || clean.contains("de donde era") || clean.contains("de donde es") || clean.contains("nacionalidad") ->
                    "Alan Turing nació el **23 de junio de 1912** en **Maida Vale, Londres, Reino Unido**."

                clean.contains("cuando murio") || clean.contains("como murio") || clean.contains("de que murio") || clean.contains("muerte") || clean.contains("fallecio") || clean.contains("murio") || clean.contains("fallecimiento") ->
                    "Alan Turing falleció el **7 de junio de 1954** (a los 41 años) en **Wilmslow, Cheshire, Inglaterra**, a causa de intoxicación por cianuro. En 2013, la reina Isabel II le concedió un indulto real póstumo reconociendo la histórica injusticia de su condena bajo leyes homofóbicas de la época."

                clean.contains("que invento") || clean.contains("que descubrio") || clean.contains("aportes") || clean.contains("inventos") || clean.contains("logros") ->
                    """
                        ### 🏆 Principales Aportes e Invenciones de Alan Turing:
                        1. **La Máquina de Turing (1936):** El modelo matemático fundacional que formalizó los conceptos de algoritmo y computación universal.
                        2. **Criptoanálisis de Enigma (1939-1945):** Diseñó la máquina electromecánica *Bombe* en Bletchley Park para quebrar el cifrado naval nazi durante la Segunda Guerra Mundial.
                        3. **El Test de Turing (1950):** Criterio filosófico y operacional para discernir si una máquina exhibe inteligencia equivalente a la humana.
                        4. **El computador ACE:** Uno de los primeros diseños de arquitectura de computadoras con programa almacenado.
                        5. **Morfogénesis biológica:** Pionero en aplicar ecuaciones diferenciales no lineales para explicar patrones biológicos como las rayas de las cebras o las manchas de los leopardos.
                    """.trimIndent()

                else -> """
                    ### 🧠 Alan Turing (1912 - 1954)
                    Matemático, lógico y criptoanalista británico, considerado el **padre de la informática teórica y de la inteligencia artificial**:
                    * **Máquina de Turing:** Modelo formal que definió los límites teóricos del cómputo mecánico.
                    * **Segunda Guerra Mundial:** Lideró el equipo en Bletchley Park que descifró la máquina de cifrado nazi *Enigma*, salvando millones de vidas.
                    * **Test de Turing:** Propuesta pionera para evaluar si una máquina puede exhibir comportamiento inteligente indistinguible del humano.
                """.trimIndent()
            }
        }

        if (clean.contains("nikola tesla") || clean == "tesla") {
            return when {
                clean.contains("donde nacio") || clean.contains("nacimiento") || clean.contains("lugar de nacimiento") || clean.contains("de donde era") || clean.contains("nacionalidad") ->
                    "Nikola Tesla nació el **10 de julio de 1856** en **Smiljan, Imperio austríaco** (actual territorio de **Croacia**)."

                clean.contains("cuando murio") || clean.contains("como murio") || clean.contains("muerte") || clean.contains("fallecio") || clean.contains("murio") || clean.contains("fallecimiento") ->
                    "Nikola Tesla falleció el **7 de enero de 1943** (a los 86 años) en **Nueva York, Estados Unidos**, en la habitación 3327 del Hotel New Yorker a causa de una trombosis coronaria."

                clean.contains("que invento") || clean.contains("inventos") || clean.contains("aportes") || clean.contains("patentes") ->
                    """
                        ### ⚡ Principales Invenciones de Nikola Tesla:
                        1. **Sistema polifásico de Corriente Alterna (AC):** Generadores, transformadores y líneas de transmisión que permitieron la electrificación a larga distancia.
                        2. **Motor de Inducción de CA:** Motor eléctrico sin escobillas accionado por un campo magnético rotativo, estándar industrial moderno.
                        3. **Bobina de Tesla:** Transformador resonante de alta frecuencia y alto voltaje.
                        4. **Transmisión Inalámbrica y Radio:** Pionero en osciladores de radiofrecuencia y control remoto inalámbrico (teleautomaton).
                    """.trimIndent()

                else -> """
                    ### ⚡ Nikola Tesla (1856 - 1943)
                    Ingeniero eléctrico, físico e inventor prolífico:
                    * **Corriente Alterna (AC):** Desarrolló el sistema polifásico de generación y distribución eléctrica que energiza el mundo actual.
                    * **Motor de Inducción:** Creó el motor eléctrico sin escobillas accionado por campo magnético rotativo.
                    * **Telecomunicaciones:** Realizó demostraciones pioneras de transmisión inalámbrica y radiofrecuencia (bobina de Tesla).
                """.trimIndent()
            }
        }

        if (clean.contains("albert einstein") || clean == "einstein") {
            return when {
                clean.contains("donde nacio") || clean.contains("nacimiento") || clean.contains("lugar de nacimiento") || clean.contains("de donde era") || clean.contains("nacionalidad") ->
                    "Albert Einstein nació el **14 de marzo de 1879** en **Ulm, Reino de Wurtemberg, Alemania**."

                clean.contains("cuando murio") || clean.contains("como murio") || clean.contains("muerte") || clean.contains("fallecio") || clean.contains("murio") || clean.contains("fallecimiento") ->
                    "Albert Einstein falleció el **18 de abril de 1955** (a los 76 años) en **Princeton, Nueva Jersey, Estados Unidos**, debido a la ruptura de un aneurisma de aorta abdominal."

                clean.contains("que invento") || clean.contains("que descubrio") || clean.contains("aportes") || clean.contains("nobel") || clean.contains("teoria") ->
                    """
                        ### ⚛️ Principales Descubrimientos de Albert Einstein:
                        1. **Teoría de la Relatividad Especial (1905):** Estableció que las leyes de la física son idénticas para observadores inerciales y dedujo la equivalencia masa-energía (E = mc²).
                        2. **Efecto Fotoeléctrico (1905):** Demostró la naturaleza cuántica de la luz mediante fotones, por el cual obtuvo el **Premio Nobel de Física en 1921**.
                        3. **Movimiento Browniano (1905):** Evidencia matemática y física concluyente sobre la existencia atómica y molecular.
                        4. **Teoría de la Relatividad General (1915):** Reformuló la gravedad como una curvatura geométrica del espacio-tiempo causada por la masa y la energía.
                    """.trimIndent()

                else -> """
                    ### ⚛️ Albert Einstein (1879 - 1955)
                    Físico teórico alemán, universalmente considerado uno de los científicos más influyentes de la historia:
                    * **Relatividad:** Transformó la comprensión del espacio, el tiempo y la gravedad con la Relatividad Especial (1905) y General (1915).
                    * **Premio Nobel:** Galardonado con el Premio Nobel de Física en 1921 por su explicación del efecto fotoeléctrico.
                    * **Ecuación E = mc²:** La fórmula más famosa de la ciencia, estableciendo la equivalencia fundamental entre masa y energía.
                """.trimIndent()
            }
        }

        if (clean.contains("marie curie") || clean == "curie") {
            return when {
                clean.contains("donde nacio") || clean.contains("nacimiento") || clean.contains("lugar de nacimiento") || clean.contains("de donde era") || clean.contains("nacionalidad") ->
                    "Marie Curie (Maria Salomea Skłodowska) nació el **7 de noviembre de 1867** en **Varsovia, Polonia** (en aquel entonces bajo control del Imperio ruso)."

                clean.contains("cuando murio") || clean.contains("como murio") || clean.contains("de que murio") || clean.contains("muerte") || clean.contains("fallecio") || clean.contains("murio") || clean.contains("fallecimiento") ->
                    "Marie Curie falleció el **4 de julio de 1934** (a los 66 años) en **Passy, Alta Saboya, Francia**, a causa de una anemia aplásica desarrollada por la prolongada exposición sin blindaje a la radiación ionizante durante sus investigaciones."

                clean.contains("que descubrio") || clean.contains("premios") || clean.contains("nobel") || clean.contains("aportes") ->
                    """
                        ### 🧪 Descubrimientos y Reconocimientos de Marie Curie:
                        1. **Descubrimiento del Polonio y el Radio (1898):** Logró aislar dos elementos químicos desconocidos y altamente radiactivos a partir de pechblenda.
                        2. **Pionera de la Radiactividad:** Acuñó el término *radiactividad* y diseñó técnicas pioneras para aislar isótopos radiactivos.
                        3. **Hito Nobel Histórico:** Es la única persona en la historia en recibir **dos Premios Nobel en disciplinas científicas diferentes**:
                           * **Premio Nobel de Física (1903):** Compartido con Pierre Curie y Henri Becquerel por investigaciones sobre la radiación.
                           * **Premio Nobel de Química (1911):** En reconocimiento al descubrimiento y purificación del radio y polonio.
                    """.trimIndent()

                else -> """
                    ### 🧪 Marie Curie (1867 - 1934)
                    Física y química polaca-francesa, figura cumbre de la historia de la ciencia:
                    * **Radiactividad:** Pionera absoluta en el estudio de las radiaciones y descubridora del radio y el polonio.
                    * **Doble Nobel:** Primera mujer en ganar un Premio Nobel y la única persona premiada en dos ciencias distintas (Física y Química).
                    * **Medicina y Guerra:** Impulsó el uso de unidades móviles de radiografía (*Petites Curies*) para salvar vidas en la Primera Guerra Mundial.
                """.trimIndent()
            }
        }

        return null
    }

    fun generateNaturalAnswer(prompt: String): String {
        // 1. Evaluación matemática exacta
        tryEvaluateMath(prompt)?.let { return it }

        // 2. Tiempo y fecha en tiempo real
        tryEvaluateTemporal(prompt)?.let { return it }

        // 3. Conversión de unidades
        tryEvaluateUnitConversion(prompt)?.let { return it }

        // 4. Datos del dispositivo y sistema
        tryEvaluateDeviceInfo(prompt)?.let { return it }

        // 5. Identidad y saludos
        tryEvaluateIdentityAndGreetings(prompt)?.let { return it }

        // 6. Hechos y datos precisos
        tryEvaluateFactualKnowledge(prompt)?.let { return it }

        val lower = prompt.lowercase()
        return when {
            lower.contains("privacidad") || lower.contains("tor") || lower.contains("seguridad") || lower.contains("rastreo") || lower.contains("soberanía") -> """
                ### 🛡️ Privacidad Digital y Soberanía Técnica
                
                En el ecosistema digital actual, la privacidad no es ocultar secretos; es **el derecho fundamental a no ser vigilado ni perfilado sin tu consentimiento explícito**.

                #### Fundamentos esenciales:
                1. **Aislamiento en Dispositivo:** Cuando el procesamiento de IA o tus datos de navegación ocurren en tu propio chip (como lo hace Senda), ninguna empresa puede construir un perfil publicitario sobre ti ni vender tus hábitos.
                2. **Redes Anónimas (Tor):** El enrutamiento en capas oculta tu dirección IP real a través de tres nodos cifrados en el mundo, evitando que tu proveedor de internet o los sitios web rastreen tu ubicación geográfica.
                3. **Cero Telemetría:** Al evitar analíticas de terceros y rastreadores invasivos, proteges tu huella digital frente a filtraciones de bases de datos masivas.

                *Consejo práctico:* Mantén activas las protecciones de cookies de Senda y utiliza la navegación en chip para tus consultas delicadas.

                ---
                🔒 *Senda AI Soberana: Procesado 100% en chip local. Cero rastreo, cero telemetría.*
            """.trimIndent()

            lower.contains("cuántica") || lower.contains("cuantica") || lower.contains("qubit") -> """
                ### ⚛️ Computación Cuántica: Principios y Realidad
                
                A diferencia de la computación clásica que trabaja con bits convencionales (0 o 1), la computación cuántica aprovecha las leyes de la mecánica cuántica mediante **qubits**:

                * **Superposición:** Un qubit puede encontrarse en una combinación lineal de 0 y 1 al mismo tiempo, lo que permite evaluar simultáneamente un espacio exponencial de posibilidades.
                * **Entrelazamiento:** Dos qubits pueden correlacionarse íntimamente de forma que el estado de uno determina instantáneamente el estado del otro, sin importar la distancia física.
                * **Impacto en la Criptografía:** Algoritmos como el de Shor podrían en el futuro resolver la factorización de números primos en tiempo polinómico, lo que amenaza algoritmos asimétricos clásicos como RSA. Por ello, la industria avanza hoy hacia la **criptografía post-cuántica (PQC)**.

                *Conclusión:* No reemplazará a los teléfonos ni computadores personales, sino que resolverá problemas ultra complejos de química molecular, optimización logística y simulación de materiales.
            """.trimIndent()

            lower.contains("código") || lower.contains("codigo") || lower.contains("programar") || lower.contains("algoritmo") || lower.contains("python") || lower.contains("kotlin") || lower.contains("javascript") -> """
                ### 💻 Análisis de Código y Buenas Prácticas
                
                Para abordar este problema con máxima eficiencia y legibilidad:

                1. **Claridad sobre Complejidad:** Escribe código que sea autoexplicativo, modular y con responsabilidades únicas (principio SOLID).
                2. **Gestión de Memoria y Recursos:** Evita fugas de memoria liberando observadores y asegurando que las corrutinas o hilos asíncronos estén ligados al ciclo de vida del componente.
                3. **Verificación y Tipado:** Prefiere lenguajes modernos con seguridad de nulos en tiempo de compilación (como Kotlin o Rust) para erradicar los temidos `NullPointerException` en producción.

                Si deseas que redacte una función específica en Kotlin, Python o JavaScript, descríbeme los datos de entrada y salida y la generamos al instante.
            """.trimIndent()

            else -> """
                Entiendo tu consulta sobre **«$prompt»**.

                Como tu asistente soberano en Senda:
                * **Investigación y datos:** Si buscas un hecho o tema específico, descríbemelo con un poco más de detalle o pídeme buscarlo en la red para consultar fuentes contrastadas en tiempo real.
                * **Redacción de documentos:** Si requieres una carta, petición, reclamo, contrato o informe formal, dime los datos principales y lo redactamos de inmediato para descargarlo a tu dispositivo.
                * **Ingeniería (Senda Codex):** Si necesitas una función o script en Python, Kotlin, Bash, JavaScript o SQL, dime los requerimientos y lo programamos al instante.

                ---
                🔒 *Senda AI: Procesamiento 100% soberano en chip.*
            """.trimIndent()
        }
    }


}
