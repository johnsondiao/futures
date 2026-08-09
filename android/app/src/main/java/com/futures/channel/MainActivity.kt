package com.futures.channel

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
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

    companion object {
        private const val TAG = "MainActivity"
        private const val REQ_NOTIFY = 1001
        private const val REQ_BATTERY_OPT = 1002
        private const val PREF_KEY_BATTERY_PROMPTED = "battery_opt_prompted"
    }

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
    private val feishuNotifier by lazy { FeishuAppNotifier() }
    private var service: MarketForegroundService? = null
    private var bound = false
    private var pageReady = false
    private var pendingBars: JSONArray? = null
    private var lastPushMs = 0L

    private val activityListener = object : MarketForegroundService.Listener {
        override fun onBars(bars: JSONArray) {
            pendingBars = bars
            maybePushBars()
        }
        override fun onStatus(msg: String) {
            statusText.text = msg
        }
        override fun onSessionExpired() {
            reconnect()
        }
    }

    private val serviceConn = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, binder: android.os.IBinder?) {
            service = (binder as? MarketForegroundService.LocalBinder)?.get()
            service?.setListener(activityListener)
        }
        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            service = null
        }
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
                        startServiceWithSession(session)
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
                    mainHandler.post { startServiceWithSession(session) }
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

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // 处理通知「✕ 清除提醒」按钮
        if (intent?.action == OpenSignalNotifier.ACTION_DISMISS_ALERT) {
            openNotifier.cancelAlert()
            Log.d(TAG, "onNewIntent: 用户从通知清除提醒")
        }
    }

    private fun ensureNotifyPermission() {
        openNotifier.ensureChannel()
        // 1) 先检查渠道是否被用户手动禁用（这个比权限更常见）
        if (openNotifier.isChannelBlockedByUser()) {
            Log.w(TAG, "ensureNotifyPermission: 渠道 ${OpenSignalNotifier.CHANNEL_ID} 被用户禁用")
            Toast.makeText(
                this,
                "「开仓信号提醒」渠道已被禁用：请到系统设置 → 应用 → 本应用 → 通知 → 开启「开仓信号提醒」",
                Toast.LENGTH_LONG
            ).show()
        }
        // 2) Android 13+ 请求 POST_NOTIFICATIONS 运行时权限
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.d(TAG, "ensureNotifyPermission: 请求 POST_NOTIFICATIONS 权限")
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIFY
            )
        }
    }

    /**
     * 引导用户将 App 加入电池优化白名单。
     * - 进入白名单后，App 不受 Doze 网络冻结限制，后台 WebSocket 可稳定保活
     * - 仅在首次进入盘面时提示一次（用 SharedPreferences 记录），避免反复打扰
     */
    private fun ensureBatteryOptimizationWhitelist() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Log.d(TAG, "ensureBatteryOptimizationWhitelist: 已在白名单")
            return
        }
        if (prefs.getBoolean(PREF_KEY_BATTERY_PROMPTED, false)) {
            Log.d(TAG, "ensureBatteryOptimizationWhitelist: 已提示过，跳过")
            return
        }
        prefs.edit().putBoolean(PREF_KEY_BATTERY_PROMPTED, true).apply()
        Toast.makeText(
            this,
            "为确保后台行情持续更新，请在接下来弹出的窗口中选择「允许」",
            Toast.LENGTH_LONG
        ).show()
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_BATTERY_OPT)
        } catch (e: Exception) {
            Log.w(TAG, "ensureBatteryOptimizationWhitelist: 直接请求失败，跳转设置页", e)
            try {
                val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(fallback)
            } catch (_: Exception) {
                Toast.makeText(this, "请手动到设置 → 电池 → 后台限制，将本应用设为不优化", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFY) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "onRequestPermissionsResult: POST_NOTIFICATIONS granted=$granted")
            if (!granted) {
                Toast.makeText(
                    this,
                    "未授予通知权限：后台时将看不到系统通知栏提醒（仍有声音/震动）",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(this, "通知权限已授予", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showLogin() {
        loginRoot.visibility = View.VISIBLE
        boardRoot.visibility = View.GONE
    }

    private fun showBoard() {
        loginRoot.visibility = View.GONE
        boardRoot.visibility = View.VISIBLE
        ensureNotifyPermission()
        ensureBatteryOptimizationWhitelist()
        webView.loadUrl("file:///android_asset/www/index.html")
    }

    /** 启动行情前台服务并绑定；session 通过 Intent 传入，Service 自行 startMd */
    private fun startServiceWithSession(session: ShinnyAuth.Session) {
        val user = prefs.getString("tq_user", null)
        val pass = prefs.getString("tq_pass", null)
        val intent = Intent(this, MarketForegroundService::class.java).apply {
            if (!user.isNullOrBlank()) putExtra(MarketForegroundService.EXTRA_USER, user)
            if (!pass.isNullOrBlank()) putExtra(MarketForegroundService.EXTRA_PASS, pass)
            putExtra(MarketForegroundService.EXTRA_ACCESS_TOKEN, session.accessToken)
            putExtra(MarketForegroundService.EXTRA_MD_URL, session.mdUrl)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        if (!bound) {
            bound = true
            bindService(
                Intent(this, MarketForegroundService::class.java),
                serviceConn,
                android.content.Context.BIND_AUTO_CREATE
            )
        }
        service?.startWithSession(session)
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
                mainHandler.post {
                    val s = service
                    if (s != null) s.updateSession(session) else startServiceWithSession(session)
                }
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

        /** @deprecated 开仓响铃改由 MarketForegroundService 的 ChannelSignalDetector 原生触发，
         *  新版 app.js 不再调用本接口；保留仅为兼容旧 JS 缓存。 */
        @Deprecated("改由原生 ChannelSignalDetector 触发，新 JS 不再调用")
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
                openNotifier.ensureChannel()
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
                val channelBlocked = openNotifier.isChannelBlockedByUser()
                val permGranted = openNotifier.canPostNotification()
                Log.d(
                    TAG,
                    "testOpenAlert: notification=$notification posted=$posted " +
                        "channelBlocked=$channelBlocked canPost=$permGranted"
                )
                val msg = when {
                    notification && channelBlocked ->
                        "已震动/响铃，但「开仓信号提醒」渠道被禁用：请到系统设置里开启该渠道"
                    notification && !permGranted ->
                        "已震动/响铃，但系统通知被拒：请到手机设置里允许本应用通知"
                    notification && !posted ->
                        "已震动/响铃，但系统通知发送失败（请检查通知设置）"
                    notification ->
                        "试听已发送：提示音 + 震动 + 通知栏（若没看到通知，请检查系统勿扰模式）"
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
                .put("feishu_enabled", prefs.getBoolean("alert_feishu_enabled", true))
                .put("feishu_app_id", prefs.getString("alert_feishu_app_id", "") ?: "")
                .put("feishu_app_secret", prefs.getString("alert_feishu_app_secret", "") ?: "")
                .put("feishu_open_id", prefs.getString("alert_feishu_open_id", "") ?: "")
                .put("feishu_mobile", prefs.getString("alert_feishu_mobile", "") ?: "")
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
                    .putBoolean("alert_feishu_enabled", o.optBoolean("feishu_enabled", true))
                    .putString(
                        "alert_feishu_app_id",
                        o.optString("feishu_app_id", "").trim().ifBlank { null }
                    )
                    .putString(
                        "alert_feishu_app_secret",
                        o.optString("feishu_app_secret", "").trim().ifBlank { null }
                    )
                    .putString(
                        "alert_feishu_open_id",
                        o.optString("feishu_open_id", "").trim().ifBlank { null }
                    )
                    .putString(
                        "alert_feishu_mobile",
                        o.optString("feishu_mobile", "").trim().ifBlank { null }
                    )
                    .apply()
                if (o.optBoolean("enabled", true) && o.optBoolean("notification", true)) {
                    mainHandler.post { ensureNotifyPermission() }
                }
            } catch (_: Exception) {
            }
        }

        /**
         * 飞书 Open ID 自动绑定：通过用户填写的手机号查询对应的飞书 open_id，
         * 成功后自动保存到 prefs，免去用户去飞书后台手动复制 open_id。
         *
         * 调用流程：前端点「点击绑定 Open ID」按钮 → 此方法在 io 线程执行 →
         * 调用 FeishuAppNotifier.fetchOpenIdByMobile → 通过 JS 回调返回结果。
         *
         * @param appId     飞书自建应用 App ID
         * @param appSecret 飞书自建应用 App Secret
         * @param mobile    用户飞书账号绑定的手机号
         * @param callback  JS 端回调函数名，回传 {openId, mobile, errorMsg} 对象
         */
        @JavascriptInterface
        fun bindFeishuOpenId(appId: String, appSecret: String, mobile: String, callback: String) {
            val missing = when {
                appId.isBlank() -> "请先填写 App ID"
                appSecret.isBlank() -> "请先填写 App Secret"
                mobile.isBlank() -> "请先填写飞书账号绑定的手机号"
                !mobile.trim().matches(Regex("^[+]?\\d{6,15}$")) ->
                    "手机号格式不正确，请填写 11 位国内手机号（海外需加 +国家码）"
                else -> null
            }
            if (missing != null) {
                val json = JSONObject()
                    .put("openId", JSONObject.NULL)
                    .put("mobile", mobile)
                    .put("errorMsg", missing)
                val js = "$callback && $callback($json);"
                mainHandler.post { webView.evaluateJavascript(js, null) }
                return
            }
            io.execute {
                val result = feishuNotifier.fetchOpenIdByMobile(
                    appId.trim(), appSecret.trim(), mobile.trim()
                )
                // 成功则同时保存 open_id 和 mobile 到 prefs，下次打开设置页时直接显示已绑定
                if (result.openId != null) {
                    prefs.edit()
                        .putString("alert_feishu_open_id", result.openId)
                        .putString("alert_feishu_mobile", mobile.trim())
                        .apply()
                }
                val json = JSONObject()
                    .put("openId", result.openId ?: JSONObject.NULL)
                    .put("mobile", mobile.trim())
                    .put("errorMsg", result.errorMsg ?: JSONObject.NULL)
                val js = "$callback && $callback($json);"
                mainHandler.post { webView.evaluateJavascript(js, null) }
            }
        }

        /** 飞书测试推送：在 io 线程执行，通过 JS 回调把结果字符串返回给 UI */
        @JavascriptInterface
        fun testFeishuPush(appId: String, appSecret: String, openId: String, callback: String) {
            val missing = when {
                appId.isBlank() -> "请先填写 App ID"
                appSecret.isBlank() -> "请先填写 App Secret"
                openId.isBlank() -> "请先点击「绑定 Open ID」按钮完成自动绑定"
                else -> null
            }
            if (missing != null) {
                val json = JSONObject().put("ok", false).put("msg", missing)
                val js = "$callback && $callback($json);"
                mainHandler.post { webView.evaluateJavascript(js, null) }
                return
            }
            io.execute {
                val result = feishuNotifier.test(appId.trim(), appSecret.trim(), openId.trim())
                val ok = result.startsWith("✅")
                val json = JSONObject().put("ok", ok).put("msg", result)
                val js = "$callback && $callback($json);"
                mainHandler.post { webView.evaluateJavascript(js, null) }
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

    override fun onResume() {
        super.onResume()
        // 前台时恢复向 WebView 推送 bars（Service 的 detector 始终运行）
        service?.setListener(activityListener)
    }

    override fun onPause() {
        super.onPause()
        // 切后台停止推 WebView（JS 会暂停），但 Service 继续保活 + 检测开仓信号
        service?.setListener(null)
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(serviceConn)
            bound = false
        }
        // 不 stopService：前台服务继续运行，保持后台行情与开仓提醒
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
