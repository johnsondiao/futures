package com.futures.channel

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.textfield.TextInputEditText
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var statusText: TextView
    private lateinit var boardRoot: LinearLayout
    private lateinit var loginRoot: View
    private lateinit var userInput: TextInputEditText
    private lateinit var passInput: TextInputEditText
    private lateinit var loginError: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val auth = ShinnyAuth()
    private val openNotifier by lazy { OpenSignalNotifier(this) }
    private var mdClient: DiffMdClient? = null
    private var pageReady = false
    private var pendingBars: JSONArray? = null
    private var lastPushMs = 0L

    companion object {
        private const val REQ_NOTIFY = 1001
    }

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

        openNotifier.ensureChannel()

        btnLogin.setOnClickListener {
            val user = userInput.text.toString().trim()
            val pass = passInput.text.toString()
            if (user.isEmpty() || pass.isEmpty()) {
                loginError.text = "请填写天勤账号和密码"
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

    private fun ensureNotifyPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIFY
            )
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
        ensureNotifyPermission()
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

        /** JS 检测到 K 线新出现「开多/开空」标记时调用。kind: long|short */
        @JavascriptInterface
        fun notifyOpenSignal(kind: String, title: String, body: String) {
            mainHandler.post {
                if (!prefs.getBoolean("alert_enabled", true)) return@post
                val sound = prefs.getBoolean("alert_sound", true)
                val vibrate = prefs.getBoolean("alert_vibrate", true)
                val notification = prefs.getBoolean("alert_notification", true)
                val toast = prefs.getBoolean("alert_toast", true)
                val posted = openNotifier.notifyOpen(
                    kind = kind,
                    title = title,
                    body = body,
                    sound = sound,
                    vibrate = vibrate,
                    notification = notification,
                )
                if (notification && !posted) {
                    ensureNotifyPermission()
                    Toast.makeText(
                        this@MainActivity,
                        "请允许「通知」权限，否则看不到系统通知栏提醒",
                        Toast.LENGTH_LONG
                    ).show()
                } else if (toast) {
                    Toast.makeText(this@MainActivity, title, Toast.LENGTH_LONG).show()
                }
            }
        }

        /** 设置页「试听」：强制走一遍声音/震动/通知，并回报结果 */
        @JavascriptInterface
        fun testOpenAlert() {
            mainHandler.post {
                if (!prefs.getBoolean("alert_enabled", true)) {
                    Toast.makeText(
                        this@MainActivity,
                        "总开关已关闭：请先打开「启用提醒」",
                        Toast.LENGTH_LONG
                    ).show()
                    return@post
                }
                ensureNotifyPermission()
                val notification = prefs.getBoolean("alert_notification", true)
                // 试听固定打开声音+震动，方便确认通道是否通
                val posted = openNotifier.notifyOpen(
                    kind = "long",
                    title = "开多信号（试听）",
                    body = "若听到提示音/震动，说明提醒通道正常",
                    sound = true,
                    vibrate = true,
                    notification = notification,
                )
                val msg = when {
                    notification && !posted ->
                        "已震动/响铃，但系统通知被拒：请到手机设置里允许本应用通知"
                    notification ->
                        "试听已发送：提示音 + 震动 + 通知栏"
                    else ->
                        "试听已发送：提示音 + 震动（未开系统通知）"
                }
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            }
        }

        @JavascriptInterface
        fun getAlertSettings(): String {
            return JSONObject()
                .put("enabled", prefs.getBoolean("alert_enabled", true))
                .put("sound", prefs.getBoolean("alert_sound", true))
                .put("vibrate", prefs.getBoolean("alert_vibrate", true))
                .put("notification", prefs.getBoolean("alert_notification", true))
                .put("toast", prefs.getBoolean("alert_toast", true))
                .toString()
        }

        @JavascriptInterface
        fun setAlertSettings(json: String) {
            try {
                val o = JSONObject(json)
                prefs.edit()
                    .putBoolean("alert_enabled", o.optBoolean("enabled", true))
                    .putBoolean("alert_sound", o.optBoolean("sound", true))
                    .putBoolean("alert_vibrate", o.optBoolean("vibrate", true))
                    .putBoolean("alert_notification", o.optBoolean("notification", true))
                    .putBoolean("alert_toast", o.optBoolean("toast", true))
                    .apply()
                if (o.optBoolean("enabled", true) && o.optBoolean("notification", true)) {
                    mainHandler.post { ensureNotifyPermission() }
                }
            } catch (_: Exception) {
            }
        }

        /** 手指在 K 线区域操作时关闭下拉刷新，避免和拖图抢手势 */
        @JavascriptInterface
        fun setSwipeEnabled(enabled: Boolean) {
            mainHandler.post {
                swipe.isEnabled = enabled
            }
        }
    }

    override fun onDestroy() {
        mdClient?.stop()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (this::webView.isInitialized) {
            webView.evaluateJavascript(
                "(function(){if(window.__closeTopPanel&&window.__closeTopPanel())return true;return false;})();"
            ) { result ->
                if (result == "true") return@evaluateJavascript
                mainHandler.post {
                    if (webView.canGoBack()) webView.goBack()
                    else {
                        @Suppress("DEPRECATION")
                        super.onBackPressed()
                    }
                }
            }
            return
        }
        super.onBackPressed()
    }
}
