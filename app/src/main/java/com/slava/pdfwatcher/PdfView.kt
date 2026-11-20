package com.slava.pdfwatcher

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Continuous vertical PDF view with pinch / double-tap zoom, fling and a draggable fast scroller. */
@SuppressLint("ViewConstructor")
class PdfView(context: Context) : View(context) {

    interface Listener {
        fun onPageChanged(page: Int, count: Int) {}
        fun onSingleTap() {}
        fun onUserScroll(dy: Float) {}
    }

    data class Pos(val page: Int, val frac: Float, val zoom: Float)

    var listener: Listener? = null

    private val d = resources.displayMetrics.density
    private var doc: PdfDoc? = null

    // Layout at zoom 1, in px. All pages share the same width.
    private var pTop = FloatArray(0)
    private var pH = FloatArray(0)
    private var pLeft = 0f
    private var pW = 0f
    private var totalH = 0f
    private var laidW = 0
    private var topPad = 0f

    private var insetTop = 0f
    private var insetBottom = 0f

    private var zoom = 1f
    private var sx = 0f
    private var sy = 0f
    private var currentPage = -1
    private var pendingPos: Pos? = null

    private var tiles: List<Tile> = emptyList()
    private var detailGen = 0

    private var highlights: Map<Int, List<List<RectF>>> = emptyMap()
    private var activeMatch: Pair<Int, List<RectF>>? = null

    var night = false
        set(v) {
            field = v
            bmpPaint.colorFilter = if (v) NIGHT_FILTER else null
            pagePaint.color = if (v) NIGHT_PAGE else 0xFFFFFFFF.toInt()
            canvasColor = if (v) NIGHT_CANVAS else dayCanvas
            invalidate()
        }

