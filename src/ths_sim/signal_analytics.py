"""基于本合约历史 K 线向前回放，评估当前条件单的触发/盈亏概率。"""

from __future__ import annotations

from typing import Any

import numpy as np
import pandas as pd


def _safe_div(a: float, b: float) -> float | None:
    if b is None or b == 0 or np.isnan(b):
        return None
    return float(a) / float(b)


def analyze_signal(ind: pd.DataFrame, horizon: int = 48) -> dict[str, Any]:
    """对与最新一根同类状态的历史样本做向前统计。

    horizon: 开仓后最多观察多少根 K（约 4 小时 @5m）。
    """
    if ind is None or len(ind) < 50:
        return _empty("样本不足")

    row = ind.iloc[-1]
    close = float(row["close"])
    atr = float(row["atr"]) if pd.notna(row["atr"]) and row["atr"] > 0 else None
    entry = None if pd.isna(row["cond_entry"]) else float(row["cond_entry"])
    tp = None if pd.isna(row["cond_tp"]) else float(row["cond_tp"])
    sl = None if pd.isna(row["cond_sl"]) else float(row["cond_sl"])

    wait_long = bool(row["wait_long"])
    wait_short = bool(row["wait_short"])
    long_lots = float(row["long_lots"])
    short_lots = float(row["short_lots"])

    if wait_long:
        kind = "wait_long"
        side = "long"
    elif wait_short:
        kind = "wait_short"
        side = "short"
    elif long_lots > 0:
        kind = "hold_long"
        side = "long"
    elif short_lots > 0:
        kind = "hold_short"
        side = "short"
    else:
        return {
            "available": False,
            "kind": "idle",
            "title": "暂无条件单",
            "note": "观望中，无需评估",
            "sample_n": 0,
            "low_sample": True,
            "p_fill": None,
            "p_tp_first": None,
            "p_sl_first": None,
            "expected_points": None,
            "bias": "none",
            "bias_text": "暂无评估",
            "tp_distance": None,
            "sl_distance": None,
            "reward_risk": None,
        }

    # 当前价距
    if side == "long":
        tp_dist = (tp - (entry if entry is not None else close)) if tp is not None else None
        sl_dist = ((entry if entry is not None else close) - sl) if sl is not None else None
        if kind.startswith("hold") and tp is not None:
            tp_dist = tp - close
        if kind.startswith("hold") and sl is not None:
            sl_dist = close - sl
    else:
        tp_dist = ((entry if entry is not None else close) - tp) if tp is not None else None
        sl_dist = (sl - (entry if entry is not None else close)) if sl is not None else None
        if kind.startswith("hold") and tp is not None:
            tp_dist = close - tp
        if kind.startswith("hold") and sl is not None:
            sl_dist = sl - close

    rr = _safe_div(tp_dist, sl_dist) if tp_dist is not None and sl_dist and sl_dist > 0 else None

    # 找历史同类信号起点：状态从 False -> True 的边沿
    if kind == "wait_long":
        flag = ind["wait_long"].fillna(False)
    elif kind == "wait_short":
        flag = ind["wait_short"].fillna(False)
    elif kind == "hold_long":
        flag = (ind["long_lots"] > 0) & ~(ind["long_lots"].shift(1).fillna(0) > 0)
        # 用开多事件更干净
        flag = ind["open_long"].fillna(False)
    else:
        flag = ind["open_short"].fillna(False)

    if kind.startswith("wait"):
        rising = flag & ~flag.shift(1).fillna(False)
        starts = np.flatnonzero(rising.to_numpy())
    else:
        starts = np.flatnonzero(flag.to_numpy())

    # 排除过近的末尾（没有足够未来）
    n = len(ind)
    starts = [i for i in starts if i < n - 3]

    fills = 0
    tp_first = 0
    sl_first = 0
    neither = 0
    evaluated = 0

    high = ind["high"].to_numpy(dtype=float)
    low = ind["low"].to_numpy(dtype=float)
    atr_arr = ind["atr"].to_numpy(dtype=float)
    upper = ind["upper"].to_numpy(dtype=float)
    lower = ind["lower"].to_numpy(dtype=float)
    color = ind["color"].to_numpy(dtype=float)

    for i in starts:
        evaluated += 1
        a0 = atr_arr[i] if atr_arr[i] > 0 else np.nan
        c0 = float(ind["close"].iloc[i])

        if kind == "wait_long":
            # 当时近似：触发价用当时 cond_entry；若空则用 close
            e = ind["cond_entry"].iloc[i]
            e = float(e) if pd.notna(e) else c0
            t = e + 0.5 * a0 if a0 == a0 else e * 1.001
            s = float(lower[i]) if lower[i] == lower[i] else e * 0.99
            filled_at = None
            for j in range(i, min(i + horizon, n)):
                if high[j] >= e:
                    filled_at = j
                    break
            if filled_at is None:
                continue
            fills += 1
            hit = _first_tp_or_sl_long(high, low, color, filled_at, t, s, horizon, n)
        elif kind == "wait_short":
            e = ind["cond_entry"].iloc[i]
            e = float(e) if pd.notna(e) else c0
            t = e - 0.5 * a0 if a0 == a0 else e * 0.999
            s = float(upper[i]) if upper[i] == upper[i] else e * 1.01
            filled_at = None
            for j in range(i, min(i + horizon, n)):
                if low[j] <= e:
                    filled_at = j
                    break
            if filled_at is None:
                continue
            fills += 1
            hit = _first_tp_or_sl_short(high, low, color, filled_at, t, s, horizon, n)
        elif kind == "hold_long":
            fills += 1  # 已开仓
            e = float(ind["entry"].iloc[i]) if pd.notna(ind["entry"].iloc[i]) else c0
            t = e + 0.5 * a0 if a0 == a0 else e * 1.001
            s = float(lower[i]) if lower[i] == lower[i] else e * 0.99
            # 若已有下一止盈列更好，用当时 cond_tp
            if pd.notna(ind["cond_tp"].iloc[i]):
                t = float(ind["cond_tp"].iloc[i])
            if pd.notna(ind["cond_sl"].iloc[i]):
                s = float(ind["cond_sl"].iloc[i])
            hit = _first_tp_or_sl_long(high, low, color, i, t, s, horizon, n)
        else:
            fills += 1
            e = float(ind["entry"].iloc[i]) if pd.notna(ind["entry"].iloc[i]) else c0
            t = e - 0.5 * a0 if a0 == a0 else e * 0.999
            s = float(upper[i]) if upper[i] == upper[i] else e * 1.01
            if pd.notna(ind["cond_tp"].iloc[i]):
                t = float(ind["cond_tp"].iloc[i])
            if pd.notna(ind["cond_sl"].iloc[i]):
                s = float(ind["cond_sl"].iloc[i])
            hit = _first_tp_or_sl_short(high, low, color, i, t, s, horizon, n)

        if hit == "tp":
            tp_first += 1
        elif hit == "sl":
            sl_first += 1
        else:
            neither += 1

    sample_n = evaluated
    low_sample = sample_n < 8
    if kind.startswith("wait"):
        p_fill = fills / sample_n if sample_n else None
        denom = max(fills, 1)
        p_tp = tp_first / denom if fills else None
        p_sl = sl_first / denom if fills else None
    else:
        p_fill = 1.0
        denom = max(fills, 1)
        p_tp = tp_first / denom if fills else None
        p_sl = sl_first / denom if fills else None

    # 期望点数（相对开仓价）
    expected = None
    if p_fill is not None and p_tp is not None and p_sl is not None and tp_dist is not None and sl_dist is not None:
        # 先盈/先损之外的既不盈也不损部分按 0
        expected = p_fill * (p_tp * max(tp_dist, 0) - p_sl * max(sl_dist, 0))

    if expected is None:
        bias = "none"
        bias_text = "样本不足，暂无倾向"
    elif expected > 0.5:
        bias = "profit"
        bias_text = "预计偏盈利"
    elif expected < -0.5:
        bias = "loss"
        bias_text = "预计偏亏损"
    else:
        bias = "neutral"
        bias_text = "预计接近打平"

    note = "基于本合约历史样本内回放，非未来保证"
    if low_sample:
        note = f"样本仅 {sample_n} 次，仅供参考 · " + note

    return {
        "available": True,
        "kind": kind,
        "title": "机会评估",
        "note": note,
        "sample_n": int(sample_n),
        "low_sample": low_sample,
        "p_fill": None if p_fill is None else round(p_fill * 100, 1),
        "p_tp_first": None if p_tp is None else round(p_tp * 100, 1),
        "p_sl_first": None if p_sl is None else round(p_sl * 100, 1),
        "expected_points": None if expected is None else round(expected, 1),
        "bias": bias,
        "bias_text": bias_text,
        "tp_distance": None if tp_dist is None else round(tp_dist, 1),
        "sl_distance": None if sl_dist is None else round(sl_dist, 1),
        "reward_risk": None if rr is None else round(rr, 2),
        "horizon_bars": horizon,
    }


