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
 * Page scaling here is a *text zoom*: `WebSettings.textZoom` makes the
 * renderer re-flow the document at a different font size, which is what
 * "scale the page" means for real. Pinch zoom (viewport zoom) stays
 * available separately and is not what the −/%/+ controls drive.
 *
 * Also used as the client for the dsh Web UI served by the container at
 * http://127.0.0.1:3080.
 */
class BrowserActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "url"
        const val DEFAULT_URL = "https://deepseek.com"

        private const val PREFS = "browser_prefs"
        private const val KEY_TEXT_ZOOM = "text_zoom"
        private const val ZOOM_MIN = 50
        private const val ZOOM_MAX = 300
        private const val ZOOM_STEP = 15
        private const val FILE_CHOOSER_REQ = 0x7101
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
    private lateinit var zoomLabel: TextView

    private var textZoom = 100
    private var pageLoading = false
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)

        webView = findViewById(R.id.browser_webview)
        urlInput = findViewById(R.id.browser_url)
        progressBar = findViewById(R.id.browser_progress)
        backBtn = findViewById(R.id.browser_back_btn)
        forwardBtn = findViewById(R.id.browser_forward_btn)
        reloadBtn = findViewById(R.id.browser_reload_btn)
        findBar = findViewById(R.id.browser_find_bar)
        findInput = findViewById(R.id.browser_find_input)
        findCount = findViewById(R.id.browser_find_count)
        zoomLabel = findViewById(R.id.browser_zoom_label)

        textZoom = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getInt(KEY_TEXT_ZOOM, 100)
            .coerceIn(ZOOM_MIN, ZOOM_MAX)

        setupWebView()
        setupFindBar()
        setupButtons()

        updateZoomLabel()
        updateReloadButton()
        updateNavState()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            loadFrom(intent.getStringExtra(EXTRA_URL) ?: DEFAULT_URL)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val s: WebSettings = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.mediaPlaybackRequiresUserGesture = true
        s.textZoom = textZoom
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
                // Never stomp on what the user is typing in the address bar.
                if (url != null && !urlInput.hasFocus()) urlInput.setText(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url != null && !urlInput.hasFocus()) urlInput.setText(url)
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

        findViewById<View>(R.id.browser_zoom_out).setOnClickListener {
            applyTextZoom(textZoom - ZOOM_STEP)
        }
        findViewById<View>(R.id.browser_zoom_in).setOnClickListener {
            applyTextZoom(textZoom + ZOOM_STEP)
        }
        zoomLabel.setOnClickListener { applyTextZoom(100) }

        findViewById<View>(R.id.browser_find_btn).setOnClickListener {
            if (findBar.visibility == View.VISIBLE) closeFindBar() else openFindBar()
        }

        findViewById<View>(R.id.browser_share).setOnClickListener { shareCurrentUrl() }

        findViewById<View>(R.id.browser_home).setOnClickListener { loadFrom(DEFAULT_URL) }
    }

    // ------------------------------------------------------------------
    // Page scale
    // ------------------------------------------------------------------

    private fun applyTextZoom(newZoom: Int) {
        textZoom = newZoom.coerceIn(ZOOM_MIN, ZOOM_MAX)
        webView.settings.textZoom = textZoom
        updateZoomLabel()
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putInt(KEY_TEXT_ZOOM, textZoom)
            .apply()
    }

    private fun updateZoomLabel() {
        zoomLabel.text = getString(R.string.browser_zoom_percent, textZoom)
    }

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
