package org.senda.browser.core

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Descargas de Senda. El archivo siempre lo trae Gecko (la misma conexión que la página: Tor o proxy,
 * cookies de la sesión, modo privado, blob:) y Senda solo guarda el flujo en Descargas. Nunca se vuelve
 * a pedir por fuera con el DownloadManager de Android, que saldría sin proxy, con la IP real y sin sesión.
 */
object SendaDownloadManager {

    private val ioExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "senda-download") }
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Guarda la respuesta que Gecko no puede mostrar (onExternalResponse). */
    fun saveResponse(context: Context, prefs: PreferencesManager, response: WebResponse, isPrivate: Boolean) {
        val mime = response.headers["Content-Type"]?.substringBefore(";")?.trim()?.ifBlank { null }
        val name = fileNameFromDisposition(response.headers["Content-Disposition"])
            ?: fileNameFromUrl(response.uri)
        val length = response.headers["Content-Length"]?.trim()?.toLongOrNull() ?: 0L
        val body = response.body
        if (body == null) {
            toast(context) { it.dl_failed.format(name) }
            return
        }
        save(context.applicationContext, prefs, body, name, mime, response.uri, length, isPrivate)
    }

    /** Descarga [url] a través de Gecko (menú de pulsación larga: enlace, imagen o video). */
    fun downloadUrl(context: Context, prefs: PreferencesManager, url: String, isPrivate: Boolean, referrer: String? = null) {
        val appContext = context.applicationContext
        if (url.startsWith("data:")) {
            saveDataUri(appContext, prefs, url, isPrivate)
            return
        }
        val request = WebRequest.Builder(url).apply { referrer?.let { referrer(it) } }.build()
        val flags = if (isPrivate) GeckoWebExecutor.FETCH_FLAGS_PRIVATE else GeckoWebExecutor.FETCH_FLAGS_NONE
        GeckoWebExecutor(SendaGeckoEngine.getRuntime()).fetch(request, flags).accept(
            { response ->
                if (response == null || response.statusCode !in 200..299) {
                    toast(appContext) { it.dl_failed.format(fileNameFromUrl(url)) }
                } else {
                    saveResponse(appContext, prefs, response, isPrivate)
                }
            },
            { toast(appContext) { it.dl_failed.format(fileNameFromUrl(url)) } }
        )
    }

    private fun saveDataUri(context: Context, prefs: PreferencesManager, url: String, isPrivate: Boolean) {
        try {
            val header = url.substringAfter("data:").substringBefore(",")
            val payload = url.substringAfter(",")
            val mime = header.substringBefore(";").ifBlank { "application/octet-stream" }
            val bytes = if (header.endsWith(";base64")) Base64.decode(payload, Base64.DEFAULT)
            else URLDecoder.decode(payload, "UTF-8").toByteArray()
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
            save(context, prefs, bytes.inputStream(), "descarga_${System.currentTimeMillis()}.$ext", mime, "data:", bytes.size.toLong(), isPrivate)
        } catch (_: Exception) {
            toast(context) { it.dl_failed.format("data:") }
        }
    }

    private fun save(
        context: Context,
        prefs: PreferencesManager,
        body: InputStream,
        rawName: String,
        mimeType: String?,
        url: String,
        expectedBytes: Long,
        isPrivate: Boolean
    ) {
        val fileName = sanitizeFileName(rawName)
        val mime = mimeType?.takeIf { it != "application/octet-stream" } ?: getMimeType(fileName)
        val id = System.currentTimeMillis().toString()
        // En privado no queda rastro en la lista de Senda; el archivo sí se guarda porque el usuario lo pidió
        if (!isPrivate) {
            prefs.addDownload(
                DownloadItem(id = id, fileName = fileName, url = url, totalBytes = expectedBytes,
                    status = "DOWNLOADING", mimeType = mime)
            )
        }
        toast(context) { it.dl_started.format(fileName) }
        ioExecutor.execute {
            var target: Uri? = null
            try {
                val (uri, finalName) = createTarget(context, fileName, mime)
                target = uri
                var written = 0L
                body.use { input ->
                    context.contentResolver.openOutputStream(uri)!!.use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                        }
                    }
                }
                finishTarget(context, uri)
                // Android puede renombrar al publicar (« (1)» si ya existía): registrar el nombre real
                val savedName = displayName(context, uri) ?: finalName
                if (!isPrivate) {
                    prefs.updateDownload(
                        DownloadItem(id = id, fileName = savedName, url = url, filePath = uri.toString(),
                            totalBytes = written, downloadedBytes = written, status = "COMPLETED", mimeType = mime)
                    )
                }
                toast(context) { it.dl_completed.format(savedName) }
            } catch (e: Exception) {
                android.util.Log.w("SendaDownload", "Descarga fallida de $fileName: ${e.message}")
                target?.let { runCatching { context.contentResolver.delete(it, null, null) } }
                if (!isPrivate) {
                    prefs.updateDownload(
                        DownloadItem(id = id, fileName = fileName, url = url, status = "FAILED", mimeType = mime)
                    )
                }
                toast(context) { it.dl_failed.format(fileName) }
            }
        }
    }

    /** Crea el archivo en Descargas y devuelve su Uri y el nombre definitivo (Android añade « (1)» si ya existe). */
    private fun createTarget(context: Context, fileName: String, mime: String): Pair<Uri, String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore no creó el archivo")
            val finalName = context.contentResolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: fileName
            return uri to finalName
        }
        // Android 8-9: carpeta de descargas propia de la app (no requiere permiso de almacenamiento)
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        var file = File(dir, fileName)
        var n = 1
        while (file.exists()) {
            file = File(dir, "${fileName.substringBeforeLast('.')} ($n).${fileName.substringAfterLast('.', "bin")}")
            n++
        }
        file.createNewFile()
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file) to file.name
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private fun finishTarget(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.authority == MediaStore.AUTHORITY) {
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        }
    }

    fun openDownloadedFile(context: Context, item: DownloadItem) {
        val strings = SendaStrings.get("SYSTEM", context)
        try {
            val uri = if (item.filePath.startsWith("content://")) {
                Uri.parse(item.filePath)
            } else {
                // Descargas registradas por versiones anteriores de Senda (ruta de archivo)
                val file = File(item.filePath)
                if (!file.exists()) {
                    Toast.makeText(context, strings.dl_file_not_found, Toast.LENGTH_SHORT).show()
                    return
                }
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }
            val mime = item.mimeType.ifBlank { getMimeType(item.fileName) }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, strings.dl_open_with).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(context, strings.dl_no_app_to_open, Toast.LENGTH_LONG).show()
        }
    }

    fun deleteDownloadedFile(context: Context, prefs: PreferencesManager, item: DownloadItem, deleteFileFromDisk: Boolean) {
        if (deleteFileFromDisk) {
            try {
                if (item.filePath.startsWith("content://")) {
                    context.contentResolver.delete(Uri.parse(item.filePath), null, null)
                } else {
                    File(item.filePath).takeIf { it.exists() }?.delete()
                }
            } catch (_: Exception) {}
        }
        prefs.deleteDownload(item.id)
    }

    /** Admite filename*=UTF-8''… (RFC 5987), que es como mandan los nombres con acentos la mayoría de webs. */
    private fun fileNameFromDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        Regex("""filename\*\s*=\s*([\w-]+)'[^']*'([^;]+)""", RegexOption.IGNORE_CASE).find(header)?.let { m ->
            return runCatching { URLDecoder.decode(m.groupValues[2].trim().trim('"'), m.groupValues[1]) }.getOrNull()
        }
        Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE).find(header)?.let { m ->
            return m.groupValues[1].trim()
        }
        return null
    }

    private fun fileNameFromUrl(url: String): String {
        val segment = runCatching { Uri.parse(url).lastPathSegment }.getOrNull()
        return segment?.takeIf { it.isNotBlank() } ?: "descarga_${System.currentTimeMillis()}"
    }

    /** Quita rutas y caracteres prohibidos, pero conserva acentos, eñes y espacios. */
    private fun sanitizeFileName(raw: String): String =
        File(raw).name
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
            .replace("..", "_")
            .trim()
            .trimStart('.')
            .take(150)
            .ifBlank { "descarga_${System.currentTimeMillis()}" }

    private fun toast(context: Context, message: (SendaStringPack) -> String) {
        mainHandler.post {
            val strings = SendaStrings.get("SYSTEM", context)
            Toast.makeText(context, message(strings), Toast.LENGTH_SHORT).show()
        }
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "Desconocido"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(java.util.Locale.US, "%.2f GB", gb)
            mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f MB", mb)
            kb >= 1.0 -> String.format(java.util.Locale.US, "%.0f KB", kb)
            else -> "$bytes B"
        }
    }

    fun getMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }
}
