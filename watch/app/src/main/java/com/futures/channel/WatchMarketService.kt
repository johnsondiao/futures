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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import java.util.concurrent.Executors

/**
 * 手表行情前台服务：持有 DiffMdClient + ChannelSignalDetector（+ 可选飞书推送）。
 *
 * 与手机版 MarketForegroundService 的差异：
 *   - 无 WebView 推送（手表无图表页），行情状态/价格通过 StateFlow 暴露给 Compose UI
 *   - **只在交易时段运行**（默认，可关）：开市窗口内持 WakeLock 跑行情；
 *     闭市后停行情/停飞书/释放锁省电，用 AlarmManager 在下次开市前精确唤醒
 *   - 飞书推送默认关闭（避免与手机端重复推送），可在设置里打开
 */
class WatchMarketService : Service() {

    companion object {
        private const val TAG = "WatchMarketSvc"
        const val EXTRA_USER = "user"
        const val EXTRA_PASS = "pass"
        const val EXTRA_ACCESS_TOKEN = "access_token"
        const val EXTRA_MD_URL = "md_url"

        private const val NOTIFY_ID = 4100
        private const val CHANNEL_FGS_ID = "watch_market_v1"
        private const val RECONNECT_DELAY_MS = 5000L
        private const val WAKE_LOCK_TAG = "WatchStrategy:MarketMd"
        private const val WAKE_LOCK_TIMEOUT_MS = 12L * 60 * 60 * 1000
        private const val WAKE_LOCK_RENEW_INTERVAL_MS = 60L * 60 * 1000
        private const val PREFS_NAME = "watch_prefs"

        /** 交易时段调度：开市期间每 30s 自检一次 */
        private const val SCHEDULE_CHECK_INTERVAL_MS = 30_000L
        private const val ACTION_OPEN_ALARM = "com.futures.watch.OPEN_ALARM"
        private const val ALARM_REQUEST_CODE = 4301
    }

    interface Listener {
        fun onStatus(msg: String)
        fun onSessionExpired()
    }

