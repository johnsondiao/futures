package com.futures.channel

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 飞书自建应用机器人推送（不需要建群，直接私聊机器人）。
 *
 * 接入流程（参考 OpenClaw 接入方式）：
 *   1) 访问 https://open.feishu.cn/app → 「开发者后台」→ 「创建企业自建应用」
 *   2) 在「凭证与基础信息」里拿到 App ID 和 App Secret
 *   3) 在「添加应用能力」里启用「机器人」
 *   4) 在「权限管理」里搜索 `im:message`，勾选 `im:message:send_as_bot`（以应用身份发消息）
 *   5) 在「版本管理与发布」里创建版本并发布（企业版飞书需要管理员审批）
 *   6) 在手机飞书里给机器人发一条消息，从飞书后台事件日志里获取 open_id（ou_ 开头）
 *   7) 把 App ID / App Secret / Open ID 三个值填到 App 设置页
 *
 * 工作原理：
 *   - 用 App ID + App Secret 换 tenant_access_token（2h 过期，本类内部缓存，提前 5 分钟刷新）
 *   - 用 tenant_access_token 调用 /im/v1/messages?receive_id_type=open_id 发交互式卡片
 *
 * 相比群机器人 Webhook 的优势：
 *   - 不需要建群，机器人直接给你私聊发消息
 *   - 不需要安全设置关键词白名单，签名校验由 App Secret 完成
 *
 * 注意：飞书个人版不能创建自建应用，必须是企业版飞书（免费版企业也行，但需要管理员开通开发者后台）
 */
class FeishuAppNotifier {

    companion object {
        private const val TAG = "FeishuAppNotifier"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        // 飞书开放平台 API 端点
        private const val URL_TOKEN =
            "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal"
        private const val URL_SEND_MESSAGE =
            "https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=open_id"

        // token 缓存：飞书返回的 expire 单位是秒，默认 7200（2h）
        // 提前 5 分钟刷新，避免边界过期
        private const val TOKEN_REFRESH_AHEAD_MS = 5L * 60 * 1000
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    /** token 缓存，避免每次推送都换一次 token */
    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpireAt: Long = 0L
    private val tokenLock = Any()

    /**
     * 推送一条交互式卡片给指定用户，不阻塞调用方：失败只记录日志。
     *
     * @param appId     飞书自建应用 App ID
     * @param appSecret 飞书自建应用 App Secret
     * @param openId    接收者的 open_id（用户先给机器人发消息后才能拿到）
     * @param kind      "long" / "short"
     * @param title     卡片标题
     * @param body      卡片正文
     * @param extraLines 附加信息行
     */
    fun send(
        appId: String?,
        appSecret: String?,
        openId: String?,
        kind: String,
        title: String,
        body: String,
        extraLines: List<String> = emptyList()
    ) {
        if (appId.isNullOrBlank() || appSecret.isNullOrBlank() || openId.isNullOrBlank()) return
        Thread {
            try {
                val token = getToken(appId, appSecret) ?: run {
                    Log.w(TAG, "send: 获取 token 失败，跳过")
                    return@Thread
                }
                val cardJson = buildInteractiveCard(kind, title, body, extraLines)
                val payload = JSONObject()
                    .put("receive_id", openId.trim())
                    .put("msg_type", "interactive")
                    .put("content", cardJson.toString())
                val req = Request.Builder()
                    .url(URL_SEND_MESSAGE)
                    .header("Authorization", "Bearer $token")
                    .post(payload.toString().toRequestBody(JSON_MEDIA))
                    .build()
                client.newCall(req).execute().use { resp ->
                    val respText = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "send: HTTP ${resp.code} body=$respText")
                        return@use
                    }
                    val json = runCatching { JSONObject(respText) }.getOrNull()
                    val code = json?.optInt("code", -1)
                    if (code == 0) {
                        Log.d(TAG, "send: 飞书推送成功 title=$title")
                    } else {
                        Log.w(
                            TAG,
                            "send: 飞书返回错误 code=$code msg=${json?.optString("msg")} body=$respText"
                        )
                        // token 失效时清缓存，下次重试
                        if (code == 99991663 || code == 99991664 || code == 99991661) {
                            synchronized(tokenLock) {
                                cachedToken = null
                                tokenExpireAt = 0L
                            }
                            Log.w(TAG, "send: token 失效($code)，已清缓存，下次将重新获取")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "send: 推送失败", e)
            }
        }.start()
    }

    /**
     * 用于设置页「测试推送」按钮：同步调用，直接返回结果信息给 UI 展示。
     */
    fun test(appId: String, appSecret: String, openId: String): String {
        return runCatching {
            val token = getToken(appId, appSecret)
                ?: return@runCatching "❌ 获取 tenant_access_token 失败，请检查 App ID / App Secret"
            val cardJson = buildInteractiveCard(
                kind = "long",
                title = "测试推送",
                body = "如果您能看到这条消息，说明自建应用机器人配置成功 ✅",
                extraLines = listOf(
                    "配置时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
                        .format(java.util.Date())}",
                    "当前合约: DCE.a2609",
                    "open_id: ${openId.take(16)}…"
                )
            )
            val payload = JSONObject()
                .put("receive_id", openId.trim())
                .put("msg_type", "interactive")
                .put("content", cardJson.toString())
            val req = Request.Builder()
                .url(URL_SEND_MESSAGE)
                .header("Authorization", "Bearer $token")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()
            client.newCall(req).execute().use { resp ->
                val respText = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@runCatching "HTTP 错误 ${resp.code}：$respText"
                val json = runCatching { JSONObject(respText) }.getOrNull()
                val code = json?.optInt("code", -1)
                if (code == 0) "✅ 发送成功，请在飞书里查看机器人会话"
                else "飞书返回错误 code=$code msg=${json?.optString("msg") ?: respText}"
            }
        }.getOrElse { "❌ 异常：${it.message}" }
    }

