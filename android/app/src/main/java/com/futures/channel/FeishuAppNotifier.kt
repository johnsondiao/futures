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
 *   4) 在「权限管理」里：
 *      - 搜索 `im:message`，勾选 `im:message:send_as_bot`（以应用身份发消息）
 *      - 搜索 `通过手机号或邮箱获取用户 ID`，勾选该权限（contact:user.id:readonly）
 *        用于把用户的手机号自动转换为 open_id，免去用户手动复制 open_id
 *   5) 在「版本管理与发布」里创建版本并发布（企业版飞书需要管理员审批）
 *   6) 在 App 设置页填入 App ID / App Secret，再填入用户飞书账号绑定的手机号，
 *      点「点击绑定 Open ID」按钮，App 自动通过手机号查出 open_id 并保存。
 *
 * 工作原理：
 *   - 用 App ID + App Secret 换 tenant_access_token（2h 过期，本类内部缓存，提前 5 分钟刷新）
 *   - 用 tenant_access_token 调用 /contact/v3/users/batch_get_id 把手机号转成 open_id
 *   - 用 tenant_access_token 调用 /im/v1/messages?receive_id_type=open_id 发交互式卡片
 *
 * 相比群机器人 Webhook 的优势：
 *   - 不需要建群，机器人直接给你私聊发消息
 *   - 不需要安全设置关键词白名单，签名校验由 App Secret 完成
 *   - 不需要用户去飞书后台找 open_id，用手机号即可自动绑定
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
        // 通过手机号/邮箱查询用户的 open_id（免手动复制 open_id）
        // POST 请求体：{"mobiles":["13xxxxxxxxx"]} ；返回 data.user_list[].user_id（即 open_id，ou_ 开头）
        private const val URL_BATCH_GET_ID =
            "https://open.feishu.cn/open-apis/contact/v3/users/batch_get_id?user_id_type=open_id"

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

    /**
     * 自动绑定：通过用户手机号查询对应的飞书 open_id，免去手动复制 open_id。
     *
     * 用户只需要在 App 设置页填入飞书账号绑定的手机号，点「点击绑定 Open ID」按钮，
     * 本方法会调用飞书 /contact/v3/users/batch_get_id 接口，自动把手机号转换成 open_id。
     *
     * 前置条件：应用必须勾选「通过手机号或邮箱获取用户 ID」权限
     * （contact:user.id:readonly），并在「应用可用范围」里把目标用户加入可用范围。
     *
     * @param mobile  用户飞书账号绑定的手机号，11 位国内手机号直接传，海外需带 +国家码
     * @return 成功返回 open_id（ou_ 开头），失败返回 null + 错误信息（通过 Log）
     */
    fun fetchOpenIdByMobile(appId: String, appSecret: String, mobile: String): FetchResult {
        return runCatching {
            val token = getToken(appId, appSecret)
                ?: return@runCatching FetchResult(null, "获取 tenant_access_token 失败，请检查 App ID / App Secret")
            val payload = JSONObject()
                .put("mobiles", JSONArray().put(mobile.trim()))
                .toString()
            val req = Request.Builder()
                .url(URL_BATCH_GET_ID)
                .header("Authorization", "Bearer $token")
                .post(payload.toRequestBody(JSON_MEDIA))
                .build()
            client.newCall(req).execute().use { resp ->
                val respText = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "fetchOpenIdByMobile: HTTP ${resp.code} body=$respText")
                    return@use FetchResult(null, "HTTP 错误 ${resp.code}")
                }
                val json = runCatching { JSONObject(respText) }.getOrNull()
                val code = json?.optInt("code", -1)
                if (code != 0) {
                    val msg = json?.optString("msg") ?: respText
                    Log.w(TAG, "fetchOpenIdByMobile: 飞书返回错误 code=$code msg=$msg")
                    // token 失效，清缓存
                    if (code == 99991663 || code == 99991664 || code == 99991661) {
                        synchronized(tokenLock) {
                            cachedToken = null
                            tokenExpireAt = 0L
                        }
                    }
                    // 权限不足：需要勾选 contact:user.id:readonly 权限
                    val hint = when (code) {
                        99991672, 99991661 ->
                            "，请到飞书开放平台 → 应用 → 权限管理，搜索「通过手机号或邮箱获取用户 ID」并勾选后重新发布"
                        230002, 230013 ->
                            "，请到飞书开放平台 → 应用 → 应用可用范围，把目标用户加入可用范围后重新发布"
                        else -> ""
                    }
                    return@use FetchResult(null, "飞书返回错误 code=$code msg=$msg$hint")
                }
                val userList = json?.optJSONObject("data")?.optJSONArray("user_list")
                    ?: return@use FetchResult(null, "未找到用户列表，请确认手机号是否正确")
                if (userList.length() == 0) {
                    return@use FetchResult(null, "手机号 $mobile 未找到对应飞书用户，请确认是否为飞书账号绑定手机号")
                }
                // user_list[0].user_id 即 open_id（因为我们请求时 user_id_type=open_id）
                val userId = userList.optJSONObject(0)?.optString("user_id").orEmpty()
                if (userId.startsWith("ou_")) {
                    Log.i(TAG, "fetchOpenIdByMobile: 找到 open_id=${userId.take(20)}… (手机号=${mobile.take(3)}***${mobile.takeLast(4)})")
                    return@use FetchResult(userId, null)
                }
                FetchResult(null, "查询到的 user_id 格式异常：$userId")
            }
        }.getOrElse { e ->
            Log.e(TAG, "fetchOpenIdByMobile: 异常", e)
            FetchResult(null, "异常：${e.message}")
        }
    }

    /** fetchOpenIdByMobile 的返回值 */
    data class FetchResult(val openId: String?, val errorMsg: String?)

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
