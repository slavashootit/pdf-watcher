package com.slava.pdfwatcher

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
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
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import java.io.FileNotFoundException
import java.io.FileOutputStream
import kotlin.concurrent.thread
import kotlin.math.roundToInt

class ViewerActivity : Activity(), PdfView.Listener {

    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    private lateinit var root: FrameLayout
    private lateinit var pdfView: PdfView
    private lateinit var topBar: LinearLayout
    private lateinit var barRow: LinearLayout
    private lateinit var searchRow: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var nightBtn: ImageButton
    private lateinit var searchEdit: EditText
    private lateinit var searchCount: TextView
    private lateinit var pill: TextView
    private lateinit var loading: ProgressBar
    private lateinit var message: LinearLayout
    private lateinit var messageTitle: TextView
    private lateinit var messageText: TextView
    private lateinit var snack: LinearLayout
    private lateinit var snackText: TextView
    private lateinit var snackAction: TextView

    private var src: DocSource? = null
    private var doc: PdfDoc? = null
    private var exporter: Exporter? = null
    private var chrome = true
    private var searching = false
    private var scrollAccum = 0f

    @Volatile private var searchGen = 0
    private var searchDone = 0
    private val results = ArrayList<Pair<Int, List<RectF>>>()
    private var activeIdx = -1

    private val hidePill = Runnable { if (!chrome) pill.animate().alpha(0f).setDuration(250) }
    private val hideSnack = Runnable { snack.animate().alpha(0f).translationY(dp(12f)).setDuration(200).withEndAction { snack.visibility = View.GONE } }
    private val searchRunnable = Runnable { runSearch(searchEdit.text.toString().trim()) }

