package com.futures.channel

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 快期 OpenID 鉴权 + 行情网关地址解析。 */
class ShinnyAuth(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    data class Session(val accessToken: String, val mdUrl: String)

    fun login(user: String, password: String): Session {
        val tokenBody = FormBody.Builder()
            .add("grant_type", "password")
            .add("username", user)
            .add("password", password)
            .add("client_id", "shinny_tq")
            .add("client_secret", "be30b9f4-6862-488a-99ad-21bde0400081")
            .build()
        val tokenReq = Request.Builder()
            .url("https://auth.shinnytech.com/auth/realms/shinnytech/protocol/openid-connect/token")
            .post(tokenBody)
            .header("Accept", "application/json")
            .header("User-Agent", "ChannelStrategyApp/1.0")
            .build()
        client.newCall(tokenReq).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("登录失败(${resp.code}): $text")
            }
            val access = JSONObject(text).getString("access_token")
            val mdUrl = fetchMdUrl(access)
            return Session(access, mdUrl)
        }
    }

    private fun fetchMdUrl(accessToken: String): String {
        val req = Request.Builder()
            .url("https://api.shinnytech.com/ns?stock=false&backtest=false")
            .get()
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .header("User-Agent", "ChannelStrategyApp/1.0")
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("获取行情地址失败(${resp.code}): $text")
            }
            val obj = JSONObject(text)
            if (!obj.has("mdurl")) throw IllegalStateException("行情地址缺失: $text")
            return obj.getString("mdurl")
        }
    }
}
