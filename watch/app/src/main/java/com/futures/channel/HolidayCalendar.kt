package com.futures.channel

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * 期货交易节假日日历。
 *
 * 数据源：天勤官方 shinny_chinese_holiday.json（tqsdk 交易日历同款数据），
 * 内容为节假日列表（yyyy-MM-dd）；交易日 = 周一至周五 且 不在列表中
 * （调休上班日恰逢周末时期货不开市，按周末规则天然排除）。
 *
 * 策略：每天联网刷新一次，本地缓存兜底；拉取失败/离线时沿用上次缓存，
 * 缓存也缺失时退化为仅按星期判断。
 */
object HolidayCalendar {

    private const val TAG = "HolidayCalendar"
    private const val URL_HOLIDAYS = "https://files.shinnytech.com/shinny_chinese_holiday.json"
    private const val PREFS_NAME = "watch_prefs"
    private const val KEY_HOLIDAYS = "trading_holidays"
    private const val KEY_UPDATED_AT = "holidays_updated_at"
    private const val REFRESH_INTERVAL_MS = 24L * 60 * 60 * 1000

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 北京时间的 yyyy-MM-dd */
    fun dateStr(now: Long = System.currentTimeMillis()): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
            .apply { timeInMillis = now }
        return String.format(
            "%04d-%02d-%02d",
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    /** 读取本地缓存的节假日集合（无缓存返回空集 = 仅按星期判断） */
    fun loadCached(ctx: Context): Set<String> {
        val raw = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_HOLIDAYS, null) ?: return emptySet()
        return raw.split(',').filter { it.length == 10 }.toSet()
    }

    /** 是否需要刷新：从未拉过 / 超过 24 小时 / 缓存未覆盖当前年份 */
    fun needRefresh(ctx: Context, now: Long = System.currentTimeMillis()): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val at = prefs.getLong(KEY_UPDATED_AT, 0L)
        if (at <= 0) return true
        if (now - at > REFRESH_INTERVAL_MS) return true
        val year = dateStr(now).substring(0, 4)
        val cached = prefs.getString(KEY_HOLIDAYS, null).orEmpty()
        return !cached.contains(year)
    }

    /** 联网刷新（需在 IO 线程调用）；成功返回最新集合并写缓存，失败返回 null */
    fun refresh(ctx: Context): Set<String>? {
        return try {
            val req = Request.Builder()
                .url(URL_HOLIDAYS)
                .header("User-Agent", "ChannelStrategyWatch/1.0")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "refresh: HTTP ${resp.code}")
                    return null
                }
                val arr = JSONArray(resp.body?.string().orEmpty())
                val set = HashSet<String>(arr.length())
                for (i in 0 until arr.length()) {
                    val d = arr.optString(i)
                    if (d.length == 10) set.add(d)
                }
                ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putString(KEY_HOLIDAYS, set.joinToString(","))
                    .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                    .apply()
                Log.i(TAG, "refresh: 节假日数据已更新，共 ${set.size} 天")
                set
            }
        } catch (e: Exception) {
            Log.e(TAG, "refresh: 拉取失败", e)
            null
        }
    }
}
