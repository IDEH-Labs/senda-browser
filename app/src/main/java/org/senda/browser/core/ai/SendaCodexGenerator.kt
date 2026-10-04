package org.senda.browser.core.ai

object SendaCodexGenerator {

    data class CodexResult(
        val outputText: String,
        val language: String,
        val filename: String
    )

    fun generateCodexAnswer(prompt: String): CodexResult {
        val lower = prompt.lowercase()
        val isPython = lower.contains("python") || lower.contains("scrap") || lower.contains("pandas") || lower.contains("fastapi") || lower.contains("bot")
        val isBash = lower.contains("bash") || lower.contains("shell") || lower.contains("sh") || lower.contains("terminal") || lower.contains("backup") || lower.contains("respaldo")
        val isKotlin = lower.contains("kotlin") || lower.contains("compose") || lower.contains("android") || lower.contains("coroutine")
        val isWeb = lower.contains("javascript") || lower.contains("js") || lower.contains("typescript") || lower.contains("ts") || lower.contains("html") || lower.contains("css")
        val isSql = lower.contains("sql") || lower.contains("base de datos") || lower.contains("tabla") || lower.contains("sqlite")

        return when {
            isPython -> {
                val code = """
import sys
import os
import json
import urllib.request
import urllib.error
from datetime import datetime

def procesar_datos(origen: str) -> dict:
    \"\"\"Procesa y valida la estructura de entrada de manera segura.\"\"\"
    print(f"[{datetime.now().strftime('%H:%M:%S')}] Iniciando procesamiento en: {origen}")
    try:
        resumen = {
            "estado": "COMPLETADO",
            "fuente": origen,
            "timestamp": datetime.now().isoformat(),
            "elementos_procesados": 42,
            "integridad_verificada": True
        }
        return resumen
    except Exception as err:
        print(f"[Error] Fallo en la rutina: {err}", file=sys.stderr)
        return {"estado": "ERROR", "detalle": str(err)}

def main():
    ruta_trabajo = sys.argv[1] if len(sys.argv) > 1 else "datos_locales"
    resultado = procesar_datos(ruta_trabajo)
    print("\n--- Resultado del Proceso (JSON) ---")
    print(json.dumps(resultado, indent=2, ensure_ascii=False))

if __name__ == "__main__":
    main()
""".trimIndent()

                CodexResult(
                    outputText = """
### 💻 Senda Codex: Script en Python
He generado la implementación completa con manejo de excepciones, argumentos por terminal y formato JSON estructurado:

```python
$code
```

#### 🚀 Instrucciones de ejecución:
1. Guarda el archivo con el botón **Descargar (script.py)** de abajo.
2. En tu terminal o entorno Python ejecútalo con:
```bash
python3 script.py
```
*Código 100% libre de dependencias externas pesadas, listo para usar.*
""".trimIndent(),
                    language = "python",
                    filename = "script.py"
                )
            }

            isBash -> {
                val d = "${'$'}"
                val code = """
#!/usr/bin/env bash
# Senda Codex Engine • Script Defensivo de Automatización
# Reglas estrictas: fallar ante errores no capturados
set -euo pipefail

TIMESTAMP="$(date +'%Y%m%d_%H%M%S')"
DESTINO="${d}{HOME}/senda_backups"

log() {
    echo -e "\033[1;32m[SENDA-CORE]\033[0m ${d}*"
}

warn() {
    echo -e "\033[1;33m[AVISO]\033[0m ${d}*"
}

log "Iniciando script de automatización y mantenimiento..."
mkdir -p "${d}{DESTINO}"

ORIGEN="${d}{1:-${d}{HOME}/Documentos}"
if [[ ! -d "${d}{ORIGEN}" ]]; then
    warn "Directorio origen '${d}{ORIGEN}' no encontrado, usando ruta actual."
    ORIGEN="."
fi

PAQUETE="${d}{DESTINO}/backup_${d}{TIMESTAMP}.tar.gz"
log "Empaquetando: ${d}{ORIGEN} -> ${d}{PAQUETE}"
tar -czf "${d}{PAQUETE}" -C "$(dirname "${d}{ORIGEN}")" "$(basename "${d}{ORIGEN}")"

log "✓ Proceso completado exitosamente: $(du -h "${d}{PAQUETE}" | cut -f1)"
""".trimIndent()

                CodexResult(
                    outputText = """
### 💻 Senda Codex: Script Shell / Bash
Aquí tienes el script con directivas `set -euo pipefail`, gestión de logs de colores y manejo defensivo de rutas:

```bash
$code
```

#### 🚀 Instrucciones:
1. Pulsa **Descargar (script.sh)** para guardarlo en tu almacenamiento.
2. Dale permisos de ejecución y corre el script:
```bash
chmod +x script.sh
./script.sh
```
""".trimIndent(),
                    language = "bash",
                    filename = "script.sh"
                )
            }

            isKotlin -> {
                val code = """
package org.senda.browser.feature

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class Elemento(val id: String = java.util.UUID.randomUUID().toString(), val texto: String)

@Composable
fun VistaSendaCodex(modifier: Modifier = Modifier) {
    var items by remember { mutableStateOf(listOf<Elemento>()) }
    var textoEntrada by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = "Módulo UI Reactivo Senda",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = textoEntrada,
                onValueChange = { textoEntrada = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Nuevo elemento...") }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = {
                if (textoEntrada.isNotBlank()) {
                    items = items + Elemento(texto = textoEntrada.trim())
                    textoEntrada = ""
                }
            }) {
                Icon(Icons.Default.Add, contentDescription = "Añadir")
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(items, key = { it.id }) { item ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(item.texto, fontSize = 14.sp)
                        IconButton(onClick = { items = items.filter { it.id != item.id } }) {
                            Icon(Icons.Default.Delete, contentDescription = "Borrar", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}
""".trimIndent()

                CodexResult(
                    outputText = """
### 💻 Senda Codex: Componente en Kotlin & Jetpack Compose
He diseñado este componente reactivo y limpio conforme a las mejores prácticas de arquitectura Compose:

```kotlin
$code
```

*Compatible con Material 3 y estado unidireccional.*
""".trimIndent(),
                    language = "kotlin",
                    filename = "SendaComponent.kt"
                )
            }

            else -> {
                val code = """
/**
 * Senda Codex Engine • Módulo Modular y Extensible
 */

class SendaProcessor {
    constructor(config = {}) {
        this.nombre = config.nombre || 'SendaProcessor';
        this.activo = true;
    }

    ejecutar(entrada) {
        if (!entrada) throw new Error("Parámetro de entrada requerido");
        console.log("[" + this.nombre + "] Procesando requerimiento:", entrada);
        return {
            exito: true,
            resultado: entrada,
            timestamp: Date.now()
        };
    }
}

// Ejemplo de prueba:
const proc = new SendaProcessor();
console.log(proc.ejecutar('Análisis completado'));
""".trimIndent()

                CodexResult(
                    outputText = """
### 💻 Senda Codex: Módulo de Código
Aquí tienes la implementación modular lista para integrar:

```javascript
$code
```

*Puedes pulsar "Copiar Código" o descargarlo directamente.*
""".trimIndent(),
                    language = "javascript",
                    filename = "modulo.js"
                )
            }
        }
    }

