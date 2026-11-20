package com.slava.pdfwatcher

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** Hands our stored PDF copies to other apps (share, print). URI: content://AUTH/<key>/<display name> */
class ShareProvider : ContentProvider() {

    override fun onCreate() = true

    private fun fileFor(uri: Uri): File? {
        val seg = uri.pathSegments
        if (seg.size < 2 || !seg[0].matches(KEY_RE)) return null
        return Recents.docFile(context ?: return null, seg[0]).takeIf { it.exists() }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val f = fileFor(uri) ?: return null
        val cols = (projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            .filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }
        val row = cols.map { if (it == OpenableColumns.DISPLAY_NAME) uri.lastPathSegment else f.length() }
        return MatrixCursor(cols.toTypedArray(), 1).apply { addRow(row) }
    }

    override fun getType(uri: Uri) = "application/pdf"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = fileFor(uri) ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    companion object {
        private const val AUTH = "com.slava.pdfwatcher.share"
        private val KEY_RE = Regex("[0-9a-f]{8,40}")

        fun uriFor(key: String, name: String): Uri =
            Uri.Builder().scheme("content").authority(AUTH).appendPath(key).appendPath(name).build()
    }
}
