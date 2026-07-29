"""策略默认可调参数。

入场逻辑（稳健组，见 scripts/robustness_report.py）：
  entry_mode=color_then_cci / channel=60 / CCI(15,4)

止盈倍数选用 **1.5 / 3 / 5 × ATR(20)**（滚动）：
  样本内胜率约 48%、净利最高档之一；第一档比 0.5ATR 远，更容易覆盖手续费。
  测算见 scripts/scan_atr_winrate.py。
"""

# 均线高低通道周期 N（上轨=MA(High,N)，下轨=MA(Low,N)）
CHANNEL_N = {
    "5m": 60,
    "60m": 60,
}
CHANNEL_N["1h"] = CHANNEL_N["60m"]

# ATR 止盈（在 5 分钟周期上计算）
ATR_PERIOD = 20
ATR_TIMEFRAME = "5m"
ATR_TRAILING = True
ATR_TP_MULTS = (1.5, 3, 5)

# 固定 3 手
ENTRY_LOTS = 3
EXIT_REMAINING_ON = ("5m_color_reverse",)

# CCI 开仓过滤（5 分钟）
CCI_PERIOD = 15
CCI_SIGNAL_PERIOD = 4
CCI_TIMEFRAME = "5m"
CCI_ENTRY_MODE = "color_then_cci"
