package com.futures.channel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import java.util.concurrent.Executors

/**
 * 行情保活前台服务：持有 DiffMdClient + OpenSignalNotifier + ChannelSignalDetector。
 *
 * - 进程优先级提升至前台，避免后台被回收
 * - PARTIAL_WAKE_LOCK 保持 CPU，对抗 Doze 网络冻结
 * - 开仓信号检测在原生层独立运行，不依赖 WebView JS（后台时 JS 会被系统暂停）
 * - 前台时通过 Listener 把 bars 推给 MainActivity 的 WebView 做展示
 */
class MarketForegroundService : Service() {

    companion object {
        private const val TAG = "MarketFgs"

        const val EXTRA_USER = "user"
        const val EXTRA_PASS = "pass"
        const val EXTRA_ACCESS_TOKEN = "access_token"
        const val EXTRA_MD_URL = "md_url"

        private const val NOTIFY_ID = 4100
        private const val CHANNEL_FGS_ID = "market_service_v1"
        private const val RECONNECT_DELAY_MS = 5000L
        private const val WAKE_LOCK_TAG = "ChannelStrategy:MarketMd"
        private const val WAKE_LOCK_TIMEOUT_MS = 12L * 60 * 60 * 1000 // 12h 上限防泄漏
        private const val PREFS_NAME = "ths_secure"
    }

    interface Listener {
        /** 前台时收到新 K 线，推给 WebView 渲染 */
        fun onBars(bars: JSONArray)
        /** 行情状态文本，更新 UI */
        fun onStatus(msg: String)
        /** 鉴权失败，要求前台 Activity 重登（后台时 Service 自愈，不回调） */
        fun onSessionExpired()
    }

