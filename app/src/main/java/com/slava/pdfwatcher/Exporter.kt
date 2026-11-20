package com.slava.pdfwatcher

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Renders pages to PNG/JPG and saves them to Pictures/PDF Watcher/<document>. Uses its own renderer. */
class Exporter(private val ctx: Context, private val src: DocSource) {

    @Volatile var cancelled = false

    val folder: String = src.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(60).ifEmpty { "PDF" }

    fun run(pages: List<Int>, dpi: Int, jpeg: Boolean, onProgress: (done: Int, total: Int) -> Unit): List<Uri> {
        val pfd = src.openPfd(ctx)
        val renderer = try {
            PdfDoc.openRenderer(pfd, src.password)
        } catch (e: Exception) {
            pfd.close()
            throw e
        }
        val out = ArrayList<Uri>()
        val pad = renderer.pageCount.toString().length.coerceAtLeast(2)
        try {
            for ((n, i) in pages.withIndex()) {
                if (cancelled) break
                val bmp = render(renderer, i, dpi)
                try {
                    val num = (i + 1).toString().padStart(pad, '0')
                    out += save(bmp, "${folder}_$num.${if (jpeg) "jpg" else "png"}", jpeg)
                } finally {
                    bmp.recycle()
                }
                onProgress(n + 1, pages.size)
            }
        } finally {
            renderer.close()
        }
        return out
    }

    private fun render(r: PdfRenderer, i: Int, dpi: Int): Bitmap {
        val p = r.openPage(i)
        try {
            val w = p.width * dpi / 72f
            val h = p.height * dpi / 72f
            val s = min(1f, min(MAX_SIDE / max(w, h), sqrt(MAX_PX / (w * h))))
            val bw = (w * s).roundToInt().coerceAtLeast(1)
            val bh = (h * s).roundToInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            val m = Matrix().apply { setScale(bw / p.width.toFloat(), bh / p.height.toFloat()) }
            p.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        } finally {
            p.close()
        }
    }

    private fun save(bmp: Bitmap, fileName: String, jpeg: Boolean): Uri {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, if (jpeg) "image/jpeg" else "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/PDF Watcher/$folder")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IllegalStateException("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)!!.use {
                bmp.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, if (jpeg) 92 else 100, it)
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    companion object {
        private const val MAX_SIDE = 7000f
        private const val MAX_PX = 36_000_000f

        /** "1-3, 7, 10-12" -> zero-based page indexes, or null if it can't be read. */
        fun parseRange(text: String, count: Int): List<Int>? {
            val out = LinkedHashSet<Int>()
            for (part in text.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }) {
                val bits = part.split('-', '–', '—').map { it.trim() }
                when (bits.size) {
                    1 -> out += (bits[0].toIntOrNull() ?: return null) - 1
                    2 -> {
                        val a = bits[0].toIntOrNull() ?: 1
                        val b = bits[1].toIntOrNull() ?: count
                        for (p in min(a, b)..max(a, b)) out += p - 1
                    }
                    else -> return null
                }
            }
            return out.filter { it in 0 until count }.ifEmpty { null }
        }
    }
}
