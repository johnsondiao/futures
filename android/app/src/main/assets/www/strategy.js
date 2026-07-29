/**
 * 通道策略主图 + 条件单 + 机会评估（与 src/ths_sim/formula_main.py / signal_analytics.py 对齐）
 * 输入 bars: [{time, open, high, low, close}, ...] time 为秒或可 Date.parse 的字符串
 */
(function (global) {
  "use strict";

  function isNum(x) {
    return typeof x === "number" && Number.isFinite(x);
  }

  function nan() {
    return NaN;
  }

  function ma(arr, n) {
    const out = new Array(arr.length).fill(nan());
    let sum = 0;
    for (let i = 0; i < arr.length; i++) {
      const v = arr[i];
      if (!isNum(v)) {
        sum = 0;
        continue;
      }
      sum += v;
      if (i >= n) {
        const old = arr[i - n];
        if (isNum(old)) sum -= old;
      }
      if (i >= n - 1) {
        let ok = true;
        for (let j = i - n + 1; j <= i; j++) {
          if (!isNum(arr[j])) {
            ok = false;
            break;
          }
        }
        if (ok) out[i] = sum / n;
      }
    }
    // recompute with simple loop for correctness on NaNs
    for (let i = 0; i < arr.length; i++) {
      if (i < n - 1) {
        out[i] = nan();
        continue;
      }
      let s = 0;
      let ok = true;
      for (let j = i - n + 1; j <= i; j++) {
        if (!isNum(arr[j])) {
          ok = false;
          break;
        }
        s += arr[j];
      }
      out[i] = ok ? s / n : nan();
    }
    return out;
  }

  function ref(arr, k) {
    k = k == null ? 1 : k;
    const out = new Array(arr.length).fill(nan());
    for (let i = k; i < arr.length; i++) out[i] = arr[i - k];
    return out;
  }

  function barsLast(cond) {
    const n = cond.length;
    const out = new Array(n);
    let last = -1;
    for (let i = 0; i < n; i++) {
      if (cond[i]) {
        last = i;
        out[i] = 0;
      } else if (last < 0) out[i] = i;
      else out[i] = i - last;
    }
    return out;
  }

  function valueWhen(cond, x) {
    const out = new Array(x.length).fill(nan());
    let last = nan();
    for (let i = 0; i < x.length; i++) {
      if (cond[i] && isNum(x[i])) last = x[i];
      out[i] = last;
    }
    return out;
  }

  function countInWindow(cond, win) {
    const n = cond.length;
    const c = cond.map((v) => (v ? 1 : 0));
    const pref = new Array(n + 1).fill(0);
    for (let i = 0; i < n; i++) pref[i + 1] = pref[i] + c[i];
    const out = new Array(n);
    for (let i = 0; i < n; i++) {
      const w = Math.max(1, Math.floor(win[i] || 1));
      const left = Math.max(0, i - w + 1);
      out[i] = pref[i + 1] - pref[left];
    }
    return out;
  }

  function existInWindow(cond, win) {
    return countInWindow(cond, win).map((v) => v > 0);
  }

  function avedev(arr, n) {
    const out = new Array(arr.length).fill(nan());
    for (let i = 0; i < arr.length; i++) {
      if (i < n - 1) continue;
      let ok = true;
      let s = 0;
      for (let j = i - n + 1; j <= i; j++) {
        if (!isNum(arr[j])) {
          ok = false;
          break;
        }
        s += arr[j];
      }
      if (!ok) continue;
      const mean = s / n;
      let d = 0;
      for (let j = i - n + 1; j <= i; j++) d += Math.abs(arr[j] - mean);
      out[i] = d / n;
    }
    return out;
  }

  function rollingSum(arr, n) {
    const out = new Array(arr.length).fill(nan());
    for (let i = 0; i < arr.length; i++) {
      if (i < n - 1) continue;
      let s = 0;
      let ok = true;
      for (let j = i - n + 1; j <= i; j++) {
        if (!isNum(arr[j])) {
          ok = false;
          break;
        }
        s += arr[j];
      }
      if (ok) out[i] = s;
    }
    return out;
  }

  function computeMainChart(bars, opts) {
    opts = opts || {};
    const channelN = opts.channel_n || 60;
    const cciP = opts.cci_p || 15;
    const cciM = opts.cci_m || 4;
    const atrN = opts.atr_n || 20;
    const n = bars.length;
    const o = bars.map((b) => +b.open);
    const h = bars.map((b) => +b.high);
    const l = bars.map((b) => +b.low);
    const c = bars.map((b) => +b.close);
    const times = bars.map((b) => b.time);

    const upper = ma(h, channelN);
    const lower = ma(l, channelN);
    const side = c.map((cv, i) => {
      if (!isNum(upper[i]) || !isNum(lower[i])) return 0;
      if (cv > upper[i]) return 1;
      if (cv < lower[i]) return -1;
      return 0;
    });
    const color = valueWhen(
      side.map((v) => v !== 0),
      side
    );

    const colorRef = ref(color, 1);
    const turnRed = color.map((v, i) => v === 1 && colorRef[i] !== 1);
    const turnGreen = color.map((v, i) => v === -1 && colorRef[i] !== -1);
    const barsRed = barsLast(turnRed);
    const barsGreen = barsLast(turnGreen);

    const typ = c.map((_, i) => (h[i] + l[i] + c[i]) / 3);
    const typMa = ma(typ, cciP);
    const typAvedev = avedev(typ, cciP);
    const cci = typ.map((t, i) => {
      if (!isNum(typMa[i]) || !isNum(typAvedev[i]) || typAvedev[i] === 0) return nan();
      return (t - typMa[i]) / (0.015 * typAvedev[i]);
    });
    const cciMa = ma(cci, cciM);
    const cciRef = ref(cci, 1);
    const cciMaRef = ref(cciMa, 1);
    const golden = cci.map(
      (v, i) => isNum(v) && isNum(cciMa[i]) && v > cciMa[i] && cciRef[i] <= cciMaRef[i]
    );
    const death = cci.map(
      (v, i) => isNum(v) && isNum(cciMa[i]) && v < cciMa[i] && cciRef[i] >= cciMaRef[i]
    );

    const candLong = golden.map(
      (g, i) => g && color[i] === 1 && barsRed[i] < barsGreen[i]
    );
    const candShort = death.map(
      (d, i) => d && color[i] === -1 && barsGreen[i] < barsRed[i]
    );
    const openLong = candLong.map(
      (v, i) => v && countInWindow(candLong, barsRed.map((x) => x + 1))[i] === 1
    );
    const openShort = candShort.map(
      (v, i) => v && countInWindow(candShort, barsGreen.map((x) => x + 1))[i] === 1
    );
    // fix: countInWindow called twice - compute once
    const cntLong = countInWindow(
      candLong,
      barsRed.map((x) => x + 1)
    );
    const cntShort = countInWindow(
      candShort,
      barsGreen.map((x) => x + 1)
    );
    for (let i = 0; i < n; i++) {
      openLong[i] = candLong[i] && cntLong[i] === 1;
      openShort[i] = candShort[i] && cntShort[i] === 1;
    }

    const barsOl = barsLast(openLong);
    const barsOs = barsLast(openShort);
    const prevC = ref(c, 1);
    const tr = c.map((_, i) => {
      const a = h[i] - l[i];
      const b = isNum(prevC[i]) ? Math.abs(h[i] - prevC[i]) : a;
      const d = isNum(prevC[i]) ? Math.abs(l[i] - prevC[i]) : a;
      return Math.max(a, b, d);
    });
    const atr = ma(tr, atrN);
    const entry = valueWhen(
      openLong.map((v, i) => v || openShort[i]),
      c
    );
    const t1l = entry.map((e, i) => (isNum(e) && isNum(atr[i]) ? e + 1.5 * atr[i] : nan()));
    const t2l = entry.map((e, i) => (isNum(e) && isNum(atr[i]) ? e + 3.0 * atr[i] : nan()));
    const t3l = entry.map((e, i) => (isNum(e) && isNum(atr[i]) ? e + 5.0 * atr[i] : nan()));
    const t1s = entry.map((e, i) => (isNum(e) && isNum(atr[i]) ? e - 1.5 * atr[i] : nan()));
    const t2s = entry.map((e, i) => (isNum(e) && isNum(atr[i]) ? e - 3.0 * atr[i] : nan()));
    const t3s = entry.map((e, i) => (isNum(e) && isNum(atr[i]) ? e - 5.0 * atr[i] : nan()));

    const hit1l = existInWindow(
      c.map((cv, i) => isNum(t1l[i]) && cv >= t1l[i]),
      barsOl.map((x) => x + 1)
    );
    const hit2l = existInWindow(
      c.map((cv, i) => isNum(t2l[i]) && cv >= t2l[i]),
      barsOl.map((x) => x + 1)
    );
    const hit3l = existInWindow(
      c.map((cv, i) => isNum(t3l[i]) && cv >= t3l[i]),
      barsOl.map((x) => x + 1)
    );
    const hit1s = existInWindow(
      c.map((cv, i) => isNum(t1s[i]) && cv <= t1s[i]),
      barsOs.map((x) => x + 1)
    );
    const hit2s = existInWindow(
      c.map((cv, i) => isNum(t2s[i]) && cv <= t2s[i]),
      barsOs.map((x) => x + 1)
    );
    const hit3s = existInWindow(
      c.map((cv, i) => isNum(t3s[i]) && cv <= t3s[i]),
      barsOs.map((x) => x + 1)
    );

    const flatLong = turnGreen.map((v, i) => v && barsOl[i] < barsOs[i]);
    const flatShort = turnRed.map((v, i) => v && barsOs[i] < barsOl[i]);
    const barsFl = barsLast(flatLong);
    const barsFs = barsLast(flatShort);

    const longLots = new Array(n);
    const shortLots = new Array(n);
    for (let i = 0; i < n; i++) {
      longLots[i] =
        barsOl[i] < barsOs[i] && barsOl[i] < barsFl[i]
          ? 3 - (hit1l[i] ? 1 : 0) - (hit2l[i] ? 1 : 0) - (hit3l[i] ? 1 : 0)
          : 0;
      shortLots[i] =
        barsOs[i] < barsOl[i] && barsOs[i] < barsFs[i]
          ? 3 - (hit1s[i] ? 1 : 0) - (hit2s[i] ? 1 : 0) - (hit3s[i] ? 1 : 0)
          : 0;
    }

    const waitLong = color.map(
      (col, i) => col === 1 && barsRed[i] <= barsOl[i] && longLots[i] === 0 && shortLots[i] === 0
    );
    const waitShort = color.map(
      (col, i) => col === -1 && barsGreen[i] <= barsOs[i] && longLots[i] === 0 && shortLots[i] === 0
    );
    const longState = waitLong.map((v, i) => v || longLots[i] > 0);
    const shortState = waitShort.map((v, i) => v || shortLots[i] > 0);

    const typRef = ref(typ, 1);
    const sumPrev = rollingSum(typRef, cciP - 1);
    const avedevPrev = ref(avedev(typ, cciP), 1);
    const sigPrev = ref(cciMa, 1);
    const trigger = sumPrev.map((sp, i) => {
      if (!isNum(sp) || !isNum(avedevPrev[i]) || !isNum(sigPrev[i])) return nan();
      return (0.015 * avedevPrev[i] * sigPrev[i] * cciP + sp) / (cciP - 1);
    });

    const condEntry = new Array(n).fill(nan());
    const condTp1 = new Array(n).fill(nan());
    const condTp2 = new Array(n).fill(nan());
    const condTp3 = new Array(n).fill(nan());
    const condTp = new Array(n).fill(nan());
    const condSl = new Array(n).fill(nan());

    for (let i = 0; i < n; i++) {
      if (waitLong[i] || waitShort[i]) {
        condEntry[i] = trigger[i];
        const sign = waitLong[i] ? 1 : -1;
        if (isNum(trigger[i]) && isNum(atr[i])) {
          condTp1[i] = trigger[i] + sign * 1.5 * atr[i];
          condTp2[i] = trigger[i] + sign * 3.0 * atr[i];
          condTp3[i] = trigger[i] + sign * 5.0 * atr[i];
          condTp[i] = condTp1[i];
        }
      } else if (longLots[i] > 0) {
        condTp1[i] = t1l[i];
        condTp2[i] = t2l[i];
        condTp3[i] = t3l[i];
        condTp[i] = !hit1l[i] ? t1l[i] : !hit2l[i] ? t2l[i] : t3l[i];
      } else if (shortLots[i] > 0) {
        condTp1[i] = t1s[i];
        condTp2[i] = t2s[i];
        condTp3[i] = t3s[i];
        condTp[i] = !hit1s[i] ? t1s[i] : !hit2s[i] ? t2s[i] : t3s[i];
      }
      if (longState[i]) condSl[i] = lower[i];
      else if (shortState[i]) condSl[i] = upper[i];
    }

    const hit1lRef = ref(
      hit1l.map((v) => (v ? 1 : 0)),
      1
    );
    const hit2lRef = ref(
      hit2l.map((v) => (v ? 1 : 0)),
      1
    );
    const hit3lRef = ref(
      hit3l.map((v) => (v ? 1 : 0)),
      1
    );
    const hit1sRef = ref(
      hit1s.map((v) => (v ? 1 : 0)),
      1
    );
    const hit2sRef = ref(
      hit2s.map((v) => (v ? 1 : 0)),
      1
    );
    const hit3sRef = ref(
      hit3s.map((v) => (v ? 1 : 0)),
      1
    );

    const tp1Long = hit1l.map(
      (v, i) => v && !(hit1lRef[i] === 1) && barsOl[i] < barsOs[i] && barsOl[i] < barsFl[i]
    );
    const tp2Long = hit2l.map(
      (v, i) => v && !(hit2lRef[i] === 1) && barsOl[i] < barsOs[i] && barsOl[i] < barsFl[i]
    );
    const tp3Long = hit3l.map(
      (v, i) => v && !(hit3lRef[i] === 1) && barsOl[i] < barsOs[i] && barsOl[i] < barsFl[i]
    );
    const tp1Short = hit1s.map(
      (v, i) => v && !(hit1sRef[i] === 1) && barsOs[i] < barsOl[i] && barsOs[i] < barsFs[i]
    );
    const tp2Short = hit2s.map(
      (v, i) => v && !(hit2sRef[i] === 1) && barsOs[i] < barsOl[i] && barsOs[i] < barsFs[i]
    );
    const tp3Short = hit3s.map(
      (v, i) => v && !(hit3sRef[i] === 1) && barsOs[i] < barsOl[i] && barsOs[i] < barsFs[i]
    );

    return {
      time: times,
      open: o,
      high: h,
      low: l,
      close: c,
      upper,
      lower,
      color,
      cci,
      cci_ma: cciMa,
      atr,
      open_long: openLong,
      open_short: openShort,
      flat_long: flatLong,
      flat_short: flatShort,
      tp1_long: tp1Long,
      tp2_long: tp2Long,
      tp3_long: tp3Long,
      tp1_short: tp1Short,
      tp2_short: tp2Short,
      tp3_short: tp3Short,
      long_lots: longLots,
      short_lots: shortLots,
      wait_long: waitLong,
      wait_short: waitShort,
      cond_entry: condEntry,
      cond_tp: condTp,
      cond_tp1: condTp1,
      cond_tp2: condTp2,
      cond_tp3: condTp3,
      cond_sl: condSl,
      entry,
      trigger,
    };
  }

  function round1(x) {
    return Math.round(x * 10) / 10;
  }
  function round2(x) {
    return Math.round(x * 100) / 100;
  }
  function numOrNull(x) {
    return isNum(x) ? x : null;
  }

  function lastBarStatus(ind) {
    const i = ind.close.length - 1;
    const entry = numOrNull(ind.cond_entry[i]);
    const tp = numOrNull(ind.cond_tp[i]);
    const tp1 = numOrNull(ind.cond_tp1[i]);
    const tp2 = numOrNull(ind.cond_tp2[i]);
    const tp3 = numOrNull(ind.cond_tp3[i]);
    const sl = numOrNull(ind.cond_sl[i]);
    const close = ind.close[i];
    const atr = isNum(ind.atr[i]) && ind.atr[i] > 0 ? ind.atr[i] : null;

    function addTps(orders, side, op, ge) {
      const sym = ge ? "≥" : "≤";
      [tp1, tp2, tp3].forEach((price, idx) => {
        if (price == null) return;
        orders.push({
          role: "止盈" + (idx + 1),
          side,
          lots: 1,
          op,
          price,
          text: "最新价 " + sym + " " + price.toFixed(0) + " → 平 1 手（止盈" + (idx + 1) + "）",
        });
      });
    }

    const orders = [];
    let state;
    let need_order;
    let summary;
    let how;

    if (ind.wait_long[i]) {
      state = "等开多";
      need_order = true;
      summary = "需要设条件单：开多 3 手";
      if (entry != null) {
        orders.push({
          role: "开多仓",
          side: "买开",
          direction: "开多仓",
          lots: 3,
          op: "大于等于",
          price: entry,
          text: "开多仓，开仓价格 " + entry.toFixed(0) + "（最新价 ≥ " + entry.toFixed(0) + " 买开 3 手）",
        });
      }
      addTps(orders, "卖平", "大于等于", true);
      if (sl != null) {
        orders.push({
          role: "止损",
          side: "卖平",
          lots: 3,
          op: "小于等于",
          price: sl,
          text: "最新价 ≤ " + sl.toFixed(0) + " → 剩余全平（止损/反向）",
        });
      }
      how = "开仓/止盈用「大于等于」，止损用「小于等于」；止盈分三档各 1 手";
    } else if (ind.wait_short[i]) {
      state = "等开空";
      need_order = true;
      summary = "需要设条件单：开空 3 手";
      if (entry != null) {
        orders.push({
          role: "开空仓",
          side: "卖开",
          direction: "开空仓",
          lots: 3,
          op: "小于等于",
          price: entry,
          text: "开空仓，开仓价格 " + entry.toFixed(0) + "（最新价 ≤ " + entry.toFixed(0) + " 卖开 3 手）",
        });
      }
      addTps(orders, "买平", "小于等于", false);
      if (sl != null) {
        orders.push({
          role: "止损",
          side: "买平",
          lots: 3,
          op: "大于等于",
          price: sl,
          text: "最新价 ≥ " + sl.toFixed(0) + " → 剩余全平（止损/反向）",
        });
      }
      how = "开仓/止盈用「小于等于」，止损用「大于等于」；止盈分三档各 1 手";
    } else if (ind.long_lots[i] > 0) {
      state = "持多" + Math.floor(ind.long_lots[i]) + "手";
      need_order = true;
      summary = "需要改条件单：持多止盈/止损";
      addTps(orders, "卖平", "大于等于", true);
      if (sl != null) {
        orders.push({
          role: "止损",
          side: "卖平",
          lots: Math.floor(ind.long_lots[i]),
          op: "小于等于",
          price: sl,
          text: "最新价 ≤ " + sl.toFixed(0) + " → 剩余全平（止损/反向）",
        });
      }
      how = "止盈「大于等于」三档各 1 手，止损「小于等于」";
    } else if (ind.short_lots[i] > 0) {
      state = "持空" + Math.floor(ind.short_lots[i]) + "手";
      need_order = true;
      summary = "需要改条件单：持空止盈/止损";
      addTps(orders, "买平", "小于等于", false);
      if (sl != null) {
        orders.push({
          role: "止损",
          side: "买平",
          lots: Math.floor(ind.short_lots[i]),
          op: "大于等于",
          price: sl,
          text: "最新价 ≥ " + sl.toFixed(0) + " → 剩余全平（止损/反向）",
        });
      }
      how = "止盈「小于等于」三档各 1 手，止损「大于等于」";
    } else {
      state = "观望";
      need_order = false;
      summary = "无需设条件单，等通道变色 / CCI 确认";
      how = "暂无条件单";
    }

    orders.forEach((o) => {
      if (o.price == null) {
        o.distance_points = null;
        o.distance_atr = null;
      } else {
        const dist = Math.abs(o.price - close);
        o.distance_points = round1(dist);
        o.distance_atr = atr ? round2(dist / atr) : null;
      }
    });

    return {
      datetime: String(ind.time[i]),
      close,
      atr: atr == null ? null : round2(atr),
      state,
      need_order,
      summary,
      how,
      orders,
      cond_entry: entry,
      cond_tp: tp,
      cond_tp1: tp1,
      cond_tp2: tp2,
      cond_tp3: tp3,
      cond_sl: sl,
      upper: numOrNull(ind.upper[i]),
      lower: numOrNull(ind.lower[i]),
      color: isNum(ind.color[i]) ? Math.trunc(ind.color[i]) : 0,
      long_lots: ind.long_lots[i],
      short_lots: ind.short_lots[i],
    };
  }

  function emptyAnalytics(msg) {
    return {
      available: false,
      kind: "idle",
      title: "机会评估",
      note: msg,
      sample_n: 0,
      low_sample: true,
      p_fill: null,
      p_tp_first: null,
      p_sl_first: null,
      expected_points: null,
      bias: "none",
      bias_text: "暂无评估",
      tp_distance: null,
      sl_distance: null,
      reward_risk: null,
    };
  }

  function firstTpOrSlLong(high, low, color, start, tp, sl, horizon, n) {
    for (let j = start; j < Math.min(start + horizon, n); j++) {
      if (high[j] >= tp) return "tp";
      if (low[j] <= sl) return "sl";
      if (j > start && color[j] < 0 && color[j - 1] >= 0) return "sl";
    }
    return "none";
  }

  function firstTpOrSlShort(high, low, color, start, tp, sl, horizon, n) {
    for (let j = start; j < Math.min(start + horizon, n); j++) {
      if (low[j] <= tp) return "tp";
      if (high[j] >= sl) return "sl";
      if (j > start && color[j] > 0 && color[j - 1] <= 0) return "sl";
    }
    return "none";
  }

  function analyzeSignal(ind, horizon) {
    horizon = horizon || 48;
    const n = ind.close.length;
    if (n < 50) return emptyAnalytics("样本不足");
    const i = n - 1;
    const close = ind.close[i];
    const entry = numOrNull(ind.cond_entry[i]);
    const tp = numOrNull(ind.cond_tp[i]);
    const sl = numOrNull(ind.cond_sl[i]);
    let kind;
    let side;
    if (ind.wait_long[i]) {
      kind = "wait_long";
      side = "long";
    } else if (ind.wait_short[i]) {
      kind = "wait_short";
      side = "short";
    } else if (ind.long_lots[i] > 0) {
      kind = "hold_long";
      side = "long";
    } else if (ind.short_lots[i] > 0) {
      kind = "hold_short";
      side = "short";
    } else {
      return {
        available: false,
        kind: "idle",
        title: "暂无条件单",
        note: "观望中，无需评估",
        sample_n: 0,
        low_sample: true,
        p_fill: null,
        p_tp_first: null,
        p_sl_first: null,
        expected_points: null,
        bias: "none",
        bias_text: "暂无评估",
        tp_distance: null,
        sl_distance: null,
        reward_risk: null,
      };
    }

    let tpDist;
    let slDist;
    if (side === "long") {
      tpDist = tp != null ? tp - (entry != null ? entry : close) : null;
      slDist = sl != null ? (entry != null ? entry : close) - sl : null;
      if (kind.indexOf("hold") === 0 && tp != null) tpDist = tp - close;
      if (kind.indexOf("hold") === 0 && sl != null) slDist = close - sl;
    } else {
      tpDist = tp != null ? (entry != null ? entry : close) - tp : null;
      slDist = sl != null ? sl - (entry != null ? entry : close) : null;
      if (kind.indexOf("hold") === 0 && tp != null) tpDist = close - tp;
      if (kind.indexOf("hold") === 0 && sl != null) slDist = sl - close;
    }
    const rr =
      tpDist != null && slDist != null && slDist > 0 ? tpDist / slDist : null;

    const starts = [];
    if (kind.indexOf("wait") === 0) {
      const flag = kind === "wait_long" ? ind.wait_long : ind.wait_short;
      for (let k = 0; k < n; k++) {
        if (flag[k] && (k === 0 || !flag[k - 1]) && k < n - 3) starts.push(k);
      }
    } else {
      const flag = kind === "hold_long" ? ind.open_long : ind.open_short;
      for (let k = 0; k < n; k++) if (flag[k] && k < n - 3) starts.push(k);
    }

    let fills = 0;
    let tpFirst = 0;
    let slFirst = 0;
    let evaluated = 0;
    const high = ind.high;
    const low = ind.low;
    const atrArr = ind.atr;
    const upper = ind.upper;
    const lower = ind.lower;
    const color = ind.color;

    for (let s = 0; s < starts.length; s++) {
      const idx = starts[s];
      evaluated += 1;
      const a0 = atrArr[idx] > 0 ? atrArr[idx] : nan();
      const c0 = ind.close[idx];
      let hit = "none";
      if (kind === "wait_long") {
        let e = numOrNull(ind.cond_entry[idx]);
        if (e == null) e = c0;
        const t = isNum(a0) ? e + 1.5 * a0 : e * 1.001;
        const slp = isNum(lower[idx]) ? lower[idx] : e * 0.99;
        let filledAt = null;
        for (let j = idx; j < Math.min(idx + horizon, n); j++) {
          if (high[j] >= e) {
            filledAt = j;
            break;
          }
        }
        if (filledAt == null) continue;
        fills += 1;
        hit = firstTpOrSlLong(high, low, color, filledAt, t, slp, horizon, n);
      } else if (kind === "wait_short") {
        let e = numOrNull(ind.cond_entry[idx]);
        if (e == null) e = c0;
        const t = isNum(a0) ? e - 1.5 * a0 : e * 0.999;
        const slp = isNum(upper[idx]) ? upper[idx] : e * 1.01;
        let filledAt = null;
        for (let j = idx; j < Math.min(idx + horizon, n); j++) {
          if (low[j] <= e) {
            filledAt = j;
            break;
          }
        }
        if (filledAt == null) continue;
        fills += 1;
        hit = firstTpOrSlShort(high, low, color, filledAt, t, slp, horizon, n);
      } else if (kind === "hold_long") {
        fills += 1;
        let t = isNum(a0) ? (isNum(ind.entry[idx]) ? ind.entry[idx] : c0) + 1.5 * a0 : c0 * 1.001;
        let slp = isNum(lower[idx]) ? lower[idx] : c0 * 0.99;
        if (isNum(ind.cond_tp[idx])) t = ind.cond_tp[idx];
        if (isNum(ind.cond_sl[idx])) slp = ind.cond_sl[idx];
        hit = firstTpOrSlLong(high, low, color, idx, t, slp, horizon, n);
      } else {
        fills += 1;
        let t = isNum(a0) ? (isNum(ind.entry[idx]) ? ind.entry[idx] : c0) - 1.5 * a0 : c0 * 0.999;
        let slp = isNum(upper[idx]) ? upper[idx] : c0 * 1.01;
        if (isNum(ind.cond_tp[idx])) t = ind.cond_tp[idx];
        if (isNum(ind.cond_sl[idx])) slp = ind.cond_sl[idx];
        hit = firstTpOrSlShort(high, low, color, idx, t, slp, horizon, n);
      }
      if (hit === "tp") tpFirst += 1;
      else if (hit === "sl") slFirst += 1;
    }

    const sampleN = evaluated;
    const lowSample = sampleN < 8;
    let pFill;
    let pTp;
    let pSl;
    if (kind.indexOf("wait") === 0) {
      pFill = sampleN ? fills / sampleN : null;
      pTp = fills ? tpFirst / fills : null;
      pSl = fills ? slFirst / fills : null;
    } else {
      pFill = 1.0;
      pTp = fills ? tpFirst / fills : null;
      pSl = fills ? slFirst / fills : null;
    }

    let expected = null;
    if (pFill != null && pTp != null && pSl != null && tpDist != null && slDist != null) {
      expected = pFill * (pTp * Math.max(tpDist, 0) - pSl * Math.max(slDist, 0));
    }

    let bias = "none";
    let biasText = "样本不足，暂无倾向";
    if (expected != null) {
      if (expected > 0.5) {
        bias = "profit";
        biasText = "预计偏盈利";
      } else if (expected < -0.5) {
        bias = "loss";
        biasText = "预计偏亏损";
      } else {
        bias = "neutral";
        biasText = "预计接近打平";
      }
    }

    let note = "基于本合约历史样本内回放，非未来保证";
    if (lowSample) note = "样本仅 " + sampleN + " 次，仅供参考 · " + note;

    return {
      available: true,
      kind,
      title: "机会评估",
      note,
      sample_n: sampleN,
      low_sample: lowSample,
      p_fill: pFill == null ? null : round1(pFill * 100),
      p_tp_first: pTp == null ? null : round1(pTp * 100),
      p_sl_first: pSl == null ? null : round1(pSl * 100),
      expected_points: expected == null ? null : round1(expected),
      bias,
      bias_text: biasText,
      tp_distance: tpDist == null ? null : round1(tpDist),
      sl_distance: slDist == null ? null : round1(slDist),
      reward_risk: rr == null ? null : round2(rr),
      horizon_bars: horizon,
    };
  }

  function buildPayload(bars, viewBars) {
    viewBars = viewBars || 300;
    const ind = computeMainChart(bars);
    const status = lastBarStatus(ind);
    const analytics = analyzeSignal(ind);
    const start = Math.max(0, ind.close.length - viewBars);
    function sliceArr(a) {
      return a.slice(start);
    }
    function numList(a, nd) {
      nd = nd == null ? 2 : nd;
      return sliceArr(a).map((v) => (isNum(v) ? +v.toFixed(nd) : null));
    }
    function boolList(a) {
      return sliceArr(a).map((v) => !!v);
    }
    const times = sliceArr(ind.time).map((t) => {
      if (typeof t === "number") {
        const d = new Date(t * (t > 1e12 ? 1 : 1000));
        const pad = (x) => String(x).padStart(2, "0");
        return (
          d.getFullYear() +
          "-" +
          pad(d.getMonth() + 1) +
          "-" +
          pad(d.getDate()) +
          " " +
          pad(d.getHours()) +
          ":" +
          pad(d.getMinutes()) +
          ":" +
          pad(d.getSeconds())
        );
      }
      return String(t);
    });
    return {
      source: "device",
      symbol: (global.CHANNEL_CFG && global.CHANNEL_CFG.symbol) || "DCE.a2609",
      period: "5m",
      status,
      analytics,
      bars: {
        time: times,
        open: numList(ind.open),
        high: numList(ind.high),
        low: numList(ind.low),
        close: numList(ind.close),
        upper: numList(ind.upper),
        lower: numList(ind.lower),
        color: sliceArr(ind.color).map((v) => (isNum(v) ? Math.trunc(v) : null)),
        cci: numList(ind.cci),
        cci_ma: numList(ind.cci_ma),
        cond_entry: numList(ind.cond_entry),
        cond_tp: numList(ind.cond_tp),
        cond_sl: numList(ind.cond_sl),
        open_long: boolList(ind.open_long),
        open_short: boolList(ind.open_short),
        flat_long: boolList(ind.flat_long),
        flat_short: boolList(ind.flat_short),
        tp1_long: boolList(ind.tp1_long),
        tp2_long: boolList(ind.tp2_long),
        tp3_long: boolList(ind.tp3_long),
        tp1_short: boolList(ind.tp1_short),
        tp2_short: boolList(ind.tp2_short),
        tp3_short: boolList(ind.tp3_short),
      },
    };
  }

  global.ChannelStrategy = {
    computeMainChart,
    lastBarStatus,
    analyzeSignal,
    buildPayload,
  };
})(typeof window !== "undefined" ? window : globalThis);
