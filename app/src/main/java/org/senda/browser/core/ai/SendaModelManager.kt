package org.senda.browser.core.ai

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

sealed class ModelDownloadState {
    object Idle : ModelDownloadState()
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long, val progressPercent: Int) : ModelDownloadState()
    object Verifying : ModelDownloadState()
    object Ready : ModelDownloadState()
    data class Error(val message: String) : ModelDownloadState()
}

/**
 * Gestor Soberano y Criptográfico del Ciclo de Vida de Modelos de IA en Senda Browser.
 * Administra el almacenamiento privado de archivos .gguf, verificación de espacio en disco,
 * integridad SHA-256 en streaming y desinstalación con un solo clic.
 */
object SendaModelManager {

    private const val MODELS_DIR = "models_ai"
    private const val STORAGE_SAFETY_MARGIN_BYTES = 120_000_000L // Margen de seguridad de 120 MB para el SO

    /**
     * Borra descargas de modelos que quedaron a medias (.tmp) porque la app se cerró mientras descargaba.
     * Se llama al arrancar el proceso, cuando ninguna descarga puede estar en curso.
     */
    fun cleanupInterruptedDownloads(context: Context) {
        File(context.filesDir, MODELS_DIR).listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
    }

    fun getModelsDirectory(context: Context): File {
        val dir = File(context.filesDir, MODELS_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getModelFile(context: Context, model: AiModelDefinition): File {
        return File(getModelsDirectory(context), model.fileName)
    }

    /**
     * Verifica si el archivo del modelo existe físicamente y tiene un tamaño válido.
     */
    fun isModelReady(context: Context, model: AiModelDefinition): Boolean {
        val file = getModelFile(context, model)
        return file.exists() && file.length() >= (model.sizeBytes * 0.90) // Margen de seguridad del tamaño
    }

    /**
     * Consulta el espacio libre disponible en la partición de almacenamiento privado de la app.
     */
    fun getAvailableStorageBytes(context: Context): Long {
        return try {
            val stat = StatFs(context.filesDir.path)
            stat.availableBytes
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
    }

    /**
     * Elimina el archivo del modelo para liberar espacio inmediatamente.
     */
    fun deleteModel(context: Context, model: AiModelDefinition): Boolean {
        val file = getModelFile(context, model)
        return if (file.exists()) {
            file.delete()
        } else {
            true
        }
    }

    /**
     * Descarga de forma segura y atómica el modelo a la memoria interna privada.
     * Incluye control previo de espacio en disco, streaming de hash SHA-256 y limpieza garantizada de temporales.
     */
    suspend fun downloadModel(
        context: Context,
        model: AiModelDefinition,
        onProgress: (ModelDownloadState) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val modelsDir = getModelsDirectory(context)
        val targetFile = getModelFile(context, model)
        val tempFile = File(modelsDir, "${model.fileName}.tmp")

        // 1. Auditoría previa de almacenamiento disponible
        val availableBytes = getAvailableStorageBytes(context)
        val requiredBytes = model.sizeBytes + STORAGE_SAFETY_MARGIN_BYTES
        if (availableBytes < requiredBytes) {
            val neededMb = (requiredBytes - availableBytes) / (1024 * 1024)
            onProgress(ModelDownloadState.Error("Espacio insuficiente en memoria interna (libera al menos $neededMb MB adicionales)"))
            return@withContext false
        }

        var connection: HttpURLConnection? = null
        try {
            onProgress(ModelDownloadState.Downloading(0L, model.sizeBytes, 0))

            connection = org.senda.browser.core.SendaNet.open(model.downloadUrl).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                onProgress(ModelDownloadState.Error("Servidor no disponible (Código HTTP $responseCode)"))
                return@withContext false
            }

            val totalBytes = if (connection.contentLengthLong > 0) connection.contentLengthLong else model.sizeBytes
            var downloadedBytes = 0L

            // Hashing en streaming con MessageDigest (0 sobrecosto de memoria)
            val digest = MessageDigest.getInstance("SHA-256")

            connection.inputStream.use { input: InputStream ->
                FileOutputStream(tempFile).use { output: FileOutputStream ->
                    val buffer = ByteArray(64 * 1024) // 64 KB buffer de alta eficiencia
                    var bytesRead: Int
                    var lastReportTime = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        digest.update(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastReportTime > 250) { // Actualizar UI cada 250ms sin congelar el hilo principal
                            lastReportTime = now
                            val percent = ((downloadedBytes * 100.0) / totalBytes).toInt().coerceIn(0, 99)
                            onProgress(ModelDownloadState.Downloading(downloadedBytes, totalBytes, percent))
                        }
                    }
                }
            }

            onProgress(ModelDownloadState.Verifying)

            // Comprobar que el archivo es exactamente el publicado (descarga corrupta o manipulada)
            val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actualSha256.equals(model.sha256Checksum, ignoreCase = true)) {
                tempFile.delete()
                onProgress(ModelDownloadState.Error("El archivo descargado no coincide con el original (SHA-256): se descartó"))
                return@withContext false
            }

            // Reemplazo atómico seguro del archivo temporal por el definitivo
            if (tempFile.renameTo(targetFile)) {
                onProgress(ModelDownloadState.Ready)
                true
            } else {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
                onProgress(ModelDownloadState.Ready)
                true
            }
        } catch (e: Exception) {
            tempFile.delete()
            onProgress(ModelDownloadState.Error("Error en la descarga: ${e.message ?: "Conexión interrumpida"}"))
            false
        } finally {
            connection?.disconnect()
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }
}
