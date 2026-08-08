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
 * 飞书自定义机器人 Webhook 推送。
 *
 * 用途：作为系统通知/震动/声音之外的「兜底通道」，防止 App 在后台、
 * 用户手机静音、或系统通知被屏蔽时漏掉开仓信号。
 *
 * 用户接入步骤（零成本）：
 *   1) 飞书建群（哪怕群里只有自己一个人）
 *   2) 群设置 → 群机器人 → 添加机器人 → 自定义机器人 → 复制 Webhook URL
 *   3) 把 URL 粘贴到 App 的设置页保存即可
 *
 * 飞书机器人的优势：
 *   - 免费、无发送量限制
 *   - 国内网络直连，不需要 VPN
 *   - 飞书 App 可在「设置 → 新消息通知」中进一步开启「短信/电话兜底提醒」
 *   - 小米 HyperOS 的飞书通知默认会进入灵动岛
 */
class FeishuWebhookNotifier {

    companion object {
        private const val TAG = "FeishuNotifier"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    /**
     * 推送一条交互式卡片到飞书机器人，不阻塞调用方：失败只记录日志。
     *
     * @param webhookUrl 用户配置的飞书机器人 Webhook；为空或空白则直接跳过
     * @param kind       "long" / "short"
     * @param title      卡片标题，如 "开多信号"
     * @param body       卡片正文，如 "DCE.a2609 K线出现开多标记 · 09:30"
     * @param extraLines 可选附加信息行（以 Markdown 列表方式追加），例如加入
     *                   "当前价: 3520" / "EMA20: 3498"，有助于事后复盘
     */
    fun send(
        webhookUrl: String?,
        kind: String,
        title: String,
        body: String,
        extraLines: List<String> = emptyList()
    ) {
        if (webhookUrl.isNullOrBlank()) return
        // 不阻塞主线程/开仓检测线程
        Thread {
            try {
                val payload = buildInteractiveCard(kind, title, body, extraLines)
                val req = Request.Builder()
                    .url(webhookUrl.trim())
                    .post(payload.toString().toRequestBody(JSON_MEDIA))
                    .build()
                client.newCall(req).execute().use { resp ->
                    val respText = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "send: HTTP ${resp.code} body=$respText")
                        return@use
                    }
                    // 飞书返回 {"code":0,"msg":"success"} 时表示成功
                    val json = runCatching { JSONObject(respText) }.getOrNull()
                    val code = json?.optInt("code", -1)
                    if (code == 0) {
                        Log.d(TAG, "send: 飞书推送成功 title=$title")
                    } else {
                        Log.w(TAG, "send: 飞书返回错误 code=$code msg=${json?.optString("msg")} body=$respText")
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
    fun test(webhookUrl: String): String {
        return runCatching {
            val payload = buildInteractiveCard(
                kind = "long",
                title = "测试推送",
                body = "如果您能看到这条消息，说明机器人配置成功 ✅",
                extraLines = listOf(
                    "配置时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
                        .format(java.util.Date())}",
                    "当前合约: DCE.a2609"
                )
            )
            val req = Request.Builder()
                .url(webhookUrl.trim())
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()
            client.newCall(req).execute().use { resp ->
                val respText = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@runCatching "HTTP 错误 ${resp.code}：$respText"
                val json = runCatching { JSONObject(respText) }.getOrNull()
                val code = json?.optInt("code", -1)
                if (code == 0) "✅ 发送成功，请在飞书群里查看"
                else "飞书返回错误 code=$code msg=${json?.optString("msg") ?: respText}"
            }
        }.getOrElse { "❌ 异常：${it.message}" }
    }

    // ===== 内部 =====

    private fun buildInteractiveCard(
        kind: String,
        title: String,
        body: String,
        extraLines: List<String>
    ): JSONObject {
        // 颜色模板：开多=红涨（turquoise/fuchsia red/green 按飞书语义）
        // 飞书 card header template 可选值: blue/green/orange/red/purple/grey/turquoise/carmine/violet/yellow
        // 我们用 red = 开多(上涨预警)、blue = 开空(下跌预警)，直观。
        val template = if (kind.equals("long", ignoreCase = true)) "red" else "blue"
        val emoji = if (kind.equals("long", ignoreCase = true)) "\uD83D\uDFE2" else "\uD83D\uDD34"

        val elements = JSONArray()

        // 正文 div
        val mdBody = StringBuilder()
            .append("**").append(body).append("**")
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

        // 底部 note
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

        return JSONObject()
            .put("msg_type", "interactive")
            .put(
                "card",
                JSONObject()
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
            )
    }
}
