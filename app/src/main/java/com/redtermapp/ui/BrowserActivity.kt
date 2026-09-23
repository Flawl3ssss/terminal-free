package com.redtermapp.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.redtermapp.R

/**
 * Lightweight built-in browser (WebView) with pinch zoom and on-screen
 * zoom buttons. Used both as a general-purpose browser and as the client
 * for the dsh Web UI served by the container at http://127.0.0.1:3080.
 */
class BrowserActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "url"
        const val DEFAULT_URL = "https://deepseek.com"

        fun launch(context: Context, url: String? = null) {
            context.startActivity(Intent(context, BrowserActivity::class.java).apply {
                if (url != null) putExtra(EXTRA_URL, url)
            })
        }
    }

    private lateinit var webView: WebView
    private lateinit var urlInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)

        webView = findViewById(R.id.browser_webview)
        urlInput = findViewById(R.id.browser_url)

        val s: WebSettings = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
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
            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                val scheme = request.url.scheme?.lowercase()
                return if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "blob" || scheme == "data") {
                    false // keep navigation inside the WebView
                } else {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    } catch (_: Exception) {
                        Toast.makeText(this@BrowserActivity, "No app can open this link", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (url != null && urlInput.text.toString() != url) {
                    urlInput.setText(url)
                }
            }
        }

        findViewById<View>(R.id.browser_back_btn).setOnClickListener {
            if (webView.canGoBack()) webView.goBack() else finish()
        }
        findViewById<TextView>(R.id.browser_go).setOnClickListener { loadFromInput() }
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { loadFromInput(); true } else false
        }
        findViewById<TextView>(R.id.browser_zoom_out).setOnClickListener { webView.zoomOut() }
        findViewById<TextView>(R.id.browser_zoom_in).setOnClickListener { webView.zoomIn() }
        findViewById<TextView>(R.id.browser_reload).setOnClickListener { webView.reload() }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            val start = intent.getStringExtra(EXTRA_URL) ?: DEFAULT_URL
            loadFrom(start)
        }
        urlInput.setText(webView.url ?: intent.getStringExtra(EXTRA_URL) ?: DEFAULT_URL)
    }

    private fun loadFromInput() = loadFrom(urlInput.text.toString())

    private fun loadFrom(raw: String) {
        val t = raw.trim()
        if (t.isEmpty()) return
        val url = when {
            t.contains("://") -> t
            t.startsWith("127.0.0.1") || t.startsWith("localhost") -> "http://$t"
            else -> "https://$t"
        }
        urlInput.setText(url)
        webView.loadUrl(url)
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
        webView.destroy()
        super.onDestroy()
    }
}
