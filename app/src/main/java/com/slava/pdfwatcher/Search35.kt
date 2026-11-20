package com.slava.pdfwatcher

import android.annotation.TargetApi
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer

/** Text search, available in the platform renderer since Android 15. */
@TargetApi(35)
object Search35 {
    /** Each match is a list of rects (one per line it spans), in PDF points. */
    fun search(r: PdfRenderer, page: Int, query: String): List<List<RectF>> {
        val p = r.openPage(page)
        try {
            return p.searchText(query).map { m -> m.bounds.map { RectF(it) } }
        } finally {
            p.close()
        }
    }
}
