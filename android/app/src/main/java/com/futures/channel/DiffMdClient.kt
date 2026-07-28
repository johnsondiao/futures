package com.futures.channel

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
    private val symbol: String = "DCE.a2609",
    private val viewWidth: Int = 2000,
    private val onStatus: (String) -> Unit,
    private val onBars: (JSONArray) -> Unit,
) {
    companion object {
        private const val DURATION_NS = 300L * 1_000_000_000L // 5m
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val mirror = JSONObject()
    private var webSocket: WebSocket? = null
    private val running = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor()

    fun start(session: ShinnyAuth.Session) {
        stop()
        running.set(true)
        onStatus("连接行情…")
        val req = Request.Builder()
            .url(session.mdUrl)
            .header("Authorization", "Bearer ${session.accessToken}")
            .header("User-Agent", "ChannelStrategyApp/1.0")
            .header("Accept", "application/json")
            .build()
        webSocket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
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
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!running.get()) return
                worker.execute {
                    try {
                        handleMessage(text)
                        webSocket.send("""{"aid":"peek_message"}""")
                    } catch (e: Exception) {
                        onStatus("解析行情失败: ${e.message}")
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onMessage(webSocket, bytes.utf8())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onStatus("行情断开: ${t.message}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onStatus("行情已关闭")
            }
        })
    }

    fun stop() {
        running.set(false)
        webSocket?.close(1000, "stop")
        webSocket = null
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
