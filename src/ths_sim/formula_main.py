"""同花顺主图指标逻辑的本地复现（与 docs/同花顺_主图_高低通道.txt 对齐）。"""

from __future__ import annotations

import numpy as np
import pandas as pd


def _ma(s: pd.Series, n: int) -> pd.Series:
    return s.rolling(n, min_periods=n).mean()


def _ref(s: pd.Series, k: int = 1) -> pd.Series:
    return s.shift(k)


def _cross_up(a: pd.Series, b: pd.Series) -> pd.Series:
    return (a > b) & (_ref(a) <= _ref(b))


def _cross_down(a: pd.Series, b: pd.Series) -> pd.Series:
    return (a < b) & (_ref(a) >= _ref(b))


def _bars_last(cond: pd.Series) -> pd.Series:
    """距上次 True 的根数；当根为 True 则为 0。从未出现则用从起始累计根数。"""
    n = len(cond)
    out = np.empty(n, dtype=float)
    last = -1
    for i, v in enumerate(cond.fillna(False).to_numpy()):
        if v:
            last = i
            out[i] = 0.0
        elif last < 0:
            out[i] = float(i)  # 从未出现：等同从起点起的距离
        else:
            out[i] = float(i - last)
    return pd.Series(out, index=cond.index)


def _value_when(cond: pd.Series, x: pd.Series) -> pd.Series:
    """VALUEWHEN：条件为真时取 x，否则沿用上次真值。"""
    return x.where(cond.fillna(False)).ffill()


def _count_in_window(cond: pd.Series, win: pd.Series) -> pd.Series:
    """COUNT(cond, win)：过去 win 根（含当根）中 cond 为真的次数。win 可为序列。"""
    c = cond.fillna(False).astype(int).to_numpy()
    w = np.maximum(win.fillna(1).astype(int).to_numpy(), 1)
    n = len(c)
    out = np.zeros(n, dtype=float)
    # 前缀和加速
    pref = np.concatenate([[0], np.cumsum(c)])
    for i in range(n):
        left = max(0, i - int(w[i]) + 1)
        out[i] = pref[i + 1] - pref[left]
    return pd.Series(out, index=cond.index)


def _exist_in_window(cond: pd.Series, win: pd.Series) -> pd.Series:
    return _count_in_window(cond, win) > 0


def _avedev(s: pd.Series, n: int) -> pd.Series:
    """AVEDEV：滚动平均绝对偏差。"""
    return s.rolling(n, min_periods=n).apply(
        lambda x: np.mean(np.abs(x - x.mean())), raw=True
    )