    inner class LocalBinder : Binder() {
        fun get(): MarketForegroundService = this@MarketForegroundService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private val notifier by lazy { OpenSignalNotifier(this) }
    private val detector by lazy { ChannelSignalDetector() }

    @Volatile private var listener: Listener? = null
    private var mdClient: DiffMdClient? = null
    private var session: ShinnyAuth.Session? = null
    private var user: String? = null
    private var pass: String? = null

    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectTask: Runnable? = null

    private val wakeLock by lazy {
        (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG
        ).apply { setReferenceCounted(false) }
    }

    private val prefs by lazy { openSecurePrefs() }

    override fun onCreate() {
        super.onCreate()
        ensureFgsChannel()
        if (wakeLock.isHeld.not()) {
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 必须在 5s 内调用 startForeground，否则 ANR/崩溃
        startForegroundCompat(buildFgsNotification("行情服务运行中"))

        intent?.let {
            it.getStringExtra(EXTRA_USER)?.let { v -> user = v }
            it.getStringExtra(EXTRA_PASS)?.let { v -> pass = v }
            val token = it.getStringExtra(EXTRA_ACCESS_TOKEN)
            val url = it.getStringExtra(EXTRA_MD_URL)
            if (!token.isNullOrBlank() && !url.isNullOrBlank()) {
                startMd(ShinnyAuth.Session(token, url))
            }
        }

        // START_STICKY 重启时 intent==null：从持久化凭据重登
        if (intent == null && mdClient == null) {
            val savedUser = prefs.getString("tq_user", null)
            val savedPass = prefs.getString("tq_pass", null)
            if (!savedUser.isNullOrBlank() && !savedPass.isNullOrBlank()) {
                user = savedUser
                pass = savedPass
                io.execute { reloginQuietly() }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        listener = null
        return false
    }

    override fun onDestroy() {
        reconnectTask?.let { reconnectHandler.removeCallbacks(it) }
        reconnectTask = null
        mdClient?.stop()
        mdClient = null
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    // ===== 对外 API（binder 调用）=====

    fun startWithSession(session: ShinnyAuth.Session) {
        this.session = session
        startMd(session)
    }

    fun updateSession(session: ShinnyAuth.Session) {
        this.session = session
        startMd(session)
    }

    fun setListener(l: Listener?) {
        listener = l
    }

    // ===== 内部 =====

    private fun startMd(session: ShinnyAuth.Session) {
        this.session = session
        mdClient?.stop()
        mdClient = DiffMdClient(
            symbol = "DCE.a2609",
            onStatus = { msg -> mainHandler.post { onStatusInternal(msg) } },
            onBars = { bars -> mainHandler.post { handleBars(bars) } },
            onAuthFailure = { mainHandler.post { handleAuthFailure() } },
            onDisconnect = { mainHandler.post { scheduleReconnect() } }
        ).also { it.start(session) }
    }

    private fun onStatusInternal(msg: String) {
        listener?.onStatus(msg)
        updateFgsNotification(msg)
    }

    private fun handleBars(bars: JSONArray) {
        // (a) 前台时推给 WebView 渲染
        listener?.onBars(bars)
        // (b) 原生开仓检测（前后台都跑，不依赖 WebView）
        io.execute {
            val signals = detector.detect(bars)
            if (signals.isNotEmpty()) {
                Log.d(TAG, "handleBars: detector 发现 ${signals.size} 个新信号")
                signals.forEach { sig ->
                    Log.i(
                        TAG,
                        "  → 开仓信号: kind=${sig.kind} time=${sig.time} barTime=${sig.barTime}"
                    )
                }
                mainHandler.post {
                    for (sig in signals) fireOpen(sig)
                }
            }
        }
    }

    private fun fireOpen(sig: ChannelSignalDetector.OpenSignal) {
        val enabled = prefs.getBoolean("alert_enabled", true)
        if (!enabled) {
            Log.d(TAG, "fireOpen: alert_enabled=false，总开关关闭，跳过")
            return
        }
        val sound = prefs.getBoolean("alert_sound", true)
        val vibrate = prefs.getBoolean("alert_vibrate", true)
        val notification = prefs.getBoolean("alert_notification", true)
        Log.d(
            TAG,
            "fireOpen: kind=${sig.kind} sound=$sound vibrate=$vibrate notification=$notification"
        )
        val label = if (sig.kind == "long") "开多" else "开空"
        val posted = notifier.notifyOpen(
            kind = sig.kind,
            title = "${label}信号",
            body = "DCE.a2609 K线出现「${label}」标记 · ${sig.barTime}",
            sound = sound,
            vibrate = vibrate,
            notification = notification,
        )
        if (notification && !posted) {
            // 失败：权限/渠道问题，把原因写入状态栏文本，方便用户自查
            val reason = when {
                !notifier.canPostNotification() -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) "通知权限未授予"
                    else if (notifier.isChannelBlockedByUser()) "渠道被手动禁用，请到设置开启"
                    else "App 通知开关被关闭"
                }
                else -> "未知原因"
            }
            Log.w(TAG, "fireOpen: 通知发送失败 → $reason")
            onStatusInternal("⚠️ 通知未发：$reason")
        } else {
            Log.i(TAG, "fireOpen: 通知已处理 posted=$posted")
        }
    }

    private fun handleAuthFailure() {
        // 前台时通知 Activity 走 UI 重登流程
        listener?.onSessionExpired()
        // 后台（无 listener）时 Service 自愈：用保存的凭据重登
        val u = user
        val p = pass
        if (u.isNullOrBlank() || p.isNullOrBlank()) {
            scheduleReconnect()
            return
        }
        io.execute { reloginQuietly() }
    }

    private fun reloginQuietly() {
        val u = user ?: return
        val p = pass ?: return
        try {
            val newSession = ShinnyAuth().login(u, p)
            mainHandler.post { startMd(newSession) }
        } catch (e: Exception) {
            onStatusInternal("重登失败：${e.message}")
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        reconnectTask?.let { reconnectHandler.removeCallbacks(it) }
        val r = Runnable {
            val s = session
            if (s != null) {
                startMd(s)
            } else {
                // 无 session，尝试重登
                io.execute { reloginQuietly() }
            }
        }
        reconnectTask = r
        reconnectHandler.postDelayed(r, RECONNECT_DELAY_MS)
    }

    // ===== 前台通知 =====

    private fun ensureFgsChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_FGS_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_FGS_ID,
            "行情服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持行情订阅与开仓提醒在后台运行"
            setShowBadge(false)
        }
        mgr.createNotificationChannel(channel)
    }

    private fun buildFgsNotification(text: String): Notification {
        val launch = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pi = PendingIntent.getActivity(this, 0, launch, piFlags)
        return NotificationCompat.Builder(this, CHANNEL_FGS_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("行情监控运行中")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pi)
            .build()
    }

    private fun updateFgsNotification(msg: String) {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        try {
            mgr.notify(NOTIFY_ID, buildFgsNotification(msg))
        } catch (_: Exception) {
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFY_ID, notification)
        }
    }

    private fun openSecurePrefs() = EncryptedSharedPreferences.create(
        this,
        PREFS_NAME,
        MasterKey.Builder(this)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
}
