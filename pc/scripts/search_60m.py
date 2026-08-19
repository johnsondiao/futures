"""60 分钟策略寻优：网格扫描入场逻辑 × ATR 退出，按入场逻辑分组找稳健区。

方法论与 5m 版一致（见 pc/scripts/goal_search_full.py + robustness_report.py）：
  不挑单点最优，找「入场逻辑固定后，ATR 退出怎么调都大概率盈利」的稳健区域。

用法:
  python pc/scripts/search_60m.py [--top 12]
"""

from __future__ import annotations

import argparse
import itertools
import json
import statistics
import sys
from collections import defaultdict
from pathlib import Path

import backtrader as bt
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from src.data.bt_feed import TIMEFRAME_MAP, make_data  # noqa: E402
from strategies.channel_trend import ChannelCciAtrStrategy  # noqa: E402

SYMBOL = "DCE.a2611"
PERIOD = "60m"
CASH = 1_000_000.0
LOTS = 3

MODES = ["color_then_cci", "cci_then_color", "color_and_cci_cross"]
CHANNELS = [40, 50, 60, 70, 80, 100]
CCI_PERIODS = [10, 15, 20, 25, 30]
CCI_SIGNALS = [3, 4, 5]
ATR_PERIOD = 20
MULTS = [
    (0.8, 1.5, 2.5),
    (1.0, 2.0, 3.0),
    (1.2, 2.5, 4.0),
    (1.5, 2.5, 4.0),
    (1.5, 3.0, 5.0),
    (2.0, 3.0, 5.0),
    (2.0, 4.0, 6.0),
    (2.5, 4.0, 6.0),
    (3.0, 5.0, 7.0),
    (3.0, 6.0, 9.0),
]


def load_cache(path: Path) -> pd.DataFrame:
    return pd.read_csv(path, parse_dates=["datetime"], index_col="datetime")


def run_once(df, params: dict) -> dict:
    tf, comp = TIMEFRAME_MAP[PERIOD]
    data = make_data(df.copy(), name="scan60", timeframe=tf, compression=comp)
    cerebro = bt.Cerebro(stdstats=False)
    cerebro.adddata(data)
    cerebro.addstrategy(ChannelCciAtrStrategy, printlog=False, **params)
    cerebro.broker.setcash(CASH)
    cerebro.broker.setcommission(commission=0.00015, mult=10.0)
    cerebro.addanalyzer(bt.analyzers.DrawDown, _name="dd")
    cerebro.addanalyzer(bt.analyzers.TradeAnalyzer, _name="trades")
    start = cerebro.broker.getvalue()
    strat = cerebro.run()[0]
    end = cerebro.broker.getvalue()
    dd = strat.analyzers.dd.get_analysis()
    trades = strat.analyzers.trades.get_analysis()
    closed = trades.get("total", {}).get("closed", 0) or 0
    won = trades.get("won", {}).get("total", 0) or 0
    return {
        "pnl": end - start,
        "max_dd": float(dd.get("max", {}).get("drawdown", 0) or 0),
        "closed": closed,
        "wr": (won / closed) if closed else 0.0,
        "params": params,
    }


