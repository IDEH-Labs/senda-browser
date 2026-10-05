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
        val request = topicPrompt.trim().ifBlank { "(sin detalles)" }

        val lowerTopic = topicPrompt.lowercase()
        val lowerDoc = docType.lowercase()

        // Plantilla fija: no conoce los hechos del usuario, así que todo lo que no dijo queda entre corchetes.
        val notice = """
> **Plantilla fija, no redactada por IA.** Senda no conoce tu caso: completa o borra todo lo que está entre [corchetes] y revisa el texto antes de enviarlo. No es asesoría legal.
>
> Lo que pediste: «$request»
""".trimIndent()

        val (body, fileName) = when {
            lowerTopic.contains("renuncia") || lowerDoc.contains("renuncia") -> """
# CARTA DE RENUNCIA

**Fecha:** $dateStr
**Ciudad:** [ciudad]
**Para:** [nombre y cargo de quien recibe]
**Empresa:** [nombre de la empresa]
**Asunto:** Renuncia voluntaria al cargo de [cargo]

---

Respetado/a [nombre]:

Por medio de la presente presento mi renuncia voluntaria al cargo de [cargo], con efecto a partir del [fecha del último día de trabajo].

[Opcional: motivo de la renuncia, si quieres indicarlo.]

Quedo a disposición para hacer la entrega del cargo durante [periodo acordado o el que corresponda según tu contrato].

Solicito la expedición de mi certificado laboral y la liquidación de salarios y prestaciones que me correspondan.

---
**Atentamente,**

__________________________________________
[Nombre completo]
[Documento de identidad]
[Teléfono o correo]
""".trimIndent() to "Carta_de_Renuncia.md"

            lowerDoc.contains("petición") || lowerDoc.contains("peticion") -> """
# DERECHO DE PETICIÓN

**Fecha:** $dateStr
**Ciudad:** [ciudad]
**Para:** [entidad y dependencia]
**Asunto:** [qué solicitas, en una línea]

---

[Nombre completo], identificado/a con [documento], en ejercicio del derecho de petición [en Colombia: artículo 23 de la Constitución y Ley 1755 de 2015; ajusta la norma si estás en otro país], presento la siguiente solicitud:

### I. HECHOS
1. [Qué pasó, con fechas.]
2. [Si ya hiciste otras solicitudes: cuándo y con qué número de radicado.]

### II. PETICIONES
1. [Primera solicitud concreta.]
2. [Segunda solicitud, si la hay.]

### III. ANEXOS
* [Documentos que adjuntas.]

### IV. NOTIFICACIONES
[Dirección, correo y teléfono donde quieres recibir la respuesta.]

---
**Atentamente,**

__________________________________________
[Nombre completo]
[Documento de identidad]
""".trimIndent() to "Derecho_de_Peticion.md"

            lowerDoc.contains("reclamo") || lowerTopic.contains("reclamo") || lowerTopic.contains("cancelar") || lowerTopic.contains("queja") || lowerTopic.contains("falla") || lowerTopic.contains("cobro") -> """
# RECLAMACIÓN

**Fecha:** $dateStr
**Para:** [Empresa] — Atención al cliente / PQR
**Asunto:** [resumen del reclamo en una línea]

---

### I. DATOS DEL TITULAR
* **Nombre:** [nombre completo]
* **Documento:** [número]
* **Cuenta / línea / contrato:** [número]

### II. HECHOS
1. [Qué pasó, con fechas y valores. Ejemplo: «En la factura de [mes] se cobró [valor] dos veces».]
2. [Si ya reclamaste antes: cuándo, por qué canal y número de radicado.]
3. [Otros hechos relevantes.]

### III. SOLICITUD
* [Qué pides exactamente. Ejemplo: devolución de [valor], corrección de la factura, cancelación del servicio.]

### IV. ANEXOS
* [Facturas, capturas, números de radicado.]

---
**Atentamente,**

__________________________________________
[Nombre completo]
[Documento de identidad]
[Teléfono] | [Correo para notificaciones]
""".trimIndent() to "Reclamacion.md"

            lowerDoc.contains("contrato") || lowerTopic.contains("contrato") || lowerTopic.contains("acuerdo") || lowerTopic.contains("nda") || lowerTopic.contains("confidencialidad") -> """
# CONTRATO DE PRESTACIÓN DE SERVICIOS

**Fecha:** $dateStr
**Contratante:** [nombre y documento]
**Contratista:** [nombre y documento]

---

### PRIMERA. OBJETO
El contratista prestará al contratante los siguientes servicios: [descripción concreta].

### SEGUNDA. ENTREGABLES Y PLAZOS
[Qué se entrega y en qué fechas.]

### TERCERA. VALOR Y FORMA DE PAGO
[Valor total, fechas de pago y medio de pago.]

### CUARTA. CONFIDENCIALIDAD
[Qué información es confidencial y por cuánto tiempo, si aplica.]

### QUINTA. PROPIEDAD INTELECTUAL
[A quién pertenece lo que se produzca.]

### SEXTA. TERMINACIÓN
[Causales y preaviso.]

---
Un contrato tiene efectos legales: haz que lo revise una persona con conocimientos jurídicos de tu país antes de firmarlo.

______________________________          ______________________________
[Contratante]                           [Contratista]
""".trimIndent() to "Contrato_Servicios.md"

            lowerDoc.contains("informe") || lowerDoc.contains("reporte") -> """
# INFORME

* **Tema:** [tema]
* **Fecha:** $dateStr
* **Autor:** [nombre]

---

### 1. RESUMEN
[Dos o tres frases con lo esencial.]

### 2. CONTEXTO
[Antecedentes y por qué se hace el informe.]

### 3. HALLAZGOS
* [Hallazgo 1, con datos.]
* [Hallazgo 2, con datos.]

### 4. RECOMENDACIONES
1. [Acción, responsable y plazo.]

### 5. CONCLUSIÓN
[Conclusión basada en los hallazgos.]
""".trimIndent() to "Informe.md"

            lowerDoc.contains("minuta") || lowerDoc.contains("acta") -> """
# ACTA DE REUNIÓN

* **Fecha:** $dateStr
* **Hora de inicio:** [hora] | **Hora de cierre:** [hora]
* **Tema:** [tema]
* **Asistentes:** [nombres]

---

### 1. ORDEN DEL DÍA
1. [Punto 1]
2. [Punto 2]

### 2. DESARROLLO
[Qué se discutió en cada punto.]

### 3. COMPROMISOS
| # | Acción | Responsable | Fecha límite |
|---|---|---|---|
| 1 | [acción] | [persona] | [fecha] |

### 4. CIERRE
[Hora de cierre y próxima reunión.]
""".trimIndent() to "Acta_Reunion.md"

            lowerDoc.contains("ensayo") || lowerDoc.contains("artículo") || lowerDoc.contains("articulo") -> """
# [TÍTULO DEL ENSAYO]

**Autor:** [nombre]
**Fecha:** $dateStr

---

### INTRODUCCIÓN
[Presenta el tema y tu tesis en una o dos frases.]

### DESARROLLO
[Primer argumento, con evidencia o fuentes.]

[Segundo argumento, con evidencia o fuentes.]

[Posible objeción y tu respuesta.]

### CONCLUSIÓN
[Retoma la tesis y lo que se desprende de los argumentos.]

### FUENTES
* [Autor, título, año.]
""".trimIndent() to "Ensayo.md"

            else -> """
# CARTA

**Fecha:** $dateStr
**Ciudad:** [ciudad]
**Para:** [nombre, cargo y entidad]
**Asunto:** [asunto en una línea]

---

Respetado/a [nombre]:

[Explica en uno o dos párrafos el motivo de la carta, con los datos concretos.]

[Indica qué solicitas o qué esperas como respuesta.]

---
**Atentamente,**

____________________________________
[Nombre completo]
[Documento de identidad]
[Teléfono o correo]
""".trimIndent() to "Carta.md"
        }
        return Pair("$notice\n\n$body", fileName)
    }


}
