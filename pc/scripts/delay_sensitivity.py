"""开仓延迟敏感性分析：不及时开仓到底亏还是赚？

基准：信号 K 线收盘触发（backtrader 默认下一根开盘成交，TP 锚定信号收盘价）。
变体：
  - slip：每笔成交额外滑点（set_slippage_perc，开/平仓都变差）
  - delay_bars：信号后迟 N 根 K 线才市价进场（TP 锚定实际进场价），
    等待期间若通道反向变色则放弃该笔（模拟用户看到趋势反转不再追）。

用法:
  python pc/scripts/delay_sensitivity.py [--period 5m|60m] [--channel 60] [--cci 15 --cci-signal 3]
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import backtrader as bt

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from src.config import STRATEGY_PROFILES  # noqa: E402
from src.data.bt_feed import TIMEFRAME_MAP, make_data  # noqa: E402
from src.data.tq_loader import load_cache  # noqa: E402
from strategies.channel_trend import ChannelCciAtrStrategy  # noqa: E402

SYMBOL = "DCE.a2611"


class DelayedEntryStrategy(ChannelCciAtrStrategy):
    params = (("delay_bars", 0),)

    def __init__(self):
        super().__init__()
        self.delay_left = None
        self.delay_dir = 0

    def next(self):
        if self.delay_left is not None:
            self.delay_left -= 1
            side = self._side()
            # 等待期间通道反向变色：趋势已反转，放弃本次进场
            if side != 0 and side == -self.delay_dir:
                self.delay_left = None
                self.delay_dir = 0
                self.color = side
                return
            if self.delay_left <= 0:
                direction = self.delay_dir
                self.delay_left = None
                self.delay_dir = 0
                super()._open(direction)
            return
        super().next()

    def _open(self, direction):
        if self.p.delay_bars > 0 and self.delay_left is None:
            self.delay_left = self.p.delay_bars
            self.delay_dir = direction
            self.pending = 0
            self.log(f"信号出现，等待 {self.p.delay_bars} 根后进场 dir={direction}")
            return
        super()._open(direction)


def run_one(df, delay_bars: int, slip_perc: float, period: str, channel: int,
            cci: int, cci_signal: int, mults: tuple) -> dict:
    tf, comp = TIMEFRAME_MAP[period]
    data = make_data(df, name=f"{SYMBOL.replace('.', '_')}_{period}", timeframe=tf, compression=comp)
    cerebro = bt.Cerebro(stdstats=False)
    cerebro.adddata(data)
    cerebro.addstrategy(
        DelayedEntryStrategy,
        channel_period=channel,
        cci_period=cci,
        cci_signal=cci_signal,
        atr_mults=mults,
        delay_bars=delay_bars,
        printlog=False,
    )
    cerebro.broker.setcash(1_000_000.0)
    cerebro.broker.setcommission(commission=0.00015, mult=10.0)
    if slip_perc > 0:
        cerebro.broker.set_slippage_perc(slip_perc)
    cerebro.addanalyzer(bt.analyzers.TradeAnalyzer, _name="trades")
    cerebro.addanalyzer(bt.analyzers.DrawDown, _name="dd")

    start = cerebro.broker.getvalue()
    strat = cerebro.run()[0]
    end = cerebro.broker.getvalue()

    t = strat.analyzers.trades.get_analysis()
    total = t.get("total", {}).get("closed", 0)
    won = t.get("won", {}).get("total", 0)
    dd = strat.analyzers.dd.get_analysis().get("max", {}).get("drawdown", 0.0)
    return {
        "net": end - start,
        "trades": total,
        "winrate": (won / total * 100) if total else 0.0,
        "dd": dd,
        "avg": (end - start) / total if total else 0.0,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--period", default="5m", choices=["5m", "60m"])
    # 缺省跟随该周期的策略档案（STRATEGY_PROFILES），显式传参则覆盖
    parser.add_argument("--channel", type=int, default=None)
    parser.add_argument("--cci", type=int, default=None)
    parser.add_argument("--cci-signal", type=int, default=None)
    parser.add_argument("--mults", type=float, nargs=3, default=None)
    args = parser.parse_args()

    profile = STRATEGY_PROFILES.get(args.period, STRATEGY_PROFILES["5m"])
    channel = args.channel if args.channel is not None else profile["channel_period"]
    cci = args.cci if args.cci is not None else profile["cci_period"]
    cci_signal = args.cci_signal if args.cci_signal is not None else profile["cci_signal"]

    cache = ROOT / "data" / "cache" / f"{SYMBOL.replace('.', '_')}_{args.period}.csv"
    df = load_cache(cache)
    mults = tuple(args.mults) if args.mults is not None else tuple(profile["atr_mults"])
    print(f"数据: {df.index[0]} → {df.index[-1]}  共 {len(df)} 根 {args.period} K 线")
    print(f"参数: 通道={channel} CCI({cci})/SMA({cci_signal}) "
          f"TP={list(mults)}×ATR(20) | 3手 | 无硬止损(反向变色平)")
    print()

    SLIP_1 = 0.00022  # 约 1 元/吨（1 个 tick）
    SLIP_2 = 0.00045  # 约 2 元/吨
    bar_min = 5 if args.period == "5m" else 60

    scenarios = [
        ("基准: 信号收盘即下单", 0, 0.0),
        ("仅滑点≈1元", 0, SLIP_1),
        ("仅滑点≈2元", 0, SLIP_2),
        (f"迟1根({bar_min}分钟)+滑点1元", 1, SLIP_1),
        (f"迟2根({bar_min*2}分钟)+滑点1元", 2, SLIP_1),
        (f"迟3根({bar_min*3}分钟)+滑点1元", 3, SLIP_1),
        (f"迟6根({bar_min*6}分钟)+滑点1元", 6, SLIP_1),
    ]

    print(f"{'场景':<28}{'净盈亏':>12}{'笔数':>6}{'胜率%':>8}{'回撤%':>8}{'每笔均值':>10}")
    print("-" * 76)
    results = []
    for name, delay, slip in scenarios:
        r = run_one(df, delay, slip, args.period, channel,
                    cci, cci_signal, mults)
        results.append((name, r))
        print(
            f"{name:<28}{r['net']:>12.0f}{r['trades']:>6}{r['winrate']:>8.1f}"
            f"{r['dd']:>8.2f}{r['avg']:>10.0f}"
        )

    base = results[0][1]["net"]
    print()
    print("相对基准的变化:")
    for name, r in results[1:]:
        print(f"  {name:<28} {r['net'] - base:>+10.0f} 元")


if __name__ == "__main__":
    main()
