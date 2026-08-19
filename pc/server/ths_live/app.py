"""通道策略实时 App 后端（手机页 + API + WebSocket）。"""

from __future__ import annotations

import asyncio
import json
import os
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

from server.ths_live.feed import LiveFeed

STATIC_DIR = Path(__file__).resolve().parent / "static"

_feed: LiveFeed | None = None
_loop: asyncio.AbstractEventLoop | None = None
_clients: set[WebSocket] = set()


def get_feed() -> LiveFeed:
    global _feed
    if _feed is None:
        _feed = LiveFeed(
            symbol=os.getenv("THS_SYMBOL", "DCE.a2611"),
            period=os.getenv("THS_PERIOD", "5m"),
            mode=os.getenv("THS_MODE", "auto"),
            bars=int(os.getenv("THS_BARS", "300")),
            cache_path=os.getenv("THS_CACHE", "") or None,
        )
    return _feed


async def _broadcast(payload: dict) -> None:
    dead: list[WebSocket] = []
    text = json.dumps(payload, ensure_ascii=False)
    for ws in list(_clients):
        try:
            await ws.send_text(text)
        except Exception:
            dead.append(ws)
    for ws in dead:
        _clients.discard(ws)


def _on_feed_update(payload: dict) -> None:
    if _loop is None:
        return
    asyncio.run_coroutine_threadsafe(_broadcast(payload), _loop)


@asynccontextmanager
async def lifespan(app: FastAPI):
    global _loop
    _loop = asyncio.get_running_loop()
    feed = get_feed()
    feed.subscribe(_on_feed_update)
    feed.start()
    # 等首包一会儿，方便 /api/snapshot 立刻有数据
    for _ in range(50):
        if feed.snapshot().get("status"):
            break
        await asyncio.sleep(0.1)
    yield
    feed.stop()


app = FastAPI(title="通道策略实时盘", lifespan=lifespan)
app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")


@app.get("/")
async def index():
    return FileResponse(STATIC_DIR / "index.html")


@app.get("/api/health")
async def health():
    snap = get_feed().snapshot()
    return {
        "ok": True,
        "source": snap.get("source"),
        "symbol": snap.get("symbol"),
        "has_data": bool(snap.get("status")),
    }


@app.get("/api/snapshot")
async def snapshot():
    return get_feed().snapshot()


@app.websocket("/ws")
async def ws_endpoint(websocket: WebSocket):
    await websocket.accept()
    _clients.add(websocket)
    try:
        await websocket.send_text(json.dumps(get_feed().snapshot(), ensure_ascii=False))
        while True:
            # 客户端心跳；忽略内容
            await websocket.receive_text()
    except WebSocketDisconnect:
        pass
    finally:
        _clients.discard(websocket)
