package com.futures.channel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var urlInput: EditText
    private lateinit var statusText: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        swipe = findViewById(R.id.swipeRefresh)
        urlInput = findViewById(R.id.urlInput)
        statusText = findViewById(R.id.statusText)
        val btnGo = findViewById<Button>(R.id.btnGo)

        val prefs = getSharedPreferences("ths_app", Context.MODE_PRIVATE)
        val saved = prefs.getString("server_url", BuildConfig.SERVER_URL) ?: BuildConfig.SERVER_URL
        urlInput.setText(saved)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "$userAgentString ChannelStrategyApp/1.0"
        }
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                statusText.text = "加载中…"
                swipe.isRefreshing = true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                statusText.text = "已连接"
                swipe.isRefreshing = false
            }

            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                statusText.text = "连接失败：检查电脑服务是否启动，以及手机与电脑是否同一 WiFi"
                swipe.isRefreshing = false
            }
        }

        btnGo.setOnClickListener { loadServer(true) }
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                loadServer(true)
                true
            } else {
                false
            }
        }
        swipe.setOnRefreshListener { webView.reload() }

        loadServer(false)
    }

    private fun loadServer(fromUser: Boolean) {
        var url = urlInput.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "请填写服务地址", Toast.LENGTH_SHORT).show()
            return
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
            urlInput.setText(url)
        }
        getSharedPreferences("ths_app", Context.MODE_PRIVATE)
            .edit()
            .putString("server_url", url)
            .apply()
        if (fromUser) {
            Toast.makeText(this, "正在打开 $url", Toast.LENGTH_SHORT).show()
        }
        webView.loadUrl(url)
    }

    override fun onBackPressed() {
        if (this::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
