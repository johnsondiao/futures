"""同花顺主图指标本地模拟器。

用法:
  python scripts/ths_indicator_sim.py
  python scripts/ths_indicator_sim.py --bars 400 --open
"""

from __future__ import annotations

import argparse
import sys
import webbrowser
from pathlib import Path

import pandas as pd
import plotly.graph_objects as go
from plotly.subplots import make_subplots

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from src.data.tq_loader import load_cache
from src.ths_sim.formula_main import compute_main_chart, last_bar_status


def _mask_line(s: pd.Series, mask: pd.Series) -> pd.Series:
    return s.where(mask)


def _fmt(v: float | None, digits: int = 0) -> str:
    if v is None:
        return "--"
    return f"{v:.{digits}f}"


def build_figure(ind: pd.DataFrame, title: str) -> go.Figure:
    fig = make_subplots(
        rows=2,
        cols=1,
        shared_xaxes=True,
        vertical_spacing=0.04,
        row_heights=[0.72, 0.28],
        subplot_titles=("主图：高低通道 + 条件单", "副图：CCI"),
    )

    x = ind.index
    red = ind["color"] > 0
    green = ind["color"] < 0

    fig.add_trace(
        go.Candlestick(
            x=x,
            open=ind["open"],
            high=ind["high"],
            low=ind["low"],
            close=ind["close"],
            name="K线",
            increasing_line_color="#ef5350",
            decreasing_line_color="#26a69a",
        ),
        row=1,
        col=1,
    )

    for name, series, color in [
        ("上轨红", _mask_line(ind["upper"], red), "#e53935"),
        ("下轨红", _mask_line(ind["lower"], red), "#e53935"),
        ("上轨绿", _mask_line(ind["upper"], green), "#43a047"),
        ("下轨绿", _mask_line(ind["lower"], green), "#43a047"),
    ]:
        fig.add_trace(
            go.Scatter(x=x, y=series, mode="lines", name=name, line=dict(color=color, width=2)),
            row=1,
            col=1,
        )

    fig.add_trace(
        go.Scatter(
            x=x,
            y=ind["cond_entry"],
            mode="lines",
            name="条件开仓价",
            line=dict(color="#00bcd4", width=2),
            connectgaps=False,
        ),
        row=1,
        col=1,
    )
    fig.add_trace(
        go.Scatter(
            x=x,
            y=ind["cond_tp"],
            mode="lines",
            name="条件止盈价",
            line=dict(color="#ef5350", width=1, dash="dot"),
            connectgaps=False,
        ),
        row=1,
        col=1,
    )
    fig.add_trace(
        go.Scatter(
            x=x,
            y=ind["cond_sl"],
            mode="lines",
            name="条件止损价",
            line=dict(color="#ab47bc", width=2, dash="dot"),
            connectgaps=False,
        ),
        row=1,
        col=1,
    )

    def add_marks(mask: pd.Series, y: pd.Series, text: str, color: str, symbol: str) -> None:
        m = mask.fillna(False)
        if not m.any():
            return
        fig.add_trace(
            go.Scatter(
                x=x[m],
                y=y[m],
                mode="markers+text",
                text=[text] * int(m.sum()),
                textposition="top center",
                marker=dict(size=9, color=color, symbol=symbol),
                name=text,
            ),
            row=1,
            col=1,
        )

    atr = ind["atr"]
    add_marks(ind["open_long"], ind["low"] - 1.2 * atr, "开多3手", "#e53935", "triangle-up")
    add_marks(ind["open_short"], ind["high"] + 1.2 * atr, "开空3手", "#43a047", "triangle-down")
    add_marks(ind["tp1_long"], ind["high"] + 0.3 * atr, "止盈1/3", "#e53935", "circle")
    add_marks(ind["tp2_long"], ind["high"] + 0.3 * atr, "止盈2/3", "#e53935", "circle")
    add_marks(ind["tp3_long"], ind["high"] + 0.3 * atr, "止盈3/3", "#e53935", "circle")
    add_marks(ind["flat_long"], ind["high"] + 1.2 * atr, "反向清仓", "#43a047", "x")
    add_marks(ind["tp1_short"], ind["low"] - 0.3 * atr, "止盈1/3空", "#43a047", "circle")
    add_marks(ind["tp2_short"], ind["low"] - 0.3 * atr, "止盈2/3空", "#43a047", "circle")
    add_marks(ind["tp3_short"], ind["low"] - 0.3 * atr, "止盈3/3空", "#43a047", "circle")
    add_marks(ind["flat_short"], ind["low"] - 1.2 * atr, "反向清仓空", "#e53935", "x")

    fig.add_trace(
        go.Scatter(x=x, y=ind["cci"], mode="lines", name="CCI", line=dict(color="#eeeeee", width=1.5)),
        row=2,
        col=1,
    )
    fig.add_trace(
        go.Scatter(x=x, y=ind["cci_ma"], mode="lines", name="CCI均", line=dict(color="#ffee58", width=1)),
        row=2,
        col=1,
    )
    fig.add_hline(y=100, line=dict(color="#666", width=1, dash="dot"), row=2, col=1)
    fig.add_hline(y=-100, line=dict(color="#666", width=1, dash="dot"), row=2, col=1)
    fig.add_hline(y=0, line=dict(color="#666", width=1, dash="dot"), row=2, col=1)

    st = last_bar_status(ind)
    panel = (
        f"最新条件单｜状态：{st['state']}｜收盘 {_fmt(st['close'])}｜"
        f"开仓 {_fmt(st['cond_entry'])}｜止盈 {_fmt(st['cond_tp'])}｜止损 {_fmt(st['cond_sl'])}｜"
        f"{st['how']}"
    )

    fig.update_layout(
        title=dict(text=f"{title}<br><sup>{panel}</sup>", x=0.01, xanchor="left"),
        template="plotly_dark",
        height=900,
        xaxis_rangeslider_visible=False,
        legend=dict(orientation="h", yanchor="bottom", y=1.02, x=0),
        margin=dict(t=110, b=40, l=50, r=20),
    )
    # 类目轴去掉休市空隙，更接近盘面观感
    fig.update_xaxes(type="category")
    return fig


