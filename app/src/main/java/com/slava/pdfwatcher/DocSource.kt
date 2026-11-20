package com.slava.pdfwatcher

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Where a document comes from: the original Uri and/or our own copy in app storage. */
class DocSource(
    val uri: Uri?,
    val name: String,
    val size: Long,
    val key: String,
    @Volatile var localFile: File?,
    private val preferLocal: Boolean,
) {
    @Volatile var password: String? = null

    val title: String get() = name.removeSuffix(".pdf").removeSuffix(".PDF")

    /** A seekable descriptor for PdfRenderer. Pipes get copied to app storage first. */
    fun openPfd(ctx: Context): ParcelFileDescriptor {
        val local = localFile?.takeIf { it.exists() }
        if (local != null && (preferLocal || uri == null)) return openFile(local)
        try {
            val u = uri ?: throw FileNotFoundException("no source")
            val pfd = if (u.scheme == "file") {
                openFile(File(u.path ?: throw FileNotFoundException()))
            } else {
                ctx.contentResolver.openFileDescriptor(u, "r") ?: throw FileNotFoundException()
            }
            if (isSeekable(pfd)) return pfd
            pfd.close()
            return openFile(copyLocal(ctx))
        } catch (e: Exception) {
            if (local != null) return openFile(local)
            throw e
        }
    }

    fun openStream(ctx: Context): InputStream {
        localFile?.takeIf { it.exists() }?.let { return it.inputStream() }
        val u = uri ?: throw FileNotFoundException()
        return ctx.contentResolver.openInputStream(u) ?: throw FileNotFoundException()
    }

    /** Copies the original into app storage (used for pipes and for reopening from "recent"). */
    @Synchronized
    fun copyLocal(ctx: Context): File {
        val f = Recents.docFile(ctx, key)
        if (f.exists() && (size <= 0 || f.length() == size)) {
            localFile = f
            return f
        }
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "$key.tmp")
        val u = uri ?: throw FileNotFoundException()
        val input = ctx.contentResolver.openInputStream(u) ?: throw FileNotFoundException()
        input.use { inp -> tmp.outputStream().use { inp.copyTo(it, 1 shl 16) } }
        if (!tmp.renameTo(f)) {
            tmp.delete()
            throw IOException("rename failed")
        }
        localFile = f
        return f
    }

    companion object {
        private fun openFile(f: File) = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)

        private fun isSeekable(pfd: ParcelFileDescriptor): Boolean = try {
            Os.lseek(pfd.fileDescriptor, 0, OsConstants.SEEK_CUR)
            true
        } catch (e: ErrnoException) {
            false
        }

        fun fromUri(ctx: Context, uri: Uri): DocSource {
            var name: String? = null
            var size = -1L
            if (uri.scheme == "content") {
                try {
                    ctx.contentResolver.query(
                        uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null,
                    )?.use { c ->
                        if (c.moveToFirst()) {
                            val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                            val si = c.getColumnIndex(OpenableColumns.SIZE)
                            if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                        }
                    }
                } catch (_: Exception) {
                }
            } else if (uri.scheme == "file") {
                uri.path?.let { File(it) }?.let { name = it.name; size = it.length() }
            }
            val n = name?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: "Документ.pdf"
            val key = keyOf(n, size, uri)
            return DocSource(uri, n, size, key, Recents.docFile(ctx, key).takeIf { it.exists() }, preferLocal = false)
        }

        fun fromRecent(ctx: Context, r: Recent) = DocSource(
            r.uri?.let(Uri::parse), r.name, r.size, r.key,
            Recents.docFile(ctx, r.key).takeIf { it.exists() }, preferLocal = true,
        )

        private fun keyOf(name: String, size: Long, uri: Uri): String {
            val basis = if (size > 0) "$name|$size" else "$name|${uri}"
            val dig = MessageDigest.getInstance("SHA-1").digest(basis.toByteArray())
            return dig.take(10).joinToString("") { "%02x".format(it) }
        }
    }
}
