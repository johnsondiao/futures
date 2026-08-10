package com.futures.channel

import android.net.Uri
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 飞书长连接（WebSocket）事件订阅客户端，参考 hermes/OpenClaw 的接入方式：
 *   - 只需要 App ID + App Secret，无需公网 IP，App 主动出站连接飞书网关
 *   - 用户在飞书里给机器人发一条私信 → im.message.receive_v1 事件自带发送者 open_id →
 *     自动完成配对，无需再去飞书后台事件日志里手动抄 open_id
 *
 * 协议实现参考官方 lark-oapi SDK（lark_oapi/ws/client.py + pbbp2.proto）：
 *   1) POST https://open.feishu.cn/callback/ws/endpoint 换取 WebSocket URL 与 ClientConfig
 *      （URL query 里带 service_id，ping 帧需要带回）
 *   2) WebSocket 二进制帧为 protobuf pbbp2.Frame，本文件内置最小手写编解码：
 *        message Header { key=1, value=2 }
 *        message Frame  { SeqID=1(uint64), LogID=2(uint64), service=3(int32),
 *                         method=4(int32), headers=5(Header*), payload_encoding=6,
 *                         payload_type=7, payload=8(bytes), LogIDNew=9 }
 *   3) method: 0=CONTROL（headers.type=ping/pong）, 1=DATA（headers.type=event/card）
 *   4) 心跳：按 ClientConfig.PingInterval（默认 120s）周期发送 ping 控制帧
 *   5) DATA 帧需在 3s 内 ACK（payload={"code":200}），否则飞书会重推
 *   6) sum>1 的大事件按 message_id/sum/seq 分片重组
 *   7) 断线自动重连（指数退避 + 随机抖动）
 *
 * 前置条件（飞书开放平台，企业自建应用）：
 *   - 启用「机器人」能力
 *   - 「事件与回调」→ 订阅方式选「使用长连接接收事件」
 *   - 添加事件 im.message.receive_v1（接收消息），并发布应用版本
 */
class FeishuWsClient(private val listener: Listener) {

    interface Listener {
        /** 用户私聊机器人时发现其 open_id（ou_ 开头），可直接持久化为推送目标 */
        fun onOpenIdDiscovered(openId: String)

        /** 连接状态变化（日志/UI 展示用） */
        fun onStateChanged(state: String)
    }

    companion object {
        private const val TAG = "FeishuWsClient"
        private const val URL_ENDPOINT = "https://open.feishu.cn/callback/ws/endpoint"
        private const val USER_AGENT = "ChannelStrategyApp/1.0"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        private const val METHOD_CONTROL = 0
        private const val METHOD_DATA = 1
        private const val TYPE_PING = "ping"
        private const val TYPE_PONG = "pong"
        private const val TYPE_EVENT = "event"

        private const val EVENT_MESSAGE_RECEIVE = "im.message.receive_v1"

        private const val INITIAL_RECONNECT_DELAY_SEC = 5L
        private const val MAX_RECONNECT_DELAY_SEC = 30L
        private const val DEFAULT_PING_INTERVAL_SEC = 120L
    }

    /** 端点请求用短超时 HTTP 客户端；WebSocket 用长超时独立客户端 */
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val wsHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // 长连接不设读超时
        .build()

    /** 阻塞式连接循环线程 */
    private val loop = Executors.newSingleThreadExecutor { r ->
        Thread(r, "feishu-ws-loop").apply { isDaemon = true }
    }

