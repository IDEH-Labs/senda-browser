package org.senda.browser.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Archivos que el usuario elige para subir a una página (<input type="file">). GeckoView no siempre puede leer
 * un content:// de otra app, así que, como Firefox para Android, se copian a la caché privada de Senda y se
 * le pasa la copia. Las copias se borran al arrancar la app.
 */
object SendaWebUploads {

    private const val DIR = "web_uploads"

    fun cleanup(context: Context) {
        File(context.cacheDir, DIR).deleteRecursively()
    }

    /** Copia cada archivo a una carpeta propia de esta elección (nombres repetidos no se pisan). */
    suspend fun copyToCache(context: Context, uris: List<Uri>): List<Uri> = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "$DIR/${System.nanoTime()}").apply { mkdirs() }
        uris.mapIndexedNotNull { i, uri ->
            try {
                val target = File(dir, "${i}_${displayName(context, uri)}")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { input.copyTo(it) }
                } ?: return@mapIndexedNotNull null
                Uri.fromFile(target)
            } catch (e: Exception) {
                android.util.Log.w("Senda", "No se pudo preparar el archivo para subir: ${e.javaClass.simpleName}")
                null
            }
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        val raw = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
        // Solo el nombre, sin rutas ni caracteres que el sistema de archivos no admite
        return raw?.substringAfterLast('/')?.replace(Regex("[\\\\/:*?\"<>|\\u0000]"), "_")?.take(120)
            ?.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "archivo"
    }
}