    // ===== 内部：token 缓存与刷新 =====

    private fun getToken(appId: String, appSecret: String): String? {
        // 快速路径：缓存未过期
        val now = System.currentTimeMillis()
        val cached = cachedToken
        if (cached != null && now < tokenExpireAt - TOKEN_REFRESH_AHEAD_MS) {
            return cached
        }
        synchronized(tokenLock) {
            // 双重检查
            val now2 = System.currentTimeMillis()
            val cached2 = cachedToken
            if (cached2 != null && now2 < tokenExpireAt - TOKEN_REFRESH_AHEAD_MS) {
                return cached2
            }
            return try {
                val req = Request.Builder()
                    .url(URL_TOKEN)
                    .post(
                        JSONObject()
                            .put("app_id", appId.trim())
                            .put("app_secret", appSecret.trim())
                            .toString()
                            .toRequestBody(JSON_MEDIA)
                    )
                    .build()
                client.newCall(req).execute().use { resp ->
                    val respText = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "getToken: HTTP ${resp.code} body=$respText")
                        return@use null
                    }
                    val json = runCatching { JSONObject(respText) }.getOrNull()
                    if (json?.optInt("code", -1) != 0) {
                        Log.w(
                            TAG,
                            "getToken: 飞书返回错误 code=${json?.optInt("code")} msg=${json?.optString("msg")}"
                        )
                        return@use null
                    }
                    val token = json.optString("tenant_access_token")
                    val expireSec = json.optInt("expire", 7200)
                    cachedToken = token
                    tokenExpireAt = System.currentTimeMillis() + expireSec * 1000L
                    Log.d(TAG, "getToken: 已获取 token，${expireSec}s 后过期")
                    token
                }
            } catch (e: Exception) {
                Log.e(TAG, "getToken: 异常", e)
                null
            }
        }
    }

    // ===== 内部：交互式卡片构造 =====

    private fun buildInteractiveCard(
        kind: String,
        title: String,
        body: String,
        extraLines: List<String>
    ): JSONObject {
        val template = if (kind.equals("long", ignoreCase = true)) "red" else "blue"
        val emoji = if (kind.equals("long", ignoreCase = true)) "\uD83D\uDFE2" else "\uD83D\uDD34"

        val elements = JSONArray()
        val mdBody = StringBuilder().append("**").append(body).append("**")
        if (extraLines.isNotEmpty()) {
            mdBody.append("\n")
            for (line in extraLines) {
                mdBody.append("• ").append(line).append("\n")
            }
        }
        elements.put(
            JSONObject()
                .put("tag", "div")
                .put(
                    "text",
                    JSONObject()
                        .put("tag", "lark_md")
                        .put("content", mdBody.toString())
                )
        )
        elements.put(
            JSONObject()
                .put("tag", "note")
                .put(
                    "elements",
                    JSONArray().put(
                        JSONObject()
                            .put("tag", "plain_text")
                            .put("content", "📱 ChannelStrategy App 推送 · 请第一时间检查盘面")
                    )
                )
        )

        // 注意：飞书 /im/v1/messages 接口里 content 字段是字符串，
        // 外层调用方会把它 toString() 后作为 content 字段，所以这里返回的是 card 对象本身
        return JSONObject()
            .put("config", JSONObject().put("wide_screen_mode", true))
            .put(
                "header",
                JSONObject()
                    .put(
                        "title",
                        JSONObject()
                            .put("tag", "plain_text")
                            .put("content", "$emoji $title")
                    )
                    .put("template", template)
            )
            .put("elements", elements)
    }
}
