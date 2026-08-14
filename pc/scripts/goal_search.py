"""Goal 搜参：在指定合约上寻找净盈亏 >= GOAL_PNL 的参数组合。

用法:
  python pc/scripts/goal_search.py
  python pc/scripts/goal_search.py --goal 10000 --symbol DCE.a2609
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
import backtrader as bt

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from src.data.bt_feed import TIMEFRAME_MAP, make_data
from src.data.tq_loader import fetch_klines, load_cache, save_cache
from strategies.channel_trend import ChannelCciAtrStrategy


def run_once(df, period: str, params: dict, cash: float = 1_000_000.0) -> dict:
    tf, comp = TIMEFRAME_MAP[period]
    data = make_data(df.copy(), name="goal", timeframe=tf, compression=comp)
    cerebro = bt.Cerebro(stdstats=False)
    cerebro.adddata(data)
    cerebro.addstrategy(ChannelCciAtrStrategy, printlog=False, **params)
    cerebro.broker.setcash(cash)
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
    return {
        "pnl": end - start,
        "return_pct": (end / start - 1) * 100,
        "max_dd": float(dd.get("max", {}).get("drawdown", 0) or 0),
        "closed": closed,
        "won": won,
        "lost": lost,
        "params": params,
    }


def param_grid(seed: int = 42, fixed_lots: int | None = 3):
    """优先高潜力组合。fixed_lots 不为 None 时锁定手数。"""
    import random

    lots_list = (fixed_lots,) if fixed_lots is not None else (3, 5, 8, 10, 12)

    priority = []
    for mode in ("color_then_cci", "color_and_cci_cross", "cci_then_color"):
        for lots in lots_list:
            for ch in (144, 120, 168, 96, 192, 72, 216, 60, 100, 130):
                for cci_p, cci_s in ((15, 4), (14, 3), (20, 5), (10, 3), (12, 4), (18, 5), (25, 5)):
                    for atr_p in (14, 10, 20, 7, 28):
                        for mults in (
                            (3, 5, 7),
                            (2, 4, 6),
                            (3, 5, 8),
                            (2, 3, 5),
                            (4, 6, 9),
                            (1.5, 3, 5),
                            (3, 6, 9),
                            (2, 5, 8),
                        ):
                            for trail in (True, False):
                                priority.append(
                                    {
                                        "channel_period": ch,
                                        "cci_period": cci_p,
                                        "cci_signal": cci_s,
                                        "atr_period": atr_p,
                                        "atr_mults": mults,
                                        "atr_trailing": trail,
                                        "entry_lots": lots,
                                        "entry_mode": mode,
                                    }
                                )

    expand = []
    for mode in ("color_and_cci_above", "color_only", "cci_cross_only"):
        for lots in lots_list:
            for ch in (144, 120, 168, 96, 72):
                for cci_p, cci_s in ((15, 4), (10, 3), (20, 5)):
                    for atr_p in (14, 10, 20):
                        for mults in ((3, 5, 7), (2, 3, 5), (2, 4, 6), (3, 5, 8)):
                            for trail in (True, False):
                                expand.append(
                                    {
                                        "channel_period": ch,
                                        "cci_period": cci_p,
                                        "cci_signal": cci_s,
                                        "atr_period": atr_p,
                                        "atr_mults": mults,
                                        "atr_trailing": trail,
                                        "entry_lots": lots,
                                        "entry_mode": mode,
                                    }
                                )

    rng = random.Random(seed)
    head = priority[:80]
    rest = priority[80:] + expand
    rng.shuffle(rest)
    for p in head + rest:
        yield p


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--symbol", default="DCE.a2609")
    parser.add_argument("--period", default="5m")
    parser.add_argument("--goal", type=float, default=10000.0)
    parser.add_argument("--bars", type=int, default=8000)
    parser.add_argument("--max-trials", type=int, default=800)
    parser.add_argument("--min-trades", type=int, default=10, help="过少交易视为无效")
    parser.add_argument(
        "--lots",
        type=int,
        default=3,
        help="固定开仓手数；传 0 表示搜索时也扫手数",
    )
    args = parser.parse_args()

    safe = args.symbol.replace(".", "_").replace("@", "_")
    cache = ROOT / "data" / "cache" / f"{safe}_{args.period}.csv"
    if cache.exists():
        df = load_cache(cache)
        print(f"缓存数据: {cache}  bars={len(df)}")
    else:
        print(f"拉取 {args.symbol} ...")
        df = fetch_klines(args.symbol, period=args.period, data_length=args.bars)
        save_cache(df, cache)

    out_dir = ROOT / "data" / "goal"
    out_dir.mkdir(parents=True, exist_ok=True)
    tag = f"lots{args.lots}" if args.lots else "lots_free"
    results_path = out_dir / f"search_log_{tag}.jsonl"
    best_path = out_dir / f"best_{tag}.json"
    summary_path = out_dir / f"summary_{tag}.json"

    best = None
    hit = None
    trials = 0
    fixed_lots = None if args.lots == 0 else args.lots

    print(
        f"GOAL: 净盈亏 >= {args.goal:.0f}  | symbol={args.symbol} "
        f"period={args.period} lots={'自由' if fixed_lots is None else fixed_lots}"
    )
    print(f"最多尝试 {args.max_trials} 组参数\n")

    with results_path.open("w", encoding="utf-8") as log:
        for params in param_grid(fixed_lots=fixed_lots):
            if trials >= args.max_trials:
                print(f"达到 max_trials={args.max_trials}，停止")
                break
            # 跳过数据不够的通道周期
            if len(df) < params["channel_period"] + 30:
                continue
            trials += 1
            try:
                res = run_once(df, args.period, params)
            except Exception as e:
                print(f"[{trials}] FAIL {params}: {e}")
                continue

            row = {**res, "trial": trials}
            # atr_mults 是 tuple，json 要 list
            row["params"] = {**params, "atr_mults": list(params["atr_mults"])}
            log.write(json.dumps(row, ensure_ascii=False) + "\n")
            log.flush()

            if res["closed"] < args.min_trades:
                continue

            if best is None or res["pnl"] > best["pnl"]:
                best = row
                best_path.write_text(
                    json.dumps(best, ensure_ascii=False, indent=2), encoding="utf-8"
                )
                print(
                    f"[{trials}] NEW BEST pnl={res['pnl']:.2f} dd={res['max_dd']:.2f}% "
                    f"trades={res['closed']} mode={params['entry_mode']} "
                    f"N={params['channel_period']} lots={params['entry_lots']} "
                    f"CCI={params['cci_period']}/{params['cci_signal']} "
                    f"ATR={params['atr_period']}x{params['atr_mults']} trail={params['atr_trailing']}"
                )

            if res["pnl"] >= args.goal and res["closed"] >= args.min_trades:
                hit = row
                print(f"\n*** GOAL HIT *** pnl={res['pnl']:.2f} trial={trials}")
                break

    summary = {
        "goal": args.goal,
        "fixed_lots": fixed_lots,
        "hit": hit is not None,
        "trials": trials,
        "best": best,
        "hit_result": hit,
    }
    summary_path.write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    # 同步一份到 best.json / summary.json 方便查看
    if best:
        (out_dir / "best.json").write_text(
            json.dumps(best, ensure_ascii=False, indent=2), encoding="utf-8"
        )
    (out_dir / "summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    print("\n======== SUMMARY ========")
    if hit:
        print(f"Goal 达成: pnl={hit['pnl']:.2f}")
        print(json.dumps(hit["params"], ensure_ascii=False, indent=2))
    elif best:
        print(f"Goal 未达成。当前最佳 pnl={best['pnl']:.2f} / goal={args.goal}")
        print(json.dumps(best["params"], ensure_ascii=False, indent=2))
    else:
        print("无有效结果")

    # 机器可读尾部，方便脚本判断
    print(f"GOAL_STATUS={'HIT' if hit else 'MISS'} BEST_PNL={best['pnl'] if best else 'NA'}")


if __name__ == "__main__":
    main()
