package com.slava.pdfwatcher

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.LoadParams
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A page rendered at "fit width" size. [reqW] is the width that was asked for. */
class BaseBitmap(val reqW: Int, val bmp: Bitmap)

/** A sharp piece of a page rendered for the current zoom. [docRect] is in layout px at zoom 1. */
class Tile(val page: Int, val docRect: RectF, val bmp: Bitmap)

class TileReq(
    val page: Int,
    val pageWz: Float,  // page width in px at the requested zoom
    val left: Int, val top: Int, val width: Int, val height: Int,  // page-local px at that zoom
    val docRect: RectF,
)

class DetailRequest(val gen: Int, val tiles: List<TileReq>)

/**
 * Owns a PdfRenderer and the single thread allowed to touch it.
 * Work order: visible pages > sharp detail > misc jobs (search, thumbs) > prefetch.
 */
class PdfDoc(private val pfd: ParcelFileDescriptor, private val renderer: PdfRenderer) {

    val pageCount = renderer.pageCount
    val ptsW = FloatArray(pageCount)
    val ptsH = FloatArray(pageCount)

    init {
        for (i in 0 until pageCount) {
            val p = renderer.openPage(i)
            try {
                ptsW[i] = p.width.toFloat().coerceAtLeast(1f)
                ptsH[i] = p.height.toFloat().coerceAtLeast(1f)
            } finally {
                p.close()
            }
        }
    }

    val cache = object : LruCache<Int, BaseBitmap>(CACHE_BYTES) {
        override fun sizeOf(key: Int, value: BaseBitmap) = value.bmp.byteCount
    }

    var onBaseReady: ((Int) -> Unit)? = null
    var onDetailReady: ((Int, List<Tile>) -> Unit)? = null

    private val lock = Object()
    private var visible = IntArray(0)
    private var prefetch = IntArray(0)
    @Volatile private var baseW = 0
    private var detail: DetailRequest? = null
    @Volatile private var latestDetailGen = 0
    private val jobs = ArrayDeque<(PdfRenderer) -> Unit>()
    private val failed = HashSet<Int>()
    private var closed = false
    private val main = Handler(Looper.getMainLooper())
    private val thread = Thread({ loop() }, "pdf-render")

    fun start() = thread.start()

    fun request(vis: IntArray, pre: IntArray, w: Int) = synchronized(lock) {
        visible = vis
        prefetch = pre
        if (w != baseW) {
            baseW = w
            cache.evictAll()
            failed.clear()
        }
        lock.notifyAll()
    }

    fun requestDetail(r: DetailRequest?) = synchronized(lock) {
        detail = r
        latestDetailGen = r?.gen ?: (latestDetailGen + 1)
        lock.notifyAll()
    }

    fun post(job: (PdfRenderer) -> Unit) = synchronized(lock) {
        jobs.addLast(job)
        lock.notifyAll()
    }

    fun close() = synchronized(lock) {
        closed = true
        jobs.clear()
        lock.notifyAll()
    }

    private fun needsBase(i: Int, w: Int): Boolean {
        if (i in failed) return false
        val b = cache.get(i)
        return b == null || b.reqW != w
    }

    private fun loop() {
        try {
            while (true) {
                var task: (() -> Unit)? = null
                synchronized(lock) {
                    while (task == null) {
                        if (closed) return
                        val w = baseW
                        if (w > 0) {
                            visible.firstOrNull { needsBase(it, w) }?.let { i -> task = { renderBase(i, w) } }
                        }
                        if (task == null) {
                            detail?.let { d -> detail = null; task = { renderDetail(d) } }
                        }
                        if (task == null) {
                            jobs.removeFirstOrNull()?.let { j -> task = { j(renderer) } }
                        }
                        if (task == null && w > 0) {
                            prefetch.firstOrNull { needsBase(it, w) }?.let { i -> task = { renderBase(i, w) } }
                        }
                        if (task == null) lock.wait()
                    }
                }
                try {
                    task!!.invoke()
                } catch (t: Throwable) {
                    Log.w(TAG, "render task failed", t)
                }
            }
        } finally {
            try { renderer.close() } catch (_: Throwable) {}
            try { pfd.close() } catch (_: Throwable) {}
            cache.evictAll()
        }
    }

    private fun renderBase(i: Int, w: Int) {
        var bw = w
        var bh = (w * ptsH[i] / ptsW[i]).roundToInt().coerceAtLeast(1)
        val px = bw.toLong() * bh
        if (px > MAX_BASE_PX) {
            val s = sqrt(MAX_BASE_PX.toDouble() / px)
            bw = (bw * s).toInt().coerceAtLeast(1)
            bh = (bh * s).toInt().coerceAtLeast(1)
        }
        val bmp = try {
            Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            cache.evictAll()
            Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        }
        bmp.eraseColor(Color.WHITE)
        try {
            val p = renderer.openPage(i)
            try {
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            } finally {
                p.close()
            }
        } catch (t: Throwable) {
            synchronized(lock) { failed.add(i) }
            throw t
        }
        if (w == baseW) cache.put(i, BaseBitmap(w, bmp))
        main.post { onBaseReady?.invoke(i) }
    }

    private fun renderDetail(d: DetailRequest) {
        val out = ArrayList<Tile>(d.tiles.size)
        val m = Matrix()
        for (t in d.tiles) {
            if (d.gen != latestDetailGen) return
            if (t.width <= 0 || t.height <= 0) continue
            val bmp = Bitmap.createBitmap(t.width, t.height, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            val s = t.pageWz / ptsW[t.page]
            m.setScale(s, s)
            m.postTranslate(-t.left.toFloat(), -t.top.toFloat())
            val p = renderer.openPage(t.page)
            try {
                p.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            } finally {
                p.close()
            }
            out.add(Tile(t.page, t.docRect, bmp))
        }
        if (d.gen != latestDetailGen) return
        main.post { onDetailReady?.invoke(d.gen, out) }
    }

    companion object {
        private const val TAG = "PdfDoc"
        private const val CACHE_BYTES = 96 * 1024 * 1024
        private const val MAX_BASE_PX = 4_500_000L

        /** Opens a renderer; throws SecurityException when the file needs a (different) password. */
        fun openRenderer(pfd: ParcelFileDescriptor, password: String?): PdfRenderer =
            if (password != null && Build.VERSION.SDK_INT >= 35) {
                PdfRenderer(pfd, LoadParams.Builder().setPassword(password).build())
            } else {
                PdfRenderer(pfd)
            }
    }
}