    /** ping 心跳调度线程 */
    private val sched = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "feishu-ws-ping").apply { isDaemon = true }
    }

    @Volatile private var running = false
    @Volatile private var ws: WebSocket? = null
    @Volatile private var state = "未启动"
    private var appId = ""
    private var appSecret = ""
    @Volatile private var gen = 0
    @Volatile private var serviceId = 0
    @Volatile private var pingIntervalSec = DEFAULT_PING_INTERVAL_SEC
    @Volatile private var reconnectDelaySec = INITIAL_RECONNECT_DELAY_SEC
    private var pingFuture: ScheduledFuture<*>? = null

    /** 分片重组缓存：message_id → 各分片（OkHttp 回调串行，无需加锁） */
    private val fragmentCache = HashMap<String, Array<ByteArray?>>()

    fun statusText(): String = state

    /** 启动（凭证变化时自动重启；相同凭证重复调用无副作用） */
    fun start(appId: String, appSecret: String) {
        synchronized(this) {
            if (running && this.appId == appId && this.appSecret == appSecret) return
            stopLocked()
            this.appId = appId
            this.appSecret = appSecret
            running = true
            gen++
            reconnectDelaySec = INITIAL_RECONNECT_DELAY_SEC
            setState("连接飞书长连接…")
            val g = gen
            loop.execute { runLoop(g) }
        }
        Log.i(TAG, "start: 飞书长连接客户端已启动 appId=${appId.take(8)}…")
    }

    fun stop() {
        synchronized(this) { stopLocked() }
    }

    private fun stopLocked() {
        if (!running && ws == null) {
            setState("未启动")
            return
        }
        running = false
        gen++
        stopPing()
        ws?.cancel()
        ws = null
        setState("未启动")
        Log.i(TAG, "stop: 飞书长连接客户端已停止")
    }

    // ===== 连接循环（loop 线程）=====

    private fun runLoop(g: Int) {
        while (running && g == gen) {
            try {
                connectAndListen(g)
            } catch (e: Exception) {
                Log.e(TAG, "runLoop: 异常", e)
            }
            stopPing()
            ws?.cancel()
            ws = null
            if (!running || g != gen) break
            // 退避重连 + 随机抖动，避免多端同时重连
            val jitter = (Math.random() * 3).toLong()
            val delay = reconnectDelaySec + jitter
            setState("飞书长连接中断，${delay}s 后重连…")
            try {
                Thread.sleep(delay * 1000)
            } catch (_: InterruptedException) {
                break
            }
            reconnectDelaySec = (reconnectDelaySec * 2).coerceAtMost(MAX_RECONNECT_DELAY_SEC)
        }
        Log.d(TAG, "runLoop: 退出 g=$g")
    }

    /** 获取端点 → 建立 WebSocket → 阻塞直到断开；返回是否成功连接过 */
    private fun connectAndListen(g: Int): Boolean {
        val ep = fetchEndpoint() ?: return false
        if (!running || g != gen) return false
        serviceId = ep.serviceId
        val latch = CountDownLatch(1)
        val req = Request.Builder()
            .url(ep.url)
            .header("User-Agent", USER_AGENT)
            .build()
        val socket = wsHttpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectDelaySec = INITIAL_RECONNECT_DELAY_SEC
                setState("飞书长连接已建立，等待机器人消息")
                Log.i(TAG, "ws onOpen")
                sendPing(webSocket)
                startPing()
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                handleFrame(bytes.toByteArray())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "ws onFailure: ${t.message}")
                latch.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "ws onClosed code=$code reason=$reason")
                latch.countDown()
            }
        })
        synchronized(this) {
            if (!running || g != gen) {
                socket.cancel()
                return false
            }
            ws = socket
        }
        latch.await()
        return true
    }

    // ===== 端点获取 =====

    private data class Endpoint(val url: String, val serviceId: Int)

    private fun fetchEndpoint(): Endpoint? {
        return try {
            val body = JSONObject()
                .put("AppID", appId)
                .put("AppSecret", appSecret)
            val req = Request.Builder()
                .url(URL_ENDPOINT)
                .header("locale", "zh")
                .header("User-Agent", USER_AGENT)
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                val code = json?.optInt("code", -1) ?: -1
                if (code != 0) {
                    val msg = json?.optString("msg").orEmpty()
                    setState("飞书长连接获取失败 code=$code $msg")
                    Log.w(TAG, "fetchEndpoint: code=$code msg=$msg body=$text")
                    return null
                }
                val data = json.optJSONObject("data") ?: return null
                val url = data.optString("URL")
                if (url.isBlank()) return null
                data.optJSONObject("ClientConfig")?.let { applyClientConfig(it) }
                val sid = Uri.parse(url).getQueryParameter("service_id")?.toIntOrNull() ?: 0
                Log.d(TAG, "fetchEndpoint: ok pingInterval=${pingIntervalSec}s serviceId=$sid")
                Endpoint(url, sid)
            }
        } catch (e: Exception) {
            setState("飞书长连接地址请求异常：${e.message}")
            Log.e(TAG, "fetchEndpoint: 异常", e)
            null
        }
    }

    /** ClientConfig 字段（PascalCase）：PingInterval / ReconnectCount / ReconnectInterval / ReconnectNonce */
    private fun applyClientConfig(conf: JSONObject) {
        val pi = conf.optLong("PingInterval", -1)
        if (pi > 0) pingIntervalSec = pi.coerceAtLeast(10)
    }

    // ===== 心跳 =====

    private fun startPing() {
        stopPing()
        pingFuture = sched.scheduleWithFixedDelay(
            { ws?.let { sendPing(it) } },
            pingIntervalSec, pingIntervalSec, TimeUnit.SECONDS
        )
    }

    private fun stopPing() {
        pingFuture?.cancel(false)
        pingFuture = null
    }

    private fun sendPing(socket: WebSocket) {
        val frame = PbFrame(method = METHOD_CONTROL, service = serviceId)
        frame.headers.add("type" to TYPE_PING)
        runCatching { socket.send(PbCodec.encode(frame).toByteString()) }
            .onFailure { Log.w(TAG, "sendPing: 失败 ${it.message}") }
    }

    // ===== 帧处理 =====

    private fun handleFrame(bytes: ByteArray) {
        val frame = try {
            PbCodec.decode(bytes)
        } catch (e: Exception) {
            Log.w(TAG, "handleFrame: protobuf 解码失败", e)
            return
        }
        when (frame.method) {
            METHOD_CONTROL -> {
                when (frame.header("type")) {
                    TYPE_PONG -> {
                        // pong 可能携带新的 ClientConfig
                        if (frame.payload.isNotEmpty()) {
                            runCatching { applyClientConfig(JSONObject(String(frame.payload, Charsets.UTF_8))) }
                        }
                    }
                    TYPE_PING -> { /* 服务端一般不发 ping，忽略 */ }
                }
            }
            METHOD_DATA -> {
                // 先 ACK 再处理，避免超过飞书 3s 处理时限触发重推
                ack(frame)
                val type = frame.header("type") ?: return
                if (type != TYPE_EVENT) return
                val full = combineFragments(frame) ?: return
                handleEvent(full)
            }
        }
    }

    private fun ack(frame: PbFrame) {
        val socket = ws ?: return
        val reply = frame.cloneFrame()
        reply.payload = JSONObject().put("code", 200).toString().toByteArray(Charsets.UTF_8)
        reply.headers.add("biz_rt" to "1")
        runCatching { socket.send(PbCodec.encode(reply).toByteString()) }
            .onFailure { Log.w(TAG, "ack: 失败 ${it.message}") }
    }

    /** sum>1 时分片重组；单片直接返回 payload；未收齐返回 null */
    private fun combineFragments(frame: PbFrame): ByteArray? {
        val sum = frame.header("sum")?.toIntOrNull() ?: 1
        if (sum <= 1) return frame.payload
        val seq = frame.header("seq")?.toIntOrNull() ?: 0
        val msgId = frame.header("message_id") ?: return frame.payload
        if (fragmentCache.size > 32) fragmentCache.clear()
        val arr = fragmentCache.getOrPut(msgId) { arrayOfNulls(sum) }
        if (seq in arr.indices) arr[seq] = frame.payload
        if (arr.any { it == null }) return null
        fragmentCache.remove(msgId)
        val joined = ByteArrayOutputStream()
        arr.forEach { joined.write(it!!) }
        return joined.toByteArray()
    }

    /** v2.0 事件结构：{ schema, header:{event_type,...}, event:{sender,message} } */
    private fun handleEvent(payload: ByteArray) {
        val json = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull() ?: return
        val eventType = json.optJSONObject("header")?.optString("event_type").orEmpty()
        Log.d(TAG, "handleEvent: event_type=$eventType")
        if (eventType != EVENT_MESSAGE_RECEIVE) return
        val event = json.optJSONObject("event") ?: return
        val sender = event.optJSONObject("sender") ?: return
        // 只认用户私聊消息，忽略机器人自身/群聊
        if (sender.optString("sender_type") != "user") return
        val chatType = event.optJSONObject("message")?.optString("chat_type").orEmpty()
        if (chatType != "p2p") return
        val openId = sender.optJSONObject("sender_id")?.optString("open_id").orEmpty()
        if (openId.isBlank()) return
        Log.i(TAG, "handleEvent: 发现私聊用户 open_id=${openId.take(12)}…")
        listener.onOpenIdDiscovered(openId)
    }

    private fun setState(s: String) {
        state = s
        listener.onStateChanged(s)
    }
}