def compute_main_chart(
    df: pd.DataFrame,
    *,
    channel_n: int = 60,
    cci_p: int = 15,
    cci_m: int = 4,
    atr_n: int = 20,
) -> pd.DataFrame:
    """输入 OHLCV DataFrame（index=datetime），输出指标全列。"""
    o = df["open"].astype(float)
    h = df["high"].astype(float)
    l = df["low"].astype(float)
    c = df["close"].astype(float)

    upper = _ma(h, channel_n)
    lower = _ma(l, channel_n)

    side = pd.Series(
        np.where(c > upper, 1, np.where(c < lower, -1, 0)),
        index=df.index,
        dtype=float,
    )
    color = _value_when(side != 0, side)

    turn_red = (color == 1) & (_ref(color) != 1)
    turn_green = (color == -1) & (_ref(color) != -1)
    bars_red = _bars_last(turn_red.fillna(False))
    bars_green = _bars_last(turn_green.fillna(False))

    typ = (h + l + c) / 3.0
    cci = (typ - _ma(typ, cci_p)) / (0.015 * _avedev(typ, cci_p))
    cci_ma = _ma(cci, cci_m)
    golden = _cross_up(cci, cci_ma)
    death = _cross_down(cci, cci_ma)

    cand_long = golden & (color == 1) & (bars_red < bars_green)
    cand_short = death & (color == -1) & (bars_green < bars_red)
    open_long = cand_long & (_count_in_window(cand_long, bars_red + 1) == 1)
    open_short = cand_short & (_count_in_window(cand_short, bars_green + 1) == 1)

    bars_ol = _bars_last(open_long.fillna(False))
    bars_os = _bars_last(open_short.fillna(False))

    prev_c = _ref(c)
    tr = pd.concat([(h - l), (h - prev_c).abs(), (l - prev_c).abs()], axis=1).max(axis=1)
    atr = _ma(tr, atr_n)

    entry = _value_when(open_long | open_short, c)
    t1l = entry + 1.5 * atr
    t2l = entry + 3.0 * atr
    t3l = entry + 5.0 * atr
    t1s = entry - 1.5 * atr
    t2s = entry - 3.0 * atr
    t3s = entry - 5.0 * atr

    hit1l = _exist_in_window(c >= t1l, bars_ol + 1)
    hit2l = _exist_in_window(c >= t2l, bars_ol + 1)
    hit3l = _exist_in_window(c >= t3l, bars_ol + 1)
    hit1s = _exist_in_window(c <= t1s, bars_os + 1)
    hit2s = _exist_in_window(c <= t2s, bars_os + 1)
    hit3s = _exist_in_window(c <= t3s, bars_os + 1)

    flat_long = turn_green & (bars_ol < bars_os)
    flat_short = turn_red & (bars_os < bars_ol)
    bars_fl = _bars_last(flat_long.fillna(False))
    bars_fs = _bars_last(flat_short.fillna(False))

    long_lots = np.where(
        (bars_ol < bars_os) & (bars_ol < bars_fl),
        3 - hit1l.astype(float) - hit2l.astype(float) - hit3l.astype(float),
        0.0,
    )
    short_lots = np.where(
        (bars_os < bars_ol) & (bars_os < bars_fs),
        3 - hit1s.astype(float) - hit2s.astype(float) - hit3s.astype(float),
        0.0,
    )
    long_lots = pd.Series(long_lots, index=df.index)
    short_lots = pd.Series(short_lots, index=df.index)

    wait_long = (color == 1) & (bars_red <= bars_ol) & (long_lots == 0) & (short_lots == 0)
    wait_short = (color == -1) & (bars_green <= bars_os) & (long_lots == 0) & (short_lots == 0)
    long_state = wait_long | (long_lots > 0)
    short_state = wait_short | (short_lots > 0)

    # CCI 交叉近似触发价
    sum_prev = _ref(typ).rolling(cci_p - 1, min_periods=cci_p - 1).sum()
    avedev_prev = _ref(_avedev(typ, cci_p))
    sig_prev = _ref(cci_ma)
    trigger = (0.015 * avedev_prev * sig_prev * cci_p + sum_prev) / (cci_p - 1)

    # 等开仓：三档止盈相对触发价；持仓：相对开仓价
    est_tp1 = np.where(wait_long, trigger + 1.5 * atr, trigger - 1.5 * atr)
    est_tp2 = np.where(wait_long, trigger + 3.0 * atr, trigger - 3.0 * atr)
    est_tp3 = np.where(wait_long, trigger + 5.0 * atr, trigger - 5.0 * atr)
    est_tp1 = pd.Series(est_tp1, index=df.index)
    est_tp2 = pd.Series(est_tp2, index=df.index)
    est_tp3 = pd.Series(est_tp3, index=df.index)

    next_tp_long = np.where(~hit1l, t1l, np.where(~hit2l, t2l, t3l))
    next_tp_short = np.where(~hit1s, t1s, np.where(~hit2s, t2s, t3s))
    next_tp_long = pd.Series(next_tp_long, index=df.index)
    next_tp_short = pd.Series(next_tp_short, index=df.index)

    cond_entry = np.where(wait_long | wait_short, trigger, np.nan)
    cond_tp1 = np.where(
        wait_long | wait_short,
        est_tp1,
        np.where(long_lots > 0, t1l, np.where(short_lots > 0, t1s, np.nan)),
    )
    cond_tp2 = np.where(
        wait_long | wait_short,
        est_tp2,
        np.where(long_lots > 0, t2l, np.where(short_lots > 0, t2s, np.nan)),
    )
    cond_tp3 = np.where(
        wait_long | wait_short,
        est_tp3,
        np.where(long_lots > 0, t3l, np.where(short_lots > 0, t3s, np.nan)),
    )
    # 条件单卡片仍展示「下一档」止盈
    cond_tp = np.where(
        wait_long | wait_short,
        est_tp1,
        np.where(long_lots > 0, next_tp_long, np.where(short_lots > 0, next_tp_short, np.nan)),
    )
    cond_sl = np.where(long_state, lower, np.where(short_state, upper, np.nan))

    tp1_long_evt = hit1l & ~_ref(hit1l).fillna(False) & (bars_ol < bars_os) & (bars_ol < bars_fl)
    tp2_long_evt = hit2l & ~_ref(hit2l).fillna(False) & (bars_ol < bars_os) & (bars_ol < bars_fl)
    tp3_long_evt = hit3l & ~_ref(hit3l).fillna(False) & (bars_ol < bars_os) & (bars_ol < bars_fl)
    tp1_short_evt = hit1s & ~_ref(hit1s).fillna(False) & (bars_os < bars_ol) & (bars_os < bars_fs)
    tp2_short_evt = hit2s & ~_ref(hit2s).fillna(False) & (bars_os < bars_ol) & (bars_os < bars_fs)
    tp3_short_evt = hit3s & ~_ref(hit3s).fillna(False) & (bars_os < bars_ol) & (bars_os < bars_fs)

    out = pd.DataFrame(
        {
            "open": o,
            "high": h,
            "low": l,
            "close": c,
            "upper": upper,
            "lower": lower,
            "color": color,
            "cci": cci,
            "cci_ma": cci_ma,
            "atr": atr,
            "open_long": open_long.fillna(False),
            "open_short": open_short.fillna(False),
            "flat_long": flat_long.fillna(False),
            "flat_short": flat_short.fillna(False),
            "tp1_long": tp1_long_evt.fillna(False),
            "tp2_long": tp2_long_evt.fillna(False),
            "tp3_long": tp3_long_evt.fillna(False),
            "tp1_short": tp1_short_evt.fillna(False),
            "tp2_short": tp2_short_evt.fillna(False),
            "tp3_short": tp3_short_evt.fillna(False),
            "long_lots": long_lots,
            "short_lots": short_lots,
            "wait_long": wait_long.fillna(False),
            "wait_short": wait_short.fillna(False),
            "cond_entry": cond_entry,
            "cond_tp": cond_tp,
            "cond_tp1": cond_tp1,
            "cond_tp2": cond_tp2,
            "cond_tp3": cond_tp3,
            "cond_sl": cond_sl,
            "entry": entry,
            "trigger": trigger,
        },
        index=df.index,
    )
    return out


