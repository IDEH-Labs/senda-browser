# Senda Browser

> **Navega tu propio camino. Sin rastros.**

**Senda** es un navegador web libre, ligero y ético para Android, construido con el motor independiente **GeckoView** (de Mozilla) y endurecido con las directivas de privacidad del proyecto **LibreWolf**.

---

## 🛡️ Pilares Éticos y Técnicos

1. **Motor Independiente:** Basado en GeckoView, libre de la hegemonía de Google Chromium y de WebViews propietarios.
2. **Privacidad LibreWolf por Defecto:**
   - Sin telemetría.
   - Sin Privacy Preserving Attribution (PPA) ni intermediarios publicitarios.
   - Aislamiento estricto de cookies por sitio (*Total Cookie Protection*).
3. **Soberanía Visual Total:**
   - Barra de herramientas reubicable a voluntad (Abajo, Arriba o Flotante).
   - Paleta de color y acento libre (código hexadecimal abierto).
   - Modo negro puro OLED (#000000) para máxima autonomía energética.
4. **Libertad de Extensiones:**
   - Instalador directo de archivos `.xpi` locales desde el almacenamiento del dispositivo, sin pasar por filtros ni requerir cuentas de Mozilla.
5. **Herramientas de Desarrollador Móvil (DevTools):**
   - Consola JavaScript e inspector web integrado directamente en pantalla, sin necesidad de cables USB ni comandos ADB.
6. **Bóveda Física:**
   - Bloqueo opcional mediante huella dactilar (`Android BiometricPrompt`).
   - Pantalla protegida contra capturas y vista de apps recientes (`FLAG_SECURE`).

---

## 📦 Compilación desde el Código Fuente

Requisitos:
- Java JDK 17 o superior.
- Android SDK (API 35).

```bash
# Clonar el repositorio
git clone https://codeberg.org/tu-usuario/senda.git
cd senda

# Compilar versión debug
./gradlew assembleDebug

# El APK se generará en:
# app/build/outputs/apk/debug/app-debug.apk
```

---

## 📜 Licencia y Atribución

Este proyecto está licenciado bajo la **GNU General Public License v3 (GPLv3)**.

- El motor **GeckoView** es desarrollado por Mozilla bajo la licencia **MPL 2.0**.
- Las políticas de privacidad y endurecimiento están inspiradas en el proyecto comunitario **LibreWolf** y **Arkenfox**.
