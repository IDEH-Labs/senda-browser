package org.senda.browser.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Files the user picks to upload to a page (<input type="file">). GeckoView cannot always read
 * a content:// URI from another app, so, like Firefox for Android, they are copied to Senda's private cache and
 * the copy is passed on. The copies are deleted when the app starts.
 */
object SendaWebUploads {

    private const val DIR = "web_uploads"

    fun cleanup(context: Context) {
        File(context.cacheDir, DIR).deleteRecursively()
    }

    /** Copies each file into a folder of its own for this selection (repeated names do not overwrite each other). */
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
        // Only the name, without paths or characters the file system does not allow
        return raw?.substringAfterLast('/')?.replace(Regex("[\\\\/:*?\"<>|\\u0000]"), "_")?.take(120)
            ?.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "archivo"
    }
}