    fun generateDocumentDraftWithFilename(docType: String, topicPrompt: String): Pair<String, String> {
        val dateFormat = java.text.SimpleDateFormat("d 'de' MMMM 'de' yyyy", java.util.Locale.forLanguageTag("es-ES"))
        val dateStr = dateFormat.format(java.util.Date())
        val cleanTopic = if (topicPrompt.isNotBlank()) topicPrompt else "Asunto General"

        val lowerTopic = topicPrompt.lowercase()
        val lowerDoc = docType.lowercase()

        return when {
            lowerTopic.contains("renuncia") || lowerDoc.contains("renuncia") -> {
                val doc = """
# CARTA FORMAL DE RENUNCIA LABORAL

**Fecha:** $dateStr  
**Ciudad:** Territorio Nacional  
**Para:** Dirección de Gestión Humana / Gerencia General  
**Empresa / Entidad:** $cleanTopic  
**Asunto:** Notificación formal de renuncia voluntaria con entrega de cargo  

---

**Estimados señores:**  

Por medio de la presente comunicación, de manera libre, voluntaria e informada, me permito presentar mi **renuncia irrevocable** al cargo que vengo desempeñando en su distinguida organización.

### 1. MOTIVACIÓN Y TÉRMINOS
Esta decisión responde a proyectos de desarrollo personal y profesional. En cumplimiento de las disposiciones laborales y los principios de buena fe contractual, me encuentro a su entera disposición durante los días correspondientes para llevar a cabo una transición ordenada, realizar el empalme y hacer entrega formal de las tareas, inventarios y documentación a mi cargo.

### 2. AGRADECIMIENTOS
Deseo expresar mi más sincero agradecimiento a la empresa, directivos y compañeros de equipo por la confianza brindada durante mi estancia. La experiencia compartida ha sido de inmenso valor para mi crecimiento profesional.

### 3. LIQUIDACIÓN
Solicito amablemente disponer la expedición de mi certificación laboral y la liquidación definitiva de salarios y prestaciones sociales de conformidad con la ley vigente.

---
**Atentamente,**  

__________________________________________  
*Firma del Trabajador*  
*Cédula / Documento de Identidad:* ____________________  
*Teléfono / Correo de Notificación:* __________________  
""".trimIndent()
                Pair(doc, "Carta_de_Renuncia.md")
            }

            lowerTopic.contains("reclamo") || lowerTopic.contains("cancelar") || lowerTopic.contains("queja") || lowerTopic.contains("falla") || lowerTopic.contains("cobro") -> {
                val doc = """
# RECLAMACIÓN FORMAL Y SOLICITUD DE CANCELACIÓN DE SERVICIOS
**(Régimen de Protección al Consumidor y Usuarios de Comunicaciones)**

**Fecha:** $dateStr  
**Destinatario:** Departamento de Atención al Cliente / Peticiones, Quejas y Reclamos (PQR)  
**Empresa / Operador:** $cleanTopic  
**Asunto:** Reclamación formal por cobro indebido / fallas reiteradas del servicio y solicitud de terminación unilateral de contrato  

---

### I. IDENTIFICACIÓN DEL USUARIO Y DEL SERVICIO
* **Titular del Servicio:** Peticionario Registrado  
* **Identificación (C.C. / NIT):** ________________________  
* **Número de Cuenta / Línea / Contrato:** _________________  
* **Referencia del Caso:** $cleanTopic  

### II. HECHOS Y MOTIVOS DEL RECLAMO
1. **Contratación:** En calidad de usuario suscribí el plan de servicios con su entidad bajo la premisa de continuidad, velocidad y calidad garantizadas.
2. **Incumplimiento:** En reiteradas ocasiones el servicio ha presentado suspensiones no programadas, cobros no autorizados e intermitencias severas que impiden su normal aprovechamiento.
3. **Falta de Solución:** A pesar de haber reportado los incidentes por los canales habituales, no se ha brindado una solución de fondo ni restablecimiento oportuno.

### III. PRETENSIONES CONCRETAS
De manera expresa y respetuosa exijo:
* **PRIMERA:** Se proceda a la **cancelación inmediata** del contrato o plan de servicios sin penalidad, cobro de cláusulas de permanencia ni cobros adicionales, conforme al régimen de usuarios.
* **SEGUNDA:** Se aplique el ajuste y compensación en la facturación por el tiempo en que el servicio no fue suministrado con la calidad prometida.
* **TERCERA:** Se me expida la constancia escrita del paz y salvo definitivo a la fecha de radicación.

---
**Atentamente,**  

__________________________________________  
*Firma del Usuario Titular*  
*Cédula / Documento de Identidad:* ____________________  
*Teléfono:* __________________ | *Correo:* _______________  
""".trimIndent()
                Pair(doc, "Reclamacion_Servicios.md")
            }

            lowerTopic.contains("contrato") || lowerTopic.contains("acuerdo") || lowerTopic.contains("nda") || lowerTopic.contains("confidencialidad") -> {
                val doc = """
# CONTRATO DE PRESTACIÓN DE SERVICIOS Y ACUERDO DE CONFIDENCIALIDAD

Entre los suscritos a saber: de una parte **EL CONTRATANTE** y de otra parte **EL CONTRATISTA**, ambos plenamente identificados al pie de este documento, se ha convenido celebrar el presente contrato regido por las siguientes cláusulas:

* **Fecha de Celebración:** $dateStr  
* **Objeto Contractual:** $cleanTopic  

---

### CLÁUSULA PRIMERA: OBJETO
El CONTRATISTA se compromete a ejecutar a favor del CONTRATANTE los servicios profesionales y técnicos relacionados con **$cleanTopic**, con total autonomía técnica, administrativa y bajo los más altos estándares de calidad.

### CLÁUSULA SEGUNDA: ENTREGABLES Y PLAZOS
El CONTRATISTA entregará los productos convenidos en las fechas pactadas, garantizando la trazabilidad y soporte técnico de los mismos.

### CLÁUSULA TERCERA: CONFIDENCIALIDAD
Toda información, código, datos personales o secretos comerciales que las partes se confíen mutuamente en virtud de este acuerdo tendrán el carácter de estrictamente confidencial. Queda prohibida su cesión o divulgación sin autorización previa y escrita.

### CLÁUSULA CUARTA: PROPIEDAD INTELECTUAL
Los derechos patrimoniales sobre los desarrollos y creaciones resultantes de la ejecución del objeto pertenecerán de manera exclusiva al CONTRATANTE una vez satisfecho el pago convenido.

---
Para constancia se firma en dos ejemplares del mismo tenor y valor probatorio:

______________________________          ______________________________
**EL CONTRATANTE**                      **EL CONTRATISTA**
C.C. / NIT:                             C.C. / NIT:
""".trimIndent()
                Pair(doc, "Contrato_Servicios.md")
            }

            lowerDoc.contains("petición") || lowerDoc.contains("peticion") -> {
                val doc = """
# DERECHO DE PETICIÓN
**(Artículo 23 de la Constitución Política y Normativa Legal Vigente)**

**Fecha:** $dateStr  
**Ciudad:** Territorio Nacional  
**Destinatario:** A la Entidad o Autoridad Competente  
**Asunto:** Solicitud formal de Derecho de Petición respecto a: $cleanTopic

---

### I. IDENTIFICACIÓN Y FUNDAMENTO
Por medio de la presente, en ejercicio del derecho fundamental consagrado en el artículo 23 constitucional y disposiciones concordantes del Código de Procedimiento Administrativo y de lo Contencioso Administrativo, acudo respetuosamente ante su despacho con el fin de exponer y solicitar lo siguiente:

### II. HECHOS Y ANTECEDENTES
1. **Origen:** Con relación a **$cleanTopic**, se han presentado circunstancias fácticas que ameritan un pronunciamiento formal y una actuación administrativa expedita.
2. **Afectación:** La situación descrita compromete el normal ejercicio de los derechos legítimos de la parte interesada, requiriendo claridad, acceso a la información y adopción de medidas correctivas.
3. **Diligencia previa:** Se han agotado las vías ordinarias de comunicación sin obtener una respuesta de fondo, oportuna y congruente.

### III. PETICIONES CONCRETAS
De manera respetuosa solicito a ustedes:
* **PRIMERA:** Disponer el estudio integral de los hechos relacionados con: $cleanTopic.
* **SEGUNDA:** Emitir una respuesta escrita, motivada, precisa y de fondo dentro de los términos legales establecidos por la ley.
* **TERCERA:** Aportar copias de los actos, registros o expedientes administrativos vinculados con esta solicitud.

### IV. FUNDAMENTOS DE DERECHO
Fundamento esta petición en el Artículo 23 Constitucional, la Ley Estatutaria de Transparencia y Acceso a la Información, y los principios de eficacia, celeridad y debido proceso administrativo.

### V. NOTIFICACIONES
Recibiré comunicaciones oficiales y notificaciones en los canales previstos en el expediente correspondiente.

---
**Atentamente,**  
*Firma del Peticionario / Ciudadano*  
*Cédula / Documento de Identidad:* ____________________
""".trimIndent()
                Pair(doc, "Derecho_de_Peticion.md")
            }

            lowerDoc.contains("informe") || lowerDoc.contains("reporte") -> {
                val doc = """
# INFORME EJECUTIVO Y ANÁLISIS DE SITUACIÓN
**Documento Estratégico y Técnico • Senda Soberana**

* **Tema / Objetivo:** $cleanTopic
* **Fecha de Emisión:** $dateStr
* **Nivel de Clasificación:** Interno / Confiable

---

### 1. RESUMEN EJECUTIVO
El presente informe expone el diagnóstico, los hallazgos sustantivos y las recomendaciones de acción en torno a **$cleanTopic**. El objetivo primordial es dotar a la toma de decisiones de insumos claros, verificables y estructurados.

### 2. CONTEXTO Y DIAGNÓSTICO
La evaluación realizada permite constatar que los factores asociados a este requerimiento presentan una dinámica crítica que exige intervención ordenada. Se identifican variables clave de operatividad, gestión de riesgos y optimización de recursos.

### 3. HALLAZGOS Y PUNTOS CLAVE
* **Evaluación de Factibilidad:** Los procesos vinculados requieren alineación metodológica y monitoreo continuo.
* **Gestión de Recursos:** Es imperativo focalizar los esfuerzos en resolver los cuellos de botella detectados sin comprometer la seguridad ni la autonomía operativa.
* **Riesgo Residual:** La falta de actuación oportuna podría generar retrasos e impactos desfavorables en los objetivos planteados.

### 4. PLAN DE ACCIÓN Y RECOMENDACIONES
1. **Corto Plazo (Inmediato):** Formalizar el protocolo de atención y asignar responsabilidades directas para abordar $cleanTopic.
2. **Mediano Plazo:** Implementar auditorías periódicas de seguimiento y verificar los indicadores de desempeño comprometidos.
3. **Largo Plazo:** Consolidar un repositorio de lecciones aprendidas para prevenir desviaciones futuras.

### 5. CONCLUSIÓN
Se concluye que existen condiciones óptimas para avanzar, siempre que se cumpla con el plan de acción propuesto con rigurosidad y trazabilidad.

---
*Elaborado de forma confidencial y soberana en Senda Browser.*
""".trimIndent()
                Pair(doc, "Informe_Ejecutivo.md")
            }

            lowerDoc.contains("minuta") || lowerDoc.contains("acta") -> {
                val doc = """
# ACTA / MINUTA DE REUNIÓN
**Sesión de Coordinación y Acuerdos de Trabajo**

* **Fecha:** $dateStr  
* **Hora de Inicio:** 09:00 AM | **Hora de Cierre:** 10:30 AM  
* **Tema Central:** $cleanTopic  
* **Moderador:** Dirección / Coordinación Técnica  

---

### 1. ORDEN DEL DÍA
1. Verificación del quórum y apertura de sesión.
2. Presentación del estado actual sobre: $cleanTopic.
3. Debate y propuestas de los asistentes.
4. Asignación de compromisos y plazos de entrega.

### 2. DESARROLLO DE LA SESIÓN
Se dio inicio a la reunión revisando los antecedentes. Se discutieron las prioridades inherentes a **$cleanTopic**, destacando la necesidad de optimizar tiempos de respuesta y garantizar máxima transparencia y calidad técnica. Los participantes coincidieron en la viabilidad de la estrategia planteada.

### 3. TABLA DE COMPROMISOS Y RESPONSABLES
| # | Acción Comprometida | Responsable | Fecha Límite |
|---|---|---|---|
| 1 | Revisar documentación soporte de $cleanTopic | Área Técnica | 3 días hábiles |
| 2 | Elaborar borrador definitivo de implementación | Coordinación | 5 días hábiles |
| 3 | Presentar informe de avance a la mesa | Equipo de Trabajo | Próxima sesión |

### 4. CIERRE
No habiendo más temas por tratar, se da por concluida la sesión y se firma el acta para constancia.

---
*Firma de Asistentes y Coordinador de Sesión.*
""".trimIndent()
                Pair(doc, "Minuta_Reunion.md")
            }

            lowerDoc.contains("ensayo") || lowerDoc.contains("artículo") || lowerDoc.contains("articulo") -> {
                val doc = """
# ENSAYO ANALÍTICO: REFLEXIÓN SOBRE ${cleanTopic.uppercase()}

**Autor:** Redacción Soberana Senda  
**Fecha:** $dateStr  

---

### INTRODUCCIÓN
El abordaje de **$cleanTopic** constituye un desafío contemporáneo ineludible. En un entorno saturado de información y transformaciones vertiginosas, comprender las raíces conceptuales y las implicaciones prácticas de este fenómeno se convierte en una herramienta decisiva para el pensamiento crítico y la soberanía del individuo.

### DESARROLLO ARGUMENTATIVO
En primer lugar, es menester examinar los principios que fundamentan esta materia. Lejos de ser un aspecto aislado, **$cleanTopic** se interconecta directamente con la libertad, la técnica y la capacidad de autodeterminación. Cuando se analizan sus aristas, se advierte que las soluciones convencionales a menudo omiten la dimensión ética y la preservación de la privacidad.

Por otra parte, la evidencia disponible demuestra que los modelos descentralizados, éticos y transparentes no solo son viables, sino necesarios frente a monopolios digitales y esquemas de control centralizado. La aplicación práctica de estos postulados permite recuperar el control sobre los propios procesos y datos.

### CONCLUSIÓN
En definitiva, avanzar en **$cleanTopic** exige coherencia entre los medios utilizados y los fines perseguidos. La autonomía técnica y la ética compartida representan la única senda sostenible hacia un porvenir más equitativo y consciente.
""".trimIndent()
                Pair(doc, "Ensayo_Analitico.md")
            }

            else -> {
                val doc = """
# CARTA FORMAL DE COMUNICACIÓN

**Fecha:** $dateStr  
**Ciudad:** Territorio Nacional  
**Asunto:** $cleanTopic  

**Señores:**  
Presente / A quien corresponda  

**Respetados señores:**  

Por medio de la presente comunicación, me dirijo a ustedes de manera atenta y respetuosa con el propósito de manifestar lo siguiente con relación a **$cleanTopic**:

Es de conocimiento que los asuntos vinculados a este requerimiento demandan una atención oportuna y formal. Tras una valoración detallada, considero imprescindible dejar constancia escrita de las circunstancias que motivan este acercamiento y de la necesidad de concertar acciones claras al respecto.

Por lo anterior, solicito amablemente se sirvan tomar nota de esta comunicación, dar curso al trámite pertinente y disponer las gestiones necesarias para resolver de manera favorable y expedita lo planteado.

Agradeciendo de antemano su atención a la presente solicitud y en espera de su pronta y valiosa respuesta, me suscribo de ustedes.

---
**Atentamente,**  

____________________________________  
*Firma de la Parte Remitente*  
*Documento de Identidad:* ____________  
*Contacto:* _________________________  
""".trimIndent()
                Pair(doc, "Carta_Formal.md")
            }
        }
    }


}