    private val dayCanvas = context.getColor(R.color.canvas)
    private var canvasColor = dayCanvas
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        setShadowLayer(3f * d, 0f, 1f * d, context.getColor(R.color.page_shadow))
    }
    private val hlPaint = Paint().apply { color = 0x66FFD54F }
    private val hlActivePaint = Paint().apply { color = 0x99FF8A3D.toInt() }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.accent) }
    private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 16f * d
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val tmp = RectF()

    private val scroller = OverScroller(context)
    private var scaling = false
    private var quickScaled = false
    private var lastFx = 0f
    private var lastFy = 0f
    private var zoomAnim: ValueAnimator? = null

    // Fast scroller
    private var thumbAlpha = 0f
    private var thumbAnim: ValueAnimator? = null
    private var draggingThumb = false
    private var grabDy = 0f
    private val thumbH = 52f * d
    private val hideThumb = Runnable { fadeThumb(0f) }

    private val detailRunnable = Runnable { requestDetail() }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaling = true
            quickScaled = true
            zoomAnim?.cancel()
            scroller.forceFinished(true)
            lastFx = detector.focusX
            lastFy = detector.focusY
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            // Follow the fingers while pinching (two-finger pan), then zoom around the focus.
            sx += lastFx - detector.focusX
            sy += lastFy - detector.focusY
            lastFx = detector.focusX
            lastFy = detector.focusY
            zoomTo(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            scaling = false
            scheduleDetail()
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaling) return false
            scrollBy2(dx, dy)
            listener?.onUserScroll(dy)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scaling || quickScaled) return false
            scroller.fling(sx.toInt(), sy.toInt(), -vx.toInt(), -vy.toInt(), 0, maxX().toInt(), 0, maxY().toInt())
            postInvalidateOnAnimation()
            return true
        }

        override fun onDoubleTapEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP && !quickScaled) {
                animateZoom(if (zoom < 1.6f) 2.5f else 1f, e.x, e.y)
            }
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            listener?.onSingleTap()
            return true
        }
    })

    // ---- public API ----

    fun setDocument(doc: PdfDoc, pos: Pos?) {
        this.doc = doc
        doc.onBaseReady = { invalidate() }
        doc.onDetailReady = { gen, t ->
            if (gen == detailGen) {
                tiles = t
                invalidate()
            }
        }
        pendingPos = pos
        laidW = 0
        relayout()
    }

    fun setInsets(top: Float, bottom: Float) {
        if (top == insetTop && bottom == insetBottom) return
        insetTop = top
        insetBottom = bottom
        relayout()
    }

    val pageCount get() = doc?.pageCount ?: 0
    val page get() = currentPage.coerceAtLeast(0)

    fun position(): Pos? {
        if (laidW == 0 || pTop.isEmpty()) return pendingPos
        val docY = sy / zoom
        val i = pageAt(docY)
        return Pos(i, (docY - pTop[i]) / pH[i], zoom)
    }

    fun goToPage(i: Int) {
        if (pTop.isEmpty()) return
        val p = i.coerceIn(0, pTop.size - 1)
        scroller.forceFinished(true)
        sy = pTop[p] * zoom - topPad
        clamp()
        onViewport()
    }

    fun setHighlights(all: Map<Int, List<List<RectF>>>, active: Pair<Int, List<RectF>>?) {
        highlights = all
        activeMatch = active
        invalidate()
    }

    /** Centers a search match on screen. [rect] is in PDF points. */
    fun showMatch(page: Int, rect: RectF) {
        val doc = doc ?: return
        if (page !in pTop.indices) return
        val s = pW / doc.ptsW[page]
        val docY = pTop[page] + rect.centerY() * s
        val docX = pLeft + rect.centerX() * s
        scroller.forceFinished(true)
        sy = docY * zoom - height / 2f
        if (zoom > 1.01f) sx = docX * zoom - width / 2f
        clamp()
        onViewport()
    }

    // ---- layout ----

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        relayout()
    }

    private fun relayout() {
        val doc = doc ?: return
        val w = width
        if (w == 0 || height == 0) return
        val anchor = if (laidW > 0) position() else pendingPos
        val side = 6f * d
        val gap = 10f * d
        pW = min(w - 2 * side, 1000f * d)
        pLeft = (w - pW) / 2f
        topPad = insetTop + 64f * d
        val n = doc.pageCount
        pTop = FloatArray(n)
        pH = FloatArray(n)
        var y = topPad
        for (i in 0 until n) {
            pTop[i] = y
            pH[i] = pW * doc.ptsH[i] / doc.ptsW[i]
            y += pH[i] + gap
        }
        totalH = y - gap + insetBottom + 84f * d
        laidW = w
        tiles = emptyList()
        pendingPos = null
        if (anchor != null && n > 0) {
            val p = anchor.page.coerceIn(0, n - 1)
            zoom = anchor.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
            sy = (pTop[p] + anchor.frac * pH[p]) * zoom
        }
        clamp()
        onViewport()
    }

    private fun maxX() = max(0f, laidW * zoom - width)
    private fun maxY() = max(0f, totalH * zoom - height)

    private fun clamp() {
        sx = sx.coerceIn(0f, maxX())
        sy = sy.coerceIn(0f, maxY())
    }

    /** Index of the last page whose top is at or above [docY]. */
    private fun pageAt(docY: Float): Int {
        var lo = 0
        var hi = pTop.size - 1
        if (hi < 0) return 0
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (pTop[mid] <= docY) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun visibleRange(): IntRange {
        if (pTop.isEmpty()) return IntRange.EMPTY
        val top = sy / zoom
        val bot = (sy + height) / zoom
        var first = pageAt(top)
        if (pTop[first] + pH[first] < top && first < pTop.size - 1) first++
        val last = pageAt(bot)
        return first..max(first, last)
    }

    // ---- viewport ----

    private fun onViewport() {
        val doc = doc ?: return
        invalidate()
        if (pTop.isEmpty()) return
        val vis = visibleRange()
        val n = pTop.size
        val pre = intArrayOf(vis.last + 1, vis.last + 2, vis.first - 1, vis.last + 3, vis.first - 2)
            .filter { it in 0 until n }.toIntArray()
        doc.request(vis.toList().toIntArray(), pre, pW.roundToInt())

        val page = when {
            sy <= 1f -> 0
            sy >= maxY() - 1f -> n - 1
            else -> pageAt((sy + height * 0.45f) / zoom)
        }
        if (page != currentPage) {
            currentPage = page
            listener?.onPageChanged(page, n)
            contentDescription = "Сторінка ${page + 1} з $n"
        }
        if (maxY() > height * 2.5f) showThumb()
        scheduleDetail()
    }

    private fun scheduleDetail() {
        removeCallbacks(detailRunnable)
        postDelayed(detailRunnable, 90)
    }

    private fun requestDetail() {
        val doc = doc ?: return
        if (scaling || !scroller.isFinished || zoomAnim?.isRunning == true || pTop.isEmpty()) return
        val gen = ++detailGen
        if (zoom <= 1.02f) {
            tiles = emptyList()
            doc.requestDetail(null)
            invalidate()
            return
        }
        val reqs = ArrayList<TileReq>()
        for (i in visibleRange()) {
            val l = pLeft * zoom - sx
            val t = pTop[i] * zoom - sy
            val r = l + pW * zoom
            val b = t + pH[i] * zoom
            val il = max(l, 0f)
            val it = max(t, 0f)
            val ir = min(r, width.toFloat())
            val ib = min(b, height.toFloat())
            if (ir <= il || ib <= it) continue
            val left = floor(il - l).toInt()
            val top = floor(it - t).toInt()
            val w = ceil(ir - l).toInt() - left
            val h = ceil(ib - t).toInt() - top
            val docRect = RectF(
                pLeft + left / zoom, pTop[i] + top / zoom,
                pLeft + (left + w) / zoom, pTop[i] + (top + h) / zoom,
            )
            reqs.add(TileReq(i, pW * zoom, left, top, w, h, docRect))
        }
        doc.requestDetail(DetailRequest(gen, reqs))
    }

    // ---- zoom & scroll ----

    private fun scrollBy2(dx: Float, dy: Float) {
        sx += dx
        sy += dy
        clamp()
        onViewport()
    }

    private fun zoomTo(target: Float, fx: Float, fy: Float) {
        val z = target.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val docX = (fx + sx) / zoom
        val docY = (fy + sy) / zoom
        zoom = z
        sx = docX * zoom - fx
        sy = docY * zoom - fy
        clamp()
        onViewport()
    }

    private fun animateZoom(target: Float, fx: Float, fy: Float) {
        zoomAnim?.cancel()
        scroller.forceFinished(true)
        zoomAnim = ValueAnimator.ofFloat(zoom, target).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { zoomTo(it.animatedValue as Float, fx, fy) }
            start()
        }
        postDelayed({ scheduleDetail() }, 240)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            sx = scroller.currX.toFloat()
            sy = scroller.currY.toFloat()
            clamp()
            onViewport()
            postInvalidateOnAnimation()
        }
    }

    // ---- fast scroller ----

    private fun trackTop() = insetTop + 72f * d
    private fun trackBottom() = height - insetBottom - 24f * d
    private fun thumbTop(): Float {
        val my = maxY()
        val f = if (my > 0) sy / my else 0f
        return trackTop() + (trackBottom() - trackTop() - thumbH) * f
    }

    private fun showThumb() {
        removeCallbacks(hideThumb)
        if (thumbAlpha < 1f && thumbAnim?.isRunning != true) fadeThumb(1f)
        if (!draggingThumb) postDelayed(hideThumb, 1400)
    }

    private fun fadeThumb(to: Float) {
        thumbAnim?.cancel()
        thumbAnim = ValueAnimator.ofFloat(thumbAlpha, to).apply {
            duration = if (to > 0f) 120 else 300
            addUpdateListener { thumbAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun hitThumb(x: Float, y: Float): Boolean {
        if (thumbAlpha < 0.5f || maxY() <= height * 2.5f) return false
        val t = thumbTop()
        return x >= width - 40f * d && y >= t - 16f * d && y <= t + thumbH + 16f * d
    }

    // ---- touch ----

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (doc == null) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                quickScaled = false
                if (hitThumb(e.x, e.y)) {
                    draggingThumb = true
                    grabDy = e.y - thumbTop()
                    scroller.forceFinished(true)
                    removeCallbacks(hideThumb)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                    return true
                }
            }
        }
        if (draggingThumb) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val span = trackBottom() - trackTop() - thumbH
                    val f = if (span > 0) ((e.y - grabDy - trackTop()) / span).coerceIn(0f, 1f) else 0f
                    sy = f * maxY()
                    clamp()
                    onViewport()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    draggingThumb = false
                    postDelayed(hideThumb, 1400)
                    scheduleDetail()
                    invalidate()
                }
            }
            return true
        }
        scaleDetector.onTouchEvent(e)
        gestureDetector.onTouchEvent(e)
        return true
    }

    // ---- drawing ----

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(canvasColor)
        val doc = doc ?: return
        if (laidW == 0 || pTop.isEmpty()) return
        val vis = visibleRange()
        for (i in vis) {
            tmp.set(pLeft * zoom - sx, pTop[i] * zoom - sy, (pLeft + pW) * zoom - sx, (pTop[i] + pH[i]) * zoom - sy)
            canvas.drawRect(tmp, pagePaint)
            doc.cache.get(i)?.let { canvas.drawBitmap(it.bmp, null, tmp, bmpPaint) }
        }
        for (t in tiles) {
            if (t.page !in vis) continue
            tmp.set(t.docRect.left * zoom - sx, t.docRect.top * zoom - sy, t.docRect.right * zoom - sx, t.docRect.bottom * zoom - sy)
            canvas.drawBitmap(t.bmp, null, tmp, bmpPaint)
        }
        if (highlights.isNotEmpty()) {
            for (i in vis) {
                val s = pW / doc.ptsW[i] * zoom
                val ox = pLeft * zoom - sx
                val oy = pTop[i] * zoom - sy
                highlights[i]?.forEach { match ->
                    val paint = if (activeMatch?.first == i && activeMatch?.second === match) hlActivePaint else hlPaint
                    for (r in match) {
                        tmp.set(ox + r.left * s, oy + r.top * s, ox + r.right * s, oy + r.bottom * s)
                        canvas.drawRect(tmp, paint)
                    }
                }
            }
        }
        drawThumb(canvas)
    }

    private fun drawThumb(canvas: Canvas) {
        if (thumbAlpha <= 0.01f || maxY() <= height * 2.5f) return
        val t = thumbTop()
        val w = if (draggingThumb) 8f * d else 5f * d
        val right = width - 5f * d
        thumbPaint.color = if (draggingThumb) context.getColor(R.color.accent) else 0x99888890.toInt()
        thumbPaint.alpha = (thumbPaint.alpha * thumbAlpha).roundToInt()
        tmp.set(right - w, t, right, t + thumbH)
        canvas.drawRoundRect(tmp, w / 2, w / 2, thumbPaint)
        if (draggingThumb) {
            val label = "${page + 1}"
            val bw = max(56f * d, bubbleText.measureText(label) + 28f * d)
            val bh = 40f * d
            val cy = t + thumbH / 2
            tmp.set(right - w - 14f * d - bw, cy - bh / 2, right - w - 14f * d, cy + bh / 2)
            canvas.drawRoundRect(tmp, bh / 2, bh / 2, bubblePaint)
            val fm = bubbleText.fontMetrics
            canvas.drawText(label, tmp.centerX(), cy - (fm.ascent + fm.descent) / 2, bubbleText)
        }
    }

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 8f
        private const val NIGHT_PAGE = 0xFF1E1E21.toInt()
        private const val NIGHT_CANVAS = 0xFF0C0C0E.toInt()

        /** Inverts lightness but keeps hues (invert + 180° hue turn), mapped to a soft dark range. */
        private val NIGHT_FILTER: ColorMatrixColorFilter = run {
            val h = arrayOf(
                floatArrayOf(-0.574f, 1.430f, 0.144f),
                floatArrayOf(0.426f, 0.430f, 0.144f),
                floatArrayOf(0.426f, 1.430f, -0.856f),
            )
            val k = 0.765f
            val off = 30f + k * 255f
            val m = FloatArray(20)
            for (r in 0..2) {
                for (c in 0..2) m[r * 5 + c] = -k * h[r][c]
                m[r * 5 + 4] = off
            }
            m[18] = 1f
            ColorMatrixColorFilter(ColorMatrix(m))
        }
    }
}
