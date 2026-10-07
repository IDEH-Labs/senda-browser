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
 * Senda's downloads. Gecko always fetches the file (the same connection as the page: Tor or proxy,
 * session cookies, private mode, blob:) and Senda only saves the stream to Downloads. It is never
 * requested again separately with Android's DownloadManager, which would go out without the proxy, with the real IP and no session.
 */
object SendaDownloadManager {

    private val ioExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "senda-download") }
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * System location picker (registered by MainActivity). Receives a suggested name and MIME type and
     * returns the chosen Uri, or null if the user cancelled. Without it the file is saved directly to Downloads.
     */
    @Volatile
    var locationPicker: ((fileName: String, mime: String, onResult: (Uri?) -> Unit) -> Unit)? = null

    fun suggestedFileName(response: WebResponse): String =
        sanitizeFileName(fileNameFromDisposition(response.headers["Content-Disposition"]) ?: fileNameFromUrl(response.uri))

    /** Saves the response Gecko cannot display (onExternalResponse). */
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

    /** Downloads [url] through Gecko (long-press menu: link, image or video). */
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
        // In private mode nothing is left in Senda's list; the file is saved because the user asked for it
        if (!isPrivate) {
            prefs.addDownload(
                DownloadItem(id = id, fileName = fileName, url = url, totalBytes = expectedBytes,
                    status = "DOWNLOADING", mimeType = mime)
            )
        }
        val picker = locationPicker
        if (prefs.askDownloadLocation && picker != null) {
            // "Ask where to save": the user picks folder and name; if they cancel, nothing is saved
            mainHandler.post {
                picker(fileName, mime) { uri ->
                    if (uri == null) {
                        runCatching { body.close() }
                        if (!isPrivate) prefs.deleteDownload(id)
                        toast(context) { it.dl_cancelled.format(fileName) }
                    } else {
                        toast(context) { it.dl_started.format(fileName) }
                        ioExecutor.execute { write(context, prefs, body, id, uri, fileName, mime, url, isPrivate, ownsTarget = false) }
                    }
                }
            }
            return
        }
        toast(context) { it.dl_started.format(fileName) }
        ioExecutor.execute {
            val (uri, finalName) = try {
                createTarget(context, fileName, mime)
            } catch (e: Exception) {
                android.util.Log.w("SendaDownload", "No se pudo crear $fileName: ${e.message}")
                runCatching { body.close() }
                markFailed(context, prefs, id, fileName, url, mime, isPrivate)
                return@execute
            }
            write(context, prefs, body, id, uri, finalName, mime, url, isPrivate, ownsTarget = true)
        }
    }

    /**
     * Copies [body] into [uri]. If it fails and Senda created the file ([ownsTarget]) it is deleted; one chosen by the
     * user is left alone, because the document provider may have overwritten a file that already existed.
     */
    private fun write(
        context: Context,
        prefs: PreferencesManager,
        body: InputStream,
        id: String,
        uri: Uri,
        fileName: String,
        mime: String,
        url: String,
        isPrivate: Boolean,
        ownsTarget: Boolean
    ) {
        try {
            var written = 0L
            body.use { input ->
                context.contentResolver.openOutputStream(uri, "wt")!!.use { output ->
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
            // Android may rename on publish (" (1)" if it already existed): record the real name
            val savedName = displayName(context, uri) ?: fileName
            if (!isPrivate) {
                prefs.updateDownload(
                    DownloadItem(id = id, fileName = savedName, url = url, filePath = uri.toString(),
                        totalBytes = written, downloadedBytes = written, status = "COMPLETED", mimeType = mime)
                )
            }
            toast(context) { it.dl_completed.format(savedName) }
        } catch (e: Exception) {
            android.util.Log.w("SendaDownload", "Descarga fallida de $fileName: ${e.message}")
            if (ownsTarget) runCatching { context.contentResolver.delete(uri, null, null) }
            markFailed(context, prefs, id, fileName, url, mime, isPrivate)
        }
    }

    private fun markFailed(context: Context, prefs: PreferencesManager, id: String, fileName: String, url: String, mime: String, isPrivate: Boolean) {
        if (!isPrivate) {
            prefs.updateDownload(DownloadItem(id = id, fileName = fileName, url = url, status = "FAILED", mimeType = mime))
        }
        toast(context) { it.dl_failed.format(fileName) }
    }

    /** Creates the file in Downloads and returns its Uri and final name (Android adds " (1)" if it already exists). */
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
        // Android 8-9: the app's own downloads folder (needs no storage permission)
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
        val strings = SendaStrings.forApp(context)
        try {
            val uri = if (item.filePath.startsWith("content://")) {
                Uri.parse(item.filePath)
            } else {
                // Downloads recorded by earlier versions of Senda (file path)
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

    /** Accepts filename*=UTF-8''… (RFC 5987), which is how most sites send names with accents. */
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

    /** Removes paths and forbidden characters, but keeps accents, ñ and spaces. */
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
            val strings = SendaStrings.forApp(context)
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