// ===== pbbp2.Frame 最小 protobuf 编解码 =====

internal data class PbFrame(
    var seqId: Long = 0,
    var logId: Long = 0,
    var service: Int = 0,
    var method: Int = 0,
    val headers: MutableList<Pair<String, String>> = mutableListOf(),
    var payloadEncoding: String = "",
    var payloadType: String = "",
    var payload: ByteArray = ByteArray(0),
    var logIdNew: String = "",
) {
    fun header(key: String): String? = headers.firstOrNull { it.first == key }?.second

    fun cloneFrame() = PbFrame(
        seqId, logId, service, method,
        headers.toMutableList(), payloadEncoding, payloadType, payload, logIdNew
    )
}

internal object PbCodec {

    fun encode(f: PbFrame): ByteArray {
        val out = ByteArrayOutputStream(128)
        writeVarintField(out, 1, f.seqId)
        writeVarintField(out, 2, f.logId)
        writeVarintField(out, 3, f.service.toLong())
        writeVarintField(out, 4, f.method.toLong())
        for ((k, v) in f.headers) {
            val sub = ByteArrayOutputStream()
            writeBytesField(sub, 1, k.toByteArray(Charsets.UTF_8))
            writeBytesField(sub, 2, v.toByteArray(Charsets.UTF_8))
            writeBytesField(out, 5, sub.toByteArray())
        }
        if (f.payloadEncoding.isNotEmpty()) {
            writeBytesField(out, 6, f.payloadEncoding.toByteArray(Charsets.UTF_8))
        }
        if (f.payloadType.isNotEmpty()) {
            writeBytesField(out, 7, f.payloadType.toByteArray(Charsets.UTF_8))
        }
        if (f.payload.isNotEmpty()) {
            writeBytesField(out, 8, f.payload)
        }
        if (f.logIdNew.isNotEmpty()) {
            writeBytesField(out, 9, f.logIdNew.toByteArray(Charsets.UTF_8))
        }
        return out.toByteArray()
    }

