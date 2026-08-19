package com.futures.channel

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 天勤 DIFF 行情客户端（5 分钟 K 线）。
 * 浏览器 WebSocket 无法带 Authorization，故放在原生 OkHttp。
 */
class DiffMdClient(
    val symbol: String = DEFAULT_SYMBOL,
    private val viewWidth: Int = 2000,
    private val onStatus: (String) -> Unit,
    private val onBars: (JSONArray) -> Unit,
    /** 鉴权失败（HTTP 401 或关闭码 1008）时触发，由上层决定是否重登 */
    private val onAuthFailure: () -> Unit = {},
    /** 非鉴权类断开时触发，由上层决定是否重连 */
    private val onDisconnect: () -> Unit = {},
) {
    companion object {
        private const val TAG = "DiffMdClient"

        /** 默认交易品种（豆一 2611）；设置页 trade_symbol 可覆盖 */
        const val DEFAULT_SYMBOL = "DCE.a2611"
        private const val DURATION_NS = 300L * 1_000_000_000L // 5m
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val STALE_THRESHOLD_MS = 90_000L // 90s 无数据视为连接僵死
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val mirror = JSONObject()
    private var webSocket: WebSocket? = null
    private val running = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 独立心跳线程：避免主线程 Looper 在 Doze 下被批量延迟。
     * 配合外层 WakeLock，能在后台稳定运行。
     */
    private val heartbeatThread = HandlerThread("md-heartbeat").apply { start() }
    private val heartbeatHandler = Handler(heartbeatThread.looper)

    /** 最后一次收到数据的时间戳，用于检测静默断线 */
    @Volatile private var lastDataMs = 0L
    private var heartbeatRunnable: Runnable? = null

    fun start(session: ShinnyAuth.Session) {
        stop()
        running.set(true)
        lastDataMs = System.currentTimeMillis()
        onStatus("连接行情…")
        Log.i(TAG, "start: 连接 ${session.mdUrl}")
        val req = Request.Builder()
            .url(session.mdUrl)
            .header("Authorization", "Bearer ${session.accessToken}")
            .header("User-Agent", "ChannelStrategyApp/1.0")
            .header("Accept", "application/json")
            .build()
        webSocket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "onOpen: WebSocket 已连接")
                onStatus("行情已连接，订阅 $symbol")
                webSocket.send("""{"aid":"peek_message"}""")
                webSocket.send(
                    JSONObject()
                        .put("aid", "set_chart")
                        .put("chart_id", "channel_5m")
                        .put("ins_list", symbol)
                        .put("duration", DURATION_NS)
                        .put("view_width", viewWidth)
                        .toString()
                )
                webSocket.send(
                    JSONObject()
                        .put("aid", "subscribe_quote")
                        .put("ins_list", symbol)
                        .toString()
                )
                startHeartbeat()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!running.get()) return
                lastDataMs = System.currentTimeMillis()
                worker.execute {
                    try {
                        handleMessage(text)
                        webSocket.send("""{"aid":"peek_message"}""")
                    } catch (e: Exception) {
                        Log.e(TAG, "onMessage: 解析失败", e)
                        onStatus("解析行情失败: ${e.message}")
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onMessage(webSocket, bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "onClosing: code=$code reason=$reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!running.get()) return
                Log.e(TAG, "onFailure: ${t.message}", t)
                stopHeartbeat()
                onStatus("行情断开: ${t.message}")
                if (response?.code == 401) onAuthFailure() else onDisconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!running.get()) return
                Log.w(TAG, "onClosed: code=$code reason=$reason")
                stopHeartbeat()
                onStatus("行情已关闭")
                if (code == 1008) onAuthFailure() else onDisconnect()
            }
        })
    }

    fun stop() {
        running.set(false)
        stopHeartbeat()
        webSocket?.close(1000, "stop")
        webSocket = null
        Log.d(TAG, "stop: 已关闭 WebSocket")
    }

    /** 彻底释放资源（HandlerThread），由 Service.onDestroy 调用 */
    fun destroy() {
        stop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            heartbeatThread.quitSafely()
        } else {
            heartbeatThread.quit()
        }
        Log.d(TAG, "destroy: 心跳线程已退出")
    }

    /** 是否处于活跃连接状态 */
    fun isAlive(): Boolean {
        return running.get() && webSocket != null
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        // 使用 object 而非 lambda，便于内部用 this 安全地引用自身（避免 heartbeatRunnable!! 空指针）
        val r = object : Runnable {
            override fun run() {
                if (!running.get()) return
                val now = System.currentTimeMillis()
                val elapsed = now - lastDataMs
                if (elapsed > STALE_THRESHOLD_MS) {
                    // 连接静默僵死（Doze 冻结网络 / 服务端不再推送），强制重连
                    Log.w(TAG, "heartbeat: ${elapsed}ms 无数据，判定连接僵死，触发重连")
                    onStatus("行情超 ${STALE_THRESHOLD_MS / 1000}s 无数据，重连中…")
                    onDisconnect()
                    return
                }
                // 主动发 peek_message 探活
                try {
                    webSocket?.send("""{"aid":"peek_message"}""")
                    Log.d(TAG, "heartbeat: peek_message 已发送 (${elapsed}ms since last data)")
                } catch (e: Exception) {
                    Log.e(TAG, "heartbeat: 发送失败，触发重连", e)
                    onDisconnect()
                    return
                }
                heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
            }
        }
        heartbeatRunnable = r
        heartbeatHandler.postDelayed(r, HEARTBEAT_INTERVAL_MS)
    }

    private fun stopHeartbeat() {
        heartbeatRunnable?.let { heartbeatHandler.removeCallbacks(it) }
        heartbeatRunnable = null
    }

    /**
     * 供外层 AlarmManager 兜底调用：Doze 下闹钟触发时，强制执行一次心跳检测。
     * AlarmManager.setExactAndAllowWhileIdle 可在 Doze 维护窗口外精确触发。
     */
    fun pokeHeartbeat() {
        heartbeatHandler.post {
            if (!running.get()) return@post
            val now = System.currentTimeMillis()
            val elapsed = now - lastDataMs
            if (elapsed > STALE_THRESHOLD_MS) {
                Log.w(TAG, "pokeHeartbeat(AlarmManager): ${elapsed}ms 无数据，触发重连")
                onStatus("AlarmManager 兜底：${elapsed / 1000}s 无数据，重连中…")
                onDisconnect()
                return@post
            }
            try {
                webSocket?.send("""{"aid":"peek_message"}""")
                Log.d(TAG, "pokeHeartbeat(AlarmManager): peek_message 已发送 (${elapsed}ms since last data)")
            } catch (e: Exception) {
                Log.e(TAG, "pokeHeartbeat(AlarmManager): 发送失败，触发重连", e)
                onDisconnect()
            }
        }
    }

    private fun handleMessage(text: String) {
        val msg = JSONObject(text)
        if (msg.optString("aid") != "rtn_data") return
        val data = msg.optJSONArray("data") ?: return
        for (i in 0 until data.length()) {
            mergePatch(mirror, data.getJSONObject(i))
        }
        val bars = extractBars()
        if (bars.length() >= 60) {
            onBars(bars)
            onStatus("行情更新 · ${bars.length()} 根")
        }
    }

    private fun extractBars(): JSONArray {
        val klines = mirror.optJSONObject("klines") ?: return JSONArray()
        val bySymbol = klines.optJSONObject(symbol) ?: return JSONArray()
        val series = bySymbol.optJSONObject(DURATION_NS.toString())
            ?: bySymbol.optJSONObject(DURATION_NS.toDouble().toLong().toString())
            ?: run {
                // 有的实现用 Number 键，遍历匹配
                val keys = bySymbol.keys()
                var found: JSONObject? = null
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k.toLongOrNull() == DURATION_NS) {
                        found = bySymbol.getJSONObject(k)
                        break
                    }
                }
                found
            }
            ?: return JSONArray()
        val data = series.optJSONObject("data") ?: return JSONArray()
        val ids = mutableListOf<Int>()
        val it = data.keys()
        while (it.hasNext()) {
            it.next().toIntOrNull()?.let { ids.add(it) }
        }
        ids.sort()
        val out = JSONArray()
        for (id in ids) {
            val bar = data.optJSONObject(id.toString()) ?: continue
            val dtNs = bar.optLong("datetime", 0L)
            if (dtNs <= 0L) continue
            val open = bar.optDouble("open", Double.NaN)
            val high = bar.optDouble("high", Double.NaN)
            val low = bar.optDouble("low", Double.NaN)
            val close = bar.optDouble("close", Double.NaN)
            if (open.isNaN() || high.isNaN() || low.isNaN() || close.isNaN()) continue
            out.put(
                JSONObject()
                    .put("time", dtNs / 1_000_000_000L)
                    .put("open", open)
                    .put("high", high)
                    .put("low", low)
                    .put("close", close)
            )
        }
        return out
    }

    /** RFC7396 JSON Merge Patch（足够覆盖 DIFF rtn_data）。 */
    private fun mergePatch(target: JSONObject, patch: JSONObject) {
        val keys = patch.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (patch.isNull(key)) {
                target.remove(key)
                continue
            }
            val value = patch.get(key)
            if (value is JSONObject) {
                val child = target.optJSONObject(key) ?: JSONObject().also { target.put(key, it) }
                mergePatch(child, value)
            } else {
                target.put(key, value)
            }
        }
    }
}
