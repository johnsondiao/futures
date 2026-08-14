"""固定入场逻辑，扫描 ATR 止盈倍数 vs 胜率/净利。

目标：找胜率约 50%、且净利较好的 ATR 倍数组合。

用法:
  python pc/scripts/scan_atr_winrate.py
"""

from __future__ import annotations

import sys
from pathlib import Path

import backtrader as bt

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from src.data.bt_feed import TIMEFRAME_MAP, make_data
from strategies.channel_trend import ChannelCciAtrStrategy
import pandas as pd


def load_cache(path: Path) -> pd.DataFrame:
    return pd.read_csv(path, parse_dates=["datetime"], index_col="datetime")


SYMBOL = "DCE.a2609"
PERIOD = "5m"
CASH = 1_000_000.0

# 固定入场（稳健组）
BASE = {
    "channel_period": 60,
    "cci_period": 15,
    "cci_signal": 4,
    "entry_lots": 3,
    "entry_mode": "color_then_cci",
    "atr_trailing": True,
}

# 候选止盈倍数（含当前近止盈 + 旧方案 + 中间档）
MULTS = [
    (0.5, 1.0, 1.5),
    (0.8, 1.5, 2.5),
    (1.0, 2.0, 3.0),
    (1.2, 2.5, 4.0),
    (1.5, 2.5, 4.0),
    (1.5, 3.0, 5.0),
    (2.0, 3.0, 5.0),
    (2.0, 4.0, 6.0),
    (2.5, 4.0, 6.0),
    (3.0, 5.0, 7.0),
]

ATR_PERIODS = [14, 20]


def run_once(df, params: dict) -> dict:
    tf, comp = TIMEFRAME_MAP[PERIOD]
    data = make_data(df.copy(), name="scan", timeframe=tf, compression=comp)
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
    lost = trades.get("lost", {}).get("total", 0) or 0
    wr = won / closed if closed else 0.0
    return {
        "pnl": end - start,
        "max_dd": float(dd.get("max", {}).get("drawdown", 0) or 0),
        "closed": closed,
        "won": won,
        "lost": lost,
        "wr": wr,
        "params": params,
    }


def main():
    cache = ROOT / "data" / "cache" / "DCE_a2609_5m.csv"
    if not cache.exists():
        raise SystemExit(f"缺少缓存 {cache}")
    df = load_cache(cache)
    print(f"数据 {df.index[0]} → {df.index[-1]}  共 {len(df)} 根")
    print(f"固定入场: {BASE}")
    print()

    rows = []
    for atr_p in ATR_PERIODS:
        for mults in MULTS:
            params = {
                **BASE,
                "atr_period": atr_p,
                "atr_mults": mults,
            }
            r = run_once(df, params)
            rows.append(r)
            print(
                f"ATR({atr_p}) x {list(mults)}  "
                f"wr={r['wr']*100:5.1f}%  pnl={r['pnl']:8.0f}  "
                f"dd={r['max_dd']*100:5.1f}%  closed={r['closed']} "
                f"({r['won']}/{r['lost']})"
            )

    print("\n======== 胜率 48%~52% ========")
    near = [r for r in rows if 0.48 <= r["wr"] <= 0.52]
    near.sort(key=lambda x: -x["pnl"])
    if not near:
        print("无；放宽到 45%~55%：")
        near = [r for r in rows if 0.45 <= r["wr"] <= 0.55]
        near.sort(key=lambda x: -x["pnl"])
    for r in near:
        p = r["params"]
        print(
            f"ATR({p['atr_period']}) x {list(p['atr_mults'])}  "
            f"wr={r['wr']*100:.1f}% pnl={r['pnl']:.0f} dd={r['max_dd']*100:.1f}%"
        )

    print("\n======== 按 |胜率-50%| 排序，取仍盈利的前几 ========")
    pos = [r for r in rows if r["pnl"] > 0]
    pos.sort(key=lambda x: (abs(x["wr"] - 0.5), -x["pnl"]))
    for r in pos[:8]:
        p = r["params"]
        print(
            f"ATR({p['atr_period']}) x {list(p['atr_mults'])}  "
            f"wr={r['wr']*100:.1f}% pnl={r['pnl']:.0f} dd={r['max_dd']*100:.1f}%  "
            f"|wr-50%|={abs(r['wr']-0.5)*100:.1f}pt"
        )

    best50 = pos[0] if pos else None
    if best50:
        p = best50["params"]
        print("\n推荐（胜率最接近50%且盈利）:")
        print(
            f"  atr_period={p['atr_period']}  atr_mults={list(p['atr_mults'])}  "
            f"trailing={p['atr_trailing']}"
        )
        print(
            f"  胜率={best50['wr']*100:.1f}%  净利={best50['pnl']:.0f}  "
            f"回撤={best50['max_dd']*100:.1f}%"
        )


if __name__ == "__main__":
    main()
