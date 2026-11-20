package com.slava.pdfwatcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var list: LinearLayout
    private lateinit var recentLabel: TextView
    private lateinit var empty: LinearLayout
    private lateinit var updateBanner: LinearLayout
    private lateinit var updateTitle: TextView
    private lateinit var versionRow: TextView

    private val uk = Locale("uk")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)

        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(20), dpi(28), dpi(20), dpi(24))
        }
        scroll.addView(col)

        col.addView(TextView(this).apply {
            text = "PDF Watcher"
            setTextColor(getColor(R.color.text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            letterSpacing = -0.02f
        })
        col.addView(TextView(this).apply {
            text = "Швидкий перегляд PDF. Без реклами. Оновлюється сам."
            setTextColor(getColor(R.color.text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dpi(4), 0, dpi(24))
        })

        updateBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(getColor(R.color.surface), dp(18f))
            setPadding(dpi(16), dpi(12), dpi(12), dpi(12))
            visibility = View.GONE
        }
        updateTitle = TextView(this).apply {
            setTextColor(getColor(R.color.text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        updateBanner.addView(updateTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        updateBanner.addView(TextView(this).apply {
            text = "Оновити"
            setTextColor(getColor(R.color.on_accent))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(dpi(18), 0, dpi(18), 0)
            background = ripple(rounded(getColor(R.color.accent), dp(14f)))
            setOnClickListener { Updater.cachedUpdate(this@MainActivity)?.let { Updater.startUpdate(this@MainActivity, it) } }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpi(44)))
        col.addView(updateBanner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dpi(16)
        })

        val open = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = ripple(rounded(getColor(R.color.accent), dp(18f)))
            elevation = dp(2f)
            isClickable = true
            contentDescription = "Відкрити PDF"
            setOnClickListener { pickFile() }
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_folder)
                imageTintList = ColorStateList.valueOf(getColor(R.color.on_accent))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dpi(22), dpi(22)).apply { marginEnd = dpi(10) })
            addView(TextView(this@MainActivity).apply {
                text = "Відкрити PDF"
                setTextColor(getColor(R.color.on_accent))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        }
        col.addView(open, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(58)))

        recentLabel = TextView(this).apply {
            text = "Нещодавні"
            setTextColor(getColor(R.color.text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.04f
            setPadding(dpi(4), dpi(30), 0, dpi(10))
        }
        col.addView(recentLabel)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)

        empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(getColor(R.color.surface), dp(18f))
            setPadding(dpi(20), dpi(18), dpi(20), dpi(20))
        }
        empty.addView(TextView(this).apply {
            text = "Як зробити PDF Watcher головним"
            setTextColor(getColor(R.color.text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        empty.addView(TextView(this).apply {
            text = "Відкрий будь-який PDF — у Telegram, пошті чи «Моїх файлах». " +
                "У вікні «Відкрити за допомогою» обери PDF Watcher і натисни «Завжди». " +
                "Відтепер усі PDF відкриватимуться тут."
            setTextColor(getColor(R.color.text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(0f, 1.25f)
            setPadding(0, dpi(6), 0, 0)
        })
        col.addView(empty, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dpi(8)
        })

        col.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        versionRow = TextView(this).apply {
            text = "PDF Watcher ${Updater.currentVersion(this@MainActivity)}  ·  Перевірити оновлення"
            setTextColor(getColor(R.color.text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(0, dpi(14), 0, dpi(14))
            background = ripple(null, rounded(getColor(R.color.surface), dp(14f)))
            setOnClickListener { checkUpdates(manual = true) }
        }
        col.addView(versionRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dpi(24)
        })

        scroll.setOnApplyWindowInsetsListener { v, ins ->
            val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            ins
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        checkUpdates(manual = false)
    }

    private fun showUpdate(rel: Updater.Release?) {
        updateBanner.visibility = if (rel == null) View.GONE else View.VISIBLE
        if (rel != null) updateTitle.text = "Є оновлення ${rel.version}"
    }

    private fun checkUpdates(manual: Boolean) {
        if (manual) versionRow.text = "Перевіряю…"
        Updater.check(this, force = manual) { rel, err ->
            if (isDestroyed) return@check
            showUpdate(rel)
            if (!manual) return@check
            versionRow.text = "PDF Watcher ${Updater.currentVersion(this)}  ·  Перевірити оновлення"
            when {
                err != null -> Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
                rel == null -> Toast.makeText(this, "У тебе найновіша версія", Toast.LENGTH_SHORT).show()
                else -> Updater.offer(this, rel)
            }
        }
    }

    private fun refresh() {
        val items = Recents.openable(this)
        list.removeAllViews()
        recentLabel.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        empty.visibility = if (items.size < 3) View.VISIBLE else View.GONE
        (empty.layoutParams as? LinearLayout.LayoutParams)?.topMargin = if (items.isEmpty()) dpi(28) else dpi(16)
        for (r in items) list.addView(row(r))
    }

    private fun row(r: Recent): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ripple(rounded(getColor(R.color.surface), dp(18f)))
            setPadding(dpi(12), dpi(12), dpi(16), dpi(12))
            setOnClickListener { openRecent(r) }
            setOnLongClickListener { askRemove(r); true }
        }
        val thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(getColor(R.color.canvas), dp(8f))
            clipRounded(dp(8f))
            val f = Recents.thumbFile(this@MainActivity, r.key)
            if (f.exists()) {
                setImageBitmap(BitmapFactory.decodeFile(f.path))
            } else {
                setImageResource(R.drawable.ic_doc)
                scaleType = ImageView.ScaleType.CENTER
                imageTintList = ColorStateList.valueOf(getColor(R.color.text_secondary))
            }
        }
        card.addView(thumb, LinearLayout.LayoutParams(dpi(46), dpi(60)))

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(14), 0, 0, 0)
        }
        texts.addView(TextView(this).apply {
            text = r.name.removeSuffix(".pdf").removeSuffix(".PDF")
            setTextColor(getColor(R.color.text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        val meta = buildString {
            if (r.pages > 0) append("${r.pages} стор.")
            if (r.page > 0) append(" · на ${r.page + 1}-й")
            append(" · ").append(whenText(r.opened))
        }.removePrefix(" · ")
        texts.addView(TextView(this).apply {
            text = meta
            setTextColor(getColor(R.color.text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dpi(3), 0, 0)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        })
        card.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        return FrameLayout(this).apply {
            addView(card)
            setPadding(0, 0, 0, dpi(8))
        }
    }

    private fun whenText(t: Long): String {
        val zone = ZoneId.systemDefault()
        val dt = Instant.ofEpochMilli(t).atZone(zone)
        val today = LocalDate.now(zone)
        val time = dt.format(DateTimeFormatter.ofPattern("H:mm", uk))
        return when (dt.toLocalDate()) {
            today -> "сьогодні, $time"
            today.minusDays(1) -> "вчора, $time"
            else -> dt.format(DateTimeFormatter.ofPattern(if (dt.year == today.year) "d MMM" else "d MMM yyyy", uk))
        }
    }

    private fun openRecent(r: Recent) {
        val i = Intent(this, ViewerActivity::class.java)
        if (Recents.docFile(this, r.key).exists()) {
            i.putExtra(ViewerActivity.EXTRA_KEY, r.key)
        } else if (r.uri != null) {
            i.setAction(Intent.ACTION_VIEW).setDataAndType(Uri.parse(r.uri), "application/pdf")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            Toast.makeText(this, "Файл більше недоступний", Toast.LENGTH_SHORT).show()
            Recents.remove(this, r.key)
            refresh()
            return
        }
        startActivity(i)
    }

    private fun askRemove(r: Recent) {
        AlertDialog.Builder(this)
            .setTitle(r.name)
            .setItems(arrayOf("Прибрати зі списку")) { _, _ ->
                Recents.remove(this, r.key)
                refresh()
            }
            .show()
    }

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/pdf")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_OPEN)
        } catch (e: Exception) {
            Toast.makeText(this, "Не знайшов вибір файлів на телефоні", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_OPEN || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        startActivity(
            Intent(this, ViewerActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/pdf")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    companion object {
        private const val REQ_OPEN = 7
    }
}
