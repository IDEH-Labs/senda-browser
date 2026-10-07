# Senda

**Navegador web libre y privado para Android, con el motor GeckoView de Mozilla.**

*Free and private web browser for Android built on Mozilla GeckoView. Interface in 8 languages: Spanish, English, German, French, Portuguese, Italian, Japanese and Chinese.*

> **Estado: alfa avanzada (0.1.0-alpha).** Su autor la usa a diario, pero todavía **no se recomienda para datos sensibles**:
> - Probada en un solo modelo de teléfono (Motorola moto g34 5G, Android 15).
> - La versión de publicación (optimizada con R8) aún no se ha probado en un dispositivo, y no hay APK firmado oficial.
> - No ha pasado una auditoría de seguridad externa.
>
> Los informes de errores son bienvenidos.

---

## Funciones

**Navegación**
- Motor GeckoView 157 (Mozilla), independiente de Chromium.
- Pestañas normales y privadas separadas, con vista en cuadrícula o lista; al abrir, página de inicio limpia, continuar donde lo dejaste o empezar de cero.
- Marcadores, historial y descargas; buscar en la página; versión de escritorio; imprimir o guardar como PDF; acceso directo en la pantalla de inicio de Android.
- Modo lectura en 8 idiomas, con «frases clave» y búsqueda dentro del texto (sin conexión: solo muestra frases literales del artículo).
- Traducción de páginas en el teléfono, con el traductor local de Gecko (el texto no sale del teléfono).
- Página de inicio con accesos directos y cuatro diseños (Enfocado, Inspirador, Informativo y Personalizado); 64 fondos de pantalla con licencia libre.

**Privacidad y seguridad**
- Protección estricta contra rastreo, cookies de terceros aisladas, limpieza de parámetros de rastreo en las URL y Global Privacy Control.
- uBlock Origin integrado.
- Modo solo HTTPS y DNS sobre HTTPS (activados por defecto).
- Red Tor integrada (opcional) o proxy SOCKS5/HTTP propio.
- Bóveda de contraseñas cifrada con el almacén de claves del teléfono: exige huella o PIN, y rellena y guarda las cuentas en las páginas.
- Bloqueo de la app con huella y protección contra capturas de pantalla (opcionales).

**Otras**
- Transmisión a la TV mediante la duplicación de pantalla de Android (Miracast), con modo TV opcional al girar el teléfono.
- Asistente opcional: con tu plan de ChatGPT o con tu propia clave de API (Claude, Gemini, Grok o Mistral). Solo envía algo cuando tú lo usas, y únicamente al servicio que elijas.
- Extensiones desde archivos `.xpi`, CSS y scripts propios (Senda Labs), y consola de desarrollo en el propio teléfono.

## Conexiones que hace Senda por su cuenta

Para que sepas exactamente qué sale de tu teléfono sin que lo pidas:

- **Safe Browsing (Google)**, activado por defecto: descarga listas de sitios peligrosos. Se puede desactivar en Ajustes.
- **uBlock Origin**: actualiza periódicamente sus listas de filtros desde los servidores de cada lista.
- **Noticias de la página de inicio**, en los diseños «Inspirador» e «Informativo», y en «Personalizado» si activas las noticias: lee los RSS de MuyLinux, EFF y FSF. El diseño «Enfocado» no muestra noticias ni las descarga.
- **Traducción**: la primera vez que traduces un idioma, descarga de Mozilla el modelo de ese idioma. La traducción se hace después en el teléfono.

Senda no incluye telemetría ni informes de uso. La detección de portal cautivo de Firefox está desactivada.

## Compilar

Requisitos: JDK 17 y Android SDK con la plataforma 37. El APK solo incluye `arm64-v8a`, y la app funciona desde Android 8.0 (API 26).

```bash
git clone https://github.com/IDEH-Labs/senda-browser.git
cd senda-browser
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

**Versión de publicación.** Sin clave, `./gradlew assembleRelease` genera un APK **sin firmar**: nunca se usa la clave de depuración. Para firmarla, define `KEYSTORE_PATH`, `KEY_ALIAS`, `STORE_PASSWORD` y `KEY_PASSWORD`, o crea `keystore.properties` en la raíz con `storeFile`, `keyAlias`, `storePassword` y `keyPassword`. Ese archivo está en `.gitignore`.

## Pruebas

Las pruebas son instrumentadas (`app/src/androidTest`) y necesitan un teléfono conectado.

```bash
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w org.senda.browser.test/androidx.test.runner.AndroidJUnitRunner
```

> **Cuidado:** `./gradlew connectedAndroidTest` desinstala la app y **borra sus datos** (contraseñas, historial…). Las pruebas que borran datos solo se ejecutan si se pasa `-e allowDestructive 1`. Las de ChatGPT y Gemini usan cuentas reales y consumen cuota.

## Licencia

Senda es software libre bajo la **GNU General Public License v3** (ver [`LICENSE`](LICENSE)). Los componentes de terceros y sus licencias están en [`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md).

---

Un proyecto de **[IDEH Labs](https://github.com/IDEH-Labs)** · [GitHub](https://github.com/IDEH-Labs/senda-browser) · [GitLab](https://gitlab.com/ideh-labs/senda-browser)
