package com.futures.channel

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
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
        private const val WAKE_LOCK_RENEW_INTERVAL_MS = 60L * 60 * 1000 // 每小时续期一次
        private const val ALARM_HEARTBEAT_INTERVAL_MS = 60_000L // 60s AlarmManager 兜底
        private const val ALARM_REQUEST_CODE = 4201
        private const val ACTION_HEARTBEAT_ALARM = "com.futures.channel.HEARTBEAT_ALARM"
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
    private val feishu by lazy { FeishuAppNotifier() }

    /**
     * 飞书长连接客户端：只用 App ID + App Secret 建 WebSocket 长连接，
     * 用户在飞书里给机器人发私信时自动拿到其 open_id 并持久化，无需手填。
     */
    private val feishuWsListener = object : FeishuWsClient.Listener {
        override fun onOpenIdDiscovered(openId: String) {
            val old = prefs.getString("alert_feishu_open_id", null)
            if (old == openId) return
            prefs.edit().putString("alert_feishu_open_id", openId).apply()
            Log.i(TAG, "feishuWs: 自动配对成功 open_id=${openId.take(12)}…")
            mainHandler.post { onStatusInternal("飞书机器人配对成功，推送已就绪") }
            // 机器人回一条配对回执，用户能立即确认链路通了
            val appId = prefs.getString("alert_feishu_app_id", null)
            val secret = prefs.getString("alert_feishu_app_secret", null)
            if (!appId.isNullOrBlank() && !secret.isNullOrBlank()) {
                feishu.sendText(
                    appId.trim(), secret.trim(), openId,
                    "配对成功 ✅\n之后出现开多/开空信号时，我会在这里第一时间推送给你。"
                )
            }
        }

        override fun onStateChanged(state: String) {
            Log.d(TAG, "feishuWs: $state")
        }
    }
    private val feishuWs by lazy { FeishuWsClient(feishuWsListener) }

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

    private val alarmManager by lazy { getSystemService(ALARM_SERVICE) as AlarmManager }

    private val wakeLockRenewHandler = Handler(Looper.getMainLooper())
    private var wakeLockRenewTask: Runnable? = null

    private val networkCallback by lazy {
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "onNetworkAvailable: 网络恢复，主动重连")
                mainHandler.post {
                    onStatusInternal("网络已恢复，重连行情…")
                    // 若已断开则立即重连，否则触发心跳探活
                    val md = mdClient
                    if (md == null || !md.isAlive()) {
                        scheduleReconnect()
                    } else {
                        md.pokeHeartbeat()
                    }
                }
            }
        }
    }

    private val heartbeatAlarmReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_HEARTBEAT_ALARM) return
            Log.d(TAG, "heartbeatAlarmReceiver: AlarmManager 触发兜底心跳")
            // 唤醒 CPU 后再触发心跳检测
            if (wakeLock.isHeld.not()) {
                try { wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS) } catch (_: Exception) {}
            }
            mdClient?.pokeHeartbeat()
            // 重新设定下一次闹钟（实现周期性兜底）
            scheduleNextHeartbeatAlarm()
        }
    }

    private var networkRegistered = false

    private val prefs by lazy { openSecurePrefs() }

    override fun onCreate() {
        super.onCreate()
        ensureFgsChannel()
        if (wakeLock.isHeld.not()) {
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        }
        startWakeLockRenew()
        registerHeartbeatAlarmReceiver()
        registerNetworkCallback()
        scheduleNextHeartbeatAlarm()
        resyncFeishu()
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
        stopWakeLockRenew()
        cancelHeartbeatAlarm()
        unregisterHeartbeatAlarmReceiver()
        unregisterNetworkCallback()
        feishuWs.stop()
        mdClient?.destroy()
        mdClient = null
        if (wakeLock.isHeld) {
            try { wakeLock.release() } catch (_: Exception) {}
        }
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

    /**
     * 按当前设置启动/停止飞书长连接（设置保存后由 MainActivity 调用）。
     * 只要填了 App ID + App Secret 就保持长连接，用于自动配对 open_id。
     */
    fun resyncFeishu() {
        val enabled = prefs.getBoolean("alert_feishu_enabled", true)
        val appId = prefs.getString("alert_feishu_app_id", null)
        val secret = prefs.getString("alert_feishu_app_secret", null)
        if (enabled && !appId.isNullOrBlank() && !secret.isNullOrBlank()) {
            feishuWs.start(appId.trim(), secret.trim())
        } else {
            feishuWs.stop()
        }
    }

    /** 飞书长连接状态（设置页展示用） */
    fun feishuWsStatus(): String = feishuWs.statusText()

    // ===== 内部 =====

    private fun startMd(session: ShinnyAuth.Session) {
        this.session = session
        // 彻底销毁旧实例，避免 HandlerThread 泄漏
        mdClient?.destroy()
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
        val feishuEnabled = prefs.getBoolean("alert_feishu_enabled", true)
        val feishuAppId = prefs.getString("alert_feishu_app_id", null)
        val feishuAppSecret = prefs.getString("alert_feishu_app_secret", null)
        val feishuOpenId = prefs.getString("alert_feishu_open_id", null)
        Log.d(
            TAG,
            "fireOpen: kind=${sig.kind} sound=$sound vibrate=$vibrate notification=$notification " +
                "feishu=$feishuEnabled appIdSet=${!feishuAppId.isNullOrBlank()} " +
                "secretSet=${!feishuAppSecret.isNullOrBlank()} openIdSet=${!feishuOpenId.isNullOrBlank()}"
        )
        val label = if (sig.kind == "long") "开多" else "开空"
        val body = "DCE.a2609 K线出现「${label}」标记 · ${sig.barTime}"
        val posted = notifier.notifyOpen(
            kind = sig.kind,
            title = "${label}信号",
            body = body,
            sound = sound,
            vibrate = vibrate,
            notification = notification,
        )
        // ---- 兜底通道：飞书自建应用机器人（不需要建群，直接私聊机器人）----
        // 即使用户关闭了系统通知、或通知渠道被禁用，只要配置了应用凭证，
        // 这里仍会把信号推送到飞书机器人会话，确保不会漏消息。
        if (feishuEnabled && !feishuAppId.isNullOrBlank() &&
            !feishuAppSecret.isNullOrBlank() && !feishuOpenId.isNullOrBlank()
        ) {
            io.execute {
                Log.i(TAG, "fireOpen: 触发飞书推送 kind=${sig.kind}")
                feishu.send(
                    appId = feishuAppId,
                    appSecret = feishuAppSecret,
                    openId = feishuOpenId,
                    kind = sig.kind,
                    title = "${label}信号",
                    body = body,
                    extraLines = listOf(
                        "信号时间: ${sig.barTime}",
                        "合约: DCE.a2609 (豆二 2609)",
                        "周期: 5分钟 K线",
                        "来源: 通道突破策略"
                    )
                )
            }
        }
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

    // ===== WakeLock 周期续期（避免12h后过期）=====

    private fun startWakeLockRenew() {
        stopWakeLockRenew()
        val r = Runnable {
            try {
                if (wakeLock.isHeld) wakeLock.release()
                wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
                Log.d(TAG, "wakeLockRenew: 已续期，next ${WAKE_LOCK_RENEW_INTERVAL_MS / 60000}min 后")
            } catch (e: Exception) {
                Log.e(TAG, "wakeLockRenew: 续期失败", e)
            }
            wakeLockRenewHandler.postDelayed(this@MarketForegroundService.wakeLockRenewTask!!, WAKE_LOCK_RENEW_INTERVAL_MS)
        }
        wakeLockRenewTask = r
        wakeLockRenewHandler.postDelayed(r, WAKE_LOCK_RENEW_INTERVAL_MS)
    }

    private fun stopWakeLockRenew() {
        wakeLockRenewTask?.let { wakeLockRenewHandler.removeCallbacks(it) }
        wakeLockRenewTask = null
    }

    // ===== AlarmManager 兜底心跳（Doze 下唯一可靠途径）=====

    private fun registerHeartbeatAlarmReceiver() {
        val filter = IntentFilter(ACTION_HEARTBEAT_ALARM)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(heartbeatAlarmReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(heartbeatAlarmReceiver, filter)
        }
    }

    private fun unregisterHeartbeatAlarmReceiver() {
        try { unregisterReceiver(heartbeatAlarmReceiver) } catch (_: Exception) {}
    }

    private fun scheduleNextHeartbeatAlarm() {
        val triggerAt = SystemClock.elapsedRealtime() + ALARM_HEARTBEAT_INTERVAL_MS
        val pi = buildHeartbeatPendingIntent()
        try {
            when {
                // Android 12+ 需要 SCHEDULE_EXACT_ALARM 权限（Manifest 声明，用户可在设置撤销）
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    alarmManager.canScheduleExactAlarmsCompat() -> {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pi
                    )
                    Log.d(TAG, "scheduleNextHeartbeatAlarm: setExactAndAllowWhileIdle 已设定")
                }
                // 降级：不需要精确闹钟权限，Doze 下会延迟到维护窗口，但优于无
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pi
                    )
                    Log.d(TAG, "scheduleNextHeartbeatAlarm: 降级 setAndAllowWhileIdle")
                }
                else -> {
                    @Suppress("DEPRECATION")
                    alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "scheduleNextHeartbeatAlarm: 设定失败", e)
        }
    }

    private fun AlarmManager.canScheduleExactAlarmsCompat(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            canScheduleExactAlarms()
        } else true
    }

    private fun cancelHeartbeatAlarm() {
        try { alarmManager.cancel(buildHeartbeatPendingIntent()) } catch (_: Exception) {}
    }

    private fun buildHeartbeatPendingIntent(): PendingIntent {
        val intent = Intent(ACTION_HEARTBEAT_ALARM).apply { setPackage(packageName) }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(this, ALARM_REQUEST_CODE, intent, flags)
    }

    // ===== 网络变化监听 =====

    private fun registerNetworkCallback() {
        if (networkRegistered) return
        val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.registerNetworkCallback(request, networkCallback)
            networkRegistered = true
            Log.d(TAG, "registerNetworkCallback: 已注册网络监听")
        } catch (e: Exception) {
            Log.e(TAG, "registerNetworkCallback: 注册失败", e)
        }
    }

    private fun unregisterNetworkCallback() {
        if (!networkRegistered) return
        val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try { cm.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
        networkRegistered = false
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