def _first_tp_or_sl_long(high, low, color, start, tp, sl, horizon, n) -> str:
    for j in range(start, min(start + horizon, n)):
        if high[j] >= tp:
            return "tp"
        if low[j] <= sl:
            return "sl"
        # 反向变色近似：颜色变绿
        if j > start and color[j] < 0 and color[j - 1] >= 0:
            return "sl"
    return "none"


def _first_tp_or_sl_short(high, low, color, start, tp, sl, horizon, n) -> str:
    for j in range(start, min(start + horizon, n)):
        if low[j] <= tp:
            return "tp"
        if high[j] >= sl:
            return "sl"
        if j > start and color[j] > 0 and color[j - 1] <= 0:
            return "sl"
    return "none"


def _empty(msg: str) -> dict[str, Any]:
    return {
        "available": False,
        "kind": "idle",
        "title": "机会评估",
        "note": msg,
        "sample_n": 0,
        "low_sample": True,
        "p_fill": None,
        "p_tp_first": None,
        "p_sl_first": None,
        "expected_points": None,
        "bias": "none",
        "bias_text": "暂无评估",
        "tp_distance": None,
        "sl_distance": None,
        "reward_risk": None,
    }


def enrich_orders_with_distance(status: dict, close: float, atr: float | None) -> dict:
    """给 orders 补距现价点数/ATR。"""
    orders = status.get("orders") or []
    for o in orders:
        p = o.get("price")
        if p is None:
            o["distance_points"] = None
            o["distance_atr"] = None
            continue
        dist = abs(float(p) - float(close))
        o["distance_points"] = round(dist, 1)
        o["distance_atr"] = round(dist / atr, 2) if atr and atr > 0 else None
    status["orders"] = orders
    return status