def last_bar_status(ind: pd.DataFrame) -> dict:
    """最新一根的条件单摘要（给盘面/App 直接照抄）。"""
    row = ind.iloc[-1]
    entry = None if pd.isna(row["cond_entry"]) else float(row["cond_entry"])
    tp = None if pd.isna(row["cond_tp"]) else float(row["cond_tp"])
    tp1 = None if pd.isna(row["cond_tp1"]) else float(row["cond_tp1"])
    tp2 = None if pd.isna(row["cond_tp2"]) else float(row["cond_tp2"])
    tp3 = None if pd.isna(row["cond_tp3"]) else float(row["cond_tp3"])
    sl = None if pd.isna(row["cond_sl"]) else float(row["cond_sl"])

    def _add_tps(orders: list[dict], *, side: str, op: str, ge: bool) -> None:
        """三档止盈各 1 手（1.5 / 3 / 5 ATR）。"""
        sym = "≥" if ge else "≤"
        for i, price in enumerate((tp1, tp2, tp3), start=1):
            if price is None:
                continue
            orders.append(
                {
                    "role": f"止盈{i}",
                    "side": side,
                    "lots": 1,
                    "op": op,
                    "price": price,
                    "text": f"最新价 {sym} {price:.0f} → 平 1 手（止盈{i}）",
                }
            )

    orders: list[dict] = []
    close = float(row["close"])
    if bool(row["wait_long"]):
        state = "等开多"
        need_order = True
        crossed = entry is not None and close >= entry
        if crossed:
            summary = f"触发价已越过（现价{close:.0f} ≥ {entry:.0f}），可按现价开多 3 手"
        else:
            summary = "需要设条件单：等涨到触发价再开多 3 手"
        if entry is not None:
            orders.append(
                {
                    "role": "开多仓",
                    "side": "买开",
                    "direction": "开多仓",
                    "lots": 3,
                    "op": "大于等于",
                    "price": entry,
                    "label": "触发价",
                    "crossed": crossed,
                    "text": (
                        f"触发价 {entry:.0f}：现价已 ≥ 触发价，条件已满足，按现价/市价开多即可"
                        if crossed
                        else f"触发价 {entry:.0f}：最新价涨到 ≥ {entry:.0f} 再买开 3 手（不是挂更低价）"
                    ),
                }
            )
        _add_tps(orders, side="卖平", op="大于等于", ge=True)
        if sl is not None:
            orders.append(
                {
                    "role": "止损",
                    "side": "卖平",
                    "lots": 3,
                    "op": "小于等于",
                    "price": sl,
                    "text": f"最新价 ≤ {sl:.0f} → 剩余全平（止损/反向）",
                }
            )
        how = (
            "触发价=CCI金叉近似价；现价已越过则别等更低价。开仓/止盈「大于等于」，止损「小于等于」"
            if crossed
            else "触发价=CCI金叉近似价；挂「最新价≥触发价」开多。止盈「大于等于」，止损「小于等于」"
        )
    elif bool(row["wait_short"]):
        state = "等开空"
        need_order = True
        crossed = entry is not None and close <= entry
        if crossed:
            summary = f"触发价已越过（现价{close:.0f} ≤ {entry:.0f}），可按现价开空 3 手"
        else:
            summary = "需要设条件单：等跌到触发价再开空 3 手"
        if entry is not None:
            orders.append(
                {
                    "role": "开空仓",
                    "side": "卖开",
                    "direction": "开空仓",
                    "lots": 3,
                    "op": "小于等于",
                    "price": entry,
                    "label": "触发价",
                    "crossed": crossed,
                    "text": (
                        f"触发价 {entry:.0f}：现价已 ≤ 触发价，条件已满足，按现价/市价开空即可"
                        if crossed
                        else f"触发价 {entry:.0f}：最新价跌到 ≤ {entry:.0f} 再卖开 3 手（不是挂更高价）"
                    ),
                }
            )
        _add_tps(orders, side="买平", op="小于等于", ge=False)
        if sl is not None:
            orders.append(
                {
                    "role": "止损",
                    "side": "买平",
                    "lots": 3,
                    "op": "大于等于",
                    "price": sl,
                    "text": f"最新价 ≥ {sl:.0f} → 剩余全平（止损/反向）",
                }
            )
        how = (
            "触发价=CCI死叉近似价；现价已越过则别等更高价。开仓/止盈「小于等于」，止损「大于等于」"
            if crossed
            else "触发价=CCI死叉近似价；挂「最新价≤触发价」开空。止盈「小于等于」，止损「大于等于」"
        )
    elif row["long_lots"] > 0:
        state = f"持多{int(row['long_lots'])}手"
        need_order = True
        summary = "需要改条件单：持多止盈/止损"
        _add_tps(orders, side="卖平", op="大于等于", ge=True)
        if sl is not None:
            orders.append(
                {
                    "role": "止损",
                    "side": "卖平",
                    "lots": int(row["long_lots"]),
                    "op": "小于等于",
                    "price": sl,
                    "text": f"最新价 ≤ {sl:.0f} → 剩余全平（止损/反向）",
                }
            )
        how = "止盈「大于等于」三档各 1 手，止损「小于等于」"
    elif row["short_lots"] > 0:
        state = f"持空{int(row['short_lots'])}手"
        need_order = True
        summary = "需要改条件单：持空止盈/止损"
        _add_tps(orders, side="买平", op="小于等于", ge=False)
        if sl is not None:
            orders.append(
                {
                    "role": "止损",
                    "side": "买平",
                    "lots": int(row["short_lots"]),
                    "op": "大于等于",
                    "price": sl,
                    "text": f"最新价 ≥ {sl:.0f} → 剩余全平（止损/反向）",
                }
            )
        how = "止盈「小于等于」三档各 1 手，止损「大于等于」"
    else:
        state = "观望"
        need_order = False
        summary = "无需设条件单，等通道变色 / CCI 确认"
        how = "暂无条件单"

    # 距现价辅助
    close = float(row["close"])
    atr = float(row["atr"]) if "atr" in row.index and pd.notna(row["atr"]) and row["atr"] > 0 else None
    for o in orders:
        p = o.get("price")
        if p is None:
            o["distance_points"] = None
            o["distance_atr"] = None
        else:
            dist = abs(float(p) - close)
            o["distance_points"] = round(dist, 1)
            o["distance_atr"] = round(dist / atr, 2) if atr else None

    return {
        "datetime": str(ind.index[-1]),
        "close": close,
        "atr": None if atr is None else round(atr, 2),
        "state": state,
        "need_order": need_order,
        "summary": summary,
        "how": how,
        "orders": orders,
        "cond_entry": entry,
        "cond_tp": tp,
        "cond_tp1": tp1,
        "cond_tp2": tp2,
        "cond_tp3": tp3,
        "cond_sl": sl,
        "upper": None if pd.isna(row["upper"]) else float(row["upper"]),
        "lower": None if pd.isna(row["lower"]) else float(row["lower"]),
        "color": int(row["color"]) if pd.notna(row["color"]) else 0,
        "long_lots": float(row["long_lots"]),
        "short_lots": float(row["short_lots"]),
    }