    fun decode(data: ByteArray): PbFrame {
        val f = PbFrame()
        var pos = 0
        while (pos < data.size) {
            val tag = readVarint(data, pos)
            pos = tag.second
            val field = (tag.first shr 3).toInt()
            val wire = (tag.first and 7).toInt()
            when (wire) {
                0 -> {
                    val v = readVarint(data, pos)
                    pos = v.second
                    when (field) {
                        1 -> f.seqId = v.first
                        2 -> f.logId = v.first
                        3 -> f.service = v.first.toInt()
                        4 -> f.method = v.first.toInt()
                    }
                }
                2 -> {
                    val len = readVarint(data, pos)
                    pos = len.second
                    val l = len.first.toInt()
                    val bytes = data.copyOfRange(pos, pos + l)
                    pos += l
                    when (field) {
                        5 -> {
                            val (k, v) = parseHeader(bytes)
                            if (k != null) f.headers.add(k to (v ?: ""))
                        }
                        6 -> f.payloadEncoding = String(bytes, Charsets.UTF_8)
                        7 -> f.payloadType = String(bytes, Charsets.UTF_8)
                        8 -> f.payload = bytes
                        9 -> f.logIdNew = String(bytes, Charsets.UTF_8)
                    }
                }
                1 -> pos += 8
                5 -> pos += 4
                else -> throw IllegalStateException("unsupported wire type $wire")
            }
        }
        return f
    }

    private fun parseHeader(data: ByteArray): Pair<String?, String?> {
        var key: String? = null
        var value: String? = null
        var pos = 0
        while (pos < data.size) {
            val tag = readVarint(data, pos)
            pos = tag.second
            val field = (tag.first shr 3).toInt()
            val wire = (tag.first and 7).toInt()
            when (wire) {
                0 -> {
                    val v = readVarint(data, pos)
                    pos = v.second
                }
                2 -> {
                    val len = readVarint(data, pos)
                    pos = len.second
                    val l = len.first.toInt()
                    val bytes = data.copyOfRange(pos, pos + l)
                    pos += l
                    when (field) {
                        1 -> key = String(bytes, Charsets.UTF_8)
                        2 -> value = String(bytes, Charsets.UTF_8)
                    }
                }
                1 -> pos += 8
                5 -> pos += 4
                else -> return key to value
            }
        }
        return key to value
    }

    private fun readVarint(data: ByteArray, startPos: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var pos = startPos
        while (true) {
            val b = data[pos++].toInt() and 0xFF
            result = result or (((b and 0x7F).toLong()) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 63) throw IllegalStateException("varint too long")
        }
        return result to pos
    }

    private fun writeVarint(out: ByteArrayOutputStream, vIn: Long) {
        var v = vIn
        while (true) {
            if (v and 0x7FL.inv() == 0L) {
                out.write(v.toInt())
                break
            } else {
                out.write((v.toInt() and 0x7F) or 0x80)
                v = v ushr 7
            }
        }
    }

    private fun writeTag(out: ByteArrayOutputStream, field: Int, wire: Int) {
        writeVarint(out, ((field shl 3) or wire).toLong())
    }

    private fun writeVarintField(out: ByteArrayOutputStream, field: Int, v: Long) {
        writeTag(out, field, 0)
        writeVarint(out, v)
    }

    private fun writeBytesField(out: ByteArrayOutputStream, field: Int, bytes: ByteArray) {
        writeTag(out, field, 2)
        writeVarint(out, bytes.size.toLong())
        out.write(bytes)
    }
}
