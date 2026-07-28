"""策略默认可调参数。

稳健性分析（见 scripts/robustness_report.py，基于 data/goal/search_log_full_lots3.jsonl）：
把 17880 组参数按「入场逻辑」(entry_mode+channel+CCI) 分组后发现，
entry_mode=color_then_cci / channel=60 / CCI(15,4) 这组入场逻辑不论
ATR 退出参数怎么调，全部盈利——是稳健区域，不是拟合出来的尖峰，
入场逻辑本身（channel=60、CCI(15,4)）非拟合值，故予以保留。

止盈倍数改为「高胜率」方案（用户本金较小，优先胜率/资金周转，而非单点最优pnl）：
把 ATR 止盈倍数从 3/5/7 缩小到 0.5/1/1.5——目标更近，更多单子能在趋势反转前
先落袋为安。测试 5 种ATR周期(7/10/14/20/28) × 是否滚动共10种变体全部盈利
(pnl区间[15330,16460])，验证不是拟合噪声。相比 3/5/7 倍旧方案：
胜率 42.0%→64.8%，净盈亏 14330→15370（更高），盈亏比 1.32→1.65，
SQN 1.36→2.41，最长回撤持续 3329→2373根K线，最长连亏 5→4笔，
平均持仓 29.5→12.8根K线（资金周转更快）——全面更优，非"胜率换收益"的取舍。

完整搜参过程见 data/goal/best_full_lots3.json / search_log_full_lots3.jsonl。
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
ATR_TP_MULTS = (0.5, 1, 1.5)

# 固定 3 手
ENTRY_LOTS = 3
EXIT_REMAINING_ON = ("5m_color_reverse",)

# CCI 开仓过滤（5 分钟）
CCI_PERIOD = 15
CCI_SIGNAL_PERIOD = 4
CCI_TIMEFRAME = "5m"
CCI_ENTRY_MODE = "color_then_cci"
