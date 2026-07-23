"""天勤 K 线拉取，输出供 backtrader 使用的 DataFrame。"""

from __future__ import annotations

import os
import time
from pathlib import Path

import pandas as pd
from dotenv import load_dotenv
from tqsdk import TqApi, TqAuth

# 周期秒数约定
DUR_5M = 5 * 60
DUR_60M = 60 * 60
DUR_1D = 24 * 60 * 60

PERIOD_SECONDS = {
    "5m": DUR_5M,
    "60m": DUR_60M,
    "1h": DUR_60M,
    "1d": DUR_1D,
}


def _load_auth() -> TqAuth:
    # 固定从项目根目录读 .env，避免从 scripts/ 启动时找不到
    root = Path(__file__).resolve().parents[2]
    load_dotenv(root / ".env")
    user = os.getenv("TQ_USER", "").strip()
    password = os.getenv("TQ_PASS", "").strip()
    if (
        not user
        or not password
        or user.startswith("你的")
        or password.startswith("你的")
    ):
        raise RuntimeError(
            f"请编辑 {root / '.env'}，填入真实的快期 TQ_USER / TQ_PASS（不要留「你的…」占位）"
        )
    return TqAuth(user, password)


def fetch_klines(
    symbol: str,
    period: str = "5m",
    data_length: int = 2000,
) -> pd.DataFrame:
    """从天勤拉取最近 N 根 K 线，返回索引为 datetime 的 OHLCV DataFrame。

    免费账户单次上限约 8000～10000 根，详见天勤 skill。
    研究用主力连续建议：KQ.m@SHFE.rb
    """
    if period not in PERIOD_SECONDS:
        raise ValueError(f"不支持的 period={period!r}，可选: {list(PERIOD_SECONDS)}")
    if data_length > 10000:
        raise ValueError("data_length 不能超过 10000（天勤 get_kline_serial 上限）")

    duration = PERIOD_SECONDS[period]
    api = TqApi(auth=_load_auth())
    try:
        klines = api.get_kline_serial(symbol, duration, data_length=data_length)
        # 等到序列就绪（非空且末根 close 有效）；最多等 30 次避免挂死
        ready = False
        for _ in range(30):
            api.wait_update(deadline=time.time() + 2)
            if len(klines) > 0 and pd.notna(klines.close.iloc[-1]):
                ready = True
                break
        if not ready:
            raise RuntimeError(f"拉取 K 线超时: {symbol} period={period}")
        df = klines.copy()
    finally:
        api.close()

    return _to_bt_dataframe(df)


def _to_bt_dataframe(raw: pd.DataFrame) -> pd.DataFrame:
    """把天勤 K 线列转成 backtrader PandasData 需要的格式。"""
    df = raw.copy()
    # 天勤 datetime 是纳秒时间戳
    df["datetime"] = pd.to_datetime(df["datetime"], unit="ns")
    df = df.set_index("datetime")
    out = pd.DataFrame(
        {
            "open": df["open"].astype(float),
            "high": df["high"].astype(float),
            "low": df["low"].astype(float),
            "close": df["close"].astype(float),
            "volume": df["volume"].astype(float),
            "openinterest": df.get("close_oi", df.get("open_oi", 0)).astype(float),
        },
        index=df.index,
    )
    out = out.dropna(subset=["open", "high", "low", "close"])
    out = out[~out.index.duplicated(keep="last")]
    out = out.sort_index()
    return out


def save_cache(df: pd.DataFrame, path: str | Path) -> Path:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    df.to_csv(path)
    return path


def load_cache(path: str | Path) -> pd.DataFrame:
    path = Path(path)
    df = pd.read_csv(path, parse_dates=["datetime"], index_col="datetime")
    return df