def entry_key(params: dict) -> tuple:
    return (
        params["entry_mode"],
        params["channel_period"],
        params["cci_period"],
        params["cci_signal"],
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--top", type=int, default=12)
    parser.add_argument("--min-trades", type=int, default=18, help="样本量下限")
    args = parser.parse_args()

    cache = ROOT / "data" / "cache" / f"{SYMBOL.replace('.', '_')}_{PERIOD}.csv"
    if not cache.exists():
        raise SystemExit(f"缺少缓存 {cache}，先跑 pc/scripts/run_backtest.py --period 60m")
    df = load_cache(cache)
    print(f"数据 {df.index[0]} → {df.index[-1]}  共 {len(df)} 根 60m K 线")

    combos = list(itertools.product(MODES, CHANNELS, CCI_PERIODS, CCI_SIGNALS, MULTS))
    print(f"网格: {len(MODES)}模式 x {len(CHANNELS)}通道 x {len(CCI_PERIODS)}CCI周期 "
          f"x {len(CCI_SIGNALS)}信号线 x {len(MULTS)}止盈组 = {len(combos)} 组")

    rows = []
    log = ROOT / "data" / "goal" / "search_log_60m.jsonl"
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open("w", encoding="utf-8") as f:
        for i, (mode, ch, cp, cs, mults) in enumerate(combos, 1):
            params = {
                "channel_period": ch,
                "cci_period": cp,
                "cci_signal": cs,
                "atr_period": ATR_PERIOD,
                "atr_mults": mults,
                "entry_lots": LOTS,
                "entry_mode": mode,
                "atr_trailing": True,
            }
            r = run_once(df, params)
            rows.append(r)
            f.write(json.dumps({"params": params, **{k: r[k] for k in ("pnl", "max_dd", "closed", "wr")}},
                               ensure_ascii=False) + "\n")
            if i % 300 == 0:
                print(f"  进度 {i}/{len(combos)}")

    # ===== 按入场逻辑分组（稳健区分析）=====
    groups: dict[tuple, list[dict]] = defaultdict(list)
    for r in rows:
        groups[entry_key(r["params"])].append(r)

    stats = []
    for key, rs in groups.items():
        # 只看样本量足够的 ATR 变体
        valid = [r for r in rs if r["closed"] >= args.min_trades]
        if len(valid) < 5:
            continue
        pnls = [r["pnl"] for r in valid]
        stats.append({
            "key": key,
            "n": len(valid),
            "mean": statistics.mean(pnls),
            "pos_frac": sum(1 for x in pnls if x > 0) / len(valid),
            "worst": min(pnls),
            "best": max(pnls),
            "stdev": statistics.pstdev(pnls),
            "rows": valid,
        })

    stats.sort(key=lambda s: (-s["pos_frac"], -s["mean"]))
    print(f"\n样本量≥{args.min_trades} 的入场逻辑组 {len(stats)} 个（每组含多个 ATR 退出变体）：\n")
    print(f"{'mode':<20}{'ch':>5}{'cci':>8}{'n':>5}{'mean':>10}{'pos%':>7}{'worst':>10}{'best':>10}{'stdev':>8}")
    for s in stats[: args.top]:
        key = s["key"]
        print(f"{key[0]:<20}{key[1]:>5}{key[2]}/{key[3]:<4}{s['n']:>5}{s['mean']:>10.0f}"
              f"{s['pos_frac']*100:>6.0f}%{s['worst']:>10.0f}{s['best']:>10.0f}{s['stdev']:>8.0f}")

    robust = [s for s in stats if s["pos_frac"] == 1.0]
    print(f"\nATR 退出怎么调都盈利(pos%=100%)的入场逻辑: {len(robust)}/{len(stats)} 组")

    # ===== 候选明细：稳健组里胜率接近 50% 且均值高的 ATR 变体 =====
    if robust:
        print("\n稳健组内 Top 明细（按均值排序，标注胜率）：")
        detail = []
        for s in robust:
            for r in s["rows"]:
                detail.append((s["key"], r))
        detail.sort(key=lambda x: -x[1]["pnl"])
        for key, r in detail[:10]:
            p = r["params"]
            print(f"  {key[0]} ch={key[1]} cci={key[2]}/{key[3]} "
                  f"ATR{p['atr_period']}x{list(p['atr_mults'])}  "
                  f"pnl={r['pnl']:.0f} wr={r['wr']*100:.0f}% dd={r['max_dd']*100:.2f}% n={r['closed']}")

    # ===== 对照：5m 现行参数在 60m 上的表现 =====
    base5m = {
        "channel_period": 60, "cci_period": 15, "cci_signal": 4,
        "atr_period": ATR_PERIOD, "atr_mults": (1.5, 3.0, 5.0),
        "entry_lots": LOTS, "entry_mode": "color_then_cci", "atr_trailing": True,
    }
    r0 = run_once(df, base5m)
    print(f"\n对照(5m现行参数直接跑60m): pnl={r0['pnl']:.0f} wr={r0['wr']*100:.1f}% "
          f"dd={r0['max_dd']*100:.2f}% n={r0['closed']}")


if __name__ == "__main__":
    main()
