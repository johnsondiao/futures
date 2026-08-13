package com.futures.channel

import android.util.Log
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 原生开仓信号检测器：与 [android/app/src/main/assets/www/strategy.js] 的
 * computeMainChart 中 open_long / open_short 判断对齐（通道 MA + CCI 金叉/死叉 + 窗口去重）。
 *
 * 支持两个策略周期（与 src/config.py 的 STRATEGY_PROFILES 一致）：
 *   - 5m：直接在 5m K 线上计算（同原有行为）
 *   - 60m：先把 5m 行情按整点聚合成 60m K 线（丢弃未收盘桶，收盘确认才出信号），
 *     并额外计算 ATR(20) 与三档止盈价，随推送附带参考价位。
 *
 * 仅负责开仓标记检测与去重，不复现止盈/止损/挂单等全部策略——这些仍由前端 JS 在前台渲染。
 * 后台时 WebView JS 会被系统暂停，本类独立运行确保开仓提醒不漏。
 */
class ChannelSignalDetector(
    private val channelN: Int = 60,
    private val cciP: Int = 15,
    private val cciM: Int = 4,
    val period: String = PERIOD_5M,
    private val atrN: Int = 20,
    private val tpMults: DoubleArray = doubleArrayOf(1.5, 3.0, 5.0),
) {
    data class OpenSignal(
        val kind: String,
        val time: Long,
        val barTime: String,
        val entry: Double = Double.NaN,
        val tp1: Double = Double.NaN,
        val tp2: Double = Double.NaN,
        val tp3: Double = Double.NaN,
        /** 信号产生时的策略周期（避免切换策略后通知文案标错） */
        val period: String = PERIOD_5M,
    )

    /** 已响过的开仓标记键："time|L" / "time|S"，与 app.js collectOpenKeys 一致。 */
    private val seenKeys = LinkedHashSet<String>()

    /** 首次 detect 只初始化 seenKeys 不响铃，对齐 app.js maybeAlertOpens 的 seenOpenKeys===null 基线分支 */
    private var initialized = false

    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    /**
     * 输入 DiffMdClient 输出的 bars JSONArray（每根含 time/open/high/low/close），
     * 返回本次新发现的开仓信号（已去重，历史信号不重复返回）。
     */
    fun detect(bars: JSONArray): List<OpenSignal> {
        val rawN = bars.length()
        if (rawN == 0) return emptyList()

        var h = DoubleArray(rawN)
        var l = DoubleArray(rawN)
        var c = DoubleArray(rawN)
        var t = LongArray(rawN)
        for (i in 0 until rawN) {
            val bar = bars.getJSONObject(i)
            h[i] = bar.optDouble("high", Double.NaN)
            l[i] = bar.optDouble("low", Double.NaN)
            c[i] = bar.optDouble("close", Double.NaN)
            t[i] = bar.optLong("time", 0L)
        }

        // 60m 策略：按整点聚合成 60m K 线，丢弃未收盘桶（收盘确认才出信号，与回测口径一致）
        if (period == PERIOD_60M) {
            val agg = aggregateBuckets(h, l, c, t, 3600L)
            if (agg == null) {
                Log.d(TAG, "detect[60m]: 聚合后无已收盘 K 线，跳过")
                return emptyList()
            }
            h = agg.h
            l = agg.l
            c = agg.c
            t = agg.t
        }
        val n = t.size
        if (n < channelN + cciP) {
            Log.d(TAG, "detect[$period]: bars 数量 $n < ${channelN + cciP}，跳过")
            return emptyList()
        }

        val upper = ma(h, channelN)
        val lower = ma(l, channelN)

        // side: close>upper?1 : close<lower?-1 : 0（upper/lower 非数时 0）
        val side = IntArray(n) { i ->
            when {
                !upper[i].isNum() || !lower[i].isNum() -> 0
                c[i] > upper[i] -> 1
                c[i] < lower[i] -> -1
                else -> 0
            }
        }
        // color = valueWhen(side≠0, side)，用 Double 承载 1.0/-1.0/NaN
        val color = valueWhen(
            BooleanArray(n) { side[it] != 0 },
            DoubleArray(n) { side[it].toDouble() }
        )

        val colorRef = ref(color, 1)
        val turnRed = BooleanArray(n) { i -> color[i] == 1.0 && colorRef[i] != 1.0 }
        val turnGreen = BooleanArray(n) { i -> color[i] == -1.0 && colorRef[i] != -1.0 }
        val barsRed = barsLast(turnRed)
        val barsGreen = barsLast(turnGreen)

        val typ = DoubleArray(n) { i -> (h[i] + l[i] + c[i]) / 3.0 }
        val typMa = ma(typ, cciP)
        val typAvedev = avedev(typ, cciP)
        val cci = DoubleArray(n) { i ->
            val ma = typMa[i]
            val ad = typAvedev[i]
            if (!ma.isNum() || !ad.isNum() || ad == 0.0) Double.NaN
            else (typ[i] - ma) / (0.015 * ad)
        }
        val cciMa = ma(cci, cciM)
        val cciRef = ref(cci, 1)
        val cciMaRef = ref(cciMa, 1)
        val golden = BooleanArray(n) { i ->
            cci[i].isNum() && cciMa[i].isNum() &&
                cci[i] > cciMa[i] && cciRef[i] <= cciMaRef[i]
        }
        val death = BooleanArray(n) { i ->
            cci[i].isNum() && cciMa[i].isNum() &&
                cci[i] < cciMa[i] && cciRef[i] >= cciMaRef[i]
        }

        val candLong = BooleanArray(n) { i ->
            golden[i] && color[i] == 1.0 && barsRed[i] < barsGreen[i]
        }
        val candShort = BooleanArray(n) { i ->
            death[i] && color[i] == -1.0 && barsGreen[i] < barsRed[i]
        }
        val winLong = IntArray(n) { barsRed[it] + 1 }
        val winShort = IntArray(n) { barsGreen[it] + 1 }
        val cntLong = countInWindow(candLong, winLong)
        val cntShort = countInWindow(candShort, winShort)
        val openLong = BooleanArray(n) { i -> candLong[i] && cntLong[i] == 1 }
        val openShort = BooleanArray(n) { i -> candShort[i] && cntShort[i] == 1 }

        // 最后一根 bar 的状态日志，便于和前端对齐
        val lastIdx = n - 1
        Log.d(
            TAG,
            "detect[$period]: bars=$n initialized=$initialized seenKeys=${seenKeys.size} " +
                "lastBar[${formatTime(t[lastIdx])}] " +
                "close=${String.format("%.0f", c[lastIdx])} " +
                "color=${if (color[lastIdx].isNum()) color[lastIdx].toInt() else "NaN"} " +
                "cci=${if (cci[lastIdx].isNum()) String.format("%.1f", cci[lastIdx]) else "NaN"} " +
                "cciMa=${if (cciMa[lastIdx].isNum()) String.format("%.1f", cciMa[lastIdx]) else "NaN"} " +
                "golden=${golden[lastIdx]} death=${death[lastIdx]} " +
                "candLong=${candLong[lastIdx]} candShort=${candShort[lastIdx]} " +
                "openLong=${openLong[lastIdx]} openShort=${openShort[lastIdx]}"
        )

        val out = mutableListOf<OpenSignal>()
        // 60m 策略：计算 ATR 与三档止盈参考价位，随推送一起发出
        val atrArr: DoubleArray? = if (period == PERIOD_60M) {
            val prevC = ref(c, 1)
            val tr = DoubleArray(n) { i ->
                val hl = h[i] - l[i]
                val pc = prevC[i]
                if (pc.isNum()) maxOf(hl, abs(h[i] - pc), abs(l[i] - pc)) else hl
            }
            ma(tr, atrN)
        } else {
            null
        }
        for (i in 0 until n) {
            if (openLong[i]) addIfNew(t[i], "long", i, +1, c[i], atrArr, out)
            if (openShort[i]) addIfNew(t[i], "short", i, -1, c[i], atrArr, out)
        }
        // 首次只初始化 seenKeys，不响历史开仓信号（基线语义，对齐 app.js）
        if (!initialized) {
            Log.i(
                TAG,
                "detect: 首次调用，仅初始化基线 seenKeys=${seenKeys.size}（历史信号共 ${out.size} 个不响铃）"
            )
            initialized = true
            return emptyList()
        }
        if (out.isNotEmpty()) {
            Log.i(TAG, "detect: 发现 ${out.size} 个新信号（非首次）→ 返回")
        }
        return out
    }

    private fun addIfNew(
        time: Long,
        kind: String,
        idx: Int,
        sign: Int,
        entryPrice: Double,
        atrArr: DoubleArray?,
        out: MutableList<OpenSignal>,
    ) {
        val suffix = if (kind == "long") "L" else "S"
        val key = "$time|$suffix"
        if (!seenKeys.add(key)) return
        var entry = Double.NaN
        var tp1 = Double.NaN
        var tp2 = Double.NaN
        var tp3 = Double.NaN
        val atrVal = atrArr?.get(idx)
        if (atrVal != null && atrVal.isNum() && entryPrice.isNum() && tpMults.size == 3) {
            entry = entryPrice
            tp1 = entry + sign * tpMults[0] * atrVal
            tp2 = entry + sign * tpMults[1] * atrVal
            tp3 = entry + sign * tpMults[2] * atrVal
        }
        out.add(OpenSignal(kind, time, formatTime(time), entry, tp1, tp2, tp3, period))
    }

    private class Agg(
        val h: DoubleArray,
        val l: DoubleArray,
        val c: DoubleArray,
        val t: LongArray,
    )

    /**
     * 按 bucketSec 分桶聚合（北京时间 UTC+8 整点对齐 epoch 小时边界，与天勤 60m K 线一致）。
     * 丢弃最后一个桶（仍在进行中），只保留已收盘 K 线。
     */
    private fun aggregateBuckets(
        h: DoubleArray,
        l: DoubleArray,
        c: DoubleArray,
        t: LongArray,
        bucketSec: Long,
    ): Agg? {
        val times = ArrayList<Long>()
        val highs = ArrayList<Double>()
        val lows = ArrayList<Double>()
        val closes = ArrayList<Double>()
        for (i in t.indices) {
            val b = (t[i] / bucketSec) * bucketSec
            if (times.isEmpty() || times.last() != b) {
                times.add(b)
                highs.add(h[i])
                lows.add(l[i])
                closes.add(c[i])
            } else {
                val k = times.size - 1
                if (h[i] > highs[k]) highs[k] = h[i]
                if (l[i] < lows[k]) lows[k] = l[i]
                closes[k] = c[i]
            }
        }
        val end = times.size - 1 // 丢弃进行中桶
        if (end <= 0) return null
        return Agg(
            highs.toDoubleArray().copyOf(end),
            lows.toDoubleArray().copyOf(end),
            closes.toDoubleArray().copyOf(end),
            times.toLongArray().copyOf(end),
        )
    }

    private fun formatTime(sec: Long): String {
        return try {
            timeFmt.format(Date(sec * 1000L))
        } catch (_: Exception) {
            sec.toString()
        }
    }

    companion object {
        const val PERIOD_5M = "5m"
        const val PERIOD_60M = "60m"
        private const val TAG = "SignalDetector"
        private fun Double.isNum(): Boolean = this.isFinite()

        /** 简单移动平均：i<n-1 时 NaN；窗口 [i-n+1, i] 任一元素非数则 NaN，否则均值。对齐 strategy.js ma。 */
        fun ma(arr: DoubleArray, n: Int): DoubleArray {
            val out = DoubleArray(arr.size) { Double.NaN }
            if (n <= 0) return out
            for (i in arr.indices) {
                if (i < n - 1) continue
                var s = 0.0
                var ok = true
                for (j in i - n + 1..i) {
                    if (!arr[j].isNum()) {
                        ok = false
                        break
                    }
                    s += arr[j]
                }
                if (ok) out[i] = s / n
            }
            return out
        }

        /** 位移：out[i] = arr[i-k]，i<k 时 NaN。 */
        fun ref(arr: DoubleArray, k: Int): DoubleArray {
            val out = DoubleArray(arr.size) { Double.NaN }
            if (k <= 0) {
                for (i in arr.indices) out[i] = arr[i]
                return out
            }
            for (i in k until arr.size) out[i] = arr[i - k]
            return out
        }

        /** 距上次 cond=true 的根数；true 时 0；之前无 true 时 i；否则 i-last。 */
        fun barsLast(cond: BooleanArray): IntArray {
            val n = cond.size
            val out = IntArray(n)
            var last = -1
            for (i in 0 until n) {
                if (cond[i]) {
                    last = i
                    out[i] = 0
                } else if (last < 0) {
                    out[i] = i
                } else {
                    out[i] = i - last
                }
            }
            return out
        }

        /** 上次 cond=true 时的 x 值，初始 NaN。 */
        fun valueWhen(cond: BooleanArray, x: DoubleArray): DoubleArray {
            val out = DoubleArray(x.size) { Double.NaN }
            var last = Double.NaN
            for (i in x.indices) {
                if (cond[i] && x[i].isNum()) last = x[i]
                out[i] = last
            }
            return out
        }

        /** 每根 bar 的窗口内 true 计数；win[i] 为该 bar 的窗口长度。对齐 strategy.js countInWindow。 */
        fun countInWindow(cond: BooleanArray, win: IntArray): IntArray {
            val n = cond.size
            val pref = IntArray(n + 1)
            for (i in 0 until n) pref[i + 1] = pref[i] + (if (cond[i]) 1 else 0)
            val out = IntArray(n)
            for (i in 0 until n) {
                val wRaw = if (i < win.size) win[i] else 1
                val w = maxOf(1, wRaw)
                val left = maxOf(0, i - w + 1)
                out[i] = pref[i + 1] - pref[left]
            }
            return out
        }

        /** 平均绝对偏差：i<n-1 时 NaN；窗口内均值后各元素绝对偏差的均值。 */
        fun avedev(arr: DoubleArray, n: Int): DoubleArray {
            val out = DoubleArray(arr.size) { Double.NaN }
            if (n <= 0) return out
            for (i in arr.indices) {
                if (i < n - 1) continue
                var s = 0.0
                var ok = true
                for (j in i - n + 1..i) {
                    if (!arr[j].isNum()) {
                        ok = false
                        break
                    }
                    s += arr[j]
                }
                if (!ok) continue
                val mean = s / n
                var d = 0.0
                for (j in i - n + 1..i) d += abs(arr[j] - mean)
                out[i] = d / n
            }
            return out
        }
    }
}
