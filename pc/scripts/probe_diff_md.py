"""Probe shinnytech auth + DIFF kline subscription (dev only)."""
from __future__ import annotations

import asyncio
import json
import os
from pathlib import Path

import websockets
from dotenv import load_dotenv
from tqsdk.auth import TqAuth

ROOT = Path(__file__).resolve().parents[1]
load_dotenv(ROOT / ".env")


async def main() -> None:
    user = os.getenv("TQ_USER", "")
    password = os.getenv("TQ_PASS", "")
    if not user or not password:
        raise SystemExit("TQ_USER/TQ_PASS missing in .env")

    auth = TqAuth(user, password)
    auth.login()
    md_url = auth._get_md_url(False, False)
    print("mdurl=", md_url)

    headers = {
        "Authorization": f"Bearer {auth._access_token}",
        "User-Agent": "tqsdk-python probe",
        "Accept": "application/json",
    }
    symbol = "DCE.a2611"
    duration_ns = 5 * 60 * 10**9  # 5m
    async with websockets.connect(md_url, additional_headers=headers, max_size=None) as ws:
        await ws.send(json.dumps({"aid": "peek_message"}))
        await ws.send(
            json.dumps(
                {
                    "aid": "set_chart",
                    "chart_id": "probe5m",
                    "ins_list": symbol,
                    "duration": duration_ns,
                    "view_width": 300,
                }
            )
        )
        await ws.send(json.dumps({"aid": "subscribe_quote", "ins_list": symbol}))
        got = 0
        last_id = None
        for _ in range(40):
            msg = json.loads(await asyncio.wait_for(ws.recv(), timeout=10))
            await ws.send(json.dumps({"aid": "peek_message"}))
            if msg.get("aid") != "rtn_data":
                continue
            for block in msg.get("data") or []:
                kl = ((block.get("klines") or {}).get(symbol) or {}).get(str(duration_ns)) or (
                    (block.get("klines") or {}).get(symbol) or {}
                ).get(duration_ns)
                if not kl:
                    # try int key via nested
                    kroot = (block.get("klines") or {}).get(symbol) or {}
                    for k, v in kroot.items():
                        if int(k) == duration_ns:
                            kl = v
                            break
                if not kl:
                    continue
                last_id = kl.get("last_id", last_id)
                data = kl.get("data") or {}
                got = max(got, len(data))
                print("klines chunk keys=", len(data), "last_id=", last_id)
            if got >= 50 and last_id is not None:
                break
        print("OK got_bars=", got, "last_id=", last_id)


if __name__ == "__main__":
    asyncio.run(main())
