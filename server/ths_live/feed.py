"""实时行情喂数：天勤长连接优先，失败可回退本地缓存。"""

from __future__ import annotations

import os
import threading
import time
from dataclasses import dataclass, field
from typing import Any, Callable

import pandas as pd

from src.data.tq_loader import PERIOD_SECONDS, _load_auth, _to_bt_dataframe, load_cache
from src.ths_sim.formula_main import compute_main_chart, last_bar_status
from src.ths_sim.signal_analytics import analyze_signal


def _bool_list(s: pd.Series) -> list:
    return s.fillna(False).astype(bool).tolist()


def _num_list(s: pd.Series, nd: int = 2) -> list:
    return [None if pd.isna(v) else round(float(v), nd) for v in s]


def _ind_to_payload(ind: pd.DataFrame, bars: int = 300) -> dict[str, Any]:
    view = ind.iloc[-bars:].copy()
    status = last_bar_status(ind)
    analytics = analyze_signal(ind)
    times = [ts.isoformat(sep=" ") for ts in view.index]
    return {
        "updated_at": time.time(),
        "status": status,
        "analytics": analytics,
        "bars": {
            "time": times,
            "open": view["open"].round(2).tolist(),
            "high": view["high"].round(2).tolist(),
            "low": view["low"].round(2).tolist(),
            "close": view["close"].round(2).tolist(),
            "upper": _num_list(view["upper"]),
            "lower": _num_list(view["lower"]),
            "color": [None if pd.isna(v) else int(v) for v in view["color"]],
            "cci": _num_list(view["cci"]),
            "cci_ma": _num_list(view["cci_ma"]),
            "cond_entry": _num_list(view["cond_entry"]),
            "cond_tp": _num_list(view["cond_tp"]),
            "cond_sl": _num_list(view["cond_sl"]),
            "open_long": _bool_list(view["open_long"]),
            "open_short": _bool_list(view["open_short"]),
            "flat_long": _bool_list(view["flat_long"]),
            "flat_short": _bool_list(view["flat_short"]),
            "tp1_long": _bool_list(view["tp1_long"]),
            "tp2_long": _bool_list(view["tp2_long"]),
            "tp3_long": _bool_list(view["tp3_long"]),
            "tp1_short": _bool_list(view["tp1_short"]),
            "tp2_short": _bool_list(view["tp2_short"]),
            "tp3_short": _bool_list(view["tp3_short"]),
        },
    }


@dataclass
class LiveFeed:
    symbol: str = "DCE.a2609"
    period: str = "5m"
    data_length: int = 2000
    bars: int = 300
    cache_path: str | None = None
    poll_seconds: float = 3.0
    mode: str = "auto"  # auto | live | cache

    _lock: threading.Lock = field(default_factory=threading.Lock, init=False, repr=False)
    _payload: dict[str, Any] = field(default_factory=dict, init=False, repr=False)
    _listeners: list[Callable[[dict[str, Any]], None]] = field(
        default_factory=list, init=False, repr=False
    )
    _thread: threading.Thread | None = field(default=None, init=False, repr=False)
    _stop: threading.Event = field(default_factory=threading.Event, init=False, repr=False)
    source: str = field(default="idle", init=False)

    def snapshot(self) -> dict[str, Any]:
        with self._lock:
            data = dict(self._payload)
        data["source"] = self.source
        data["symbol"] = self.symbol
        data["period"] = self.period
        return data

    def subscribe(self, callback: Callable[[dict[str, Any]], None]) -> None:
        with self._lock:
            self._listeners.append(callback)

    def _publish(self, payload: dict[str, Any]) -> None:
        with self._lock:
            self._payload = payload
            listeners = list(self._listeners)
        for cb in listeners:
            try:
                cb(payload)
            except Exception:
                pass

    def _update_from_df(self, df: pd.DataFrame, source: str) -> None:
        if df is None or df.empty:
            return
        ind = compute_main_chart(df)
        payload = _ind_to_payload(ind, bars=self.bars)
        self.source = source
        self._publish(payload)

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._run, name="ths-live-feed", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()

    def _run(self) -> None:
        want_live = self.mode in ("auto", "live")
        if want_live:
            try:
                self._run_live()
                return
            except Exception as exc:
                self.source = f"live_error:{exc}"
                if self.mode == "live":
                    return
        self._run_cache_poll()

    def _run_live(self) -> None:
        from tqsdk import TqApi

        duration = PERIOD_SECONDS[self.period]
        api = TqApi(auth=_load_auth())
        try:
            klines = api.get_kline_serial(self.symbol, duration, data_length=self.data_length)
            for _ in range(30):
                if self._stop.is_set():
                    return
                api.wait_update(deadline=time.time() + 2)
                if len(klines) > 0 and pd.notna(klines.close.iloc[-1]):
                    break
            self._update_from_df(_to_bt_dataframe(klines.copy()), "live")
            while not self._stop.is_set():
                api.wait_update(deadline=time.time() + 2)
                if api.is_changing(klines):
                    self._update_from_df(_to_bt_dataframe(klines.copy()), "live")
        finally:
            api.close()

    def _run_cache_poll(self) -> None:
        path = self.cache_path
        if not path:
            root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
            path = os.path.join(
                root, "data", "cache", f"{self.symbol.replace('.', '_')}_{self.period}.csv"
            )
        while not self._stop.is_set():
            try:
                df = load_cache(path)
                self._update_from_df(df, "cache")
            except Exception as exc:
                self.source = f"cache_error:{exc}"
            self._stop.wait(self.poll_seconds)