def main() -> None:
    parser = argparse.ArgumentParser(description="同花顺主图指标本地模拟器")
    parser.add_argument(
        "--cache",
        default=str(ROOT / "data" / "cache" / "DCE_a2609_5m.csv"),
        help="K线缓存 CSV",
    )
    parser.add_argument("--bars", type=int, default=500, help="只显示最近 N 根")
    parser.add_argument(
        "--out",
        default=str(ROOT / "data" / "reports" / "ths_indicator_sim.html"),
        help="输出 HTML 路径",
    )
    parser.add_argument("--open", action="store_true", help="生成后用浏览器打开")
    args = parser.parse_args()

    df = load_cache(args.cache)
    if args.bars > 0 and len(df) > args.bars:
        warm = max(args.bars + 120, args.bars)
        ind_full = compute_main_chart(df.iloc[-warm:])
        ind = ind_full.iloc[-args.bars :]
    else:
        ind = compute_main_chart(df)

    st = last_bar_status(ind)
    print("==== 最新条件单 ====")
    print(f"时间   {st['datetime']}")
    print(f"状态   {st['state']}")
    print(f"收盘   {_fmt(st['close'], 2)}")
    print(f"开仓价 {_fmt(st['cond_entry'], 2)}")
    print(f"止盈价 {_fmt(st['cond_tp'], 2)}")
    print(f"止损价 {_fmt(st['cond_sl'], 2)}")
    print(f"说明   {st['how']}")
    print(
        "事件统计 "
        f"开多={int(ind['open_long'].sum())} 开空={int(ind['open_short'].sum())} "
        f"反向平多={int(ind['flat_long'].sum())} 反向平空={int(ind['flat_short'].sum())}"
    )

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    fig = build_figure(ind, title=f"同花顺指标模拟 | {Path(args.cache).stem} | 最近{len(ind)}根")
    fig.write_html(str(out), include_plotlyjs="cdn")
    print(f"网页: {out}")

    if args.open:
        webbrowser.open(out.resolve().as_uri())


if __name__ == "__main__":
    main()
