"""启动通道策略实时服务（供安卓 App / 手机浏览器访问）。

用法:
  # 优先天勤实时；失败自动用本地缓存
  python pc/scripts/run_ths_live.py

  # 强制缓存（无账号也能看盘面原型）
  set THS_MODE=cache
  python pc/scripts/run_ths_live.py --host 0.0.0.0 --port 8080
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8080)
    args = parser.parse_args()

    import uvicorn

    uvicorn.run(
        "server.ths_live.app:app",
        host=args.host,
        port=args.port,
        reload=False,
        log_level="info",
    )


if __name__ == "__main__":
    main()