    private val textColor get() = getColor(R.color.text)
    private val textSecondaryColor get() = getColor(R.color.text_secondary)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        buildUi()
        setContentView(root)
        pdfView.night = prefs.getBoolean("night", false)
        updateNightIcon()
        load(null)
    }

    // ---------- UI ----------

    private fun buildUi() {
        root = FrameLayout(this)

        pdfView = PdfView(this).also { it.listener = this }
        root.addView(pdfView, FrameLayout.LayoutParams(MATCH, MATCH))

        // Top bar
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.surface_bar))
            elevation = dp(3f)
        }
        barRow = row().apply {
            addView(iconButton(R.drawable.ic_back, "Назад") { finish() })
            titleView = TextView(this@ViewerActivity).apply {
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.MIDDLE
                setPadding(dpi(4), 0, dpi(4), 0)
                setOnClickListener { showInfo() }
            }
            addView(titleView, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (Build.VERSION.SDK_INT >= 35) addView(iconButton(R.drawable.ic_search, "Пошук") { openSearch() })
            nightBtn = iconButton(R.drawable.ic_moon, "Нічний режим") { toggleNight() }
            addView(nightBtn)
            addView(iconButton(R.drawable.ic_image, "Зберегти як зображення") { showExportDialog() })
            val more = iconButton(R.drawable.ic_more, "Ще") {}
            more.setOnClickListener { showMenu(more) }
            addView(more)
        }
        searchRow = row().apply {
            visibility = View.GONE
            addView(iconButton(R.drawable.ic_close, "Закрити пошук") { closeSearch() })
            searchEdit = EditText(this@ViewerActivity).apply {
                hint = "Пошук у документі"
                setTextColor(textColor)
                setHintTextColor(textSecondaryColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                background = null
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                setOnEditorActionListener { _, _, ev ->
                    if (ev != null && ev.action != KeyEvent.ACTION_DOWN) return@setOnEditorActionListener true
                    main.removeCallbacks(searchRunnable)
                    if (results.isNotEmpty() && searchDone > 0) step(1) else runSearch(searchEdit.text.toString().trim())
                    hideKeyboard()
                    true
                }
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        main.removeCallbacks(searchRunnable)
                        main.postDelayed(searchRunnable, 350)
                    }
                })
            }
            addView(searchEdit, LinearLayout.LayoutParams(0, WRAP, 1f))
            searchCount = TextView(this@ViewerActivity).apply {
                setTextColor(textSecondaryColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dpi(4), 0, dpi(4), 0)
                fontFeatureSettings = "tnum"
            }
            addView(searchCount)
            addView(iconButton(R.drawable.ic_up, "Попередній збіг") { step(-1) })
            addView(iconButton(R.drawable.ic_down, "Наступний збіг") { step(1) })
        }
        topBar.addView(barRow)
        topBar.addView(searchRow)
        root.addView(topBar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

        // Page pill
        pill = TextView(this).apply {
            setTextColor(getColor(R.color.on_pill))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            fontFeatureSettings = "tnum"
            setPadding(dpi(18), dpi(10), dpi(18), dpi(10))
            background = ripple(rounded(getColor(R.color.pill), dp(22f)))
            elevation = dp(4f)
            visibility = View.INVISIBLE
            contentDescription = "Перейти до сторінки"
            setOnClickListener { if (alpha > 0.5f) goToPageDialog() }
        }
        root.addView(pill, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))

        // Snackbar
        snack = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(getColor(R.color.pill), dp(16f))
            elevation = dp(6f)
            setPadding(dpi(18), dpi(12), dpi(10), dpi(12))
            visibility = View.GONE
        }
        snackText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        snackAction = TextView(this).apply {
            setTextColor(0xFFFF9C82.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dpi(14), dpi(8), dpi(10), dpi(8))
            background = ripple(rounded(Color.TRANSPARENT, dp(10f)), rounded(Color.WHITE, dp(10f)))
        }
        snack.addView(snackText, LinearLayout.LayoutParams(0, WRAP, 1f))
        snack.addView(snackAction)
        root.addView(snack, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply {
            leftMargin = dpi(16); rightMargin = dpi(16)
        })

        // Loading & message
        loading = ProgressBar(this).apply { indeterminateTintList = ColorStateList.valueOf(getColor(R.color.accent)) }
        root.addView(loading, FrameLayout.LayoutParams(dpi(44), dpi(44), Gravity.CENTER))

        message = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpi(32), 0, dpi(32), 0)
            visibility = View.GONE
        }
        messageTitle = TextView(this).apply {
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }
        messageText = TextView(this).apply {
            setTextColor(textSecondaryColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(0, dpi(8), 0, dpi(20))
            setLineSpacing(0f, 1.2f)
        }
        val close = TextView(this).apply {
            text = "Закрити"
            setTextColor(getColor(R.color.on_accent))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(dpi(28), dpi(12), dpi(28), dpi(12))
            background = ripple(rounded(getColor(R.color.accent), dp(14f)))
            setOnClickListener { finish() }
        }
        message.addView(messageTitle)
        message.addView(messageText)
        message.addView(close)
        root.addView(message, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))

        root.setOnApplyWindowInsetsListener { _, ins ->
            val b = ins.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            topBar.setPadding(b.left, b.top, b.right, 0)
            (pill.layoutParams as FrameLayout.LayoutParams).bottomMargin = b.bottom + dpi(18)
            (snack.layoutParams as FrameLayout.LayoutParams).bottomMargin = b.bottom + dpi(74)
            pill.requestLayout()
            pdfView.setInsets(b.top.toFloat(), b.bottom.toFloat())
            ins
        }
    }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dpi(56)
        setPadding(dpi(4), 0, dpi(4), 0)
    }

    // ---------- loading ----------

    private fun load(password: String?) {
        loading.visibility = View.VISIBLE
        message.visibility = View.GONE
        val startIntent = intent
        thread(name = "pdf-open") {
            val s = try {
                src ?: resolve(startIntent)
            } catch (e: Exception) {
                ui { showError("Не вдалося відкрити файл", "Застосунок, з якого відкрито PDF, не дав до нього доступу.") }
                return@thread
            }
            if (password != null) s.password = password
            val pfd = try {
                s.openPfd(this)
            } catch (e: Exception) {
                ui { showError("Не вдалося відкрити файл", "Файл недоступний: можливо, його переміщено або видалено.") }
                return@thread
            }
            val renderer = try {
                PdfDoc.openRenderer(pfd, s.password)
            } catch (e: SecurityException) {
                closeQuietly(pfd)
                ui { src = s; askPassword(s.password != null) }
                return@thread
            } catch (e: Exception) {
                closeQuietly(pfd)
                ui { showError("Файл пошкоджений", "Це не схоже на справжній PDF або файл зіпсовано.") }
                return@thread
            }
            val d = try {
                PdfDoc(pfd, renderer)
            } catch (e: Exception) {
                try { renderer.close() } catch (_: Exception) {}
                ui { showError("Файл пошкоджений", "Не вдалося прочитати сторінки документа.") }
                return@thread
            }
            ui {
                if (isDestroyed) d.close() else onLoaded(s, d)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun resolve(i: Intent): DocSource {
        i.getStringExtra(EXTRA_KEY)?.let { k -> Recents.get(this, k)?.let { return DocSource.fromRecent(this, it) } }
        val uri: Uri? = if (i.action == Intent.ACTION_SEND) i.getParcelableExtra(Intent.EXTRA_STREAM) else i.data
        return DocSource.fromUri(this, uri ?: throw FileNotFoundException("no uri"))
    }

    private fun onLoaded(s: DocSource, d: PdfDoc) {
        src = s
        doc = d
        d.start()
        titleView.text = s.title
        loading.visibility = View.GONE
        val r = Recents.get(this, s.key)
        pdfView.setDocument(d, r?.let { PdfView.Pos(it.page, it.frac, it.zoom) })
        pill.visibility = View.VISIBLE
        pill.alpha = 1f
        Recents.put(
            this, Recent(
                s.key, s.name, s.uri?.toString(), s.size, System.currentTimeMillis(), d.pageCount,
                r?.page ?: 0, r?.frac ?: 0f, r?.zoom ?: 1f,
            )
        )
        makeThumb(s, d)
        keepCopy(s)
    }

    private fun makeThumb(s: DocSource, d: PdfDoc) {
        val f = Recents.thumbFile(this, s.key)
        if (f.exists() || d.pageCount == 0) return
        d.post { r ->
            val p = r.openPage(0)
            try {
                val w = 200
                val h = (w * p.height / p.width.toFloat()).roundToInt().coerceIn(1, 600)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                f.parentFile?.mkdirs()
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                bmp.recycle()
            } finally {
                p.close()
            }
        }
    }

    /** Files opened from Telegram/Gmail etc. are readable only for now; keep a copy so "Recent" works later. */
    private fun keepCopy(s: DocSource) {
        val u = s.uri ?: return
        if (s.localFile?.exists() == true) return
        if (contentResolver.persistedUriPermissions.any { it.isReadPermission && it.uri == u }) return
        if (s.size > MAX_COPY) return
        val app = applicationContext
        thread(name = "pdf-copy") {
            try {
                s.copyLocal(app)
            } catch (_: Exception) {
            }
        }
    }

    private fun askPassword(wrong: Boolean) {
        loading.visibility = View.GONE
        if (Build.VERSION.SDK_INT < 35) {
            showError("Файл захищено паролем", "Відкривати PDF із паролем уміє Android 15 і новіші.")
            return
        }
        val edit = EditText(this).apply {
            isSingleLine = true  // before inputType: setSingleLine would drop the password masking
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE
            hint = "Пароль"
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Потрібен пароль")
            .setMessage(if (wrong) "Пароль неправильний. Спробуйте ще раз." else "Цей PDF захищено паролем.")
            .setView(padded(edit))
            .setCancelable(false)
            .setPositiveButton("Відкрити") { _, _ -> load(edit.text.toString()) }
            .setNegativeButton("Скасувати") { _, _ -> finish() }
            .create()
        edit.setOnEditorActionListener { _, _, ev ->
            if (ev == null || ev.action == KeyEvent.ACTION_DOWN) {
                dlg.dismiss()
                load(edit.text.toString())
            }
            true
        }
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dlg.show()
        edit.requestFocus()
    }

    private fun showError(title: String, details: String) {
        loading.visibility = View.GONE
        pill.visibility = View.GONE
        messageTitle.text = title
        messageText.text = details
        message.visibility = View.VISIBLE
    }

    override fun onPause() {
        super.onPause()
        val s = src ?: return
        val p = pdfView.position() ?: return
        if (doc == null) return
        Recents.update(this, s.key) { it.copy(page = p.page, frac = p.frac, zoom = p.zoom) }
    }

    override fun onDestroy() {
        super.onDestroy()
        searchGen++
        exporter?.cancelled = true
        doc?.close()
        main.removeCallbacksAndMessages(null)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (searching) closeSearch() else super.onBackPressed()
    }

    // ---------- chrome ----------

    override fun onPageChanged(page: Int, count: Int) {
        pill.text = "${page + 1} / $count"
        if (!chrome) flashPill()
    }

    override fun onSingleTap() {
        if (searching) hideKeyboard() else setChrome(!chrome)
    }

    override fun onUserScroll(dy: Float) {
        if (dy > 0) scrollAccum += dy else scrollAccum = 0f
        if (chrome && !searching && scrollAccum > dp(28f)) setChrome(false)
        if (!chrome) flashPill()
    }

    private fun setChrome(show: Boolean) {
        if (chrome == show) return
        chrome = show
        scrollAccum = 0f
        topBar.animate().translationY(if (show) 0f else -topBar.height.toFloat())
            .setDuration(200).setInterpolator(DecelerateInterpolator())
        main.removeCallbacks(hidePill)
        pill.animate().alpha(if (show) 1f else 0f).setDuration(200)
        window.insetsController?.let {
            if (show) {
                it.show(WindowInsets.Type.systemBars())
            } else {
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                it.hide(WindowInsets.Type.systemBars())
            }
        }
    }

    private fun flashPill() {
        if (doc == null) return
        main.removeCallbacks(hidePill)
        if (pill.alpha < 1f) pill.animate().alpha(1f).setDuration(120)
        main.postDelayed(hidePill, 1300)
    }

    private fun toggleNight() {
        pdfView.night = !pdfView.night
        prefs.edit().putBoolean("night", pdfView.night).apply()
        updateNightIcon()
    }

    private fun updateNightIcon() {
        nightBtn.imageTintList = ColorStateList.valueOf(if (pdfView.night) getColor(R.color.accent_text) else textColor)
        nightBtn.contentDescription = if (pdfView.night) "Вимкнути нічний режим" else "Нічний режим"
    }

    private fun showMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, "Перейти до сторінки")
        m.menu.add(0, 2, 1, "Зберегти як зображення")
        m.menu.add(0, 3, 2, "Поділитися PDF")
        m.menu.add(0, 4, 3, "Друкувати")
        m.menu.add(0, 5, 4, "Про файл")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> goToPageDialog()
                2 -> showExportDialog()
                3 -> sharePdf()
                4 -> print()
                5 -> showInfo()
            }
            true
        }
        m.show()
    }

    private fun snack(msg: String, action: String? = null, onAction: (() -> Unit)? = null) {
        snackText.text = msg
        snackAction.visibility = if (action != null) View.VISIBLE else View.GONE
        snackAction.text = action
        snackAction.setOnClickListener { main.removeCallbacks(hideSnack); hideSnack.run(); onAction?.invoke() }
        snack.animate().cancel()
        snack.visibility = View.VISIBLE
        snack.alpha = 0f
        snack.translationY = dp(12f)
        snack.animate().alpha(1f).translationY(0f).setDuration(200)
        main.removeCallbacks(hideSnack)
        main.postDelayed(hideSnack, if (action != null) 6000 else 3500)
    }

    // ---------- go to page / info ----------

    private fun goToPageDialog() {
        val n = pdfView.pageCount
        if (n == 0) return
        val edit = EditText(this).apply {
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "1 – $n"
            setText("${pdfView.page + 1}")
            selectAll()
            imeOptions = EditorInfo.IME_ACTION_GO
        }
        val go = { edit.text.toString().trim().toIntOrNull()?.let { pdfView.goToPage(it - 1) } }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Перейти до сторінки")
            .setView(padded(edit))
            .setPositiveButton("Перейти") { _, _ -> go() }
            .setNegativeButton("Скасувати", null)
            .create()
        edit.setOnEditorActionListener { _, _, ev ->
            if (ev == null || ev.action == KeyEvent.ACTION_DOWN) {
                go()
                dlg.dismiss()
            }
            true
        }
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dlg.show()
        edit.requestFocus()
    }

    private fun showInfo() {
        val s = src ?: return
        val n = doc?.pageCount ?: 0
        val size = if (s.size > 0) Formatter.formatShortFileSize(this, s.size) else "невідомо"
        AlertDialog.Builder(this)
            .setTitle(s.name)
            .setMessage("$n ${plural(n, "сторінка", "сторінки", "сторінок")} · $size")
            .setPositiveButton("Гаразд", null)
            .show()
    }

    // ---------- export ----------

    private fun showExportDialog() {
        val d = doc ?: return
        val n = d.pageCount
        val cur = pdfView.page
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(24), dpi(4), dpi(24), 0)
        }
        fun label(t: String) = TextView(this).apply {
            text = t
            setTextColor(textSecondaryColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dpi(14), 0, dpi(2))
        }
        fun radio(t: String) = RadioButton(this).apply {
            id = View.generateViewId()
            text = t
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            minHeight = dpi(44)
        }

        val pagesGroup = RadioGroup(this)
        val rCur = radio("Поточна сторінка — ${cur + 1}")
        val rAll = radio("Усі сторінки — $n")
        val rRange = radio("Вибрати сторінки…")
        pagesGroup.addView(rCur)
        if (n > 1) {
            pagesGroup.addView(rAll)
            pagesGroup.addView(rRange)
        }
        val rangeEdit = EditText(this).apply {
            hint = "Наприклад: 1-3, 7"
            inputType = InputType.TYPE_CLASS_PHONE
            isSingleLine = true
            visibility = View.GONE
        }
        pagesGroup.setOnCheckedChangeListener { _, id ->
            rangeEdit.visibility = if (id == rRange.id) View.VISIBLE else View.GONE
            if (id == rRange.id) rangeEdit.requestFocus()
        }
        pagesGroup.check(rCur.id)

        val qGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val q150 = radio("Звичайна")
        val q300 = radio("Висока")
        qGroup.addView(q150, RadioGroup.LayoutParams(0, WRAP, 1f))
        qGroup.addView(q300, RadioGroup.LayoutParams(0, WRAP, 1f))
        qGroup.check(if (prefs.getInt("dpi", 300) == 150) q150.id else q300.id)
        val qHint = label("Звичайна — 150 DPI, для екрана й месенджерів. Висока — 300 DPI, для друку.").apply {
            setPadding(0, 0, 0, 0)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }

        val fGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val fPng = radio("PNG")
        val fJpg = radio("JPG")
        fGroup.addView(fPng, RadioGroup.LayoutParams(0, WRAP, 1f))
        fGroup.addView(fJpg, RadioGroup.LayoutParams(0, WRAP, 1f))
        fGroup.check(if (prefs.getBoolean("jpeg", false)) fJpg.id else fPng.id)

        box.addView(label("Які сторінки"))
        box.addView(pagesGroup)
        box.addView(rangeEdit)
        box.addView(label("Якість"))
        box.addView(qGroup)
        box.addView(qHint)
        box.addView(label("Формат"))
        box.addView(fGroup)

        val dlg = AlertDialog.Builder(this)
            .setTitle("Зберегти як зображення")
            .setView(box)
            .setPositiveButton("Зберегти", null)
            .setNegativeButton("Скасувати", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pages = when (pagesGroup.checkedRadioButtonId) {
                    rAll.id -> (0 until n).toList()
                    rRange.id -> Exporter.parseRange(rangeEdit.text.toString(), n)
                    else -> listOf(cur)
                }
                if (pages == null) {
                    rangeEdit.error = "Вкажіть сторінки від 1 до $n"
                    return@setOnClickListener
                }
                val dpi = if (qGroup.checkedRadioButtonId == q150.id) 150 else 300
                val jpeg = fGroup.checkedRadioButtonId == fJpg.id
                prefs.edit().putInt("dpi", dpi).putBoolean("jpeg", jpeg).apply()
                dlg.dismiss()
                startExport(pages, dpi, jpeg)
            }
        }
        dlg.show()
    }

    private fun startExport(pages: List<Int>, dpi: Int, jpeg: Boolean) {
        val s = src ?: return
        val ex = Exporter(applicationContext, s)
        exporter = ex
        var progress: AlertDialog? = null
        var bar: ProgressBar? = null
        var label: TextView? = null
        if (pages.size > 1) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpi(24), dpi(8), dpi(24), 0)
            }
            label = TextView(this).apply {
                text = "0 з ${pages.size}"
                setTextColor(textSecondaryColor)
                fontFeatureSettings = "tnum"
            }
            bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = pages.size
                progressTintList = ColorStateList.valueOf(getColor(R.color.accent))
            }
            box.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
            box.addView(label)
            progress = AlertDialog.Builder(this)
                .setTitle("Зберігаю зображення")
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("Зупинити") { _, _ -> ex.cancelled = true }
                .show()
        } else {
            snack("Зберігаю…")
        }
        thread(name = "pdf-export") {
            try {
                val uris = ex.run(pages, dpi, jpeg) { done, total ->
                    ui {
                        bar?.progress = done
                        label?.text = "$done з $total"
                    }
                }
                ui {
                    progress?.dismiss()
                    if (uris.isEmpty()) return@ui
                    val what = if (uris.size == 1) "Сторінку збережено" else "Збережено ${uris.size} ${plural(uris.size, "зображення", "зображення", "зображень")}"
                    snack("$what у Галерею\nPictures › PDF Watcher › ${ex.folder}", "Поділитися") {
                        shareImages(uris, jpeg)
                    }
                }
            } catch (e: Exception) {
                ui {
                    progress?.dismiss()
                    snack("Не вдалося зберегти зображення")
                }
            }
        }
    }

    private fun shareImages(uris: List<Uri>, jpeg: Boolean) {
        val type = if (jpeg) "image/jpeg" else "image/png"
        val i = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        i.type = type
        i.clipData = ClipData.newRawUri("", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startSafely(Intent.createChooser(i, null))
    }

    // ---------- share & print ----------

    private fun sharePdf() {
        val s = src ?: return
        val uri = s.localFile?.takeIf { it.exists() }?.let { ShareProvider.uriFor(s.key, s.name) }
            ?: s.uri?.takeIf { it.scheme == "content" }
        if (uri == null) {
            snack("Цим файлом не можна поділитися")
            return
        }
        val i = Intent(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri(s.name, uri)
        startSafely(Intent.createChooser(i, null))
    }

    private fun print() {
        val s = src ?: return
        val pages = doc?.pageCount ?: PrintDocumentInfo.PAGE_COUNT_UNKNOWN
        val pm = getSystemService(PrintManager::class.java) ?: return
        pm.print(s.title, object : PrintDocumentAdapter() {
            override fun onLayout(
                oldAttributes: PrintAttributes?, newAttributes: PrintAttributes, cancel: CancellationSignal?,
                callback: LayoutResultCallback, extras: Bundle?,
            ) {
                if (cancel?.isCanceled == true) {
                    callback.onLayoutCancelled()
                    return
                }
                val info = PrintDocumentInfo.Builder(s.name)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(pages)
                    .build()
                callback.onLayoutFinished(info, oldAttributes == null)
            }

            override fun onWrite(
                pageRanges: Array<out PageRange>, destination: ParcelFileDescriptor,
                cancel: CancellationSignal?, callback: WriteResultCallback,
            ) {
                val app = applicationContext
                thread(name = "pdf-print") {
                    try {
                        s.openStream(app).use { inp -> FileOutputStream(destination.fileDescriptor).use { inp.copyTo(it) } }
                        main.post { callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES)) }
                    } catch (e: Exception) {
                        main.post { callback.onWriteFailed(e.message) }
                    }
                }
            }
        }, null)
    }

    // ---------- search (Android 15+) ----------

    private fun openSearch() {
        if (doc == null) return
        searching = true
        setChrome(true)
        barRow.visibility = View.GONE
        searchRow.visibility = View.VISIBLE
        searchEdit.requestFocus()
        showKeyboard(searchEdit)
    }

    private fun closeSearch() {
        searching = false
        searchGen++
        main.removeCallbacks(searchRunnable)
        results.clear()
        activeIdx = -1
        pdfView.setHighlights(emptyMap(), null)
        hideKeyboard()
        searchEdit.setText("")
        searchCount.text = ""
        searchRow.visibility = View.GONE
        barRow.visibility = View.VISIBLE
    }

    private fun runSearch(q: String) {
        val gen = ++searchGen
        results.clear()
        activeIdx = -1
        searchDone = 0
        pdfView.setHighlights(emptyMap(), null)
        val d = doc
        if (d == null || q.isEmpty() || Build.VERSION.SDK_INT < 35) {
            searchCount.text = ""
            return
        }
        searchCount.text = "…"
        val n = d.pageCount
        val start = pdfView.page
        for (k in 0 until n) {
            val i = (start + k) % n
            d.post { r ->
                if (gen != searchGen) return@post
                val found = try {
                    Search35.search(r, i, q)
                } catch (e: Exception) {
                    emptyList()
                }
                main.post { if (gen == searchGen) onSearchPage(i, found, n) }
            }
        }
    }

    private fun onSearchPage(page: Int, found: List<List<RectF>>, total: Int) {
        searchDone++
        if (found.isNotEmpty()) {
            var at = results.indexOfFirst { it.first > page }
            if (at < 0) at = results.size
            results.addAll(at, found.map { page to it })
            if (activeIdx >= at) activeIdx += found.size
            if (activeIdx < 0) {
                activeIdx = at
                showActive()
            }
        }
        if (activeIdx >= 0) pdfView.setHighlights(highlightMap(), results[activeIdx])
        updateSearchCount(total)
    }

    private fun updateSearchCount(total: Int) {
        val finished = searchDone >= total
        searchCount.text = when {
            results.isEmpty() && finished -> "Нічого"
            results.isEmpty() -> "…"
            else -> "${activeIdx + 1}/${results.size}" + if (finished) "" else "…"
        }
    }

    private fun step(dir: Int) {
        if (results.isEmpty()) return
        activeIdx = (activeIdx + dir).mod(results.size)
        showActive()
        updateSearchCount(doc?.pageCount ?: 0)
    }

    private fun showActive() {
        val (page, rects) = results[activeIdx]
        pdfView.setHighlights(highlightMap(), results[activeIdx])
        val u = RectF(rects.first())
        rects.forEach { u.union(it) }
        pdfView.showMatch(page, u)
    }

    private fun highlightMap(): Map<Int, List<List<RectF>>> =
        results.groupBy({ it.first }, { it.second })

    // ---------- helpers ----------

    private fun ui(block: () -> Unit) = main.post { if (!isDestroyed) block() }

    private fun padded(v: View) = FrameLayout(this).apply {
        setPadding(dpi(24), dpi(8), dpi(24), 0)
        addView(v, FrameLayout.LayoutParams(MATCH, WRAP))
    }

    private fun showKeyboard(v: View) {
        v.post { getSystemService(InputMethodManager::class.java)?.showSoftInput(v, 0) }
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(root.windowToken, 0)
    }

    private fun startSafely(i: Intent) {
        try {
            startActivity(i)
        } catch (e: Exception) {
            snack("Немає застосунку для цієї дії")
        }
    }

    private fun closeQuietly(pfd: ParcelFileDescriptor) {
        try { pfd.close() } catch (_: Exception) {}
    }

    companion object {
        const val EXTRA_KEY = "com.slava.pdfwatcher.KEY"
        private const val MAX_COPY = 300L * 1024 * 1024
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        fun plural(n: Int, one: String, few: String, many: String): String {
            val a = n % 100
            val b = n % 10
            return when {
                a in 11..14 -> many
                b == 1 -> one
                b in 2..4 -> few
                else -> many
            }
        }
    }
}
