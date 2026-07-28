package com.futures.channel

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var statusText: TextView
    private lateinit var boardRoot: LinearLayout
    private lateinit var loginRoot: LinearLayout
    private lateinit var userInput: EditText
    private lateinit var passInput: EditText
    private lateinit var loginError: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val auth = ShinnyAuth()
    private var mdClient: DiffMdClient? = null
    private var pageReady = false
    private var pendingBars: JSONArray? = null
    private var lastPushMs = 0L

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(this)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            this,
            "ths_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        boardRoot = findViewById(R.id.boardRoot)
        loginRoot = findViewById(R.id.loginRoot)
        webView = findViewById(R.id.webView)
        swipe = findViewById(R.id.swipeRefresh)
        statusText = findViewById(R.id.statusText)
        userInput = findViewById(R.id.userInput)
        passInput = findViewById(R.id.passInput)
        loginError = findViewById(R.id.loginError)
        val btnLogin = findViewById<Button>(R.id.btnLogin)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "$userAgentString ChannelStrategyApp/1.0"
        }
        webView.addJavascriptInterface(Bridge(), "ChannelBridge")
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                statusText.text = "盘面已加载"
            }
        }
        swipe.setOnRefreshListener {
            reconnect()
            swipe.isRefreshing = false
        }

        btnLogin.setOnClickListener {
            val user = userInput.text.toString().trim()
            val pass = passInput.text.toString()
            if (user.isEmpty() || pass.isEmpty()) {
                loginError.text = "请填写快期账户和密码"
                return@setOnClickListener
            }
            loginError.text = "登录中…"
            btnLogin.isEnabled = false
            io.execute {
                try {
                    val session = auth.login(user, pass)
                    prefs.edit()
                        .putString("tq_user", user)
                        .putString("tq_pass", pass)
                        .apply()
                    mainHandler.post {
                        btnLogin.isEnabled = true
                        showBoard()
                        startMd(session)
                    }
                } catch (e: Exception) {
                    mainHandler.post {
                        btnLogin.isEnabled = true
                        loginError.text = e.message ?: "登录失败"
                    }
                }
            }
        }

        val savedUser = prefs.getString("tq_user", null)
        val savedPass = prefs.getString("tq_pass", null)
        if (!savedUser.isNullOrBlank() && !savedPass.isNullOrBlank()) {
            userInput.setText(savedUser)
            passInput.setText(savedPass)
            showBoard()
            statusText.text = "自动登录中…"
            io.execute {
                try {
                    val session = auth.login(savedUser, savedPass)
                    mainHandler.post { startMd(session) }
                } catch (e: Exception) {
                    mainHandler.post {
                        showLogin()
                        loginError.text = "自动登录失败：${e.message}"
                    }
                }
            }
        } else {
            showLogin()
        }
    }

    private fun showLogin() {
        loginRoot.visibility = View.VISIBLE
        boardRoot.visibility = View.GONE
        mdClient?.stop()
    }

    private fun showBoard() {
        loginRoot.visibility = View.GONE
        boardRoot.visibility = View.VISIBLE
        webView.loadUrl("file:///android_asset/www/index.html")
    }

    private fun startMd(session: ShinnyAuth.Session) {
        mdClient?.stop()
        mdClient = DiffMdClient(
            symbol = "DCE.a2609",
            onStatus = { msg -> mainHandler.post { statusText.text = msg } },
            onBars = { bars ->
                mainHandler.post {
                    pendingBars = bars
                    maybePushBars()
                }
            }
        ).also { it.start(session) }
    }

    private fun reconnect() {
        val user = prefs.getString("tq_user", null)
        val pass = prefs.getString("tq_pass", null)
        if (user.isNullOrBlank() || pass.isNullOrBlank()) {
            showLogin()
            return
        }
        statusText.text = "重新连接…"
        io.execute {
            try {
                val session = auth.login(user, pass)
                mainHandler.post { startMd(session) }
            } catch (e: Exception) {
                mainHandler.post {
                    statusText.text = "重连失败：${e.message}"
                    Toast.makeText(this, e.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun maybePushBars() {
        val bars = pendingBars ?: return
        if (!pageReady) return
        val now = System.currentTimeMillis()
        if (now - lastPushMs < 400) return
        lastPushMs = now
        val meta = JSONObject()
            .put("symbol", "DCE.a2609")
            .put("source", "device")
        val js =
            "window.__onRawBars && window.__onRawBars($bars, $meta);"
        webView.evaluateJavascript(js, null)
    }

    inner class Bridge {
        @JavascriptInterface
        fun pageReady() {
            mainHandler.post {
                pageReady = true
                maybePushBars()
            }
        }
    }

    override fun onDestroy() {
        mdClient?.stop()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (this::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
