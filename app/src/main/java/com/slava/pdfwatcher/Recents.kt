package com.slava.pdfwatcher

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Recent(
    val key: String,
    val name: String,
    val uri: String?,
    val size: Long,
    val opened: Long,
    val pages: Int,
    val page: Int = 0,
    val frac: Float = 0f,
    val zoom: Float = 1f,
)

/** Recently opened files plus the reading position of each one. */
object Recents {
    private const val MAX = 30
    private const val PREFS = "recents"

    fun docFile(ctx: Context, key: String) = File(ctx.filesDir, "docs/$key.pdf")
    fun thumbFile(ctx: Context, key: String) = File(ctx.filesDir, "thumbs/$key.jpg")

    @Synchronized
    fun all(ctx: Context): List<Recent> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("list", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getJSONObject(it).toRecent() }.sortedByDescending { it.opened }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun get(ctx: Context, key: String) = all(ctx).firstOrNull { it.key == key }

    /** Files we can still open: our own copy exists, or the system kept our read access. */
    fun openable(ctx: Context): List<Recent> {
        val persisted = ctx.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }.toSet()
        return all(ctx).filter { docFile(ctx, it.key).exists() || (it.uri != null && Uri.parse(it.uri) in persisted) }
    }

    @Synchronized
    fun put(ctx: Context, r: Recent) {
        val list = all(ctx).filter { it.key != r.key }.toMutableList()
        list.add(0, r)
        while (list.size > MAX) {
            val gone = list.removeAt(list.size - 1)
            docFile(ctx, gone.key).delete()
            thumbFile(ctx, gone.key).delete()
        }
        save(ctx, list)
    }

    @Synchronized
    fun update(ctx: Context, key: String, f: (Recent) -> Recent) {
        val list = all(ctx).map { if (it.key == key) f(it) else it }
        save(ctx, list)
    }

    @Synchronized
    fun remove(ctx: Context, key: String) {
        save(ctx, all(ctx).filter { it.key != key })
        docFile(ctx, key).delete()
        thumbFile(ctx, key).delete()
    }

    private fun save(ctx: Context, list: List<Recent>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("list", arr.toString()).apply()
    }

    private fun Recent.toJson() = JSONObject().apply {
        put("key", key); put("name", name); put("uri", uri ?: JSONObject.NULL); put("size", size)
        put("opened", opened); put("pages", pages); put("page", page)
        put("frac", frac.toDouble()); put("zoom", zoom.toDouble())
    }

    private fun JSONObject.toRecent() = Recent(
        key = getString("key"),
        name = getString("name"),
        uri = if (isNull("uri")) null else getString("uri"),
        size = optLong("size", -1),
        opened = optLong("opened"),
        pages = optInt("pages"),
        page = optInt("page"),
        frac = optDouble("frac", 0.0).toFloat(),
        zoom = optDouble("zoom", 1.0).toFloat(),
    )
}
