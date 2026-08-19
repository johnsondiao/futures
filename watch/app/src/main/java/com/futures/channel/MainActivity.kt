package com.futures.channel

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import kotlinx.coroutines.launch

/**
 * 手表主界面（Wear Compose）：
 *   - 主页：最新价 + 行情状态 + 最近信号 + 进设置
 *   - 设置：提醒开关/震动/策略切换（5m/60m）/飞书推送（默认关，避免与手机重复）
 *   - 登录：首次使用输入天勤账号密码
 */
class MainActivity : ComponentActivity() {

    private var service: WatchMarketService? = null
    private var bound = false

    private val statusState = mutableStateOf("启动中…")
    private val priceState = mutableStateOf<Double?>(null)
    private val signalState = mutableStateOf<ChannelSignalDetector.OpenSignal?>(null)

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? WatchMarketService.LocalBinder)?.get() ?: return
            service = svc
            svc.setListener(object : WatchMarketService.Listener {
                override fun onStatus(msg: String) {}
                override fun onSessionExpired() {}
            })
            lifecycleScope.launch {
                launch { svc.statusFlow.collect { statusState.value = it } }
                launch { svc.priceFlow.collect { priceState.value = it } }
                launch { svc.lastSignalFlow.collect { signalState.value = it } }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private val notifyPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifyPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        startWatchService()
        bindService(Intent(this, WatchMarketService::class.java), conn, BIND_AUTO_CREATE)
        bound = true

        setContent {
            WatchRoot(
                status = statusState.value,
                price = priceState.value,
                signal = signalState.value,
                getService = { service },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == OpenSignalNotifier.ACTION_DISMISS_ALERT) {
            OpenSignalNotifier(this).cancelAlert()
        }
    }

    private fun startWatchService() {
        val p = getSharedPreferences("watch_prefs", MODE_PRIVATE)
        val intent = Intent(this, WatchMarketService::class.java).apply {
            p.getString("tq_user", null)?.let { putExtra(WatchMarketService.EXTRA_USER, it) }
            p.getString("tq_pass", null)?.let { putExtra(WatchMarketService.EXTRA_PASS, it) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(conn)
            bound = false
        }
        // 不停服务：后台继续盯盘
        super.onDestroy()
    }
}

// ===== Compose UI =====

private enum class Screen { HOME, SETTINGS, LOGIN }

@Composable
private fun WatchRoot(
    status: String,
    price: Double?,
    signal: ChannelSignalDetector.OpenSignal?,
    getService: () -> WatchMarketService?,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("watch_prefs", android.content.Context.MODE_PRIVATE) }
    var screen by remember {
        mutableStateOf(
            if (prefs.getString("tq_user", null).isNullOrBlank()) Screen.LOGIN else Screen.HOME
        )
    }
    val strategy = remember(screen) { prefs.getString("strategy_profile", "5m") ?: "5m" }
    val symbol = remember(screen) {
        prefs.getString("trade_symbol", null)?.trim()?.takeIf { it.isNotBlank() }
            ?: DiffMdClient.DEFAULT_SYMBOL
    }

    when (screen) {
        Screen.LOGIN -> LoginScreen(
            getService = getService,
            onDone = { screen = Screen.HOME },
        )
        Screen.HOME -> HomeScreen(
            status = status,
            price = price,
            signal = signal,
            strategy = strategy,
            symbol = symbol,
            openSettings = { screen = Screen.SETTINGS },
        )
        Screen.SETTINGS -> SettingsScreen(
            getService = getService,
            onBack = { screen = Screen.HOME },
        )
    }
}

