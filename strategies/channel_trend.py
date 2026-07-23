"""通道变色 + CCI 过滤 + ATR 分批止盈（单周期，默认 5m）。

规则见 docs/strategy_rules.md / src/config.py。
"""

from __future__ import annotations

import backtrader as bt

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


class HighLowChannel(bt.Indicator):
    lines = ("upper", "lower")
    params = (("period", 144),)

    def __init__(self):
        self.lines.upper = bt.indicators.SimpleMovingAverage(
            self.data.high, period=self.p.period
        )
        self.lines.lower = bt.indicators.SimpleMovingAverage(
            self.data.low, period=self.p.period
        )


class ChannelCciAtrStrategy(bt.Strategy):
    params = (
        ("channel_period", CHANNEL_N["5m"]),
        ("cci_period", CCI_PERIOD),
        ("cci_signal", CCI_SIGNAL_PERIOD),
        ("atr_period", ATR_PERIOD),
        ("atr_mults", ATR_TP_MULTS),
        ("atr_trailing", ATR_TRAILING),
        ("entry_lots", ENTRY_LOTS),
        ("entry_mode", CCI_ENTRY_MODE),
        ("printlog", False),
    )

    def __init__(self):
        self.channel = HighLowChannel(self.data, period=self.p.channel_period)
        self.cci = bt.ind.CommodityChannelIndex(self.data, period=self.p.cci_period)
        self.cci_ma = bt.ind.SMA(self.cci, period=self.p.cci_signal)
        self.atr = bt.ind.ATR(self.data, period=self.p.atr_period)

        self.color = 0  # 1 红, -1 绿
        self.pending = 0  # 等待 CCI 确认的方向
        self.entry_price = None
        self.locked_atr = None
        self.tp_hits = 0  # 已触发几档止盈
        self.order = None

    def log(self, txt):
        if self.p.printlog:
            dt = self.data.datetime.datetime(0)
            print(f"{dt.isoformat()} {txt}")

    def notify_order(self, order):
        if order.status in (
            order.Completed,
            order.Canceled,
            order.Margin,
            order.Rejected,
        ):
            self.order = None

    def _side(self) -> int:
        close = float(self.data.close[0])
        upper = float(self.channel.upper[0])
        lower = float(self.channel.lower[0])
        if close > upper:
            return 1
        if close < lower:
            return -1
        return 0

    def _cci_cross_up(self) -> bool:
        return float(self.cci[-1]) <= float(self.cci_ma[-1]) and float(
            self.cci[0]
        ) > float(self.cci_ma[0])

    def _cci_cross_down(self) -> bool:
        return float(self.cci[-1]) >= float(self.cci_ma[-1]) and float(
            self.cci[0]
        ) < float(self.cci_ma[0])

    def _cci_above(self) -> bool:
        return float(self.cci[0]) > float(self.cci_ma[0])

    def _cci_below(self) -> bool:
        return float(self.cci[0]) < float(self.cci_ma[0])

    def _want_entry(self, direction: int, color_changed: bool) -> bool:
        mode = self.p.entry_mode
        if mode in ("to_be_swept", "color_then_cci"):
            # 变色后等待 CCI 交叉确认
            if color_changed:
                self.pending = direction
                return False
            if self.pending == direction:
                if direction > 0 and self._cci_cross_up():
                    return True
                if direction < 0 and self._cci_cross_down():
                    return True
            return False

        if mode == "color_only":
            return color_changed

        if mode == "color_and_cci_cross":
            if not color_changed:
                return False
            return self._cci_cross_up() if direction > 0 else self._cci_cross_down()

        if mode == "color_and_cci_above":
            if not color_changed:
                return False
            return self._cci_above() if direction > 0 else self._cci_below()

        if mode == "cci_cross_only":
            return self._cci_cross_up() if direction > 0 else self._cci_cross_down()

        if mode == "cci_then_color":
            # CCI 先交叉，再等变色
            if direction > 0 and self._cci_cross_up():
                self.pending = 1
            elif direction < 0 and self._cci_cross_down():
                self.pending = -1
            return color_changed and self.pending == direction

        return False

    def _atr_value(self) -> float:
        if self.p.atr_trailing or self.locked_atr is None:
            return float(self.atr[0])
        return float(self.locked_atr)

    def _open(self, direction: int):
        lots = int(self.p.entry_lots)
        self.entry_price = float(self.data.close[0])
        self.locked_atr = float(self.atr[0])
        self.tp_hits = 0
        self.pending = 0
        if direction > 0:
            self.log(f"开多 {lots}手 @ {self.entry_price:.2f}")
            self.order = self.buy(size=lots)
        else:
            self.log(f"开空 {lots}手 @ {self.entry_price:.2f}")
            self.order = self.sell(size=lots)

    def _flat_all(self, reason: str):
        if not self.position:
            return
        self.log(f"{reason} 平剩余 size={self.position.size}")
        self.order = self.close()
        self.entry_price = None
        self.locked_atr = None
        self.tp_hits = 0

    def _check_atr_tp(self):
        if not self.position or self.entry_price is None:
            return
        if self.order:
            return

        atr = self._atr_value()
        if atr <= 0:
            return

        close = float(self.data.close[0])
        mults = list(self.p.atr_mults)
        long = self.position.size > 0

        while self.tp_hits < len(mults) and abs(self.position.size) > 0:
            k = mults[self.tp_hits]
            target = (
                self.entry_price + k * atr if long else self.entry_price - k * atr
            )
            hit = close >= target if long else close <= target
            if not hit:
                break
            # 最后一档若只剩 1 手也平；否则每次平 1 手
            size = 1 if abs(self.position.size) > 1 or self.tp_hits < len(mults) - 1 else abs(
                self.position.size
            )
            size = min(size, abs(int(self.position.size)))
            self.log(f"止盈 {k}xATR target={target:.2f} close={close:.2f} 平{size}手")
            if long:
                self.order = self.sell(size=size)
            else:
                self.order = self.buy(size=size)
            self.tp_hits += 1
            # 同根只触发一档，避免同一 next 连续下多笔部分平仓冲突
            break

        if not self.position:
            self.entry_price = None
            self.locked_atr = None
            self.tp_hits = 0

    def next(self):
        if len(self) < self.p.channel_period + 2:
            return
        if self.order:
            return

        side = self._side()
        color_changed = False
        new_color = self.color

        if side != 0:
            if self.color == 0:
                self.color = side
                self.log(f"初始定位 {'红' if side > 0 else '绿'}")
            elif side != self.color:
                color_changed = True
                new_color = side
                self.log(
                    f"变色 {'红' if self.color > 0 else '绿'}→{'红' if side > 0 else '绿'} "
                    f"close={float(self.data.close[0]):.2f}"
                )

        # 持仓：反向变色 → 平剩余
        if self.position and color_changed:
            if (self.position.size > 0 and new_color < 0) or (
                self.position.size < 0 and new_color > 0
            ):
                self._flat_all("反向变色")
                self.color = new_color
                self.pending = new_color  # color_then_cci：变色后等 CCI
                return

        if color_changed:
            self.color = new_color
            # 取消相反方向的 pending
            if self.pending and self.pending != new_color:
                self.pending = 0

        # 持仓：ATR 分批止盈
        if self.position:
            self._check_atr_tp()
            return

        # 空仓：寻找开仓
        direction = self.color if self.color != 0 else 0
        if direction == 0:
            return

        # color_then_cci / cci_then_color 用 pending；变色当根也可能直接满足其它模式
        if self._want_entry(direction, color_changed):
            self._open(direction)


# 兼容旧名
ChannelColorStrategy = ChannelCciAtrStrategy
