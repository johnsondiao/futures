"""回测：通道 + CCI + ATR 分批止盈。默认豆一 DCE.a2609 / 5m。"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import backtrader as bt

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from src.config import (
    ATR_PERIOD,
    ATR_TP_MULTS,
    CCI_ENTRY_MODE,
    CCI_PERIOD,
    CCI_SIGNAL_PERIOD,
    CHANNEL_N,
    ENTRY_LOTS,
)
from src.data.bt_feed import TIMEFRAME_MAP, make_data
from src.data.tq_loader import fetch_klines, load_cache, save_cache
from strategies.channel_trend import ChannelCciAtrStrategy


def _trade_summary(trades) -> None:
    total = trades.get("total", {})
    won = trades.get("won", {})
    lost = trades.get("lost", {})
    pnl = trades.get("pnl", {})
    print("--- 成交摘要 ---")
    print(f"总笔数: {total.get('total', 0)}  已平仓: {total.get('closed', 0)}")
    print(f"盈利笔: {won.get('total', 0)}  亏损笔: {lost.get('total', 0)}")
    net = pnl.get("net", {})
    if isinstance(net, dict):
        print(f"净盈亏合计: {net.get('total', 0):.2f}")
        print(f"平均每笔净盈亏: {net.get('average', 0):.2f}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--symbol", default="DCE.a2609")
    parser.add_argument("--period", default="5m", choices=["5m", "60m", "1d"])
    parser.add_argument("--bars", type=int, default=8000)
    parser.add_argument("--cash", type=float, default=1_000_000.0)
    parser.add_argument("--mode", default=CCI_ENTRY_MODE, help="开仓模式")
    parser.add_argument("--printlog", action="store_true")
    args = parser.parse_args()

    symbol = args.symbol
    period = args.period
    channel_n = CHANNEL_N.get(period, 144)
    safe = symbol.replace(".", "_").replace("@", "_")
    cache = ROOT / "data" / "cache" / f"{safe}_{period}.csv"

    if cache.exists():
        print(f"使用缓存: {cache}")
        df = load_cache(cache)
    else:
        print(f"从天勤拉取 {symbol} {period} bars={args.bars} ...")
        df = fetch_klines(symbol, period=period, data_length=args.bars)
        save_cache(df, cache)
        print(f"已缓存 {len(df)} 根 → {cache}")

    if len(df) < channel_n + 20:
        raise SystemExit(f"数据过短: {len(df)} 根，通道 N={channel_n}")

    print(f"数据区间: {df.index[0]} → {df.index[-1]}  共 {len(df)} 根")
    print(
        f"通道 N={channel_n} | CCI({CCI_PERIOD})/SMA({CCI_SIGNAL_PERIOD}) | "
        f"ATR({ATR_PERIOD}) x {ATR_TP_MULTS} | 开仓{ENTRY_LOTS}手 | mode={args.mode}"
    )

    tf, comp = TIMEFRAME_MAP[period]
    data = make_data(df, name=f"{safe}_{period}", timeframe=tf, compression=comp)

    cerebro = bt.Cerebro(stdstats=True)
    cerebro.adddata(data)
    cerebro.addstrategy(
        ChannelCciAtrStrategy,
        channel_period=channel_n,
        entry_mode=args.mode,
        printlog=args.printlog,
    )
    cerebro.broker.setcash(args.cash)
    cerebro.broker.setcommission(commission=0.00015, mult=10.0)

    cerebro.addanalyzer(bt.analyzers.DrawDown, _name="dd")
    cerebro.addanalyzer(bt.analyzers.TradeAnalyzer, _name="trades")
    cerebro.addanalyzer(
        bt.analyzers.SharpeRatio,
        _name="sharpe",
        timeframe=bt.TimeFrame.Days,
        riskfreerate=0.0,
    )

    start = cerebro.broker.getvalue()
    print(f"初始资金: {start:.2f}")
    results = cerebro.run()
    strat = results[0]
    end = cerebro.broker.getvalue()
    print(f"最终资金: {end:.2f}")
    print(f"收益: {end - start:.2f}  ({(end / start - 1) * 100:.2f}%)")
    dd = strat.analyzers.dd.get_analysis()
    print(f"最大回撤: {dd.get('max', {}).get('drawdown', 0):.2f}%")
    sharpe = strat.analyzers.sharpe.get_analysis()
    print(f"夏普(日): {sharpe.get('sharperatio')}")
    _trade_summary(strat.analyzers.trades.get_analysis())


if __name__ == "__main__":
    main()
