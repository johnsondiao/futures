"""导出可缩放回测网页图：K线 + 通道 + 开平仓标记 + 盈亏。

用法:
  python scripts/export_chart.py --symbol DCE.a2609 --period 5m --open
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import backtrader as bt
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from src.config import (
    ATR_PERIOD,
    ATR_TP_MULTS,
    ATR_TRAILING,
    CCI_ENTRY_MODE,
    CCI_PERIOD,
    CCI_SIGNAL_PERIOD,
    CHANNEL_N,
    ENTRY_LOTS,
)
from src.data.bt_feed import TIMEFRAME_MAP, make_data
from src.data.tq_loader import fetch_klines, load_cache, save_cache
from strategies.channel_trend import ChannelCciAtrStrategy


class ChartRecordingStrategy(ChannelCciAtrStrategy):
    """记录成交、完整回合与通道序列。"""

    def __init__(self):
        super().__init__()
        self.fills = []
        self.closed_trades = []
        self.series = []
        self._seq = 0
        self._open_side = None  # 'long' / 'short'
        self._open_price = None
        self._open_dt = None
        self._open_size = 0.0

    def next(self):
        dt = self.data.datetime.datetime(0)
        upper = lower = None
        if len(self) >= self.p.channel_period:
            upper = float(self.channel.upper[0])
            lower = float(self.channel.lower[0])
        self.series.append(
            {
                "datetime": dt.isoformat(sep=" "),
                "open": float(self.data.open[0]),
                "high": float(self.data.high[0]),
                "low": float(self.data.low[0]),
                "close": float(self.data.close[0]),
                "upper": upper,
                "lower": lower,
            }
        )
        super().next()

    def notify_order(self, order):
        super().notify_order(order)
        if order.status != order.Completed:
            return
        dt = bt.num2date(order.executed.dt)
        side = "买" if order.isbuy() else "卖"
        size = float(order.executed.size)
        price = float(order.executed.price)
        self.fills.append(
            {
                "datetime": dt.isoformat(sep=" "),
                "side": side,
                "price": price,
                "size": size,
                "comm": float(order.executed.comm),
            }
        )

        # 用成交流追踪分批开平，生成可读回合
        if self._open_side is None:
            self._open_side = "long" if order.isbuy() else "short"
            self._open_price = price
            self._open_dt = dt.isoformat(sep=" ")
            self._open_size = abs(size)
            return

        # 同向加仓：更新均价
        opening_long = self._open_side == "long" and order.isbuy()
        opening_short = self._open_side == "short" and order.issell()
        if opening_long or opening_short:
            total = self._open_size + abs(size)
            self._open_price = (
                self._open_price * self._open_size + price * abs(size)
            ) / total
            self._open_size = total
            return

        # 反向 = 平仓（可能分批）
        close_size = min(self._open_size, abs(size))
        self._seq += 1
        direction = "多" if self._open_side == "long" else "空"
        # 粗算本段盈亏（含乘数在 broker 里，这里用价差*手数*10 与回测一致）
        mult = 10.0
        if self._open_side == "long":
            pnl = (price - self._open_price) * close_size * mult
        else:
            pnl = (self._open_price - price) * close_size * mult
        # 手续费粗分：本笔成交佣金
        pnlcomm = pnl - float(order.executed.comm)

        self.closed_trades.append(
            {
                "id": self._seq,
                "direction": direction,
                "entry_time": self._open_dt,
                "exit_time": dt.isoformat(sep=" "),
                "entry_price": float(self._open_price),
                "exit_price": price,
                "size": close_size,
                "pnl": pnl,
                "pnlcomm": pnlcomm,
            }
        )
        self._open_size -= close_size
        if self._open_size <= 1e-8:
            self._open_side = None
            self._open_price = None
            self._open_dt = None
            self._open_size = 0.0
            # 若反向开仓超额，剩余作为新开仓
            remain = abs(size) - close_size
            if remain > 1e-8:
                self._open_side = "long" if order.isbuy() else "short"
                self._open_price = price
                self._open_dt = dt.isoformat(sep=" ")
                self._open_size = remain


def build_html(df: pd.DataFrame, series, fills, trades, meta: dict, out_path: Path):
    import plotly.graph_objects as go
    from plotly.subplots import make_subplots

    ser = pd.DataFrame(series)
    ser["datetime"] = pd.to_datetime(ser["datetime"])
    ohlc = df.copy()
    if not isinstance(ohlc.index, pd.DatetimeIndex):
        ohlc.index = pd.to_datetime(ohlc.index)

    fig = make_subplots(
        rows=2,
        cols=1,
        shared_xaxes=True,
        vertical_spacing=0.06,
        row_heights=[0.72, 0.28],
        subplot_titles=("价格 / 通道 / 开平仓", "累计净盈亏"),
    )

    fig.add_trace(
        go.Candlestick(
            x=ohlc.index,
            open=ohlc["open"],
            high=ohlc["high"],
            low=ohlc["low"],
            close=ohlc["close"],
            name="K线",
            increasing_line_color="#ef5350",
            decreasing_line_color="#26a69a",
        ),
        row=1,
        col=1,
    )

    if not ser.empty and ser["upper"].notna().any():
        fig.add_trace(
            go.Scatter(
                x=ser["datetime"],
                y=ser["upper"],
                name="通道上轨",
                line=dict(color="#ff9800", width=1.2),
            ),
            row=1,
            col=1,
        )
        fig.add_trace(
            go.Scatter(
                x=ser["datetime"],
                y=ser["lower"],
                name="通道下轨",
                line=dict(color="#2196f3", width=1.2),
            ),
            row=1,
            col=1,
        )

    buys = [f for f in fills if f["side"] == "买"]
    sells = [f for f in fills if f["side"] == "卖"]

    if buys:
        fig.add_trace(
            go.Scatter(
                x=[pd.Timestamp(f["datetime"]) for f in buys],
                y=[f["price"] for f in buys],
                mode="markers",
                name="买入",
                marker=dict(
                    symbol="triangle-up",
                    size=11,
                    color="#e53935",
                    line=dict(width=1, color="#ffffff"),
                ),
                text=[
                    f"买入<br>价格 {f['price']:.2f}<br>手数 {abs(f['size']):.0f}<br>{f['datetime']}"
                    for f in buys
                ],
                hoverinfo="text",
            ),
            row=1,
            col=1,
        )
    if sells:
        fig.add_trace(
            go.Scatter(
                x=[pd.Timestamp(f["datetime"]) for f in sells],
                y=[f["price"] for f in sells],
                mode="markers",
                name="卖出",
                marker=dict(
                    symbol="triangle-down",
                    size=11,
                    color="#1e88e5",
                    line=dict(width=1, color="#ffffff"),
                ),
                text=[
                    f"卖出<br>价格 {f['price']:.2f}<br>手数 {abs(f['size']):.0f}<br>{f['datetime']}"
                    for f in sells
                ],
                hoverinfo="text",
            ),
            row=1,
            col=1,
        )

    for t in trades:
        color = "#c62828" if t["pnlcomm"] >= 0 else "#2e7d32"
        fig.add_trace(
            go.Scatter(
                x=[pd.Timestamp(t["entry_time"]), pd.Timestamp(t["exit_time"])],
                y=[t["entry_price"], t["exit_price"]],
                mode="lines",
                line=dict(color=color, width=1, dash="dot"),
                showlegend=False,
                hoverinfo="skip",
            ),
            row=1,
            col=1,
        )
        mid_t = pd.Timestamp(t["entry_time"]) + (
            pd.Timestamp(t["exit_time"]) - pd.Timestamp(t["entry_time"])
        ) / 2
        mid_p = (t["entry_price"] + t["exit_price"]) / 2
        fig.add_trace(
            go.Scatter(
                x=[mid_t],
                y=[mid_p],
                mode="text",
                text=[f"#{t['id']} {t['pnlcomm']:+.0f}"],
                textposition="top center",
                textfont=dict(size=9, color=color),
                showlegend=False,
                hovertext=(
                    f"回合 #{t['id']} {t['direction']}<br>"
                    f"开仓 {t['entry_time']} @ {t['entry_price']:.2f}<br>"
                    f"平仓 {t['exit_time']} @ {t['exit_price']:.2f}<br>"
                    f"手数 {t['size']:.0f}<br>"
                    f"盈亏 {t['pnlcomm']:+.2f}"
                ),
                hoverinfo="text",
            ),
            row=1,
            col=1,
        )

    if trades:
        tdf = pd.DataFrame(trades).sort_values("exit_time")
        tdf["exit_time"] = pd.to_datetime(tdf["exit_time"])
        tdf["cum_pnl"] = tdf["pnlcomm"].cumsum()
        fig.add_trace(
            go.Scatter(
                x=tdf["exit_time"],
                y=tdf["cum_pnl"],
                name="累计净盈亏",
                line=dict(color="#6a1b9a", width=2),
                fill="tozeroy",
                fillcolor="rgba(106,27,154,0.12)",
                hovertemplate="%{x}<br>累计 %{y:.2f}<extra></extra>",
            ),
            row=2,
            col=1,
        )

    title = (
        f"{meta['symbol']} {meta['period']} | "
        f"{meta['mode']} N={meta['channel_n']} "
        f"CCI={meta['cci']} ATR={meta['atr']} ×{meta['lots']}手 | "
        f"净盈亏 {meta['pnl']:+.2f}"
    )
    fig.update_layout(
        title=title,
        template="plotly_white",
        height=920,
        legend=dict(orientation="h", yanchor="bottom", y=1.02, x=0),
        margin=dict(l=40, r=20, t=80, b=40),
        hovermode="closest",
    )
    fig.update_xaxes(rangeslider_visible=False, row=1, col=1)
    fig.update_xaxes(
        title_text="时间（滚轮/框选缩放，底栏拖动）",
        rangeslider_visible=True,
        rangeslider_thickness=0.06,
        row=2,
        col=1,
    )
    fig.update_yaxes(title_text="价格", row=1, col=1)
    fig.update_yaxes(title_text="累计盈亏", row=2, col=1)

    rows_html = []
    for t in trades:
        pnl = t.get("pnlcomm", 0) or 0
        cls = "pos" if pnl >= 0 else "neg"
        rows_html.append(
            "<tr>"
            f"<td>{t['id']}</td>"
            f"<td>{t['direction']}</td>"
            f"<td>{t['entry_time']}</td>"
            f"<td>{t['entry_price']:.2f}</td>"
            f"<td>{t['exit_time']}</td>"
            f"<td>{t['exit_price']:.2f}</td>"
            f"<td>{t['size']:.0f}</td>"
            f"<td class='{cls}'>{pnl:+.2f}</td>"
            "</tr>"
        )

    pnl_cls = "pos" if meta["pnl"] >= 0 else "neg"
    table = f"""
    <h2>开平仓明细（{len(trades)} 笔）</h2>
    <p>账户净盈亏：<b class="{pnl_cls}">{meta['pnl']:+.2f}</b>
       &nbsp;|&nbsp; 最大回撤：{meta['max_dd']:.2f}%
       &nbsp;|&nbsp; 胜/负：{meta['won']}/{meta['lost']}
       &nbsp;|&nbsp; 明细盈亏合计：<b>{sum(t['pnlcomm'] for t in trades):+.2f}</b></p>
    <div class="wrap"><table>
      <thead><tr>
        <th>#</th><th>方向</th><th>开仓时间</th><th>开仓价</th>
        <th>平仓时间</th><th>平仓价</th><th>手数</th><th>盈亏</th>
      </tr></thead>
      <tbody>{''.join(rows_html)}</tbody>
    </table></div>
    """

    chart_html = fig.to_html(full_html=False, include_plotlyjs="cdn")
    page = f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8"/>
  <meta name="viewport" content="width=device-width, initial-scale=1"/>
  <title>{meta['symbol']} 回测图</title>
  <style>
    body {{ font-family: "Segoe UI", "PingFang SC", sans-serif; margin: 16px; background:#fafafa; color:#222; }}
    h1 {{ font-size: 1.2rem; margin: 0 0 6px; }}
    h2 {{ font-size: 1.05rem; margin: 20px 0 8px; }}
    .meta {{ color:#555; font-size: 0.9rem; margin-bottom: 8px; }}
    .tip {{ font-size: 0.85rem; color:#666; margin: 0 0 12px; }}
    .pos {{ color:#c62828; font-weight:600; }}
    .neg {{ color:#2e7d32; font-weight:600; }}
    .wrap {{ overflow:auto; max-height: 440px; border:1px solid #e0e0e0; border-radius:8px; background:#fff; }}
    table {{ border-collapse: collapse; width: 100%; font-size: 0.85rem; }}
    th, td {{ padding: 6px 10px; border-bottom: 1px solid #eee; text-align: right; white-space: nowrap; }}
    th:nth-child(1), td:nth-child(1), th:nth-child(2), td:nth-child(2),
    th:nth-child(3), td:nth-child(3), th:nth-child(5), td:nth-child(5) {{ text-align:left; }}
    th {{ position: sticky; top: 0; background:#f5f5f5; z-index:1; }}
  </style>
</head>
<body>
  <h1>{title}</h1>
  <div class="meta">数据 {meta['start']} → {meta['end']}（{meta['bars']} 根）</div>
  <div class="tip">红三角=买入，蓝三角=卖出；虚线连接开平仓；数字为该笔盈亏。滚轮/框选缩放，底部滑条拖时间。</div>
  {chart_html}
  {table}
</body>
</html>
"""
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(page, encoding="utf-8")
    return out_path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--symbol", default="DCE.a2609")
    parser.add_argument("--period", default="5m")
    parser.add_argument("--bars", type=int, default=8000)
    parser.add_argument("--cash", type=float, default=1_000_000.0)
    parser.add_argument("--open", action="store_true")
    args = parser.parse_args()

    symbol, period = args.symbol, args.period
    channel_n = CHANNEL_N.get(period, 60)
    safe = symbol.replace(".", "_").replace("@", "_")
    cache = ROOT / "data" / "cache" / f"{safe}_{period}.csv"

    if cache.exists():
        df = load_cache(cache)
        print(f"使用缓存: {cache}")
    else:
        print(f"拉取 {symbol} {period} ...")
        df = fetch_klines(symbol, period=period, data_length=args.bars)
        save_cache(df, cache)

    tf, comp = TIMEFRAME_MAP[period]
    data = make_data(df, name=f"{safe}_{period}", timeframe=tf, compression=comp)

    cerebro = bt.Cerebro(stdstats=False)
    cerebro.adddata(data)
    cerebro.addstrategy(
        ChartRecordingStrategy,
        channel_period=channel_n,
        cci_period=CCI_PERIOD,
        cci_signal=CCI_SIGNAL_PERIOD,
        atr_period=ATR_PERIOD,
        atr_mults=ATR_TP_MULTS,
        atr_trailing=ATR_TRAILING,
        entry_lots=ENTRY_LOTS,
        entry_mode=CCI_ENTRY_MODE,
        printlog=False,
    )
    cerebro.broker.setcash(args.cash)
    cerebro.broker.setcommission(commission=0.00015, mult=10.0)
    cerebro.addanalyzer(bt.analyzers.DrawDown, _name="dd")
    cerebro.addanalyzer(bt.analyzers.TradeAnalyzer, _name="ta")

    start = cerebro.broker.getvalue()
    strat = cerebro.run()[0]
    end = cerebro.broker.getvalue()
    dd = strat.analyzers.dd.get_analysis()
    ta = strat.analyzers.ta.get_analysis()

    fills = strat.fills
    trades = strat.closed_trades
    meta = {
        "symbol": symbol,
        "period": period,
        "mode": CCI_ENTRY_MODE,
        "channel_n": channel_n,
        "cci": f"{CCI_PERIOD}/{CCI_SIGNAL_PERIOD}",
        "atr": f"{ATR_PERIOD}x{list(ATR_TP_MULTS)}",
        "lots": ENTRY_LOTS,
        "pnl": end - start,
        "max_dd": float(dd.get("max", {}).get("drawdown", 0) or 0),
        "won": ta.get("won", {}).get("total", 0) or 0,
        "lost": ta.get("lost", {}).get("total", 0) or 0,
        "start": str(df.index[0]),
        "end": str(df.index[-1]),
        "bars": len(df),
    }

    out_dir = ROOT / "data" / "reports"
    out_dir.mkdir(parents=True, exist_ok=True)
    html_path = out_dir / f"{safe}_{period}_chart.html"
    json_path = out_dir / f"{safe}_{period}_trades.json"
    json_path.write_text(
        json.dumps({"meta": meta, "fills": fills, "trades": trades}, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    path = build_html(df, strat.series, fills, trades, meta, html_path)
    print(f"净盈亏: {meta['pnl']:.2f}")
    print(f"成交笔数(开平回合): {len(trades)}  原始成交: {len(fills)}")
    print(f"网页: {path}")

    if args.open:
        import webbrowser

        webbrowser.open(path.resolve().as_uri())


if __name__ == "__main__":
    main()
