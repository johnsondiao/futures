"""策略默认可调参数。

Goal（固定 3 手，净盈亏>=10000，DCE.a2609/5m）命中见 data/goal/best_lots3.json
"""

# 均线高低通道周期 N（上轨=MA(High,N)，下轨=MA(Low,N)）
CHANNEL_N = {
    "5m": 60,
    "60m": 60,
}
CHANNEL_N["1h"] = CHANNEL_N["60m"]

# ATR 止盈（在 5 分钟周期上计算）
ATR_PERIOD = 14
ATR_TIMEFRAME = "5m"
ATR_TRAILING = True
ATR_TP_MULTS = (3, 6, 9)

# 固定 3 手
ENTRY_LOTS = 3
EXIT_REMAINING_ON = ("5m_color_reverse",)

# CCI 开仓过滤（5 分钟）
CCI_PERIOD = 15
CCI_SIGNAL_PERIOD = 4
CCI_TIMEFRAME = "5m"
CCI_ENTRY_MODE = "color_then_cci"
