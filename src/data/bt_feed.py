"""backtrader 数据源封装。"""

from __future__ import annotations

import backtrader as bt
import pandas as pd


class FuturesPandasData(bt.feeds.PandasData):
    """期货 OHLCV + 持仓量。"""

    params = (
        ("datetime", None),
        ("open", "open"),
        ("high", "high"),
        ("low", "low"),
        ("close", "close"),
        ("volume", "volume"),
        ("openinterest", "openinterest"),
    )


def make_data(df: pd.DataFrame, name: str = "data", timeframe=None, compression=1):
    """把标准 OHLCV DataFrame 转成 Cerebro 可用的 data feed。"""
    kwargs = {"dataname": df, "name": name}
    if timeframe is not None:
        kwargs["timeframe"] = timeframe
        kwargs["compression"] = compression
    return FuturesPandasData(**kwargs)


# 常用周期映射（方便脚本调用）
TIMEFRAME_MAP = {
    "5m": (bt.TimeFrame.Minutes, 5),
    "60m": (bt.TimeFrame.Minutes, 60),
    "1h": (bt.TimeFrame.Minutes, 60),
    "1d": (bt.TimeFrame.Days, 1),
}
