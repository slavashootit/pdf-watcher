package com.slava.pdfwatcher

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update from GitHub Releases (no Play Store).
 * Checks releases/latest at most every 6 h, compares tag_name with versionName,
 * downloads the .apk asset into cacheDir/update and hands it to the system installer
 * through [UpdateProvider]. The APK must be signed with the same release.keystore.
 */
private const val DIALOG_THEME = 0

object Updater {
    const val REPO = "slavashootit/pdf-watcher"
    private const val PREFS = "updater"
    private const val CHECK_EVERY_MS = 6L * 60 * 60 * 1000

    data class Release(val version: String, val apkUrl: String, val size: Long, val notes: String)

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var checking = false

    fun currentVersion(ctx: Context): String =
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" }

    /** "1.10.2" > "1.9" — numeric, part by part; non-digits ignored. */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(s: String) = s.trim().removePrefix("v").removePrefix("V").split('.', '-', '+')
            .map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        val a = parts(remote); val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Cached newer release (from the last successful check), or null. */
    fun cachedUpdate(ctx: Context): Release? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val v = p.getString("version", null) ?: return null
        val url = p.getString("url", null) ?: return null
        if (!isNewer(v, currentVersion(ctx))) return null
        return Release(v, url, p.getLong("size", 0), p.getString("notes", "") ?: "")
    }

    /**
     * Calls [done] on the main thread with the newer release (or null when up to date).
     * [error] is non-null when the check failed. Without [force] the network is hit at most every 6 h.
     */
    fun check(ctx: Context, force: Boolean, done: (Release?, String?) -> Unit) {
        val app = ctx.applicationContext
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val fresh = System.currentTimeMillis() - p.getLong("checkedAt", 0) < CHECK_EVERY_MS
        if ((!force && fresh) || checking) { done(cachedUpdate(app), null); return }
        checking = true
        Thread {
            var rel: Release? = null
            var err: String? = null
            try {
                val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 15_000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("User-Agent", "${app.packageName}-updater")
                val code = c.responseCode
                if (code == 404) {
                    p.edit().putLong("checkedAt", System.currentTimeMillis()).remove("version").apply()
                } else if (code != 200) {
                    err = "GitHub відповів $code"
                } else {
                    val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                    val tag = j.optString("tag_name").removePrefix("v").removePrefix("V")
                    val assets = j.optJSONArray("assets")
                    var url: String? = null; var size = 0L
                    if (assets != null) for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                            url = a.optString("browser_download_url"); size = a.optLong("size"); break
                        }
                    }
                    val e = p.edit().putLong("checkedAt", System.currentTimeMillis())
                    if (tag.isNotEmpty() && url != null) {
                        e.putString("version", tag).putString("url", url).putLong("size", size)
                            .putString("notes", j.optString("body"))
                    } else e.remove("version")
                    e.apply()
                    rel = cachedUpdate(app)
                }
                c.disconnect()
            } catch (e: Exception) {
                err = "Немає зв'язку з GitHub"
            }
            checking = false
            main.post { done(rel, err) }
        }.start()
    }

    private fun apkFile(ctx: Context, version: String) =
        File(File(ctx.cacheDir, "update").apply { mkdirs() }, "update-$version.apk")

    /** «Є оновлення X» dialog with release notes → [startUpdate]. */
    fun offer(act: Activity, rel: Release) {
        val notes = rel.notes.replace("\r", "").lines()
            .filterNot { it.isBlank() || it.startsWith("#") }
            .joinToString("\n") { it.removePrefix("- ").removePrefix("* ").let { l -> "• $l" } }
            .take(600)
        AlertDialog.Builder(act, DIALOG_THEME)
            .setTitle("Є оновлення ${rel.version}")
            .setMessage(if (notes.isBlank()) "Зараз у тебе ${currentVersion(act)}." else notes)
            .setPositiveButton("Оновити") { _, _ -> startUpdate(act, rel) }
            .setNegativeButton("Пізніше", null)
            .show()
    }

    /** Full flow for the «Оновити» button: permission → download (with progress) → installer. */
    fun startUpdate(act: Activity, rel: Release) {
        if (!act.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(act, DIALOG_THEME)
                .setTitle("Дозволь встановлювати оновлення")
                .setMessage(
                    "Android дозволяє ставити застосунки не з Play Маркету лише з дозволу.\n\n" +
                        "На наступному екрані увімкни «Дозволити з цього джерела», повернись назад " +
                        "і ще раз натисни «Оновити»."
                )
                .setPositiveButton("Відкрити налаштування") { _, _ ->
                    try {
                        act.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}"))
                        )
                    } catch (_: Exception) {
                        act.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                    }
                }
                .setNegativeButton("Не зараз", null)
                .show()
            return
        }
        val f = apkFile(act, rel.version)
        if (f.exists() && (rel.size <= 0 || f.length() == rel.size) && verify(act, f, rel.version)) {
            install(act, f); return
        }
        val msg = android.widget.TextView(act).apply {
            text = "Починаю…"
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            textSize = 16f
        }
        var cancelled = false
        val dlg = AlertDialog.Builder(act, DIALOG_THEME)
            .setTitle("Завантажую ${rel.version}")
            .setView(msg)
            .setNegativeButton("Скасувати") { _, _ -> cancelled = true }
            .setCancelable(false)
            .show()
        Thread {
            val tmp = File(f.parentFile, f.name + ".part")
            var ok = false
            var err = "Не вдалося завантажити"
            try {
                f.parentFile?.listFiles()?.forEach { if (it.name != f.name) it.delete() }
                val c = URL(rel.apkUrl).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000; c.readTimeout = 30_000
                c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", "${act.packageName}-updater")
                val total = c.contentLengthLong.takeIf { it > 0 } ?: rel.size
                c.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L; var lastPct = -1
                        while (!cancelled) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            val pct = if (total > 0) (done * 100 / total).toInt() else -1
                            if (pct != lastPct) {
                                lastPct = pct
                                val mb = "%.1f".format(done / 1048576.0)
                                main.post { msg.text = if (pct >= 0) "$pct %  ·  $mb МБ" else "$mb МБ" }
                            }
                        }
                    }
                }
                c.disconnect()
                if (!cancelled) {
                    if (rel.size > 0 && tmp.length() != rel.size) err = "Файл завантажився не повністю"
                    else if (!tmp.renameTo(f)) err = "Не вдалося зберегти файл"
                    else if (!verify(act, f, rel.version)) { err = "Файл оновлення пошкоджений"; f.delete() }
                    else ok = true
                }
            } catch (_: Exception) {
                err = "Немає зв'язку — спробуй ще раз"
            }
            tmp.delete()
            main.post {
                if (act.isFinishing || act.isDestroyed) return@post
                try { dlg.dismiss() } catch (_: Exception) {}
                when {
                    cancelled -> Unit
                    ok -> install(act, f)
                    else -> AlertDialog.Builder(act, DIALOG_THEME).setTitle("Оновлення").setMessage(err)
                        .setPositiveButton("Добре", null).show()
                }
            }
        }.start()
    }

    /** Same package and the expected version — guards against a wrong or truncated file. */
    private fun verify(ctx: Context, f: File, version: String): Boolean {
        val info = ctx.packageManager.getPackageArchiveInfo(f.path, 0) ?: return false
        return info.packageName == ctx.packageName && !isNewer(version, info.versionName ?: "0")
    }

    private fun install(act: Activity, f: File) {
        val uri = Uri.Builder().scheme("content").authority(act.packageName + ".update").appendPath(f.name).build()
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            act.startActivity(i)
        } catch (_: Exception) {
            AlertDialog.Builder(act, DIALOG_THEME).setTitle("Оновлення")
                .setMessage("Не знайшов системний встановлювач застосунків.")
                .setPositiveButton("Добре", null).show()
        }
    }
}

/** Serves the downloaded update APK to the system package installer (read-only). */
class UpdateProvider : ContentProvider() {
    override fun onCreate() = true

    private fun fileFor(uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        if (!name.matches(Regex("update-[0-9A-Za-z.\\-]+\\.apk"))) return null
        return File(File(context?.cacheDir ?: return null, "update"), name).takeIf { it.exists() }
    }

    override fun getType(uri: Uri) = "application/vnd.android.package-archive"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = fileFor(uri) ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val f = fileFor(uri) ?: return null
        val cols = (projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            .filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }
        val row = cols.map { if (it == OpenableColumns.DISPLAY_NAME) f.name else f.length() }
        return MatrixCursor(cols.toTypedArray(), 1).apply { addRow(row) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
