"""稳健性分析：把「入场逻辑」(entry_mode+channel+CCI) 与「ATR退出参数」拆开看。

用途：避免只挑单点最优（容易是噪声尖峰），转而找一个「入场逻辑固定后，
不论ATR怎么调都大概率盈利」的稳健区域。

依赖：先跑过 pc/scripts/goal_search_full.py 生成的 pc/data/goal/search_log_full_*.jsonl

用法:
  python pc/scripts/robustness_report.py --lots 3 --top 15
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def entry_key(params: dict) -> tuple:
    return (
        params["entry_mode"],
        params["channel_period"],
        params["cci_period"],
        params["cci_signal"],
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--lots", type=int, default=3)
    parser.add_argument("--top", type=int, default=15)
    args = parser.parse_args()

    tag = f"lots{args.lots}" if args.lots else "lots_free"
    log_path = ROOT / "data" / "goal" / f"search_log_full_{tag}.jsonl"
    if not log_path.exists():
        sys.exit(f"找不到 {log_path}，请先跑 pc/scripts/goal_search_full.py")

    rows = [json.loads(l) for l in log_path.open(encoding="utf-8")]

    groups: dict[tuple, list[dict]] = defaultdict(list)
    for r in rows:
        groups[entry_key(r["params"])].append(r)

    stats = []
    for key, rs in groups.items():
        pnls = [r["pnl"] for r in rs]
        n = len(pnls)
        mean = statistics.mean(pnls)
        pos_frac = sum(1 for x in pnls if x > 0) / n
        stats.append(
            {
                "key": key,
                "n": n,
                "mean": mean,
                "pos_frac": pos_frac,
                "worst": min(pnls),
                "best": max(pnls),
                "stdev": statistics.pstdev(pnls),
                "rows": rs,
            }
        )

    stats.sort(key=lambda s: (-s["pos_frac"], -s["mean"]))

    print(f"共 {len(stats)} 组「入场逻辑」(mode, channel, cci_period/signal)，"
          f"每组内含全部 ATR 退出参数组合\n")
    print(f"{'mode':<20}{'ch':>5}{'cci':>8}{'n':>5}{'mean':>10}{'pos%':>7}{'worst':>10}{'best':>10}{'stdev':>8}")
    for s in stats[: args.top]:
        key = s["key"]
        cci = f"{key[2]}/{key[3]}"
        print(
            f"{key[0]:<20}{key[1]:>5}{cci:>8}{s['n']:>5}{s['mean']:>10.0f}"
            f"{s['pos_frac']*100:>6.0f}%{s['worst']:>10.0f}{s['best']:>10.0f}{s['stdev']:>8.0f}"
        )

    fully_robust = [s for s in stats if s["pos_frac"] == 1.0]
    print(f"\n入场逻辑分组里，ATR 退出参数怎么调都盈利(pos%=100%)的有 "
          f"{len(fully_robust)}/{len(stats)} 组")

    if fully_robust:
        best = fully_robust[0]
        key = best["key"]
        print(
            f"\n推荐的稳健入场逻辑: entry_mode={key[0]} channel_period={key[1]} "
            f"cci_period={key[2]} cci_signal={key[3]}"
        )
        print(f"  该组 {best['n']} 种ATR退出参数下 pnl 范围: "
              f"[{best['worst']:.0f}, {best['best']:.0f}]  均值={best['mean']:.0f}  "
              f"标准差={best['stdev']:.0f}（相对标准差={best['stdev']/best['mean']*100:.1f}%）")
        print("  说明：只要入场逻辑选对，ATR 退出参数在合理范围内怎么调本组合都没亏过，"
              "可以放心选用简单/规整的默认值，而不必迷信单点最优。")


if __name__ == "__main__":
    main()
