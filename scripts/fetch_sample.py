"""验证天勤账号：拉取主力合约一根周期样本并打印。"""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from src.data.tq_loader import fetch_klines, save_cache


def main():
    symbol = "KQ.m@SHFE.rb"
    period = "5m"
    print(f"拉取 {symbol} {period} ...")
    df = fetch_klines(symbol, period=period, data_length=100)
    path = save_cache(df, ROOT / "data" / "cache" / f"rb_{period}_sample.csv")
    print(df.tail())
    print(f"共 {len(df)} 根，已缓存: {path}")


if __name__ == "__main__":
    main()
