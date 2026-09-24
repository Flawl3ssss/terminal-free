package com.redtermapp.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.ValueCallback
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.redtermapp.R

/**
 * Built-in browser (WebView).
 *
 * No user zoom at all: pinch, double-tap and the built-in zoom controls
 * are disabled (`supportZoom=false`). The "Scale" menu drives a REAL page
 * scale - blocks, images, spacing - by rewriting the page's
 * `meta viewport` to a fixed `initial-scale`; `WebSettings.textZoom`
 * (font size only) is deliberately NOT used.
 *
 * Also used as the client for the dsh Web UI served by the container at
 * http://127.0.0.1:3080.
 */
class BrowserActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "url"
        const val DEFAULT_URL = "https://deepseek.com"

        private const val PREFS = "browser_prefs"
        private const val KEY_PAGE_ZOOM = "page_zoom"
        private const val KEY_LAST_URL = "last_url"
        // Whole-page scale: multiplicative step, 50%..300% (viewport meta).
        private const val PAGE_ZOOM_MIN = 0.5f
        private const val PAGE_ZOOM_MAX = 3.0f
        private const val ZOOM_FACTOR = 1.15f
        private const val FILE_CHOOSER_REQ = 0x7101

        fun launch(context: Context, url: String? = null) {
            context.startActivity(Intent(context, BrowserActivity::class.java).apply {
                if (url != null) putExtra(EXTRA_URL, url)
            })
        }
    }

    private lateinit var webView: WebView
    private lateinit var urlInput: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var backBtn: ImageButton
    private lateinit var forwardBtn: ImageButton
    private lateinit var reloadBtn: ImageButton
    private lateinit var findBar: View
    private lateinit var findInput: EditText
    private lateinit var findCount: TextView

    private var pageZoom = 1f
    private var pageLoading = false
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)
        com.redtermapp.util.ScreenTabs.attach(this, R.id.tab_browser)

        webView = findViewById(R.id.browser_webview)
        urlInput = findViewById(R.id.browser_url)
        progressBar = findViewById(R.id.browser_progress)
        backBtn = findViewById(R.id.browser_back_btn)
        forwardBtn = findViewById(R.id.browser_forward_btn)
        reloadBtn = findViewById(R.id.browser_reload_btn)
        findBar = findViewById(R.id.browser_find_bar)
        findInput = findViewById(R.id.browser_find_input)
        findCount = findViewById(R.id.browser_find_count)

        pageZoom = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getFloat(KEY_PAGE_ZOOM, 1f)
            .coerceIn(PAGE_ZOOM_MIN, PAGE_ZOOM_MAX)

        setupWebView()
        setupFindBar()
        setupButtons()

        updateReloadButton()
        updateNavState()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            // The last visited page survives app restarts (saved on every
            // finished load); an explicit EXTRA_URL (dsh, share intents)
            // still wins over it.
            loadFrom(intent.getStringExtra(EXTRA_URL) ?: lastUrl() ?: DEFAULT_URL)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val s: WebSettings = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        // No user zoom at all: no pinch, no double-tap, no zoom buttons.
        // Page scale is driven by applyViewportScale() instead.
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.mediaPlaybackRequiresUserGesture = true
        // Sandbox: pages must not reach app-private files or content providers.
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.allowFileAccessFromFileURLs = false
        s.allowUniversalAccessFromFileURLs = false

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val scheme = request.url.scheme?.lowercase()
                return if (scheme == "http" || scheme == "https" || scheme == "about" ||
                    scheme == "blob" || scheme == "data"
                ) {
                    false // keep navigation inside the WebView
                } else {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    } catch (_: Exception) {
                        Toast.makeText(
                            this@BrowserActivity,
                            "No app can open this link",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    true
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Pin the page-scale viewport as early as possible so the
                // first layout pass already uses it.
                if (pageZoom != 1f) applyViewportScale()
                // Never stomp on what the user is typing in the address bar.
                if (url != null && !urlInput.hasFocus()) urlInput.setText(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Re-assert after the DOM is complete (dynamic <head> etc.).
                if (pageZoom != 1f) applyViewportScale()
                if (url != null && !urlInput.hasFocus()) urlInput.setText(url)
                persistUrl(url)
                pageLoading = false
                updateReloadButton()
                updateNavState()
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                updateNavState()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true && error != null) {
                    Toast.makeText(
                        this@BrowserActivity,
                        getString(R.string.browser_load_failed, error.errorCode),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                pageLoading = newProgress < 100
                if (newProgress in 1..99) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = View.GONE
                }
                updateReloadButton()
            }

            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                return try {
                    startActivityForResult(
                        Intent.createChooser(intent, getString(R.string.browser_choose_file)),
                        FILE_CHOOSER_REQ
                    )
                    true
                } catch (_: Exception) {
                    callback?.onReceiveValue(null)
                    fileCallback = null
                    false
                }
            }
        }

        webView.setFindListener { ordinal, total, done ->
            if (!done) return@setFindListener
            findCount.text = if (total > 0) {
                getString(R.string.browser_find_count, ordinal + 1, total)
            } else {
                getString(R.string.browser_find_none)
            }
        }
    }

    private fun setupFindBar() {
        findInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString().orEmpty()
                if (query.isEmpty()) {
                    webView.clearMatches()
                    findCount.text = getString(R.string.browser_find_none)
                } else {
                    webView.findAll(query)
                }
            }
        })
        findInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                webView.findNext(true)
                true
            } else false
        }
        findViewById<View>(R.id.browser_find_next).setOnClickListener { webView.findNext(true) }
        findViewById<View>(R.id.browser_find_prev).setOnClickListener { webView.findNext(false) }
        findViewById<View>(R.id.browser_find_close).setOnClickListener { closeFindBar() }
    }

    private fun setupButtons() {
        backBtn.setOnClickListener {
            if (webView.canGoBack()) webView.goBack() else finish()
        }
        forwardBtn.setOnClickListener {
            if (webView.canGoForward()) webView.goForward()
        }
        reloadBtn.setOnClickListener {
            if (pageLoading) webView.stopLoading() else webView.reload()
        }

        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                loadFromInput()
                urlInput.clearFocus()
                true
            } else false
        }
        urlInput.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) v.post { urlInput.selectAll() }
        }

        findViewById<View>(R.id.browser_menu_btn).setOnClickListener { openOverflowMenu() }
    }

    // ------------------------------------------------------------------
    // Page scale - the WHOLE page (blocks, images, text), not just fonts
    // ------------------------------------------------------------------

    private fun applyPageZoom(newZoom: Float) {
        pageZoom = newZoom.coerceIn(PAGE_ZOOM_MIN, PAGE_ZOOM_MAX)
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putFloat(KEY_PAGE_ZOOM, pageZoom)
            .apply()
        applyViewportScale()
        Toast.makeText(
            this,
            getString(R.string.browser_zoom_percent, Math.round(pageZoom * 100)),
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * Rewrites `meta viewport` via JS: a pinned initial-scale zooms the
     * ENTIRE layout viewport - blocks, images, spacing - which is what a
     * real page scale is. The site's original content is parked in
     * `window.__tf_vp` on first touch, so returning to 100% restores it
     * without a reload. Callers skip this at 100%: no JS on default pages.
     */
    private fun applyViewportScale() {
        val z = pageZoom
        val js = """
            (function() {
                var d = document;
                if (!d || !d.documentElement) return 'no-document';
                var w = d.defaultView;
                var m = d.querySelector('meta[name="viewport"]');
                if (typeof w.__tf_vp === 'undefined') w.__tf_vp = m ? m.content : null;
                if ($z === 1.0) {
                    var orig = w.__tf_vp;
                    if (m) {
                        if (orig !== null && orig !== undefined) m.content = orig;
                        else if (m.parentNode) m.parentNode.removeChild(m);
                    }
                    return 'reset';
                }
                if (!m) {
                    m = d.createElement('meta');
                    m.name = 'viewport';
                    (d.head || d.documentElement).appendChild(m);
                }
                m.content = 'width=device-width, initial-scale=' + $z +
                    ', minimum-scale=' + $z + ', maximum-scale=' + $z +
                    ', user-scalable=no';
                return 'scaled';
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // ------------------------------------------------------------------
    // Overflow menu (⋮) - the old bottom toolbar collapsed into one button
    // ------------------------------------------------------------------

    private fun openOverflowMenu() {
        val items = arrayOf(
            getString(R.string.browser_find_in_page),
            getString(R.string.browser_zoom_out),
            getString(R.string.browser_zoom_in),
            getString(R.string.browser_zoom_reset),
            getString(R.string.browser_share),
            getString(R.string.browser_home),
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> if (findBar.visibility == View.VISIBLE) closeFindBar() else openFindBar()
                    1 -> applyPageZoom(pageZoom / ZOOM_FACTOR)
                    2 -> applyPageZoom(pageZoom * ZOOM_FACTOR)
                    3 -> applyPageZoom(1f)
                    4 -> shareCurrentUrl()
                    5 -> loadFrom(DEFAULT_URL)
                }
            }
            .show()
    }

    // ------------------------------------------------------------------
    // Last page persistence (survives app restarts / process death)
    // ------------------------------------------------------------------

    private fun persistUrl(url: String?) {
        if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
            getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_URL, url)
                .apply()
        }
    }

    private fun lastUrl(): String? =
        getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LAST_URL, null)

    // ------------------------------------------------------------------
    // Navigation / reload state
    // ------------------------------------------------------------------

    private fun updateNavState() {
        backBtn.isEnabled = webView.canGoBack()
        backBtn.alpha = if (webView.canGoBack()) 1f else 0.35f
        forwardBtn.isEnabled = webView.canGoForward()
        forwardBtn.alpha = if (webView.canGoForward()) 1f else 0.35f
    }

    private fun updateReloadButton() {
        if (pageLoading) {
            reloadBtn.setImageResource(R.drawable.ic_stop)
            reloadBtn.contentDescription = getString(R.string.browser_stop)
        } else {
            reloadBtn.setImageResource(R.drawable.ic_reload)
            reloadBtn.contentDescription = getString(R.string.browser_reload)
        }
    }

    // ------------------------------------------------------------------
    // Find in page
    // ------------------------------------------------------------------

    private fun openFindBar() {
        findBar.visibility = View.VISIBLE
        findInput.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(findInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeFindBar() {
        webView.clearMatches()
        findInput.setText("")
        findCount.text = getString(R.string.browser_find_none)
        findBar.visibility = View.GONE
        urlInput.clearFocus()
        webView.requestFocus()
    }

    // ------------------------------------------------------------------
    // Address bar
    // ------------------------------------------------------------------

    private fun loadFromInput() = loadFrom(urlInput.text.toString())

    private fun loadFrom(raw: String) {
        val t = raw.trim()
        if (t.isEmpty()) return
        val url = when {
            t.contains("://") || t.startsWith("about:") -> t
            t.startsWith("127.0.0.1") || t.startsWith("localhost") -> "http://$t"
            // No dot and/or contains spaces -> treat as a search query.
            t.contains(' ') || !t.contains('.') ->
                "https://duckduckgo.com/?q=${Uri.encode(t)}"
            else -> "https://$t"
        }
        urlInput.setText(url)
        webView.loadUrl(url)
    }

    private fun shareCurrentUrl() {
        val url = webView.url
        if (url.isNullOrEmpty()) {
            Toast.makeText(this, R.string.browser_nothing_to_share, Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        try {
            startActivity(Intent.createChooser(send, null))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.browser_nothing_to_share, Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER_REQ) {
            val cb = fileCallback
            fileCallback = null
            cb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        persistUrl(webView.url)
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        webView.destroy()
        super.onDestroy()
    }
}
