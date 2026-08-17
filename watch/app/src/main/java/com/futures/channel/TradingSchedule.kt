package com.futures.channel

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * DCE 期货交易时段表（北京时间，周一至周五）。
 *
 * 实际时段：
 *   日盘 09:00–10:15 / 10:30–11:30 / 13:30–15:00
 *   夜盘 21:00–23:00（豆二 a2609）
 *
 * 窗口前后各留缓冲：提前 5 分钟唤醒建连、延后 5 分钟再停，
 * 上午两个日盘时段间隔仅 15 分钟，合并为一个大窗口避免反复停启。
 *
 * 注意：不含节假日判断（无日历数据源）；节假日落在工作日时行情服务端无数据，
 * App 只是空转重连，影响很小。
 */
object TradingSchedule {

    private data class Window(val startMin: Int, val endMin: Int)

    /** 带缓冲的运行窗口（分钟数，相对 00:00） */
    private val WINDOWS = listOf(
        Window(8 * 60 + 55, 11 * 60 + 35),  // 日盘上午 09:00–10:15 + 10:30–11:30（含小节休息）
        Window(13 * 60 + 25, 15 * 60 + 5),  // 日盘下午 13:30–15:00
        Window(20 * 60 + 55, 23 * 60 + 5),  // 夜盘 21:00–23:00
    )

    private val TZ = TimeZone.getTimeZone("Asia/Shanghai")
    private val DOW_CN = arrayOf("", "周日", "周一", "周二", "周三", "周四", "周五", "周六")

    /** 当前是否处于交易运行窗口内 */
    fun isOpen(now: Long = System.currentTimeMillis()): Boolean {
        val cal = Calendar.getInstance(TZ).apply { timeInMillis = now }
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) return false
        val m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return WINDOWS.any { m >= it.startMin && m < it.endMin }
    }

    /** 下一个运行窗口开始的时刻（毫秒）；当前已在窗口内则返回 now */
    fun nextOpenAt(now: Long = System.currentTimeMillis()): Long {
        if (isOpen(now)) return now
        val day0 = Calendar.getInstance(TZ).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        for (d in 0..7) {
            val day = day0.clone() as Calendar
            day.add(Calendar.DAY_OF_YEAR, d)
            val dow = day.get(Calendar.DAY_OF_WEEK)
            if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) continue
            for (w in WINDOWS) {
                val startMs = day.timeInMillis + w.startMin * 60_000L
                if (startMs > now) return startMs
            }
        }
        return now + 3_600_000L
    }

    /** 下次开市的可读文案，如「周一 09:00」 */
    fun nextOpenText(now: Long = System.currentTimeMillis()): String {
        val at = nextOpenAt(now)
        val cal = Calendar.getInstance(TZ).apply { timeInMillis = at }
        val hm = SimpleDateFormat("HH:mm", Locale.CHINA).apply { timeZone = TZ }
        return "${DOW_CN[cal.get(Calendar.DAY_OF_WEEK)]} ${hm.format(Date(at))}"
    }
}
