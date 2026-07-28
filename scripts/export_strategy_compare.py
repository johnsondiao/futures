"""Export cache bars + Python status for JS strategy compare."""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from src.data.tq_loader import load_cache
from src.ths_sim.formula_main import compute_main_chart, last_bar_status
from src.ths_sim.signal_analytics import analyze_signal

cache = ROOT / "data" / "cache" / "DCE_a2609_5m.csv"
df = load_cache(str(cache))
ind = compute_main_chart(df)
status = last_bar_status(ind)
analytics = analyze_signal(ind)

bars = []
for ts, row in df.iterrows():
    bars.append(
        {
            "time": int(ts.timestamp()),
            "open": float(row["open"]),
            "high": float(row["high"]),
            "low": float(row["low"]),
            "close": float(row["close"]),
        }
    )

out = {
    "bars": bars,
    "py_status": {
        "state": status["state"],
        "need_order": status["need_order"],
        "close": status["close"],
        "cond_entry": status["cond_entry"],
        "cond_tp1": status["cond_tp1"],
        "cond_tp2": status["cond_tp2"],
        "cond_tp3": status["cond_tp3"],
        "cond_sl": status["cond_sl"],
        "orders_n": len(status["orders"]),
    },
    "py_analytics": {
        "available": analytics["available"],
        "kind": analytics["kind"],
        "sample_n": analytics["sample_n"],
        "p_fill": analytics["p_fill"],
        "p_tp_first": analytics["p_tp_first"],
        "p_sl_first": analytics["p_sl_first"],
        "expected_points": analytics["expected_points"],
    },
}
path = ROOT / "data" / "goal" / "strategy_compare_input.json"
path.parent.mkdir(parents=True, exist_ok=True)
path.write_text(json.dumps(out), encoding="utf-8")
print("wrote", path, "bars", len(bars), "state", status["state"])