    inner class LocalBinder : Binder() {
        fun get(): WatchMarketService = this@WatchMarketService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private val notifier by lazy { OpenSignalNotifier(this) }
    private val feishu by lazy { FeishuAppNotifier() }

    /** 惰性持有（需等 prefs 就绪）；策略切换时整体替换 */
    private var detectorRef: ChannelSignalDetector? = null
    private val detector: ChannelSignalDetector
        get() = detectorRef ?: makeDetector().also { detectorRef = it }

    // ===== UI 状态流 =====
    private val _status = MutableStateFlow("未连接")
    val statusFlow: StateFlow<String> = _status
    private val _price = MutableStateFlow<Double?>(null)
    val priceFlow: StateFlow<Double?> = _price
    private val _lastSignal = MutableStateFlow<ChannelSignalDetector.OpenSignal?>(null)
    val lastSignalFlow: StateFlow<ChannelSignalDetector.OpenSignal?> = _lastSignal

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
    private val wakeLockRenewHandler = Handler(Looper.getMainLooper())
    private var wakeLockRenewTask: Runnable? = null

    private val networkCallback by lazy {
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "onNetworkAvailable: 网络恢复，触发调度检查")
                mainHandler.post { checkSchedule() }
            }
        }
    }
    private var networkRegistered = false

    private val alarmManager by lazy { getSystemService(ALARM_SERVICE) as AlarmManager }

    /** 开市前精确唤醒闹钟 */
    private val openAlarmReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_OPEN_ALARM) return
            Log.i(TAG, "openAlarm: 开市闹钟触发，启动行情")
            checkSchedule()
        }
    }
    private var alarmRegistered = false

    /** 开市期间周期性自检：维持行情连接；闭市则进入休眠 */
    private val scheduleChecker = object : Runnable {
        override fun run() {
            checkSchedule()
            mainHandler.postDelayed(this, SCHEDULE_CHECK_INTERVAL_MS)
        }
    }

    private val prefs by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ===== 飞书（可选）=====
    private val feishuWsListener = object : FeishuWsClient.Listener {
        override fun onOpenIdDiscovered(openId: String) {
            val old = prefs.getString("alert_feishu_open_id", null)
            if (old == openId) return
            prefs.edit().putString("alert_feishu_open_id", openId).apply()
            Log.i(TAG, "feishuWs: 自动配对成功 open_id=${openId.take(12)}…")
            val appId = prefs.getString("alert_feishu_app_id", null)
            val secret = prefs.getString("alert_feishu_app_secret", null)
            if (!appId.isNullOrBlank() && !secret.isNullOrBlank()) {
                feishu.sendText(
                    appId.trim(), secret.trim(), openId,
                    "（手表）配对成功 ✅\n之后出现开多/开空信号时，会在这里推送给你。"
                )
            }
        }

        override fun onStateChanged(state: String) {
            Log.d(TAG, "feishuWs: $state")
            prefs.edit().putString("feishu_ws_state", state).apply()
        }
    }
    private val feishuWs by lazy { FeishuWsClient(feishuWsListener) }

    override fun onCreate() {
        super.onCreate()
        ensureFgsChannel()
        startWakeLockRenew()
        registerNetworkCallback()
        registerOpenAlarmReceiver()
        mainHandler.post(scheduleChecker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(buildFgsNotification("行情服务运行中"))
        intent?.let {
            it.getStringExtra(EXTRA_USER)?.let { v -> user = v }
            it.getStringExtra(EXTRA_PASS)?.let { v -> pass = v }
            val token = it.getStringExtra(EXTRA_ACCESS_TOKEN)
            val url = it.getStringExtra(EXTRA_MD_URL)
            if (!token.isNullOrBlank() && !url.isNullOrBlank()) {
                session = ShinnyAuth.Session(token, url)
            }
        }
        // START_STICKY 重启 / 首次启动：统一走调度（开市则连行情，闭市则休眠）
        mainHandler.removeCallbacks(scheduleChecker)
        mainHandler.post(scheduleChecker)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        listener = null
        return false
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(scheduleChecker)
        reconnectTask?.let { reconnectHandler.removeCallbacks(it) }
        reconnectTask = null
        stopWakeLockRenew()
        cancelOpenAlarm()
        unregisterOpenAlarmReceiver()
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

    fun setListener(l: Listener?) {
        listener = l
    }

    fun startWithSession(session: ShinnyAuth.Session) {
        this.session = session
        startMd(session)
    }

    fun saveCredentials(user: String, pass: String) {
        this.user = user
        this.pass = pass
        prefs.edit().putString("tq_user", user).putString("tq_pass", pass).apply()
    }

    /** 用已保存的凭登录并启动行情（UI 登录页调用）；闭市时只存凭据，等开市自动启动 */
    fun loginAndStart(user: String, pass: String, onError: (String) -> Unit) {
        saveCredentials(user, pass)
        io.execute {
            try {
                val s = ShinnyAuth().login(user, pass)
                mainHandler.post {
                    session = s
                    if (sessionAllowedNow()) {
                        startMd(s)
                    } else {
                        updateStatus("登录成功 · 当前闭市，${TradingSchedule.nextOpenText()} 自动启动")
                        scheduleOpenAlarm()
                    }
                }
            } catch (e: Exception) {
                mainHandler.post { onError(e.message ?: "登录失败") }
            }
        }
    }

    private fun makeDetector(): ChannelSignalDetector {
        return if (currentStrategy() == "60m") {
            ChannelSignalDetector(
                channelN = 60, cciP = 15, cciM = 3,
                period = ChannelSignalDetector.PERIOD_60M,
                atrN = 20, tpMults = doubleArrayOf(0.8, 1.5, 2.5),
            )
        } else {
            ChannelSignalDetector()
        }
    }

    private fun currentStrategy(): String =
        prefs.getString("strategy_profile", "5m") ?: "5m"

    fun currentProfile(): String = currentStrategy()

    /** 设置保存后由 MainActivity 调用：策略切换时重建检测器 */
    fun resyncStrategy() {
        val profile = currentStrategy()
        if (detector.period != profile) {
            detectorRef = makeDetector()
            Log.i(TAG, "resyncStrategy: 已切换到 $profile")
            updateStatus("已切换到 ${if (profile == "60m") "60 分钟" else "5 分钟"}策略")
        }
    }

    /** 按设置启动/停止飞书长连接（默认关闭，避免与手机端重复推送） */
    fun resyncFeishu() {
        val enabled = prefs.getBoolean("alert_feishu_enabled", false)
        val appId = prefs.getString("alert_feishu_app_id", null)
        val secret = prefs.getString("alert_feishu_app_secret", null)
        if (enabled && !appId.isNullOrBlank() && !secret.isNullOrBlank()) {
            feishuWs.start(appId.trim(), secret.trim())
        } else {
            feishuWs.stop()
        }
    }

    fun feishuWsStatus(): String = feishuWs.statusText()

    /** 设置页切换「仅开市时段运行」后立即重新调度 */
    fun applyScheduleNow() {
        mainHandler.removeCallbacks(scheduleChecker)
        mainHandler.post(scheduleChecker)
    }

    // ===== 交易时段调度 =====

    /** 是否允许立即跑行情：未启用时段限制，或当前在交易窗口内 */
    private fun sessionAllowedNow(): Boolean {
        val scheduled = prefs.getBoolean("run_only_in_session", true)
        return !scheduled || TradingSchedule.isOpen()
    }

    private fun checkSchedule() {
        if (sessionAllowedNow()) {
            ensureAwake()
            ensureMarketRunning()
            resyncFeishu()
        } else {
            enterSessionClosed()
        }
    }

    /** 开市窗口内：确保行情在跑 */
    private fun ensureMarketRunning() {
        val md = mdClient
        if (md != null && md.isAlive()) return
        val s = session
        if (s != null) {
            startMd(s)
            return
        }
        val savedUser = user ?: prefs.getString("tq_user", null)
        val savedPass = pass ?: prefs.getString("tq_pass", null)
        if (!savedUser.isNullOrBlank() && !savedPass.isNullOrBlank()) {
            user = savedUser
            pass = savedPass
            updateStatus("开市中 · 登录行情…")
            io.execute { reloginQuietly() }
        } else {
            updateStatus("请先登录天勤账号")
        }
    }

    /** 闭市：停行情/停飞书/释放锁省电，并预约下次开市闹钟 */
    private fun enterSessionClosed() {
        mdClient?.destroy()
        mdClient = null
        feishuWs.stop()
        releaseWakeLock()
        val msg = "闭市中 · 下次开市 ${TradingSchedule.nextOpenText()}"
        if (_status.value != msg) {
            Log.i(TAG, "enterSessionClosed: 进入闭市休眠，${TradingSchedule.nextOpenText()}")
            updateStatus(msg)
        }
        scheduleOpenAlarm()
    }

    private fun ensureAwake() {
        if (!wakeLock.isHeld) {
            try {
                wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
                Log.d(TAG, "ensureAwake: WakeLock 已获取")
            } catch (e: Exception) {
                Log.e(TAG, "ensureAwake: 获取 WakeLock 失败", e)
            }
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld) {
            try {
                wakeLock.release()
                Log.d(TAG, "releaseWakeLock: WakeLock 已释放（闭市省电）")
            } catch (_: Exception) {}
        }
    }

    /** 预约下次开市窗口开始的精确闹钟（Doze 下也能唤醒） */
    private fun scheduleOpenAlarm() {
        val delay = TradingSchedule.nextOpenAt() - System.currentTimeMillis()
        if (delay <= 0) {
            mainHandler.post { checkSchedule() }
            return
        }
        val trigger = SystemClock.elapsedRealtime() + delay
        val pi = buildOpenAlarmPendingIntent()
        try {
            if (alarmManager.canScheduleExactAlarmsCompat()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi
                )
            }
            Log.d(TAG, "scheduleOpenAlarm: 已预约 ${delay / 60000} 分钟后唤醒")
        } catch (e: Exception) {
            Log.e(TAG, "scheduleOpenAlarm: 预约失败", e)
        }
    }

    private fun cancelOpenAlarm() {
        try { alarmManager.cancel(buildOpenAlarmPendingIntent()) } catch (_: Exception) {}
    }

    private fun buildOpenAlarmPendingIntent(): PendingIntent {
        val intent = Intent(ACTION_OPEN_ALARM).apply { setPackage(packageName) }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(this, ALARM_REQUEST_CODE, intent, flags)
    }

    private fun AlarmManager.canScheduleExactAlarmsCompat(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            canScheduleExactAlarms()
        } else true
    }

    private fun registerOpenAlarmReceiver() {
        if (alarmRegistered) return
        val filter = android.content.IntentFilter(ACTION_OPEN_ALARM)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(openAlarmReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(openAlarmReceiver, filter)
        }
        alarmRegistered = true
    }

    private fun unregisterOpenAlarmReceiver() {
        if (!alarmRegistered) return
        try { unregisterReceiver(openAlarmReceiver) } catch (_: Exception) {}
        alarmRegistered = false
    }

    // ===== 内部 =====

    private fun startMd(session: ShinnyAuth.Session) {
        this.session = session
        mdClient?.destroy()
        mdClient = DiffMdClient(
            symbol = "DCE.a2609",
            onStatus = { msg -> mainHandler.post { updateStatus(msg) } },
            onBars = { bars -> mainHandler.post { handleBars(bars) } },
            onAuthFailure = { mainHandler.post { handleAuthFailure() } },
            onDisconnect = { mainHandler.post { scheduleReconnect() } }
        ).also { it.start(session) }
    }

    private fun updateStatus(msg: String) {
        _status.value = msg
        listener?.onStatus(msg)
        updateFgsNotification(msg)
    }

    private fun handleBars(bars: JSONArray) {
        // 最新价供表盘展示
        val last = bars.optJSONObject(bars.length() - 1)
        if (last != null) {
            val c = last.optDouble("close", Double.NaN)
            if (c.isFinite()) _price.value = c
        }
        io.execute {
            val signals = detector.detect(bars)
            if (signals.isNotEmpty()) {
                mainHandler.post {
                    for (sig in signals) fireOpen(sig)
                }
            }
        }
    }

    private fun fireOpen(sig: ChannelSignalDetector.OpenSignal) {
        val enabled = prefs.getBoolean("alert_enabled", true)
        if (!enabled) {
            Log.d(TAG, "fireOpen: alert_enabled=false，跳过")
            return
        }
        val vibrate = prefs.getBoolean("alert_vibrate", true)
        val feishuEnabled = prefs.getBoolean("alert_feishu_enabled", false)
        val feishuAppId = prefs.getString("alert_feishu_app_id", null)
        val feishuAppSecret = prefs.getString("alert_feishu_app_secret", null)
        val feishuOpenId = prefs.getString("alert_feishu_open_id", null)

        _lastSignal.value = sig

        val label = if (sig.kind == "long") "开多" else "开空"
        val periodLabel = if (sig.period == ChannelSignalDetector.PERIOD_60M) "60分钟" else "5分钟"
        val body = "DCE.a2609 ${periodLabel}K线出现「${label}」标记 · ${sig.barTime}"
        val posted = notifier.notifyOpen(
            kind = sig.kind,
            title = "${label}信号",
            body = body,
            sound = true,
            vibrate = vibrate,
            notification = true,
        )

        // 飞书推送（可选；手机端通常已推送，手表端默认关闭避免重复）
        if (feishuEnabled && !feishuAppId.isNullOrBlank() &&
            !feishuAppSecret.isNullOrBlank() && !feishuOpenId.isNullOrBlank()
        ) {
            io.execute {
                feishu.send(
                    appId = feishuAppId,
                    appSecret = feishuAppSecret,
                    openId = feishuOpenId,
                    kind = sig.kind,
                    title = "${label}信号（手表）",
                    body = body,
                    extraLines = buildList {
                        add("信号时间: ${sig.barTime}")
                        add("合约: DCE.a2609 (豆二 2609)")
                        add("周期: ${periodLabel} K线")
                        if (sig.entry.isFinite()) {
                            add("参考开仓: ${String.format("%.0f", sig.entry)}")
                            if (sig.tp1.isFinite()) add("止盈1: ${String.format("%.0f", sig.tp1)}")
                            if (sig.tp2.isFinite()) add("止盈2: ${String.format("%.0f", sig.tp2)}")
                            if (sig.tp3.isFinite()) add("止盈3: ${String.format("%.0f", sig.tp3)}")
                        }
                        add("来源: 通道突破策略 · Watch")
                    }
                )
            }
        }
        if (!posted) {
            Log.w(TAG, "fireOpen: 通知发送失败（检查通知权限）")
            updateStatus("⚠️ 通知未发出：请检查通知权限")
        }
    }

    private fun handleAuthFailure() {
        listener?.onSessionExpired()
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
            updateStatus("重登失败：${e.message}")
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        reconnectTask?.let { reconnectHandler.removeCallbacks(it) }
        val r = Runnable {
            // 统一走调度：闭市时不会重连，改为休眠 + 预约开市闹钟
            checkSchedule()
        }
        reconnectTask = r
        reconnectHandler.postDelayed(r, RECONNECT_DELAY_MS)
    }

    // ===== 前台通知 =====

    private fun ensureFgsChannel() {
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
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
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
            startForeground(
                NOTIFY_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFY_ID, notification)
        }
    }

    // ===== WakeLock 周期续期 =====

    private fun startWakeLockRenew() {
        stopWakeLockRenew()
        val r = object : Runnable {
            override fun run() {
                try {
                    // 只在持锁（开市运行中）时续期；闭市已放锁则跳过
                    if (wakeLock.isHeld) {
                        wakeLock.release()
                        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "wakeLockRenew: 续期失败", e)
                }
                wakeLockRenewHandler.postDelayed(this, WAKE_LOCK_RENEW_INTERVAL_MS)
            }
        }
        wakeLockRenewTask = r
        wakeLockRenewHandler.postDelayed(r, WAKE_LOCK_RENEW_INTERVAL_MS)
    }

    private fun stopWakeLockRenew() {
        wakeLockRenewTask?.let { wakeLockRenewHandler.removeCallbacks(it) }
        wakeLockRenewTask = null
    }

    private fun registerNetworkCallback() {
        if (networkRegistered) return
        val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.registerNetworkCallback(request, networkCallback)
            networkRegistered = true
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
}