@Composable
private fun HomeScreen(
    status: String,
    price: Double?,
    signal: ChannelSignalDetector.OpenSignal?,
    strategy: String,
    symbol: String,
    openSettings: () -> Unit,
) {
    Scaffold(timeText = { TimeText() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "$symbol · " + if (strategy == "60m") "60 分钟策略" else "5 分钟策略",
                fontSize = 11.sp,
                color = Color(0xFF9AA0A6),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = price?.let { String.format("%.0f", it) } ?: "—",
                fontSize = 36.sp,
                color = Color.White,
            )
            Text(
                text = status,
                fontSize = 11.sp,
                color = Color(0xFFB0BEC5),
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            signal?.let { sig ->
                Spacer(Modifier.height(3.dp))
                val label = if (sig.kind == "long") "开多" else "开空"
                Text(
                    text = "${label}信号 · ${sig.barTime}",
                    fontSize = 12.sp,
                    color = if (sig.kind == "long") Color(0xFFEF5350) else Color(0xFF66BB6A),
                )
            }
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = openSettings,
                modifier = Modifier.size(ButtonDefaults.SmallButtonSize),
            ) {
                Text("设置", fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun WearInput(label: String, value: String, onValueChange: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(label, fontSize = 10.sp, color = Color(0xFF9AA0A6))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
        )
    }
}

@Composable
private fun LoginScreen(
    getService: () -> WatchMarketService?,
    onDone: () -> Unit,
) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val listState = rememberScalingLazyListState()

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Text("登录天勤账号", fontSize = 14.sp, color = Color.White)
            }
            item {
                WearInput("账号", user) { user = it }
            }
            item {
                WearInput("密码", pass) { pass = it }
            }
            item {
                error?.let {
                    Text(it, fontSize = 11.sp, color = Color(0xFFEF5350), textAlign = TextAlign.Center)
                }
            }
            item {
                Button(
                    onClick = {
                        val svc = getService()
                        if (svc == null) {
                            error = "服务未就绪，请稍后再试"
                            return@Button
                        }
                        if (user.isBlank() || pass.isBlank()) {
                            error = "请填写账号和密码"
                            return@Button
                        }
                        error = null
                        svc.loginAndStart(user.trim(), pass.trim()) { msg -> error = msg }
                        onDone()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("登录并开始监控")
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    getService: () -> WatchMarketService?,
    onBack: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("watch_prefs", android.content.Context.MODE_PRIVATE) }

    var alertEnabled by remember { mutableStateOf(prefs.getBoolean("alert_enabled", true)) }
    var vibrate by remember { mutableStateOf(prefs.getBoolean("alert_vibrate", true)) }
    var runOnlyInSession by remember { mutableStateOf(prefs.getBoolean("run_only_in_session", true)) }
    var feishuEnabled by remember { mutableStateOf(prefs.getBoolean("alert_feishu_enabled", false)) }
    var feishuAppId by remember { mutableStateOf(prefs.getString("alert_feishu_app_id", "") ?: "") }
    var feishuAppSecret by remember { mutableStateOf(prefs.getString("alert_feishu_app_secret", "") ?: "") }
    var strategy by remember { mutableStateOf(prefs.getString("strategy_profile", "5m") ?: "5m") }
    var tradeSymbol by remember { mutableStateOf(prefs.getString("trade_symbol", "") ?: "") }
    var symbolError by remember { mutableStateOf<String?>(null) }
    val listState = rememberScalingLazyListState()

    /** 保存交易品种：格式合法才写入；留空回退默认品种 */
    fun saveSymbol() {
        val sym = tradeSymbol.trim()
        if (sym.isNotEmpty() && (sym.contains(Regex("\\s")) || !sym.contains("."))) {
            symbolError = "格式如 DCE.a2611"
            return
        }
        symbolError = null
        prefs.edit().putString("trade_symbol", sym.ifBlank { null }).apply()
        getService()?.resyncSymbol()
    }

    fun save() {
        prefs.edit()
            .putBoolean("alert_enabled", alertEnabled)
            .putBoolean("alert_vibrate", vibrate)
            .putBoolean("run_only_in_session", runOnlyInSession)
            .putBoolean("alert_feishu_enabled", feishuEnabled)
            .putString("alert_feishu_app_id", feishuAppId.trim().ifBlank { null })
            .putString("alert_feishu_app_secret", feishuAppSecret.trim().ifBlank { null })
            .putString("strategy_profile", strategy)
            .apply()
        val svc = getService()
        svc?.resyncStrategy()
        svc?.resyncFeishu()
        svc?.applyScheduleNow()
    }

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Text("设置", fontSize = 14.sp, color = Color.White)
            }
            item {
                WearInput("交易品种", tradeSymbol) { tradeSymbol = it; symbolError = null }
            }
            item {
                symbolError?.let {
                    Text(it, fontSize = 10.sp, color = Color(0xFFEF5350), textAlign = TextAlign.Center)
                }
            }
            item {
                Button(onClick = { saveSymbol() }, modifier = Modifier.fillMaxWidth()) {
                    Text("保存品种（默认 DCE.a2611）", fontSize = 11.sp)
                }
            }
            item {
                ToggleChip(
                    checked = alertEnabled,
                    onCheckedChange = { alertEnabled = it; save() },
                    label = { Text("开仓提醒") },
                    toggleControl = { Switch(checked = alertEnabled, onCheckedChange = null) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                ToggleChip(
                    checked = vibrate,
                    onCheckedChange = { vibrate = it; save() },
                    label = { Text("震动") },
                    toggleControl = { Switch(checked = vibrate, onCheckedChange = null) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                ToggleChip(
                    checked = runOnlyInSession,
                    onCheckedChange = { runOnlyInSession = it; save() },
                    label = { Text("仅开市时段运行") },
                    secondaryLabel = { Text("闭市自动休眠省电，开市前自动唤醒", fontSize = 10.sp) },
                    toggleControl = { Switch(checked = runOnlyInSession, onCheckedChange = null) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // 策略切换（5m / 60m）
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                ) {
                    Button(
                        onClick = { strategy = "5m"; save() },
                        enabled = strategy != "5m",
                        modifier = Modifier.size(ButtonDefaults.DefaultButtonSize),
                    ) { Text("5m", fontSize = 12.sp) }
                    Button(
                        onClick = { strategy = "60m"; save() },
                        enabled = strategy != "60m",
                        modifier = Modifier.size(ButtonDefaults.DefaultButtonSize),
                    ) { Text("60m", fontSize = 12.sp) }
                }
            }
            item {
                ToggleChip(
                    checked = feishuEnabled,
                    onCheckedChange = { feishuEnabled = it; save() },
                    label = { Text("飞书推送") },
                    secondaryLabel = { Text("默认关，避免与手机重复", fontSize = 10.sp) },
                    toggleControl = { Switch(checked = feishuEnabled, onCheckedChange = null) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (feishuEnabled) {
                item {
                    WearInput("飞书 App ID", feishuAppId) { feishuAppId = it }
                }
                item {
                    WearInput("飞书 App Secret", feishuAppSecret) { feishuAppSecret = it }
                }
                item {
                    Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) {
                        Text("保存飞书配置", fontSize = 12.sp)
                    }
                }
            }
            item {
                Button(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("返回", fontSize = 12.sp) }
            }
        }
    }
}
