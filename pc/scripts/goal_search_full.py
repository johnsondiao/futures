"""穷举网格搜索（非随机抽样），在单一样本上找网格内的真正最优参数组合。

与 goal_search.py 的区别：
- goal_search.py 默认 shuffle 后只跑 --max-trials（默认500）组，覆盖率不到3%；
- 本脚本穷举 param_grid() 定义的全部组合（固定手数时约 17880 组），用多进程并行跑完，
  保证在这份网格 + 这份数据上找到的是真正的 grid-optimal，而不是抽样最优。

用法:
  python pc/scripts/goal_search_full.py --symbol DCE.a2611 --period 5m --lots 3 --workers 12
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path
from multiprocessing import Pool, cpu_count

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from src.data.tq_loader import load_cache, save_cache, fetch_klines  # noqa: E402
from scripts.goal_search import run_once, param_grid  # noqa: E402


def _all_params(fixed_lots: int | None):
    """去重后的全量参数组合（不 shuffle，不截断）。"""
    seen = set()
    for p in param_grid(fixed_lots=fixed_lots):
        key = json.dumps(p, sort_keys=True, default=str)
        if key in seen:
            continue
        seen.add(key)
        yield p


_DF = None
_PERIOD = None


def _init_worker(df, period):
    global _DF, _PERIOD
    _DF = df
    _PERIOD = period


def _worker(params: dict) -> dict:
    try:
        res = run_once(_DF, _PERIOD, params)
        res["params"] = {**params, "atr_mults": list(params["atr_mults"])}
        res["error"] = None
    except Exception as e:  # pragma: no cover
        res = {"pnl": float("-inf"), "params": {**params, "atr_mults": list(params["atr_mults"])}, "error": str(e)}
    return res


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--symbol", default="DCE.a2611")
    parser.add_argument("--period", default="5m")
    parser.add_argument("--bars", type=int, default=8000)
    parser.add_argument("--lots", type=int, default=3, help="固定开仓手数；0=手数也纳入穷举")
    parser.add_argument("--min-trades", type=int, default=10)
    parser.add_argument("--workers", type=int, default=max(1, cpu_count() - 2))
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

    fixed_lots = None if args.lots == 0 else args.lots
    all_params = list(_all_params(fixed_lots))
    total = len(all_params)
    print(f"穷举网格总组合数: {total}  workers={args.workers}")

    out_dir = ROOT / "data" / "goal"
    out_dir.mkdir(parents=True, exist_ok=True)
    tag = f"lots{args.lots}" if args.lots else "lots_free"
    log_path = out_dir / f"search_log_full_{tag}.jsonl"
    best_path = out_dir / f"best_full_{tag}.json"
    summary_path = out_dir / f"summary_full_{tag}.json"

    best = None
    valid_pnls = []
    all_pnls = []
    t0 = time.time()
    done = 0

    with log_path.open("w", encoding="utf-8") as log, Pool(
        processes=args.workers, initializer=_init_worker, initargs=(df, args.period)
    ) as pool:
        for res in pool.imap_unordered(_worker, all_params, chunksize=8):
            done += 1
            if res.get("error"):
                continue
            all_pnls.append(res["pnl"])
            log.write(json.dumps(res, ensure_ascii=False) + "\n")
            if res["closed"] >= args.min_trades:
                valid_pnls.append(res["pnl"])
                if best is None or res["pnl"] > best["pnl"]:
                    best = res
                    best_path.write_text(
                        json.dumps(best, ensure_ascii=False, indent=2), encoding="utf-8"
                    )
                    elapsed = time.time() - t0
                    print(
                        f"[{done}/{total} {elapsed:.0f}s] NEW BEST pnl={res['pnl']:.2f} "
                        f"dd={res['max_dd']:.2f}% trades={res['closed']} "
                        f"mode={res['params']['entry_mode']} N={res['params']['channel_period']} "
                        f"CCI={res['params']['cci_period']}/{res['params']['cci_signal']} "
                        f"ATR={res['params']['atr_period']}x{res['params']['atr_mults']} "
                        f"trail={res['params']['atr_trailing']}"
                    )
            if done % 500 == 0:
                elapsed = time.time() - t0
                print(f"进度 {done}/{total}  已用时 {elapsed:.0f}s")

    elapsed = time.time() - t0
    summary = {
        "total_combinations": total,
        "valid_count": len(valid_pnls),
        "mean_pnl_all": sum(all_pnls) / len(all_pnls) if all_pnls else None,
        "positive_frac_all": sum(1 for p in all_pnls if p > 0) / len(all_pnls) if all_pnls else None,
        "mean_pnl_valid": sum(valid_pnls) / len(valid_pnls) if valid_pnls else None,
        "positive_frac_valid": sum(1 for p in valid_pnls if p > 0) / len(valid_pnls) if valid_pnls else None,
        "elapsed_sec": elapsed,
        "best": best,
    }
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")

    print("\n======== 穷举搜索完成 ========")
    print(f"总组合数: {total}  有效(closed>={args.min_trades}): {len(valid_pnls)}  用时: {elapsed:.0f}s")
    if all_pnls:
        print(f"全部组合均值pnl: {summary['mean_pnl_all']:.2f}  正收益占比: {summary['positive_frac_all']*100:.1f}%")
    if best:
        print(f"网格内真正最优 pnl={best['pnl']:.2f}  (之前500组随机抽样得到的是 12609.63)")
        print(json.dumps(best["params"], ensure_ascii=False, indent=2))
    else:
        print("无有效结果")


if __name__ == "__main__":
    main()
