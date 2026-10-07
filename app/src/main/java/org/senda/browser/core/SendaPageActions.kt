package org.senda.browser.core

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.Executors

/**
 * Actions on the current page that need Android: print or save as PDF and add a shortcut
 * to the home screen.
 */
object SendaPageActions {

    private val ioExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "senda-print") }
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Opens Android's print dialog with the PDF Gecko generated. From there you print or choose
     * "Save as PDF". The temporary PDF lives in Senda's private cache and is deleted when the dialog closes.
     */
    fun print(activity: Activity, pdf: InputStream, title: String) {
        val strings = SendaStrings.forApp(activity)
        ioExecutor.execute {
            val dir = File(activity.cacheDir, "print").apply { mkdirs() }
            // Leftovers from an earlier print job that never finished
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "senda_${System.currentTimeMillis()}.pdf")
            val ok = try {
                pdf.use { input -> FileOutputStream(file).use { input.copyTo(it) } }
                file.length() > 0
            } catch (_: Exception) {
                false
            }
            mainHandler.post {
                if (!ok || activity.isFinishing || activity.isDestroyed) {
                    file.delete()
                    if (!ok) Toast.makeText(activity, strings.page_print_failed, Toast.LENGTH_SHORT).show()
                    return@post
                }
                val jobName = sanitize(title).ifBlank { "Senda" }
                val manager = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
                try {
                    manager.print(jobName, PdfFileAdapter(file, "$jobName.pdf"), null)
                } catch (_: Exception) {
                    file.delete()
                    Toast.makeText(activity, strings.page_print_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private class PdfFileAdapter(private val file: File, private val name: String) : PrintDocumentAdapter() {
        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes?,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback,
            extras: Bundle?
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback.onLayoutCancelled()
                return
            }
            val info = PrintDocumentInfo.Builder(name)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .build()
            callback.onLayoutFinished(info, oldAttributes != newAttributes)
        }

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback
        ) {
            try {
                file.inputStream().use { input ->
                    FileOutputStream(destination.fileDescriptor).use { input.copyTo(it) }
                }
                if (cancellationSignal?.isCanceled == true) callback.onWriteCancelled()
                else callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback.onWriteFailed(e.message)
            }
        }

        override fun onFinish() {
            file.delete()
        }
    }

    /**
     * Asks Android to pin a shortcut that opens [url] in Senda. Android shows its own
     * confirmation; Senda adds nothing unless the user accepts it.
     */
    fun addToHomeScreen(context: Context, url: String, title: String, icon: Bitmap?) {
        val strings = SendaStrings.forApp(context)
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            Toast.makeText(context, strings.page_shortcut_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        val label = sanitize(title).ifBlank { Uri.parse(url).host ?: url }.take(40)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .setClassName(context, "org.senda.browser.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val shortcut = ShortcutInfoCompat.Builder(context, "page_" + url.hashCode().toUInt().toString(16))
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(IconCompat.createWithAdaptiveBitmap(icon ?: letterIcon(label)))
            .setIntent(intent)
            .build()
        val requested = try {
            ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
        } catch (_: Exception) {
            false
        }
        if (!requested) Toast.makeText(context, strings.page_shortcut_unsupported, Toast.LENGTH_LONG).show()
    }

    /** Adaptive icon with the site's initial: no favicon is downloaded to create it. */
    private fun letterIcon(label: String): Bitmap {
        val size = 216
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val hue = (label.lowercase().hashCode() and 0x7fffffff) % 360
        canvas.drawColor(android.graphics.Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.45f, 0.55f)))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = size * 0.36f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val letter = label.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "S"
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(letter, size / 2f, y, paint)
        return bitmap
    }

    private fun sanitize(text: String): String =
        text.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), " ").replace(Regex("\\s+"), " ").trim().take(80)
}
